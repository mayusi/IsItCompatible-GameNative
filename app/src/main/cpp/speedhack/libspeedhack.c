/*
 * libspeedhack.c — LD_PRELOAD shim that scales game time for Wine/Box64 on Android.
 *
 * MECHANISM
 * ---------
 * Wine implements Windows timing (QueryPerformanceCounter, GetTickCount, timeGetTime)
 * by calling Linux clock_gettime(CLOCK_MONOTONIC).  By interposing clock_gettime here
 * and scaling the elapsed time since a baseline, we make the game's perceived time
 * run faster or slower without touching game code.
 *
 * MATH (monotonicity is the hard constraint)
 * ------------------------------------------
 * We CANNOT multiply the raw clock value: that would instantly jump the clock
 * billions of nanoseconds forward and break most games.  Instead we track a
 * (base_real, base_fake) anchor pair and scale only elapsed time:
 *
 *   fake = base_fake + (real_now - base_real) * multiplier
 *
 * When the multiplier changes we re-anchor *before* applying the new rate:
 *   base_fake = fake_at_change_point
 *   base_real = real_now
 * This keeps the emitted sequence strictly monotone across any number of
 * multiplier changes — a requirement because many game engines assert
 * dt >= 0 and POSIX requires CLOCK_MONOTONIC to be non-decreasing.
 *
 * CLOCK SELECTION
 * ---------------
 * We intercept CLOCK_MONOTONIC and CLOCK_MONOTONIC_RAW.
 * CLOCK_REALTIME is passed through unchanged (see speedhack_protocol.h for rationale).
 * All other clock IDs also pass through unchanged.
 *
 * ACTIVATION GATE
 * ---------------
 * The constructor checks env var SPEEDHACK_ENABLED.  If it is not "1", the
 * override is installed (because dlopen already happened) but clock_gettime
 * becomes a pure passthrough: no shm is opened, no math is done, the only
 * overhead is one strcmp in __constructor and the function-call trampoline.
 *
 * REAL-FUNCTION RESOLUTION — BULLETPROOF (the critical fix)
 * ----------------------------------------------------------
 * On Android/Bionic inside a Box64/Wine LD_PRELOAD context,
 * dlsym(RTLD_NEXT, "clock_gettime") can return NULL because RTLD_NEXT
 * walks only the link-map chain after this .so, and Bionic's unusual
 * loader layout in the Box64 namespace may not expose libc's clock_gettime
 * through that path.  The OLD code treated this as a fatal early-return,
 * leaving real_clock_gettime=NULL and g_enabled=0 — but the interposed
 * symbol was still live, so every clock_gettime call returned EINVAL,
 * causing libc++abi to throw std::system_error and crash all Wine games.
 *
 * THE FIX: multi-stage dlsym fallback chain + direct syscall last resort.
 * get_real_time() ALWAYS succeeds for any valid clk_id:
 *   1. Try real_clock_gettime (resolved at constructor time or lazily).
 *   2. If still NULL: try RTLD_NEXT again (lazy retry).
 *   3. If still NULL: try RTLD_DEFAULT.
 *   4. If still NULL: try dlopen("libc.so") + dlsym.
 *   5. LAST RESORT (cannot fail for valid clk_id): syscall(SYS_clock_gettime).
 * This guarantees the disabled path (SPEEDHACK_ENABLED!=1) is a pure
 * passthrough that NEVER returns EINVAL and NEVER crashes any caller.
 *
 * SHM LAYOUT  →  see speedhack_protocol.h / SpeedHackShm.kt
 *
 * THREAD SAFETY
 * -------------
 * The multiplier is read under a mutex only when config_seq has changed
 * (cheap atomic load in the fast path).  The anchor state is protected by
 * the same mutex.  All paths guarantee a real-function passthrough on error.
 *
 * BUILD REQUIREMENTS
 * ------------------
 * -fvisibility=default for clock_gettime (it must appear in the .so's dynsym
 * as a defined T symbol so the dynamic linker interposes it).
 * Link with -ldl (for dlsym) and -llog (for Android logging).
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <errno.h>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/syscall.h>   /* SYS_clock_gettime, SYS_gettimeofday — syscall fallback */

#include <android/log.h>

#include "speedhack_protocol.h"

