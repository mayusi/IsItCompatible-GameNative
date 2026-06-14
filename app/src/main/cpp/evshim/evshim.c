#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <pthread.h>
#include <unistd.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <linux/futex.h>
#include <sys/syscall.h>
#include <jni.h>
#include <SDL2/SDL.h>
#include <android/log.h>
#include <sys/stat.h>
#include <time.h>
#include <limits.h>

#include "macro_protocol.h"

static int g_debug_enabled = 0;
#define LOGI(...) dprintf(STDOUT_FILENO, __VA_ARGS__)
#define LOGE(...) dprintf(STDERR_FILENO, __VA_ARGS__)
#define LOGD(...) do { if (g_debug_enabled) dprintf(STDOUT_FILENO, __VA_ARGS__); } while (0)

#define LOG_TAG "evshim"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) do { if (g_debug_enabled) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)

/* -- Shared memory layout --
 *
 *  offset  size  field
 *  ------  ----  -----
 *       0     4  seq              — futex word
 *       4     2  lx
 *       6     2  ly
 *       8     2  rx
 *      10     2  ry
 *      12     2  lt
 *      14     2  rt
 *      16    15  btn[15]
 *      31     1  hat
 *      32     2  low_freq_rumble
 *      34     2  high_freq_rumble
 *      36     4  rumble_seq       — futex word (rumble: Wine -> Java)
 *                                   total: 40 bytes
 */

#define SHM_DATA_SIZE  64
#define MAX_GAMEPADS    4

struct gamepad_state {
    int16_t  lx, ly, rx, ry, lt, rt;
    uint8_t  btn[15];
    uint8_t  hat;
    uint16_t low_freq_rumble;
    uint16_t high_freq_rumble;
};

struct gamepad_io {
    atomic_uint       seq;
    struct gamepad_state state;
    atomic_uint       rumble_seq;
};

_Static_assert(sizeof(struct gamepad_io) <= SHM_DATA_SIZE, "gamepad_io exceeds SHM_DATA_SIZE");

static struct gamepad_io *shm [MAX_GAMEPADS];
static int vjoy_ids[MAX_GAMEPADS];
static size_t g_shm_map_size = 0;
static int g_is_wine = 0;

/*
 * FIX 1: derive the package base path at runtime rather than using a
 * hardcoded string. When EVSHIM_BASE_PATH is not set (Java-side load),
 * read /proc/self/cmdline — on Android that contains the package name —
 * and build /data/data/<pkg>/files.  This works for both app.gamenative
 * and app.gamenative.iic without hard-coding either suffix.
 * Falls back to /data/data/app.gamenative.iic/files if the cmdline read
 * fails, which is correct for the IIC fork.
 */
static void build_gamepad_dir(char *out, size_t size)
{
    const char *base = getenv("EVSHIM_BASE_PATH");

    if (!base || !*base) {
        /* Read process name from /proc/self/cmdline.
         * cmdline is NUL-delimited; the first token is the package name
         * (or package:process for sub-processes — strip from the colon). */
        static char derived_base[PATH_MAX];
        derived_base[0] = '\0';

        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            char cmdline[256];
            ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
            close(fd);
            if (n > 0) {
                cmdline[n] = '\0';
                /* Strip ":processname" suffix if present */
                char *colon = strchr(cmdline, ':');
                if (colon) *colon = '\0';
                /* Sanity: package name should contain at least one dot */
                if (strchr(cmdline, '.') != NULL && cmdline[0] != '\0') {
                    snprintf(derived_base, sizeof(derived_base),
                             "/data/data/%s/files", cmdline);
                    base = derived_base;
                    ALOGI("evshim: derived base path from cmdline: %s", derived_base);
                }
            }
        }

        /* Final fallback: hardcode the correct IIC package path */
        if (!base || !*base) {
            base = "/data/data/app.gamenative.iic/files";
            ALOGI("evshim: using hardcoded fallback base path: %s", base);
        }
    }

    snprintf(out, size, "%s/gamepad_shm", base);
}

