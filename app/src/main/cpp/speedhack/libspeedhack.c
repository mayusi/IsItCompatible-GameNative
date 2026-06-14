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

#include <android/log.h>

#include "speedhack_protocol.h"

#define LOG_TAG "libspeedhack"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ---- real function pointer ---- */
typedef int (*real_clock_gettime_fn)(clockid_t, struct timespec *);
typedef int (*real_gettimeofday_fn)(struct timeval *, struct timezone *);

static real_clock_gettime_fn  real_clock_gettime  = NULL;
static real_gettimeofday_fn   real_gettimeofday   = NULL;

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
 * real = the value returned by the real clock_gettime (input)
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
    /* Resolve real functions first — needed even in passthrough mode */
    real_clock_gettime = (real_clock_gettime_fn)dlsym(RTLD_NEXT, "clock_gettime");
    if (!real_clock_gettime) {
        LOGE("dlsym(clock_gettime) failed: %s — speedhack disabled", dlerror());
        return;
    }

    real_gettimeofday = (real_gettimeofday_fn)dlsym(RTLD_NEXT, "gettimeofday");
    /* gettimeofday failure is non-fatal: we just won't override it */

    const char *enabled = getenv("SPEEDHACK_ENABLED");
    if (!enabled || strcmp(enabled, "1") != 0) {
        /* Not enabled: keep g_enabled=0, clock_gettime is a pure passthrough */
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
    if (!real_clock_gettime) {
        /* Should never happen (constructor would have bailed), but be safe */
        errno = EINVAL;
        return -1;
    }

    int ret = real_clock_gettime(clk_id, tp);
    if (ret != 0) return ret;  /* propagate errors from real clock */

    if (!g_enabled) return 0;  /* passthrough when disabled */

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
 */
int gettimeofday(struct timeval *tv, struct timezone *tz)
{
    if (!real_gettimeofday) {
        /* Fallback: use clock_gettime to implement it */
        struct timespec ts;
        int r = clock_gettime(CLOCK_REALTIME, &ts);
        if (r == 0 && tv) {
            tv->tv_sec  = ts.tv_sec;
            tv->tv_usec = (suseconds_t)(ts.tv_nsec / 1000);
        }
        return r;
    }

    int ret = real_gettimeofday(tv, tz);
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