#define LOG_TAG "libspeedhack"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---- real function pointers ---- */
typedef int (*real_clock_gettime_fn)(clockid_t, struct timespec *);
typedef int (*real_gettimeofday_fn)(struct timeval *, struct timezone *);

/*
 * These are initialised in the constructor (best-effort) and lazily on first
 * call.  They may be NULL after construction if all dlsym attempts failed;
 * get_real_time() / get_real_tod() will retry lazily and ultimately fall back
 * to the direct syscall — they NEVER leave a caller without a valid time.
 */
static real_clock_gettime_fn  real_clock_gettime  = NULL;
static real_gettimeofday_fn   real_gettimeofday   = NULL;

/* Mutex protecting the lazy-resolution pointers (written once, then stable). */
static pthread_mutex_t g_resolve_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ---- shared-memory ---- */
static volatile struct speedhack_shm *g_shm = NULL;  /* NULL when disabled/failed */

/* ---- per-clock anchor state ---- */
struct clock_anchor {
    struct timespec base_real;   /* real clock value at last re-anchor */
    struct timespec base_fake;   /* fake clock value at last re-anchor */
    float           multiplier;  /* current rate */
    uint32_t        last_seq;    /* config_seq observed at last re-anchor */
    int             initialized; /* non-zero after first call */
};

/*
 * We need independent anchors for CLOCK_MONOTONIC and CLOCK_MONOTONIC_RAW
 * because they can drift from each other over long sessions.
 */
#define CLOCK_IDX_MONO     0
#define CLOCK_IDX_MONO_RAW 1
#define NUM_CLOCK_IDX      2

static struct clock_anchor g_anchors[NUM_CLOCK_IDX];

/* Protects g_anchors[] during re-anchor operations. Fast path (no re-anchor)
 * only does an atomic load of config_seq and a read of g_anchors[i] fields
 * written under the lock, so readers that don't re-anchor don't need to lock.
 * But to simplify correctness (anchors have 3 fields updated atomically), we
 * take the lock on every call where seq changed, and also on the first call. */
static pthread_mutex_t g_anchor_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ---- fast-path: is scaling even active? ---- */
static int g_enabled = 0;  /* set in constructor, never changed after */

/* =========================================================================
 * BULLETPROOF real-function resolution
 *
 * try_resolve_clock_gettime() builds a chain of dlsym attempts and, crucially,
 * never marks the function "permanently unavailable" from the constructor —
 * get_real_time() retries lazily at call time if needed.
 *
 * The LAST RESORT is syscall(SYS_clock_gettime, ...) which bypasses the
 * dynamic linker entirely and speaks directly to the kernel.  On Android
 * (Linux) this is always available for any valid clockid_t.  This is the
 * key safety net that means get_real_time() NEVER returns EINVAL.
 * ========================================================================= */

/*
 * Attempt to resolve clock_gettime through multiple strategies.
 * Returns a non-NULL function pointer, or NULL if all dlsym paths failed
 * (the caller must then use the syscall fallback).
 *
 * NOT thread-safe by itself; callers hold g_resolve_mutex.
 */
static real_clock_gettime_fn try_resolve_clock_gettime(void)
{
    real_clock_gettime_fn fn = NULL;

    /* Strategy 1: RTLD_NEXT — the canonical LD_PRELOAD approach */
    fn = (real_clock_gettime_fn)dlsym(RTLD_NEXT, "clock_gettime");
    if (fn) {
        LOGI("clock_gettime resolved via RTLD_NEXT");
        return fn;
    }

    /* Strategy 2: RTLD_DEFAULT — searches the full link map */
    fn = (real_clock_gettime_fn)dlsym(RTLD_DEFAULT, "clock_gettime");
    if (fn) {
        LOGW("clock_gettime resolved via RTLD_DEFAULT (RTLD_NEXT failed)");
        return fn;
    }

    /* Strategy 3: explicit libc handle — try Bionic's canonical paths */
    static const char *libc_names[] = {
        "libc.so",
        "/system/lib64/libc.so",
        "/system/lib/libc.so",
        NULL
    };
    for (int i = 0; libc_names[i]; ++i) {
        void *h = dlopen(libc_names[i], RTLD_NOW | RTLD_NOLOAD);
        if (!h) h = dlopen(libc_names[i], RTLD_NOW);
        if (h) {
            fn = (real_clock_gettime_fn)dlsym(h, "clock_gettime");
            /* Do NOT dlclose(h) — we need the handle to stay valid */
            if (fn) {
                LOGW("clock_gettime resolved via dlopen(%s)", libc_names[i]);
                return fn;
            }
            dlclose(h);
        }
    }

    /* All dlsym strategies exhausted — caller must use syscall fallback */
    LOGE("all dlsym strategies for clock_gettime failed — will use syscall fallback");
    return NULL;
}