static int mkdir_gameshm(const char *path)
{
    struct stat st;

    if (stat(path, &st) == 0) {
        if (S_ISDIR(st.st_mode)) {
            return 0;
        }

        errno = ENOTDIR;
        return -1;
    }

    /* SEC-5: restrict directory to owner-only (0700) — world-readable directories
     * allow other processes to enumerate the gamepad_shm directory entries. */
    if (mkdir(path, 0700) < 0 && errno != EEXIST) {
        return -1;
    }

    return 0;
}

// mmap setup
// java side still need to get the shm here to get the futex word address
static void setup_shm(int players)
{
    g_shm_map_size = (size_t)sysconf(_SC_PAGESIZE);

    char gamepad_dir[PATH_MAX];
    build_gamepad_dir(gamepad_dir, sizeof(gamepad_dir));

    if (mkdir_gameshm(gamepad_dir) < 0) {
        LOGE("evshim: failed to create/check dir '%s': %s\n",
             gamepad_dir,
             strerror(errno));
        return;
    }

    for (int i = 0; i < players; i++) {
        char path[PATH_MAX];
        snprintf(path, sizeof(path),
                 "%s/gamepad%s.mem",
                 gamepad_dir,
                 (i == 0) ? "" : (char[2]){'0' + i, '\0'});

        /* SEC-5: 0666 would allow any process on the device to read/write the
         * gamepad shared-memory file.  Restrict to owner-only (0600) so only
         * the app process itself can access controller state and rumble data. */
        int fd = open(path, O_RDWR | O_CREAT, 0600);
        if (fd < 0) {
            LOGE("evshim: P%d open '%s' failed: %s\n", i, path, strerror(errno));
            continue;
        }

        if (ftruncate(fd, SHM_DATA_SIZE) < 0) {
            LOGE("evshim: P%d ftruncate failed: %s\n", i, strerror(errno));
            close(fd);
            continue;
        }

        shm[i] = mmap(NULL, g_shm_map_size,
                      PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        struct stat st;
        fstat(fd, &st);
        ALOGI("evshim: java P%d mmap'd inode=%lu dev=%lu addr=%p\n",
             i, (unsigned long)st.st_ino,
             (unsigned long)st.st_dev,
             (void *)shm[i]);
        LOGI("evshim: P%d mmap'd inode=%lu dev=%lu addr=%p\n",
             i, (unsigned long)st.st_ino,
             (unsigned long)st.st_dev,
             (void *)shm[i]);
        close(fd);

        if (shm[i] == MAP_FAILED) {
            LOGE("evshim: P%d mmap failed: %s\n", i, strerror(errno));
            shm[i] = NULL;
            continue;
        }

        if (!g_is_wine) {
            ALOGI("evshim: resetting controller state");
            memset(shm[i], 0, sizeof(struct gamepad_io));
        }
    }
}

static void        *sdl_handle = NULL;

static int          (*p_SDL_Init)                   (uint32_t);
static const char  *(*p_SDL_GetError)               (void);
static SDL_Joystick*(*p_SDL_JoystickOpen)           (int);
static int          (*p_SDL_JoystickAttachVirtualEx) (const SDL_VirtualJoystickDesc *);
static int          (*p_SDL_JoystickSetVirtualAxis)  (SDL_Joystick *, int, int16_t);
static int          (*p_SDL_JoystickSetVirtualButton)(SDL_Joystick *, int, uint8_t);
static int          (*p_SDL_JoystickSetVirtualHat)   (SDL_Joystick *, int, uint8_t);
static void         (*p_SDL_GetVersion)              (SDL_version *);

#define GETFUNCPTR(name) \
    do { \
        if (!(p_##name = (typeof(p_##name))dlsym(sdl_handle, #name))) \
            LOGE("evshim: failed to load SDL symbol: %s\n", #name); \
    } while (0)

static int OnRumble(void *userdata, uint16_t low, uint16_t high)
{
    int idx = (int)(intptr_t)userdata;
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return -1;

    shm[idx]->state.low_freq_rumble  = low;
    shm[idx]->state.high_freq_rumble = high;

    // Wake up the Java thread waiting for rumble updates
    atomic_thread_fence(memory_order_seq_cst);
    atomic_fetch_add_explicit(&shm[idx]->rumble_seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAKE, 1, NULL, NULL, 0);

    LOGD("evshim: rumble P%d low=%u high=%u\n", idx, low, high);
    return 0;
}

static bool try_read_state(struct gamepad_io *s, uint32_t *last_seq, struct gamepad_io *out)
{
    uint32_t seq1 = atomic_load_explicit(&s->seq, memory_order_acquire);
    if (seq1 == *last_seq) {
        return false;
    }

    // copy controller state
    out->state = s->state;

    uint32_t seq2 = atomic_load_explicit(&s->seq, memory_order_acquire);
    if (seq2 != seq1) {
        // android side updates at the screen refresh rate
        // which is at a much slower pace than the unbounded loop in the wine processes
        // this should never happen under normal circumstances
        LOGD("evshim: seq race in try_read_state, retrying\n");
        return false;
    }

    *last_seq = seq2;
    return true;
}

/* =========================================================================
 * Macro / Turbo subsystem
 *
 * ACTIVATION: enabled only when MACRO_ENABLED=1 is in the environment.
 * When disabled (the default), g_macro_enabled == 0 and the entire block
 * is skipped — zero cost, zero risk to existing controller behaviour.
 *
 * The macro SHM is opened once in initialize_all_pads(); on failure we
 * log and set g_macro_shm = NULL, which causes all per-tick transform
 * calls to be no-ops (same as disabled).
 *
 * Per-player state is stored in vjoy_updater_state (stack-allocated in each
 * vjoy_updater thread, never shared) — no cross-thread races.
 * ========================================================================= */

static int               g_macro_enabled = 0;
static struct macro_shm *g_macro_shm     = NULL;  /* mmap'd macro control block */
static size_t            g_macro_map_size = 0;

/*
 * Tick rate used for the timed futex wait in vjoy_updater.
 * 8 ms => ~120 Hz tick; fine enough for 10Hz turbo (5 ticks per half-period)
 * while still waking immediately on real input (futex wakes on seq change).
 */
#define VJOY_TICK_NS  8000000L   /* 8 ms in nanoseconds */
#define VJOY_TICK_HZ  125        /* 1000 / 8 = 125 Hz */

/* Per-player per-button turbo phase counter (counts ticks; flips at half-period) */
typedef struct {
    uint32_t turbo_tick[MACRO_BTN_COUNT];   /* ticks since last turbo toggle, per button */
    uint8_t  turbo_phase[MACRO_BTN_COUNT];  /* current output: 0 = pressed, 1 = released */
} turbo_state_t;

/* Per-player macro playback state (one per vjoy_updater thread) */
typedef struct {
    int       active_slot;    /* -1 = not playing; 0..MACRO_SLOT_COUNT-1 = playing */
    int       cur_step;       /* current step index within the active macro */
    uint64_t  step_start_ns;  /* monotonic nanoseconds when the current step started */
} macro_play_t;

/* Snapshot of macro config (re-read when config_seq changes) */
typedef struct {
    uint32_t          config_seq;
    uint16_t          turbo_mask;
    uint8_t           turbo_rate_hz;
    struct macro_slot slots[MACRO_SLOT_COUNT];
} macro_config_t;

/* Get monotonic nanoseconds (CLOCK_MONOTONIC) */
static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

/*
 * setup_macro_shm — open / create / mmap the macro control block.
 * Called once from initialize_all_pads() when MACRO_ENABLED=1.
 * On any error: logs and returns without setting g_macro_shm (feature degrades
 * to no-op rather than crashing).
 */
static void setup_macro_shm(void)
{
    const char *base = getenv("MACRO_BASE_PATH");
    if (!base || !*base) {
        /* Fall back to EVSHIM_BASE_PATH if MACRO_BASE_PATH not set separately */
        base = getenv("EVSHIM_BASE_PATH");
    }
    if (!base || !*base) {
        ALOGE("evshim: macro: neither MACRO_BASE_PATH nor EVSHIM_BASE_PATH set; "
              "macro feature disabled\n");
        return;
    }

    /* Create <base>/macro_shm/ directory (0700) */
    char dir[PATH_MAX];
    snprintf(dir, sizeof(dir), "%s/macro_shm", base);

    struct stat st;
    if (stat(dir, &st) != 0) {
        if (mkdir(dir, 0700) < 0 && errno != EEXIST) {
            ALOGE("evshim: macro: mkdir '%s' failed: %s\n", dir, strerror(errno));
            return;
        }
    } else if (!S_ISDIR(st.st_mode)) {
        ALOGE("evshim: macro: '%s' exists but is not a directory\n", dir);
        return;
    }

    /* Open / create the control block file (0600) */
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/macro.mem", dir);

    int fd = open(path, O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        ALOGE("evshim: macro: open '%s' failed: %s\n", path, strerror(errno));
        return;
    }

    if (ftruncate(fd, MACRO_SHM_SIZE) < 0) {
        ALOGE("evshim: macro: ftruncate failed: %s\n", strerror(errno));
        close(fd);
        return;
    }

    g_macro_map_size = (size_t)sysconf(_SC_PAGESIZE);
    void *mapped = mmap(NULL, g_macro_map_size,
                        PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);

    if (mapped == MAP_FAILED) {
        ALOGE("evshim: macro: mmap failed: %s\n", strerror(errno));
        g_macro_map_size = 0;
        return;
    }

    g_macro_shm = (struct macro_shm *)mapped;

    /* Write magic + version so Kotlin can detect that the shim has opened the file. */
    g_macro_shm->magic         = MACRO_SHM_MAGIC;
    g_macro_shm->proto_version = MACRO_PROTO_VERSION;
    /* config_seq starts at 0; turbo_mask/turbo_rate_hz zeroed = no turbo by default */

    ALOGI("evshim: macro shm ready at %s\n", path);
}

/*
 * macro_read_config — copy config from the shared block into a local snapshot.
 * Must only be called when g_macro_shm != NULL.
 */
static void macro_read_config(macro_config_t *cfg)
{
    /* Plain (non-atomic) reads — safe here because:
     *   1. config_seq is read FIRST (acquire); the shim acts on the values only
     *      after observing the new seq, so the happen-before edge is maintained
     *      as long as the UI writes fields before bumping config_seq.
     *   2. All config fields are read by a single reader thread (this player's
     *      vjoy_updater) and written infrequently by the UI — benign if a torn
     *      read occurs on a 16-bit field: we'll just re-read on the next seq bump.
     */
    cfg->config_seq    = g_macro_shm->config_seq;
    cfg->turbo_mask    = g_macro_shm->turbo_mask;
    cfg->turbo_rate_hz = g_macro_shm->turbo_rate_hz;
    /* Clamp rate to valid range */
    if (cfg->turbo_rate_hz < 1)   cfg->turbo_rate_hz = 1;
    if (cfg->turbo_rate_hz > 120) cfg->turbo_rate_hz = 120;
    for (int s = 0; s < (int)MACRO_SLOT_COUNT; s++) {
        cfg->slots[s] = g_macro_shm->slots[s];
    }
}

/*
 * macro_transform — apply turbo + macro playback transforms to output_btn[].
 *
 * Called from vjoy_updater once per tick, AFTER reading the gamepad snapshot but
 * BEFORE writing to SDL. Modifies output_btn[] in-place.
 *
 * Parameters:
 *   output_btn  — 15-element array; initialised to snap.state.btn[], modified here.
 *   snap        — raw gamepad snapshot (read-only; used to detect button transitions).
 *   prev_btn    — output_btn[] from the PREVIOUS tick (for trigger edge detection).
 *   turbo       — per-player turbo state (mutated in-place).
 *   play        — per-player macro playback state (mutated in-place).
 *   cfg         — current macro config snapshot.
 */
static void macro_transform(
    uint8_t          output_btn[MACRO_BTN_COUNT],
    const struct gamepad_state *snap,
    const uint8_t    prev_btn[MACRO_BTN_COUNT],
    turbo_state_t   *turbo,
    macro_play_t    *play,
    const macro_config_t *cfg)
{
    const uint64_t now = now_ns();

    /* ---- TURBO ---- */
    for (int i = 0; i < (int)MACRO_BTN_COUNT; i++) {
        if (!(cfg->turbo_mask & (1u << i))) {
            /* Turbo not enabled for this button — pass through raw value */
            turbo->turbo_tick[i]  = 0;
            turbo->turbo_phase[i] = 0;
            continue;
        }

        if (!snap->btn[i]) {
            /* Button is physically released — always output released, reset phase */
            output_btn[i] = 0;
            turbo->turbo_tick[i]  = 0;
            turbo->turbo_phase[i] = 0;
            continue;
        }

        /* Button is held. Compute half-period in ticks at VJOY_TICK_HZ. */
        uint32_t half_period = (uint32_t)VJOY_TICK_HZ / (2u * (uint32_t)cfg->turbo_rate_hz);
        if (half_period < 1) half_period = 1;

        turbo->turbo_tick[i]++;
        if (turbo->turbo_tick[i] >= half_period) {
            turbo->turbo_tick[i] = 0;
            turbo->turbo_phase[i] ^= 1u;
        }

        /* Phase 0 = fire (pressed), Phase 1 = suppress (released) */
        output_btn[i] = turbo->turbo_phase[i] ? 0u : 1u;
    }

    /* ---- MACRO PLAYBACK ---- */
    /*
     * First pass: check each slot for a trigger edge (released->pressed).
     * We only start a new macro if no macro is currently playing.
     */
    if (play->active_slot < 0) {
        for (int s = 0; s < (int)MACRO_SLOT_COUNT; s++) {
            const struct macro_slot *slot = &cfg->slots[s];
            if (slot->trigger_btn == 0xFF || slot->step_count == 0) continue;
            int t = (int)slot->trigger_btn;
            if (t >= (int)MACRO_BTN_COUNT) continue;

            /* Rising edge: was released last tick, now pressed */
            bool was_released = (prev_btn[t] == 0);
            bool is_pressed   = (snap->btn[t] != 0);
            if (was_released && is_pressed) {
                play->active_slot  = s;
                play->cur_step     = 0;
                play->step_start_ns = now;
                ALOGD("evshim: macro slot %d triggered by btn %d\n", s, t);
                break;
            }
        }
    }

    /* Second pass: if a macro is playing, apply its current step */
    if (play->active_slot >= 0) {
        int s = play->active_slot;
        const struct macro_slot *slot = &cfg->slots[s];

        /* Safety: if config changed and the slot is now invalid, abort */
        if (s >= (int)MACRO_SLOT_COUNT ||
            slot->trigger_btn == 0xFF ||
            slot->step_count == 0 ||
            play->cur_step >= (int)slot->step_count) {
            play->active_slot = -1;
            goto macro_done;
        }

        const struct macro_step *step = &slot->steps[play->cur_step];
        uint32_t duration_ms = step->duration_ms;
        if (duration_ms < 1)    duration_ms = 1;
        if (duration_ms > 5000) duration_ms = 5000;

        /* Check if this step has elapsed */
        uint64_t elapsed_ns = now - play->step_start_ns;
        if (elapsed_ns >= (uint64_t)duration_ms * 1000000ULL) {
            play->cur_step++;
            play->step_start_ns = now;

            if (play->cur_step >= (int)slot->step_count) {
                /* Macro finished */
                ALOGD("evshim: macro slot %d finished\n", s);
                play->active_slot = -1;
                goto macro_done;
            }

            step = &slot->steps[play->cur_step];
        }

        /* Apply the step: OR the step's btn_mask into output_btn[].
         * Suppress the trigger button while the macro runs. */
        uint16_t mask = step->btn_mask;
        for (int i = 0; i < (int)MACRO_BTN_COUNT; i++) {
            if (mask & (1u << i)) {
                output_btn[i] = 1;
            }
        }
        /* Suppress trigger button during playback */
        int t = (int)slot->trigger_btn;
        if (t < (int)MACRO_BTN_COUNT) {
            output_btn[t] = 0;
        }
    }

macro_done:;
}

/*
 * FIX 2: SDL_JoystickOpen can return NULL if Wine's own SDL init races
 * and re-initialises the joystick subsystem between SDL_JoystickAttachVirtualEx
 * and the Open call, invalidating the virtual joystick id.  Add a retry loop:
 * on NULL, re-attach the virtual joystick (getting a fresh id) and retry Open
 * a few times with brief sleeps.  This also covers the case where initialize_wine()
 * runs slightly before Wine finishes its own SDL joystick init.
 */
static void *vjoy_updater(void *arg)
{
    int idx = (int)(intptr_t)arg;
    struct gamepad_io *s = shm[idx];

    SDL_Joystick *js = NULL;
    const int MAX_ATTACH_RETRIES = 5;
    const int RETRY_SLEEP_MS     = 500; /* 500 ms between retries */

    for (int attempt = 0; attempt < MAX_ATTACH_RETRIES && !js; attempt++) {
        if (attempt > 0) {
            LOGI("evshim: P%d vjoy open retry %d/%d after %d ms\n",
                 idx, attempt, MAX_ATTACH_RETRIES - 1, RETRY_SLEEP_MS);
            struct timespec ts = { .tv_sec = 0, .tv_nsec = RETRY_SLEEP_MS * 1000000L };
            nanosleep(&ts, NULL);

            /* Re-attach the virtual joystick to get a fresh device id */
            SDL_VirtualJoystickDesc d = {0};
            d.version    = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
            d.type       = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
            d.naxes      = 6; d.nbuttons = 15; d.nhats = 1;
            d.Rumble     = &OnRumble; d.userdata = (void*)(intptr_t)idx;
            d.vendor_id  = 0x045E;  /* Microsoft */
            d.product_id = 0x028E;  /* Xbox 360 Controller */
            d.button_mask = 0xFFFF;
            d.axis_mask   = 0x3F;
            d.name = "Xbox 360 Controller";

            int new_id = p_SDL_JoystickAttachVirtualEx(&d);
            if (new_id < 0) {
                LOGE("evshim: P%d re-attach failed on retry %d: %s\n",
                     idx, attempt, p_SDL_GetError());
                continue;
            }
            vjoy_ids[idx] = new_id;
            LOGD("evshim: P%d re-attached virtual joystick id=%d\n", idx, new_id);
        }

        js = p_SDL_JoystickOpen(vjoy_ids[idx]);
    }

    if (!js) {
        LOGE("evshim: P%d SDL_JoystickOpen failed after %d attempts; giving up\n",
             idx, MAX_ATTACH_RETRIES);
        return NULL;
    }

    LOGI("evshim: vjoy_updater P%d running (PID %d)\n", idx, getpid());

    uint32_t last_seq = atomic_load_explicit(&s->seq, memory_order_acquire);

    /* ---- macro / turbo per-player state (stack-allocated, not shared) ---- */
    turbo_state_t turbo;
    memset(&turbo, 0, sizeof(turbo));

    macro_play_t play;
    play.active_slot  = -1;
    play.cur_step     = 0;
    play.step_start_ns = 0;

    macro_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));

    /* Track last observed config_seq to detect UI config changes */
    uint32_t last_cfg_seq = (uint32_t)-1;

    /* Track previous output_btn for macro trigger edge detection */
    uint8_t prev_btn[MACRO_BTN_COUNT];
    memset(prev_btn, 0, sizeof(prev_btn));

    /* Timespec for the timed futex wait */
    struct timespec tick_ts = { .tv_sec = 0, .tv_nsec = VJOY_TICK_NS };

    for (;;) {
        struct gamepad_io snap;
        bool got_new_state = try_read_state(s, &last_seq, &snap);

        if (!got_new_state) {
            /*
             * No new gamepad event. Use a timed futex wait so turbo toggling
             * and macro playback advance even when the controller state is idle.
             * FUTEX_WAIT_PRIVATE with a timeout wakes on:
             *   (a) a new state event (seq changed -> FUTEX_WAKE from notifyStateChanged)
             *   (b) timeout expiry (tick) — we drive turbo/macro from these ticks
             * If seq changed between our check and the syscall, the kernel returns
             * EAGAIN immediately (no lost wakeup).
             */
            syscall(SYS_futex, &s->seq, FUTEX_WAIT, last_seq, &tick_ts, NULL, 0);
            /* Re-read state after wakeup */
            got_new_state = try_read_state(s, &last_seq, &snap);
        }

        /* If macro is disabled, fall through to direct SDL writes (original behaviour) */
        if (!g_macro_enabled || !g_macro_shm) {
            if (got_new_state) {
                p_SDL_JoystickSetVirtualAxis(js, 0, snap.state.lx);
                p_SDL_JoystickSetVirtualAxis(js, 1, snap.state.ly);
                p_SDL_JoystickSetVirtualAxis(js, 2, snap.state.rx);
                p_SDL_JoystickSetVirtualAxis(js, 3, snap.state.ry);
                p_SDL_JoystickSetVirtualAxis(js, 4, snap.state.lt);
                p_SDL_JoystickSetVirtualAxis(js, 5, snap.state.rt);
                for (int i = 0; i < 15; i++) {
                    p_SDL_JoystickSetVirtualButton(js, i, snap.state.btn[i]);
                }
                p_SDL_JoystickSetVirtualHat(js, 0, snap.state.hat);
            }
            continue;
        }

        /*
         * Macro/turbo is enabled.
         * We tick every loop iteration (real input wakeup OR timer tick)
         * so turbo + macro timing is driven by wall-clock ticks.
         * If we didn't get new state, reuse the last known snap to allow
         * turbo/macro to advance without an actual gamepad event.
         */
        if (!got_new_state) {
            /* Reuse whatever snap was last written; we need the last known
             * button state for turbo/macro logic. Build it from the SHM directly
             * (no seq check needed — we just need the last stable value). */
            snap.state = s->state;
        }

        /* Re-read macro config if UI bumped config_seq */
        uint32_t cur_cfg_seq = g_macro_shm->config_seq;
        if (cur_cfg_seq != last_cfg_seq) {
            macro_read_config(&cfg);
            last_cfg_seq = cfg.config_seq;
            /* Reset turbo phases on config change so stale phase doesn't persist */
            memset(&turbo, 0, sizeof(turbo));
        }

        /* Build output button array starting from raw snapshot */
        uint8_t output_btn[MACRO_BTN_COUNT];
        for (int i = 0; i < (int)MACRO_BTN_COUNT; i++) {
            output_btn[i] = snap.state.btn[i];
        }

        /* Apply turbo + macro transform */
        macro_transform(output_btn, &snap.state, prev_btn, &turbo, &play, &cfg);

        /* Update prev_btn for next tick (use raw snap for edge detection) */
        for (int i = 0; i < (int)MACRO_BTN_COUNT; i++) {
            prev_btn[i] = snap.state.btn[i];
        }

        /* Write axes (always pass through unchanged) */
        p_SDL_JoystickSetVirtualAxis(js, 0, snap.state.lx);
        p_SDL_JoystickSetVirtualAxis(js, 1, snap.state.ly);
        p_SDL_JoystickSetVirtualAxis(js, 2, snap.state.rx);
        p_SDL_JoystickSetVirtualAxis(js, 3, snap.state.ry);
        p_SDL_JoystickSetVirtualAxis(js, 4, snap.state.lt);
        p_SDL_JoystickSetVirtualAxis(js, 5, snap.state.rt);

        /* Write transformed buttons */
        for (int i = 0; i < 15; i++) {
            p_SDL_JoystickSetVirtualButton(js, i, output_btn[i]);
        }

        p_SDL_JoystickSetVirtualHat(js, 0, snap.state.hat);
    }

    return NULL;
}

static void initialize_wine(int players)
{
    sdl_handle = dlopen("libSDL2-2.0.so.0", RTLD_LAZY | RTLD_GLOBAL);
    if (!sdl_handle) { LOGE("dlopen SDL failed: %s\n", dlerror()); return; }

    GETFUNCPTR(SDL_Init);  GETFUNCPTR(SDL_GetError);
    GETFUNCPTR(SDL_JoystickOpen);  GETFUNCPTR(SDL_JoystickAttachVirtualEx);
    GETFUNCPTR(SDL_JoystickSetVirtualAxis);  GETFUNCPTR(SDL_JoystickSetVirtualButton);
    GETFUNCPTR(SDL_JoystickSetVirtualHat);
    GETFUNCPTR(SDL_GetVersion);
    if (!p_SDL_Init || !p_SDL_GetError || !p_SDL_JoystickOpen ||
                !p_SDL_JoystickAttachVirtualEx || !p_SDL_JoystickSetVirtualAxis ||
                !p_SDL_JoystickSetVirtualButton || !p_SDL_JoystickSetVirtualHat ||
                !p_SDL_GetVersion) {
        LOGE("evshim: SDL symbol resolution incomplete; aborting init\n");
        dlclose(sdl_handle);
        sdl_handle = NULL;
        return;
    }

    p_SDL_Init(SDL_INIT_JOYSTICK);

    SDL_version v;
    p_SDL_GetVersion(&v);
    LOGI("evshim: SDL %d.%d.%d bound\n", v.major, v.minor, v.patch);

    for (int i = 0; i < players; i++) {
        if (!shm[i]) continue;

        SDL_VirtualJoystickDesc d = {0};
        d.version = SDL_VIRTUAL_JOYSTICK_DESC_VERSION;
        d.type    = SDL_JOYSTICK_TYPE_GAMECONTROLLER;
        d.naxes   = 6; d.nbuttons = 15; d.nhats = 1;
        d.Rumble  = &OnRumble;  d.userdata = (void*)(intptr_t)i;
        d.vendor_id = 0x045E;  // Microsoft
        d.product_id = 0x028E; // Xbox 360 Controller
        d.button_mask = 0xFFFF;
        d.axis_mask = 0x3F;

        char name[64];
        snprintf(name, sizeof name, "Xbox 360 Controller");
        d.name = strdup(name);

        vjoy_ids[i] = p_SDL_JoystickAttachVirtualEx(&d);
        if (vjoy_ids[i] < 0) {
            LOGE("evshim: P%d SDL attach failed: %s\n", i, p_SDL_GetError());
            munmap(shm[i], g_shm_map_size);
            shm[i] = NULL;
            continue;
        }

        LOGD("evshim: P%d virtual joystick id=%d ready\n", i, vjoy_ids[i]);

        pthread_t tid;
        pthread_create(&tid, NULL, vjoy_updater, (void *)(intptr_t)i);
        pthread_detach(tid);
    }
}

__attribute__((constructor))
static void initialize_all_pads(void)
{
    g_is_wine = getenv("EVSHIM_WINE") != NULL;

    const char *dbg = getenv("EVSHIM_DEBUG");
    g_debug_enabled = dbg && strchr("1yY", *dbg);

    int players = 1;
    const char *ep = getenv("EVSHIM_MAX_PLAYERS");
    if (ep) players = atoi(ep);
    if (players > MAX_GAMEPADS) players = MAX_GAMEPADS;

    /* Macro / Turbo subsystem — gated on MACRO_ENABLED=1 */
    const char *me = getenv("MACRO_ENABLED");
    g_macro_enabled = (me && me[0] == '1');
    if (g_macro_enabled && g_is_wine) {
        /* Only the Wine side runs vjoy_updater threads that apply the transform.
         * The Java side (WinHandler) just writes to SHM — it doesn't need the macro shm. */
        setup_macro_shm();
    }

    setup_shm(players);

    if (g_is_wine) {
        LOGI("evshim: Wine process init (%d player(s), macro=%d)\n",
             players, g_macro_enabled);
        initialize_wine(players);
    } else {
        ALOGI("evshim: Java process init (%d player(s), macro=%d)\n",
              players, g_macro_enabled);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_notifyStateChanged(JNIEnv *env, jclass cls, jint idx)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return;

    atomic_thread_fence(memory_order_seq_cst); // not sure if necessary
    atomic_fetch_add_explicit(&shm[idx]->seq, 1u, memory_order_release);
    syscall(SYS_futex, &shm[idx]->seq, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

JNIEXPORT jint JNICALL
Java_com_winlator_winhandler_WinHandler_waitForRumble(JNIEnv *env, jclass cls, jint idx, jint last_seq)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return last_seq;

    uint32_t current_seq = atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);

    if (current_seq != (uint32_t)last_seq) {
        return current_seq;
    }

    // sleep until Wine triggers OnRumble or teardown signaled
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAIT, current_seq, NULL, NULL, 0);

    return atomic_load_explicit(&shm[idx]->rumble_seq, memory_order_acquire);
}

JNIEXPORT void JNICALL
Java_com_winlator_winhandler_WinHandler_rumbleTeardown(JNIEnv *env, jclass cls, jint idx)
{
    if (idx < 0 || idx >= MAX_GAMEPADS || !shm[idx]) return;
    syscall(SYS_futex, &shm[idx]->rumble_seq, FUTEX_WAKE, 1, NULL, NULL, 0);
}