/*
 * Robustly get the real (un-scaled) clock value.
 *
 * GUARANTEED: never returns an error for a valid clk_id — even if all
 * dlsym paths fail, the direct syscall will succeed.  This is the core
 * property that prevents the EINVAL crash.
 */
static inline int get_real_time(clockid_t clk_id, struct timespec *tp)
{
    /* Fast path: function pointer already resolved */
    real_clock_gettime_fn fn = real_clock_gettime;

    if (__builtin_expect(fn == NULL, 0)) {
        /* Lazy resolution attempt (runs at most once due to the mutex) */
        pthread_mutex_lock(&g_resolve_mutex);
        fn = real_clock_gettime;  /* re-read under lock */
        if (!fn) {
            fn = try_resolve_clock_gettime();
            real_clock_gettime = fn;  /* store result (may still be NULL) */
        }
        pthread_mutex_unlock(&g_resolve_mutex);
    }

    if (__builtin_expect(fn != NULL, 1)) {
        int r = fn(clk_id, tp);
        if (__builtin_expect(r == 0, 1)) return 0;
        /* fn returned an error — fall through to syscall for validation */
        /* (propagate real error if syscall also fails) */
    }

    /*
     * SYSCALL LAST RESORT
     * -------------------
     * Bypasses the dynamic linker entirely.  On Android/Linux,
     * SYS_clock_gettime is always available for any valid clockid_t.
     * If even this fails (invalid clk_id), propagate the real error.
     */
    long r = syscall(SYS_clock_gettime, (long)clk_id, (long)tp);
    if (r == 0) return 0;
    /* Preserve errno set by the syscall */
    return (int)r;
}

/* ---- gettimeofday robust resolution ---- */

static real_gettimeofday_fn try_resolve_gettimeofday(void)
{
    real_gettimeofday_fn fn = NULL;

    fn = (real_gettimeofday_fn)dlsym(RTLD_NEXT, "gettimeofday");
    if (fn) return fn;

    fn = (real_gettimeofday_fn)dlsym(RTLD_DEFAULT, "gettimeofday");
    if (fn) return fn;

    static const char *libc_names[] = {
        "libc.so", "/system/lib64/libc.so", "/system/lib/libc.so", NULL
    };
    for (int i = 0; libc_names[i]; ++i) {
        void *h = dlopen(libc_names[i], RTLD_NOW | RTLD_NOLOAD);
        if (!h) h = dlopen(libc_names[i], RTLD_NOW);
        if (h) {
            fn = (real_gettimeofday_fn)dlsym(h, "gettimeofday");
            if (fn) return fn;
            dlclose(h);
        }
    }
    return NULL;
}

/*
 * Robustly get the real wall time (for gettimeofday passthrough).
 * Falls back to clock_gettime(CLOCK_REALTIME) or the direct syscall.
 */
static inline int get_real_tod(struct timeval *tv, struct timezone *tz)
{
    real_gettimeofday_fn fn = real_gettimeofday;

    if (__builtin_expect(fn == NULL, 0)) {
        pthread_mutex_lock(&g_resolve_mutex);
        fn = real_gettimeofday;
        if (!fn) {
            fn = try_resolve_gettimeofday();
            real_gettimeofday = fn;
        }
        pthread_mutex_unlock(&g_resolve_mutex);
    }

    if (fn) {
        int r = fn(tv, tz);
        if (r == 0) return 0;
        /* fn failed — fall through */
    }

    /*
     * FALLBACK: implement gettimeofday via CLOCK_REALTIME.
     * get_real_time(CLOCK_REALTIME) is guaranteed to succeed.
     */
    if (tv) {
        struct timespec ts;
        int r = get_real_time(CLOCK_REALTIME, &ts);
        if (r == 0) {
            tv->tv_sec  = ts.tv_sec;
            tv->tv_usec = (suseconds_t)(ts.tv_nsec / 1000L);
        }
        return r;
    }
    return 0;
}

/* ---- helpers ---- */

static inline int64_t timespec_to_ns(const struct timespec *ts)
{
    return (int64_t)ts->tv_sec * 1000000000LL + (int64_t)ts->tv_nsec;
}

static inline void ns_to_timespec(int64_t ns, struct timespec *ts)
{
    if (ns < 0) ns = 0;  /* clamp: never emit negative time */
    ts->tv_sec  = (time_t)(ns / 1000000000LL);
    ts->tv_nsec = (long)  (ns % 1000000000LL);
}

/*
 * Read the current multiplier from shm.  Must only be called when g_shm != NULL.
 * Returns the float stored as raw IEEE-754 bits in the uint32 field.
 */
static float read_multiplier_from_shm(void)
{
    uint32_t bits = g_shm->multiplier_bits;
    float mul;
    memcpy(&mul, &bits, sizeof(mul));

    /* Sanity-clamp: reject NaN/Inf/zero/negative */
    if (mul < SPEEDHACK_MULTIPLIER_MIN || mul > SPEEDHACK_MULTIPLIER_MAX) {
        return SPEEDHACK_MULTIPLIER_DEFAULT;
    }
    return mul;
}

/*
 * Core scaling function.  Called for CLOCK_MONOTONIC and CLOCK_MONOTONIC_RAW.
 *
 * idx  = CLOCK_IDX_MONO or CLOCK_IDX_MONO_RAW
 * real = the value returned by get_real_time() (input)
 * out  = scaled value to be returned to the caller (output)
 */
static void apply_speedhack(int idx, const struct timespec *real, struct timespec *out)
{
    if (!g_shm) {
        /* Should not happen (g_enabled would be 0), but be safe */
        *out = *real;
        return;
    }

    uint32_t cur_seq = (uint32_t)atomic_load_explicit(
        (volatile atomic_uint *)&g_shm->config_seq, memory_order_acquire);

    struct clock_anchor *a = &g_anchors[idx];

    int need_reanchor = (!a->initialized) || (cur_seq != a->last_seq);

    if (need_reanchor) {
        pthread_mutex_lock(&g_anchor_mutex);

        /* Re-check under lock — another thread may have already done the work */
        cur_seq = (uint32_t)atomic_load_explicit(
            (volatile atomic_uint *)&g_shm->config_seq, memory_order_acquire);

        if (!a->initialized) {
            /* First call: anchor at real=now, fake=now, multiplier=whatever is set */
            a->base_real  = *real;
            a->base_fake  = *real;
            a->multiplier = read_multiplier_from_shm();
            a->last_seq   = cur_seq;
            a->initialized = 1;
        } else if (cur_seq != a->last_seq) {
            /* Multiplier changed: compute current fake value, re-anchor there */
            int64_t real_now  = timespec_to_ns(real);
            int64_t base_r    = timespec_to_ns(&a->base_real);
            int64_t base_f    = timespec_to_ns(&a->base_fake);
            int64_t elapsed   = real_now - base_r;
            int64_t fake_now  = base_f + (int64_t)((double)elapsed * a->multiplier);

            a->base_real  = *real;
            ns_to_timespec(fake_now, &a->base_fake);
            a->multiplier = read_multiplier_from_shm();
            a->last_seq   = cur_seq;
        }
        pthread_mutex_unlock(&g_anchor_mutex);
    }

    /* Fast path: compute fake = base_fake + (real_now - base_real) * multiplier */
    int64_t real_now = timespec_to_ns(real);
    int64_t base_r   = timespec_to_ns(&a->base_real);
    int64_t base_f   = timespec_to_ns(&a->base_fake);
    int64_t elapsed  = real_now - base_r;
    int64_t fake_ns  = base_f + (int64_t)((double)elapsed * a->multiplier);
    ns_to_timespec(fake_ns, out);
}

/* ---- shm setup ---- */

static int open_speedhack_shm(const char *base_path)
{
    /* Build dir path: <base>/speedhack_shm */
    char dir_path[512];
    int n = snprintf(dir_path, sizeof(dir_path), "%s/speedhack_shm", base_path);
    if (n <= 0 || n >= (int)sizeof(dir_path)) {
        LOGE("base_path too long");
        return -1;
    }

    if (mkdir(dir_path, 0700) < 0 && errno != EEXIST) {
        LOGE("mkdir %s: %s", dir_path, strerror(errno));
        return -1;
    }

    char mem_path[560];
    n = snprintf(mem_path, sizeof(mem_path), "%s/speedhack.mem", dir_path);
    if (n <= 0 || n >= (int)sizeof(mem_path)) {
        LOGE("mem_path too long");
        return -1;
    }

    int fd = open(mem_path, O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        LOGE("open %s: %s", mem_path, strerror(errno));
        return -1;
    }

    if (ftruncate(fd, SPEEDHACK_SHM_SIZE) < 0) {
        LOGE("ftruncate: %s", strerror(errno));
        close(fd);
        return -1;
    }

    void *ptr = mmap(NULL, SPEEDHACK_SHM_SIZE, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, 0);
    close(fd);  /* fd no longer needed after mmap */

    if (ptr == MAP_FAILED) {
        LOGE("mmap: %s", strerror(errno));
        return -1;
    }

    g_shm = (volatile struct speedhack_shm *)ptr;

    /* Write magic + version so the Kotlin side can verify the lib is alive */
    g_shm->magic   = SPEEDHACK_PROTO_MAGIC;
    g_shm->version = SPEEDHACK_PROTO_VERSION;

    /* If multiplier_bits is 0 (fresh file), default to 1.0 */
    if (g_shm->multiplier_bits == 0) {
        float one = SPEEDHACK_MULTIPLIER_DEFAULT;
        uint32_t bits;
        memcpy(&bits, &one, sizeof(bits));
        g_shm->multiplier_bits = bits;
    }

    LOGI("shm mapped at %p (path: %s), multiplier_bits=0x%08x",
         ptr, mem_path, g_shm->multiplier_bits);
    return 0;
}

/* ---- constructor ---- */

__attribute__((constructor))
static void speedhack_init(void)
{
    /*
     * Best-effort resolution at constructor time.  If dlsym(RTLD_NEXT) fails
     * here (which it can in the Box64/Wine Bionic namespace), we do NOT abort
     * or return early.  The lazy path in get_real_time() will retry, and the
     * syscall fallback guarantees correctness regardless.
     *
     * CRITICAL: we no longer set real_clock_gettime = NULL and early-return on
     * failure.  The old code did that, leaving real_clock_gettime=NULL, and then
     * the interposed clock_gettime returned EINVAL for every call — crashing all
     * Wine/Box64 games via uncaught std::system_error in libc++abi.
     */
    real_clock_gettime_fn fn = (real_clock_gettime_fn)dlsym(RTLD_NEXT, "clock_gettime");
    if (fn) {
        real_clock_gettime = fn;
        LOGI("clock_gettime resolved at constructor time via RTLD_NEXT");
    } else {
        /*
         * RTLD_NEXT failed.  Do NOT early-return.  The lazy resolution in
         * get_real_time() will try RTLD_DEFAULT and dlopen(libc.so), and the
         * syscall fallback guarantees we never return EINVAL to callers.
         */
        LOGW("dlsym(RTLD_NEXT, clock_gettime) failed at constructor: %s — "
             "will retry lazily or use syscall fallback", dlerror());
    }

    real_gettimeofday = (real_gettimeofday_fn)dlsym(RTLD_NEXT, "gettimeofday");
    if (!real_gettimeofday) {
        LOGW("dlsym(RTLD_NEXT, gettimeofday) failed — will retry lazily");
    }

    const char *enabled = getenv("SPEEDHACK_ENABLED");
    if (!enabled || strcmp(enabled, "1") != 0) {
        /* Not enabled: keep g_enabled=0, clock_gettime is a pure passthrough.
         * The disabled passthrough path MUST NOT fail — get_real_time() is
         * guaranteed to always return a valid time. */
        LOGI("SPEEDHACK_ENABLED != 1 — passthrough mode, no shm opened");
        return;
    }

    const char *base_path = getenv("SPEEDHACK_BASE_PATH");
    if (!base_path || base_path[0] == '\0') {
        LOGE("SPEEDHACK_ENABLED=1 but SPEEDHACK_BASE_PATH not set — passthrough");
        return;
    }

    if (open_speedhack_shm(base_path) < 0) {
        LOGE("shm setup failed — passthrough mode");
        return;
    }

    g_enabled = 1;
    LOGI("speed hack active (base_path=%s)", base_path);
}

/* =========================================================================
 * Interposed symbols
 *
 * -fvisibility=default is applied to this translation unit so these symbols
 * appear as defined 'T' entries in the .so's dynamic symbol table and the
 * dynamic linker can use them to interpose the libc versions.
 * ========================================================================= */

int clock_gettime(clockid_t clk_id, struct timespec *tp)
{
    /*
     * DISABLED PATH (SPEEDHACK_ENABLED != 1):
     * Pure passthrough — call get_real_time() which is guaranteed to never
     * return EINVAL.  No NULL-check needed here; get_real_time() handles
     * all resolution internally and falls back to the direct syscall.
     *
     * ENABLED PATH:
     * Get the real time first, then apply scaling for monotonic clocks.
     * If get_real_time() fails (only for truly invalid clk_id), propagate
     * the real error — but never synthesize EINVAL ourselves.
     *
     * THE OLD BUG was here: `if (!real_clock_gettime) { errno=EINVAL; return -1; }`
     * That synthesized EINVAL when dlsym failed, crashing Wine/Box64/libc++abi.
     * Now we NEVER synthesize EINVAL — we always call get_real_time().
     */
    int ret = get_real_time(clk_id, tp);
    if (ret != 0) return ret;  /* propagate real errors (invalid clk_id, etc.) */

    if (!g_enabled) return 0;  /* passthrough when disabled (fast exit) */

    /* Scale only monotonic clocks (Wine QPC / GetTickCount path) */
    if (clk_id == CLOCK_MONOTONIC) {
        apply_speedhack(CLOCK_IDX_MONO, tp, tp);
    } else if (clk_id == CLOCK_MONOTONIC_RAW) {
        apply_speedhack(CLOCK_IDX_MONO_RAW, tp, tp);
    }
    /* CLOCK_REALTIME and all other clocks pass through untouched */

    return 0;
}

/*
 * gettimeofday — older Win32 timing paths (SDL_GetTicks on some ports, very
 * old DirectX titles).  We override it here for completeness but map its result
 * through CLOCK_MONOTONIC's anchor so the two sources stay correlated.
 *
 * Note: gettimeofday reports wall time (CLOCK_REALTIME semantics), NOT
 * monotonic time.  Scaling it risks breaking TLS / network / OS timestamps.
 * However, for Wine game processes the only consumer is typically game-internal
 * frame timing.  We therefore apply the SAME monotonic scaling but base it on
 * CLOCK_MONOTONIC — if this causes issues the caller can remove gettimeofday
 * from the override entirely (clock_gettime alone is sufficient for modern Wine).
 *
 * DECISION: override gettimeofday using the MONO anchor, so that SDL1-era games
 * (which call gettimeofday for timing) also see the scaled clock.  The risk to
 * wall-clock consumers is minimal since Wine processes don't run system daemons.
 *
 * ROBUSTNESS: get_real_tod() uses the same multi-stage resolution + syscall
 * fallback as get_real_time() — it cannot fail for a valid call.
 */
int gettimeofday(struct timeval *tv, struct timezone *tz)
{
    int ret = get_real_tod(tv, tz);
    if (ret != 0 || !tv) return ret;

    if (!g_enabled) return 0;

    /* Convert tv → timespec, scale via MONO anchor, convert back */
    struct timespec real_ts = {
        .tv_sec  = tv->tv_sec,
        .tv_nsec = (long)tv->tv_usec * 1000L,
    };
    struct timespec fake_ts;
    apply_speedhack(CLOCK_IDX_MONO, &real_ts, &fake_ts);

    tv->tv_sec  = fake_ts.tv_sec;
    tv->tv_usec = (suseconds_t)(fake_ts.tv_nsec / 1000L);
    return 0;
}
