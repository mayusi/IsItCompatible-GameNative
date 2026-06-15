/*
 * libtrainer.c — in-process memory scanner/freezer for the GameNative IIC fork.
 *
 * Loaded via LD_PRELOAD into the Box64/Wine host process. Because it is
 * in-process, it reads and writes the SAME address space without ptrace or root.
 * All memory access goes through /proc/self/mem (pread/pwrite) so unmapped or
 * guard pages return EIO instead of SIGSEGV, making the scan crash-safe.
 *
 * Communication with the Android UI:
 *   - One mmap'd file: <TRAINER_BASE_PATH>/trainer_shm/trainer.mem  (see header)
 *   - cmd_seq  : Android bumps + FUTEX_WAKEs after writing a command
 *   - resp_seq : worker bumps + FUTEX_WAKEs after writing a response
 *
 * Gate: the constructor is a complete no-op unless TRAINER_ENABLED=1 is set
 * in the environment (the Android side sets this before launching the game).
 * If setup fails for any reason, the library silently does nothing; the game
 * keeps running normally.
 *
 * Concurrency model:
 *   - One worker thread:  waits on cmd_seq, dispatches commands one at a time.
 *   - One freeze thread:  loops every ~50 ms re-writing all frozen values.
 *   - The worker and freeze thread share the freeze table via a pthread_mutex.
 *   - No other game threads are touched or blocked.
 *   - All shm writes precede the resp_seq bump so the Android side always sees
 *     a consistent snapshot.
 */

#define _GNU_SOURCE
#include <stdatomic.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>      /* kill() for stale-owner detection */
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <linux/futex.h>
#include <limits.h>
#include <ctype.h>
#include <android/log.h>

#include "trainer_protocol.h"

/* ---- logging ------------------------------------------------------------ */
#define LOG_TAG "trainer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* ---- limits ------------------------------------------------------------- */

/* Maximum tracked addresses in the internal match table.
 * If a scan finds more matches than this we set TST_TOO_MANY.
 * 1 MiB of addresses @ 8 bytes each = 131072 entries → ~1 MB heap. */
#define MATCH_CAP (1u << 20)

/* /proc/self/maps line buffer */
#define MAPS_LINE_MAX 256

/* Freeze check interval (nanoseconds) */
#define FREEZE_INTERVAL_NS (50 * 1000 * 1000L)  /* 50 ms */

/* ---- value size helper -------------------------------------------------- */
static inline size_t vtype_size(uint32_t vtype)
{
    switch (vtype) {
        case TVT_I8:  case TVT_U8:  return 1;
        case TVT_I16: case TVT_U16: return 2;
        case TVT_I32: case TVT_U32: return 4;
        case TVT_I64: case TVT_U64: return 8;
        case TVT_F32:               return 4;
        case TVT_F64:               return 8;
        default:                    return 0;
    }
}

/* ---- value comparison helpers ------------------------------------------ */

/* Compare two raw 8-byte slots interpreted as the given vtype.
 * Returns:
 *   0  : a == b
 *  >0  : a >  b  (signed/unsigned per type)
 *  <0  : a <  b
 */
static int value_cmp(uint64_t a_raw, uint64_t b_raw, uint32_t vtype)
{
#define EXTRACT(T, raw) ({ T _v; memcpy(&_v, &(raw), sizeof(_v)); _v; })
    switch (vtype) {
        case TVT_I8:  { int8_t  a=EXTRACT(int8_t,a_raw),  b=EXTRACT(int8_t,b_raw);  return (a>b)-(a<b); }
        case TVT_U8:  { uint8_t a=EXTRACT(uint8_t,a_raw), b=EXTRACT(uint8_t,b_raw); return (a>b)-(a<b); }
        case TVT_I16: { int16_t a=EXTRACT(int16_t,a_raw), b=EXTRACT(int16_t,b_raw); return (a>b)-(a<b); }
        case TVT_U16: { uint16_t a=EXTRACT(uint16_t,a_raw),b=EXTRACT(uint16_t,b_raw);return (a>b)-(a<b); }
        case TVT_I32: { int32_t a=EXTRACT(int32_t,a_raw), b=EXTRACT(int32_t,b_raw); return (a>b)-(a<b); }
        case TVT_U32: { uint32_t a=EXTRACT(uint32_t,a_raw),b=EXTRACT(uint32_t,b_raw);return (a>b)-(a<b); }
        case TVT_I64: { int64_t a=EXTRACT(int64_t,a_raw), b=EXTRACT(int64_t,b_raw); return (a>b)-(a<b); }
        case TVT_U64: { uint64_t a=EXTRACT(uint64_t,a_raw),b=EXTRACT(uint64_t,b_raw);return (a>b)-(a<b); }
        case TVT_F32: { float   a=EXTRACT(float,a_raw),   b=EXTRACT(float,b_raw);   return (a>b)-(a<b); }
        case TVT_F64: { double  a=EXTRACT(double,a_raw),  b=EXTRACT(double,b_raw);  return (a>b)-(a<b); }
        default: return 0;
    }
#undef EXTRACT
}

static inline bool value_eq(uint64_t a, uint64_t b, uint32_t vtype)
{
    return value_cmp(a, b, vtype) == 0;
}

/* ---- match table -------------------------------------------------------- */

/*
 * Each entry tracks:
 *   addr       : the address in the game's address space
 *   prev_value : the raw value read during the most-recent scan pass
 *                (needed for INCREASED/DECREASED/CHANGED/UNCHANGED filters)
 */
typedef struct {
    uint64_t addr;
    uint64_t prev_value;
} match_entry_t;

static match_entry_t *g_matches   = NULL;  /* heap-allocated array         */
static uint32_t       g_match_cnt = 0;     /* used entries                 */
static uint32_t       g_match_cap = 0;     /* allocated entries            */
/* The value-type of the current match set, captured at SCAN_NEW. SCAN_NEXT
 * reuses it so the caller never has to re-specify the type when narrowing
 * (the Android side sends TVT_NONE for scan-next on purpose). */
static uint32_t       g_scan_vtype = TVT_NONE;

static void matches_free(void)
{
    free(g_matches);
    g_matches   = NULL;
    g_match_cnt = 0;
    g_match_cap = 0;
    g_scan_vtype = TVT_NONE;
}

static bool matches_reserve(uint32_t need)
{
    if (need <= g_match_cap) return true;
    /* Grow by doubling, cap at MATCH_CAP */
    uint32_t new_cap = g_match_cap ? g_match_cap : 4096;
    while (new_cap < need && new_cap < MATCH_CAP) new_cap *= 2;
    if (new_cap < need) new_cap = need;
    if (new_cap > MATCH_CAP) new_cap = MATCH_CAP;

    match_entry_t *p = realloc(g_matches, (size_t)new_cap * sizeof(*p));
    if (!p) return false;
    g_matches   = p;
    g_match_cap = new_cap;
    return true;
}

/* ---- freeze table ------------------------------------------------------- */

typedef struct {
    uint64_t addr;
    uint64_t value;
    uint32_t vtype;
    bool     active;
} freeze_entry_t;

static freeze_entry_t g_freezes[TRAINER_FREEZE_CAP];
static pthread_mutex_t g_freeze_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ---- patch table -------------------------------------------------------- */

#define TRAINER_PATCH_CAP 16u   /* max simultaneously applied code patches */
#define TRAINER_PATCH_MAX_LEN 32u /* max bytes per patch */

typedef struct {
    uint64_t addr;             /* patched address (absolute, in this process) */
    uint32_t len;              /* number of bytes patched (<= TRAINER_PATCH_MAX_LEN) */
    uint8_t  orig[32];         /* original bytes saved at patch time, for restore */
    bool     active;
} patch_entry_t;

static patch_entry_t    g_patches[TRAINER_PATCH_CAP];
static pthread_mutex_t  g_patch_mutex = PTHREAD_MUTEX_INITIALIZER;

/* ---- shared memory ------------------------------------------------------ */

/* The mmap'd control block; NULL if shm setup failed. */
static struct trainer_shm *g_shm = NULL;
static size_t g_shm_map_size = 0;

/* /proc/self/mem file descriptor, opened O_RDWR once on init. */
static int g_mem_fd = -1;

/* ---- futex helpers ------------------------------------------------------- */

static inline void futex_wake_all(atomic_uint *word)
{
    syscall(SYS_futex, (int *)word, FUTEX_WAKE, INT_MAX, NULL, NULL, 0);
}

/*
 * Timed wait: block until *word != val, a spurious wake, or the timeout
 * elapses — whichever comes first.  The caller MUST re-check *word after
 * this returns, because (a) spurious wakes are possible and (b) this is
 * the primary poll mechanism: Android (pure Kotlin) has NO way to issue
 * FUTEX_WAKE.  We therefore use a short timeout so the worker re-checks
 * cmd_seq periodically even without a wake signal from the Android side.
 *
 * timeout_ns should be 20–50 ms; callers use FUTEX_POLL_NS.
 */
#define FUTEX_POLL_NS  20000000L   /* 20 ms — poll cadence when idle */

static inline uint32_t futex_wait_timed(atomic_uint *word, uint32_t val,
                                        long timeout_ns)
{
    struct timespec ts = {
        .tv_sec  = timeout_ns / 1000000000L,
        .tv_nsec = timeout_ns % 1000000000L,
    };
    syscall(SYS_futex, (int *)word, FUTEX_WAIT, (int)val, &ts, NULL, 0);
    return atomic_load_explicit(word, memory_order_acquire);
}

/* Kept for callers that don't need a timeout (e.g. we may add JNI wake later) */
static inline uint32_t futex_wait(atomic_uint *word, uint32_t val)
{
    return futex_wait_timed(word, val, FUTEX_POLL_NS);
}

/* ---- response helpers --------------------------------------------------- */

/*
 * Bump resp_seq and wake the Android side.
 * Must be called AFTER all response fields are written.
 */
static inline void resp_bump(void)
{
    atomic_fetch_add_explicit(
        (atomic_uint *)&g_shm->resp_seq, 1u, memory_order_release);
    futex_wake_all((atomic_uint *)&g_shm->resp_seq);
}

static inline void resp_set_status(uint32_t status)
{
    atomic_store_explicit(
        (atomic_uint *)&g_shm->resp_status, status, memory_order_relaxed);
}

static inline void resp_set_error(uint32_t err)
{
    atomic_store_explicit(
        (atomic_uint *)&g_shm->resp_error, err, memory_order_relaxed);
}

static inline void resp_set_progress(uint32_t pct)
{
    atomic_store_explicit(
        (atomic_uint *)&g_shm->resp_progress, pct, memory_order_relaxed);
    /* Bump resp_seq so Android can show live progress without waiting for
     * the full scan to finish.  We do NOT call resp_bump() here to avoid
     * hammering; caller can decide. */
}

/* ---- /proc/self/maps region parsing ------------------------------------- */

typedef struct {
    uintptr_t start;
    uintptr_t end;
} maps_region_t;

/*
 * Build a list of memory regions from /proc/self/maps that are:
 *   - readable AND writable (perms[0]=='r' && perms[1]=='w')
 *   - private (perms[3]=='p')  — shared COW pages belong to the game
 *   - not [vvar], [vdso], [vsyscall], or the trainer's own shm
 *
 * Returns the number of regions placed in *out_regions (caller must free).
 * Returns -1 on error.
 */
static int parse_maps(maps_region_t **out_regions, int *out_count)
{
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) { LOGE("trainer: fopen /proc/self/maps: %s", strerror(errno)); return -1; }

    int cap   = 512;
    int count = 0;
    maps_region_t *regions = malloc((size_t)cap * sizeof(*regions));
    if (!regions) { fclose(f); return -1; }

    char line[MAPS_LINE_MAX];
    uintptr_t shm_start = (uintptr_t)g_shm;
    uintptr_t shm_end   = shm_start + g_shm_map_size;

    while (fgets(line, sizeof(line), f)) {
        uintptr_t start, end;
        char perms[5];
        unsigned long offset;
        unsigned int dev_major, dev_minor;
        unsigned long inode;
        char name[128];
        name[0] = '\0';

        int matched = sscanf(line, "%lx-%lx %4s %lx %x:%x %lu %127s",
                             (unsigned long *)&start, (unsigned long *)&end,
                             perms, &offset, &dev_major, &dev_minor, &inode, name);
        if (matched < 4) continue;

        /* Must be rw-p or rwxp */
        if (perms[0] != 'r' || perms[1] != 'w') continue;
        if (perms[3] != 'p') continue;

        /* Skip special kernel-mapped regions */
        if (strstr(name, "[vvar]"))      continue;
        if (strstr(name, "[vdso]"))      continue;
        if (strstr(name, "[vsyscall]"))  continue;

        /* Skip the trainer's own shm mapping to avoid scanning our control block */
        if (start < shm_end && end > shm_start) continue;

        /* FIX 2: Skip file-backed library/code regions (default ON via
         * TRAINER_SCAN_ANON_ONLY).  These are .so/.dll/.exe mappings that
         * hold code/rodata, not the game's mutable heap.  We only keep:
         *   - anonymous rw-p regions (inode==0, no backing file)
         *   - [heap] and [stack] pseudo-regions (inode==0 with bracket names)
         * File-backed regions with an actual inode (library mmaps, Wine PE
         * sections mapped into the Wine process as files) are skipped.
         * Set TRAINER_SCAN_ANON_ONLY=0 to disable this filter for debugging. */
        {
            static int scan_anon_only = -1;  /* -1 = not yet read */
            if (scan_anon_only < 0) {
                const char *env = getenv("TRAINER_SCAN_ANON_ONLY");
                /* Default ON (treat unset or "1" or anything other than "0" as on) */
                scan_anon_only = (!env || env[0] != '0') ? 1 : 0;
                LOGI("trainer: TRAINER_SCAN_ANON_ONLY=%d", scan_anon_only);
            }
            if (scan_anon_only) {
                /* Skip any region backed by a real file (inode != 0 with a path name
                 * containing '/' — i.e. a mapped .so, .dll, .exe, or data file). */
                bool has_path = (name[0] == '/' || (name[0] != '\0' && name[0] != '['));
                if (inode != 0 && has_path) continue;
            }
        }

        /* Grow array if needed */
        if (count >= cap) {
            int new_cap = cap * 2;
            maps_region_t *tmp = realloc(regions, (size_t)new_cap * sizeof(*tmp));
            if (!tmp) break;  /* return what we have so far */
            regions = tmp;
            cap = new_cap;
        }

        regions[count].start = start;
        regions[count].end   = end;
        count++;
    }

    fclose(f);
    *out_regions = regions;
    *out_count   = count;
    return 0;
}

/* ---- read a value from the game's address space via /proc/self/mem ------ */

/*
 * Read `size` bytes from address `addr` into `buf` using pread on g_mem_fd.
 * Returns true on success, false if the read fails (e.g. unmapped page).
 * Never dereferences a pointer — always goes through the fd.
 */
static inline bool mem_read_safe(uintptr_t addr, void *buf, size_t size)
{
    ssize_t n = pread(g_mem_fd, buf, size, (off_t)addr);
    return n == (ssize_t)size;
}

/*
 * Write `size` bytes from `buf` to address `addr` via pwrite on g_mem_fd.
 */
static inline bool mem_write_safe(uintptr_t addr, const void *buf, size_t size)
{
    ssize_t n = pwrite(g_mem_fd, buf, size, (off_t)addr);
    return n == (ssize_t)size;
}

/* Read a typed value; stores the raw bits (zero-extended to uint64) in *out. */
static bool mem_read_value(uintptr_t addr, uint32_t vtype, uint64_t *out)
{
    size_t sz = vtype_size(vtype);
    if (!sz) return false;

    uint64_t raw = 0;
    if (!mem_read_safe(addr, &raw, sz)) return false;
    *out = raw;
    return true;
}

/* Write a typed value from a raw uint64 word. */
static bool mem_write_value(uintptr_t addr, uint32_t vtype, uint64_t raw)
{
    size_t sz = vtype_size(vtype);
    if (!sz) return false;
    return mem_write_safe(addr, &raw, sz);
}

/* ---- publish results window to shm -------------------------------------- */

static void publish_results(uint32_t total_count, bool too_many)
{
    uint32_t surface = total_count < TRAINER_RESULT_CAP ? total_count : TRAINER_RESULT_CAP;

    for (uint32_t i = 0; i < surface; i++) {
        g_shm->results[i] = g_matches[i].addr;
    }
    /* Zero out any stale entries from a previous (larger) scan */
    for (uint32_t i = surface; i < TRAINER_RESULT_CAP; i++) {
        g_shm->results[i] = 0;
    }

    atomic_store_explicit(
        (atomic_uint *)&g_shm->resp_count, total_count, memory_order_relaxed);
    resp_set_status(too_many ? TST_TOO_MANY : TST_READY);
}

/* ---- command handlers --------------------------------------------------- */

static void cmd_ping(void)
{
    resp_set_status(TST_READY);
    resp_bump();
    LOGD("trainer: PING ok");
}

/*
 * TCMD_SCAN_NEW
 * Parse /proc/self/maps, walk all rw-private regions via pread(/proc/self/mem),
 * collect all addresses where the value equals cmd_value (by cmd_vtype).
 *
 * Periodically bumps resp_seq with TST_SCANNING so the UI sees live progress,
 * but avoids bumping on every address (only per-region, or on 5% increments).
 */
static void cmd_scan_new(uint32_t vtype, uint64_t target_value)
{
    size_t vsz = vtype_size(vtype);
    if (!vsz) {
        LOGE("trainer: SCAN_NEW bad vtype %u", vtype);
        resp_set_status(TST_ERROR); resp_set_error(EINVAL); resp_bump();
        return;
    }

    maps_region_t *regions = NULL;
    int nregions = 0;
    int pm = parse_maps(&regions, &nregions);
    /* [DIAG] Log what the scanner sees in THIS process — the key to the in-game
     * "SCAN ERROR". In the app process the self-test passes; in the Box64 game
     * process this tells us nregions, the mem fd, and the process identity. */
    {
        char dbgcmd[128]; dbgcmd[0] = '\0';
        int cfd = open("/proc/self/cmdline", O_RDONLY);
        if (cfd >= 0) { ssize_t cn = read(cfd, dbgcmd, sizeof(dbgcmd)-1); if (cn > 0) dbgcmd[cn] = '\0'; close(cfd); }
        LOGI("trainer: [DIAG] SCAN_NEW in pid=%d cmdline='%s' parse_maps=%d nregions=%d mem_fd=%d vtype=%u",
             getpid(), dbgcmd, pm, nregions, g_mem_fd, vtype);
    }
    if (pm < 0 || nregions == 0) {
        LOGE("trainer: SCAN_NEW parse_maps failed (pm=%d nregions=%d errno=%d:%s)", pm, nregions, errno, strerror(errno));
        free(regions);
        resp_set_status(TST_ERROR); resp_set_error(ENODATA); resp_bump();
        return;
    }

    /* Set scanning status and reset progress */
    resp_set_status(TST_SCANNING);
    resp_set_progress(0);
    resp_bump();   /* tell Android we started */

    matches_free();

    /* Remember the type so SCAN_NEXT can reuse it. Must be set AFTER
     * matches_free() (which clears g_scan_vtype back to TVT_NONE). */
    g_scan_vtype = vtype;

    bool too_many = false;
    uint32_t total = 0;

    /* Read buffer: scan one page at a time to amortise pread syscall overhead */
    const size_t CHUNK = 4096;
    uint8_t *buf = malloc(CHUNK);
    if (!buf) {
        free(regions);
        resp_set_status(TST_ERROR); resp_set_error(ENOMEM); resp_bump();
        return;
    }

    int last_progress = -1;
    uint64_t dbg_unreadable = 0;   /* [DIAG] count chunks pread couldn't read */
    uint64_t dbg_bytes_scanned = 0;

    for (int ri = 0; ri < nregions; ri++) {
        uintptr_t start = regions[ri].start;
        uintptr_t end   = regions[ri].end;

        /* Progress: 0..100 proportional to region index */
        int progress = (ri * 100) / nregions;
        if (progress != last_progress) {
            resp_set_progress((uint32_t)progress);
            atomic_fetch_add_explicit(
                (atomic_uint *)&g_shm->resp_seq, 1u, memory_order_release);
            futex_wake_all((atomic_uint *)&g_shm->resp_seq);
            last_progress = progress;
        }

        uintptr_t pos = start;
        while (pos + vsz <= end) {
            /* Read a chunk */
            size_t avail = end - pos;
            size_t to_read = avail < CHUNK ? avail : CHUNK;

            ssize_t got = pread(g_mem_fd, buf, to_read, (off_t)pos);
            if (got <= 0) {
                /* Unreadable chunk — skip it */
                dbg_unreadable++;
                pos += CHUNK;
                continue;
            }
            dbg_bytes_scanned += (uint64_t)got;

            /* Walk the chunk, stepping by vsz */
            size_t g = (size_t)got;
            for (size_t off = 0; off + vsz <= g; off += vsz) {
                uint64_t candidate = 0;
                memcpy(&candidate, buf + off, vsz);

                if (value_eq(candidate, target_value, vtype)) {
                    uintptr_t addr = pos + off;
                    if (!too_many) {
                        if (total < MATCH_CAP) {
                            if (!matches_reserve(total + 1)) {
                                /* OOM — stop collecting but keep counting */
                                too_many = true;
                            } else {
                                g_matches[total].addr       = (uint64_t)addr;
                                g_matches[total].prev_value = candidate;
                                g_match_cnt = total + 1;
                            }
                        } else {
                            too_many = true;
                        }
                    }
                    total++;
                }
            }
            pos += (size_t)got;
        }
    }

    free(buf);
    free(regions);

    g_match_cnt = too_many ? MATCH_CAP : total;
    publish_results(total, too_many);
    resp_set_progress(100);
    resp_bump();

    LOGI("trainer: SCAN_NEW vtype=%u found %u matches (too_many=%d) [DIAG bytes_scanned=%llu unreadable_chunks=%llu]",
         vtype, total, too_many,
         (unsigned long long)dbg_bytes_scanned, (unsigned long long)dbg_unreadable);
}

/*
 * parse_maps_aob
 * Like parse_maps() but for AOB scanning: includes ANY readable region
 * (perms[0]=='r'), regardless of write/exec bit, private/shared, or
 * file-backed/anonymous.  Still skips the trainer's own shm mapping and
 * the [vvar]/[vdso]/[vsyscall] kernel-special regions.  The
 * TRAINER_SCAN_ANON_ONLY filter is intentionally NOT applied: AOB patterns
 * commonly live in r-x code sections and file-backed PE/library regions.
 *
 * Returns 0 on success (caller must free *out), -1 on error.
 */
static int parse_maps_aob(maps_region_t **out, int *out_count)
{
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) { LOGE("trainer: fopen /proc/self/maps (aob): %s", strerror(errno)); return -1; }

    int cap   = 512;
    int count = 0;
    maps_region_t *regions = malloc((size_t)cap * sizeof(*regions));
    if (!regions) { fclose(f); return -1; }

    char line[MAPS_LINE_MAX];
    uintptr_t shm_start = (uintptr_t)g_shm;
    uintptr_t shm_end   = shm_start + g_shm_map_size;

    while (fgets(line, sizeof(line), f)) {
        uintptr_t start, end;
        char perms[5];
        unsigned long offset;
        unsigned int dev_major, dev_minor;
        unsigned long inode;
        char name[128];
        name[0] = '\0';

        int matched = sscanf(line, "%lx-%lx %4s %lx %x:%x %lu %127s",
                             (unsigned long *)&start, (unsigned long *)&end,
                             perms, &offset, &dev_major, &dev_minor, &inode, name);
        if (matched < 4) continue;

        /* Must be readable — that is the only constraint for AOB */
        if (perms[0] != 'r') continue;

        /* Skip special kernel-mapped regions */
        if (strstr(name, "[vvar]"))      continue;
        if (strstr(name, "[vdso]"))      continue;
        if (strstr(name, "[vsyscall]"))  continue;

        /* Skip the trainer's own shm mapping */
        if (start < shm_end && end > shm_start) continue;

        /* Grow array if needed */
        if (count >= cap) {
            int new_cap = cap * 2;
            maps_region_t *tmp = realloc(regions, (size_t)new_cap * sizeof(*tmp));
            if (!tmp) break;  /* return what we have so far */
            regions = tmp;
            cap = new_cap;
        }

        regions[count].start = start;
        regions[count].end   = end;
        count++;
    }

    fclose(f);
    *out       = regions;
    *out_count = count;
    return 0;
}

/*
 * TCMD_AOB_SCAN
 * Scan all readable regions for the byte pattern in g_shm->cmd_pattern[],
 * gated by g_shm->cmd_mask[] (0xFF = must match, 0x00 = wildcard).
 * For each hit the resolved address = match_base + data_offset is stored
 * in the match table and surfaced in results[].
 *
 * data_offset is cmd_addr reinterpreted as int64_t (a signed displacement).
 */
static void cmd_aob_scan(int64_t data_offset)
{
    /* 1. Validate and clamp pattern_len */
    uint32_t pattern_len = g_shm->cmd_pattern_len;
    if (pattern_len == 0 || pattern_len > TRAINER_PATTERN_CAP) {
        LOGE("trainer: AOB_SCAN bad pattern_len=%u", pattern_len);
        resp_set_status(TST_ERROR); resp_set_error(EINVAL); resp_bump();
        return;
    }

    /* 2. Local copies of pattern and mask */
    uint8_t pattern[TRAINER_PATTERN_CAP];
    uint8_t mask[TRAINER_PATTERN_CAP];
    memcpy(pattern, g_shm->cmd_pattern, pattern_len);
    memcpy(mask,    g_shm->cmd_mask,    pattern_len);

    /* 3. Enumerate all readable regions (AOB-specific: r-x included) */
    maps_region_t *regions = NULL;
    int nregions = 0;
    int pm = parse_maps_aob(&regions, &nregions);
    {
        char dbgcmd[128]; dbgcmd[0] = '\0';
        int cfd = open("/proc/self/cmdline", O_RDONLY);
        if (cfd >= 0) {
            ssize_t cn = read(cfd, dbgcmd, sizeof(dbgcmd) - 1);
            if (cn > 0) dbgcmd[cn] = '\0';
            close(cfd);
        }
        LOGI("trainer: [DIAG] AOB_SCAN pid=%d cmdline='%s' pattern_len=%u data_offset=%lld parse_maps_aob=%d nregions=%d mem_fd=%d",
             getpid(), dbgcmd, pattern_len, (long long)data_offset, pm, nregions, g_mem_fd);
    }
    if (pm < 0 || nregions == 0) {
        LOGE("trainer: AOB_SCAN parse_maps_aob failed (pm=%d nregions=%d errno=%d:%s)",
             pm, nregions, errno, strerror(errno));
        free(regions);
        resp_set_status(TST_ERROR); resp_set_error(ENODATA); resp_bump();
        return;
    }

    /* Signal scanning start */
    resp_set_status(TST_SCANNING);
    resp_set_progress(0);
    resp_bump();

    matches_free();
    /* g_scan_vtype: carry cmd_vtype through so SCAN_NEXT / READ / FREEZE work */
    g_scan_vtype = (uint32_t)atomic_load_explicit(
        (atomic_uint *)&g_shm->cmd_vtype, memory_order_relaxed);

    bool    too_many      = false;
    uint32_t total        = 0;
    uint64_t bytes_scanned = 0;

    /* Read buffer: 4096-byte page chunk + overlap of (pattern_len-1) bytes
     * so patterns straddling consecutive chunk boundaries are not missed.
     * Allocation: CHUNK + (pattern_len - 1) is at most 4096 + 255 = 4351 bytes. */
    const size_t CHUNK   = 4096;
    const size_t overlap = (size_t)(pattern_len - 1);
    const size_t bufsize = CHUNK + overlap;
    uint8_t *buf = malloc(bufsize);
    if (!buf) {
        free(regions);
        resp_set_status(TST_ERROR); resp_set_error(ENOMEM); resp_bump();
        return;
    }

    int last_progress = -1;

    for (int ri = 0; ri < nregions; ri++) {
        uintptr_t start = regions[ri].start;
        uintptr_t end   = regions[ri].end;

        /* Progress: 0..100 proportional to region index */
        int progress = (nregions > 0) ? (ri * 100) / nregions : 0;
        if (progress != last_progress) {
            resp_set_progress((uint32_t)progress);
            atomic_fetch_add_explicit(
                (atomic_uint *)&g_shm->resp_seq, 1u, memory_order_release);
            futex_wake_all((atomic_uint *)&g_shm->resp_seq);
            last_progress = progress;
        }

        uintptr_t pos = start;
        /* Carry-over bytes from the previous chunk (initially none) */
        size_t carry = 0;

        while (pos < end) {
            /* How many fresh bytes can we read this iteration? */
            size_t avail    = end - pos;
            size_t to_read  = avail < CHUNK ? avail : CHUNK;

            /* carry bytes from the previous iteration are already in buf[0..carry-1]
             * (placed there by the tail memmove at the end of the last loop body).
             * Read fresh bytes directly after them. */
            ssize_t got = pread(g_mem_fd, buf + carry, to_read, (off_t)pos);
            if (got <= 0) {
                /* Unreadable chunk — skip past it; reset carry */
                pos  += CHUNK;
                carry = 0;
                continue;
            }

            size_t total_in_buf = carry + (size_t)got;
            bytes_scanned      += (uint64_t)got;

            /* Sliding-window match over total_in_buf bytes.
             * A candidate at offset p is valid only when p + pattern_len fits. */
            if (total_in_buf >= (size_t)pattern_len) {
                size_t limit = total_in_buf - (size_t)pattern_len; /* inclusive */
                for (size_t p = 0; p <= limit; p++) {
                    bool match = true;
                    for (uint32_t i = 0; i < pattern_len; i++) {
                        if (mask[i] == 0x00) continue;  /* wildcard */
                        if (buf[p + i] != pattern[i]) { match = false; break; }
                    }
                    if (match) {
                        /* base = address of buf[p] in the target address space.
                         * buf[0] corresponds to (pos - carry), so:
                         *   base = (pos - carry) + p
                         * resolved = base + data_offset  (signed arithmetic) */
                        uintptr_t base     = (pos - carry) + p;
                        uint64_t  resolved = (uint64_t)((int64_t)base + data_offset);

                        if (!too_many) {
                            if (total < MATCH_CAP) {
                                if (!matches_reserve(total + 1)) {
                                    too_many = true;
                                } else {
                                    g_matches[total].addr       = resolved;
                                    g_matches[total].prev_value = 0;
                                    g_match_cnt = total + 1;
                                }
                            } else {
                                too_many = true;
                            }
                        }
                        total++;
                    }
                }
            }

            /* Advance pos by the fresh bytes consumed; keep overlap as carry
             * so the next iteration can match patterns that straddle the boundary.
             * If got < overlap there is nothing useful to carry — reset. */
            pos += (size_t)got;
            carry = ((size_t)got >= overlap) ? overlap : 0;
            /* Preserve the carry bytes at the END of current buffer for next memmove.
             * They sit at buf[total_in_buf - carry .. total_in_buf - 1]. */
            if (carry > 0) {
                memmove(buf, buf + total_in_buf - carry, carry);
            }
        }
    }

    free(buf);
    free(regions);

    g_match_cnt = too_many ? MATCH_CAP : total;

    if (total == 0) {
        /* Zero out any stale entries from a previous scan */
        for (uint32_t i = 0; i < TRAINER_RESULT_CAP; i++) g_shm->results[i] = 0;
        atomic_store_explicit(
            (atomic_uint *)&g_shm->resp_count, 0u, memory_order_relaxed);
        resp_set_status(TST_NO_MATCH);
    } else {
        publish_results(total, too_many);
    }
    resp_set_progress(100);
    resp_bump();

    LOGI("trainer: [DIAG] AOB_SCAN done: total=%u too_many=%d bytes_scanned=%llu nregions=%d pattern_len=%u data_offset=%lld",
         total, too_many,
         (unsigned long long)bytes_scanned, nregions, pattern_len, (long long)data_offset);
}

/*
 * TCMD_SCAN_NEXT
 * Re-reads each currently-tracked address. Keeps only those satisfying
 * cmd_filter. Updates prev_value for all survivors.
 */
static void cmd_scan_next(uint32_t vtype, uint32_t filter, uint64_t ref_value)
{
    /* The narrowing scan always uses the type captured at SCAN_NEW. The Android
     * side intentionally sends TVT_NONE here, so honour the remembered type
     * (falling back to the incoming value only if a scan-new never ran). */
    if (g_scan_vtype != TVT_NONE) {
        vtype = g_scan_vtype;
    }

    size_t vsz = vtype_size(vtype);
    if (!vsz || !g_matches || g_match_cnt == 0) {
        resp_set_status(TST_READY);
        atomic_store_explicit(
            (atomic_uint *)&g_shm->resp_count, 0u, memory_order_relaxed);
        resp_bump();
        return;
    }

    resp_set_status(TST_SCANNING);
    resp_set_progress(0);
    resp_bump();

    uint32_t write_idx = 0;

    for (uint32_t i = 0; i < g_match_cnt; i++) {
        uint64_t cur = 0;
        if (!mem_read_value((uintptr_t)g_matches[i].addr, vtype, &cur)) {
            /* Address became unreadable — drop it */
            continue;
        }

        uint64_t prev = g_matches[i].prev_value;
        bool keep = false;

        switch (filter) {
            case TFLT_EXACT:
                keep = value_eq(cur, ref_value, vtype);
                break;
            case TFLT_INCREASED:
                keep = value_cmp(cur, prev, vtype) > 0;
                break;
            case TFLT_DECREASED:
                keep = value_cmp(cur, prev, vtype) < 0;
                break;
            case TFLT_CHANGED:
                keep = !value_eq(cur, prev, vtype);
                break;
            case TFLT_UNCHANGED:
                keep = value_eq(cur, prev, vtype);
                break;
            default:
                keep = false;
                break;
        }

        if (keep) {
            g_matches[write_idx].addr       = g_matches[i].addr;
            g_matches[write_idx].prev_value = cur;   /* update snapshot */
            write_idx++;
        }
    }

    g_match_cnt = write_idx;
    bool too_many = (g_match_cnt > TRAINER_RESULT_CAP);
    publish_results(g_match_cnt, too_many);
    resp_set_progress(100);
    resp_bump();

    LOGI("trainer: SCAN_NEXT filter=%u survivors=%u", filter, write_idx);
}

static void cmd_read(uintptr_t addr, uint32_t vtype)
{
    uint64_t val = 0;
    if (!mem_read_value(addr, vtype, &val)) {
        LOGE("trainer: READ 0x%lx failed: %s", (unsigned long)addr, strerror(errno));
        resp_set_status(TST_ERROR); resp_set_error(EIO); resp_bump();
        return;
    }
    g_shm->resp_value = val;
    resp_set_status(TST_READY);
    resp_bump();
}

static void cmd_write(uintptr_t addr, uint32_t vtype, uint64_t value)
{
    if (!mem_write_value(addr, vtype, value)) {
        LOGE("trainer: WRITE 0x%lx failed: %s", (unsigned long)addr, strerror(errno));
        resp_set_status(TST_ERROR); resp_set_error(EIO); resp_bump();
        return;
    }
    resp_set_status(TST_READY);
    resp_bump();
}

static void cmd_freeze(uintptr_t addr, uint32_t vtype, uint64_t value)
{
    pthread_mutex_lock(&g_freeze_mutex);

    /* Check if addr already has a slot (update it) */
    for (uint32_t i = 0; i < TRAINER_FREEZE_CAP; i++) {
        if (g_freezes[i].active && g_freezes[i].addr == (uint64_t)addr) {
            g_freezes[i].value = value;
            g_freezes[i].vtype = vtype;
            pthread_mutex_unlock(&g_freeze_mutex);
            resp_set_status(TST_READY);
            resp_bump();
            return;
        }
    }

    /* Find a free slot */
    for (uint32_t i = 0; i < TRAINER_FREEZE_CAP; i++) {
        if (!g_freezes[i].active) {
            g_freezes[i].addr   = (uint64_t)addr;
            g_freezes[i].value  = value;
            g_freezes[i].vtype  = vtype;
            g_freezes[i].active = true;
            pthread_mutex_unlock(&g_freeze_mutex);
            resp_set_status(TST_READY);
            resp_bump();
            return;
        }
    }

    pthread_mutex_unlock(&g_freeze_mutex);
    LOGE("trainer: FREEZE table full (cap %u)", TRAINER_FREEZE_CAP);
    resp_set_status(TST_ERROR); resp_set_error(ENOSPC); resp_bump();
}

static void cmd_unfreeze(uintptr_t addr)
{
    pthread_mutex_lock(&g_freeze_mutex);

    if (addr == 0) {
        /* Clear all */
        memset(g_freezes, 0, sizeof(g_freezes));
    } else {
        for (uint32_t i = 0; i < TRAINER_FREEZE_CAP; i++) {
            if (g_freezes[i].active && g_freezes[i].addr == (uint64_t)addr) {
                g_freezes[i].active = false;
            }
        }
    }

    pthread_mutex_unlock(&g_freeze_mutex);
    resp_set_status(TST_READY);
    resp_bump();
}

/*
 * Helper: restore all active patches in the patch table WITHOUT touching
 * resp_* (so it can be called from cmd_reset which issues its own resp).
 * Caller must NOT hold g_patch_mutex; this function acquires it internally.
 */
static void patches_restore_all_nolock(void)
{
    patch_entry_t snap[TRAINER_PATCH_CAP];
    pthread_mutex_lock(&g_patch_mutex);
    memcpy(snap, g_patches, sizeof(snap));
    pthread_mutex_unlock(&g_patch_mutex);

    long page_sz = sysconf(_SC_PAGESIZE);
    if (page_sz <= 0) page_sz = 4096;

    uint32_t restored = 0;
    for (uint32_t i = 0; i < TRAINER_PATCH_CAP; i++) {
        if (!snap[i].active) continue;

        uintptr_t tgt  = (uintptr_t)snap[i].addr;
        uint32_t  len  = snap[i].len;
        uintptr_t page = tgt & ~(uintptr_t)(page_sz - 1);
        size_t    span = (tgt + len) - page;

        if (mprotect((void *)page, span, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
            LOGE("trainer: PATCH_RESTORE[%u]: mprotect(rwx) 0x%lx failed: %s",
                 i, (unsigned long)tgt, strerror(errno));
            continue;
        }

        memcpy((void *)tgt, snap[i].orig, len);

        /* I-cache flush so the CPU / Box64 dynarec picks up the restored bytes */
        __builtin___clear_cache((char *)tgt, (char *)(tgt + len));

        /* Best-effort: restore page to r-x (code pages are typically not writable) */
        if (mprotect((void *)page, span, PROT_READ | PROT_EXEC) < 0) {
            LOGE("trainer: PATCH_RESTORE[%u]: mprotect(r-x) restore 0x%lx failed (non-fatal): %s",
                 i, (unsigned long)tgt, strerror(errno));
        }

        /* Mark inactive in the live table */
        pthread_mutex_lock(&g_patch_mutex);
        g_patches[i].active = false;
        pthread_mutex_unlock(&g_patch_mutex);

        restored++;
    }
    LOGI("trainer: patches_restore_all_nolock: restored %u active patch(es)", restored);
}

/*
 * TCMD_PATCH
 * NOP-an-instruction (or any byte-level code patch) at cmd_addr.
 * Writes cmd_patch_len bytes from cmd_pattern[] to the target address using
 * mprotect(rwx) + direct memcpy so the write is visible in this address space.
 *
 * *** Box64 dynarec risk ***: after the write we issue __builtin___clear_cache
 * to request an i-cache flush. However, Box64's SMC (self-modifying code)
 * detection is the definitive mechanism that forces retranslation of the
 * patched JIT block. Whether that detection fires depends on the Box64 version
 * and whether the page is being actively executed. This is the PRIMARY risk
 * area and MUST be tested on device.
 */
static void cmd_patch(uintptr_t addr, uint32_t len)
{
    /* 1. Validate */
    if (addr == 0 || len == 0 || len > TRAINER_PATCH_MAX_LEN) {
        LOGE("trainer: PATCH bad args addr=0x%lx len=%u (max %u)",
             (unsigned long)addr, len, TRAINER_PATCH_MAX_LEN);
        resp_set_status(TST_ERROR); resp_set_error(EINVAL); resp_bump();
        return;
    }

    /* 2. Local copy of patch bytes from shm (cmd_pattern[] dual-use: patch bytes here) */
    uint8_t patchbuf[TRAINER_PATCH_MAX_LEN];
    memcpy(patchbuf, g_shm->cmd_pattern, len);

    /* 3. Compute page range covering [addr, addr+len) */
    long page_sz = sysconf(_SC_PAGESIZE);
    if (page_sz <= 0) page_sz = 4096;
    uintptr_t page_start = addr & ~(uintptr_t)(page_sz - 1);
    size_t    span       = (addr + len) - page_start;

    /* 4. Snapshot original bytes for restore via pread (crash-safe; mirrors mem_read_safe) */
    uint8_t orig[TRAINER_PATCH_MAX_LEN];
    memset(orig, 0, sizeof(orig));
    ssize_t got = pread(g_mem_fd, orig, len, (off_t)addr);
    if (got != (ssize_t)len) {
        LOGE("trainer: PATCH pread orig 0x%lx len=%u failed: %s",
             (unsigned long)addr, len, strerror(errno));
        resp_set_status(TST_ERROR); resp_set_error(EIO); resp_bump();
        return;
    }

    /* 5. Make page writable */
    if (mprotect((void *)page_start, span, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
        LOGE("trainer: PATCH mprotect(rwx) 0x%lx span=%zu failed: %s",
             (unsigned long)page_start, span, strerror(errno));
        resp_set_status(TST_ERROR); resp_set_error(errno); resp_bump();
        return;
    }

    /* 6. Write patch bytes directly — same address space, page is now writable */
    memcpy((void *)addr, patchbuf, len);

    /* 7. *** BOX64 DYNAREC RISK STEP ***
     * Flush i-cache over [addr, addr+len) so the CPU/kernel knows code bytes
     * changed. On arm64 this maps to dc cvau + ic ivau instructions. This asks
     * the hardware to discard the old fetch. Box64's SMC detection MUST also
     * pick this up (it watches for writes to translated pages); this cache-flush
     * is the in-process half of that handshake. If Box64 has already JIT'd the
     * block and SMC detection misses it, the old translation will execute until
     * Box64 retranslates — this is the known risk. Test on device with the
     * target game running under Box64. */
    __builtin___clear_cache((char *)addr, (char *)(addr + len));
    LOGI("trainer: PATCH i-cache flush issued for 0x%lx len=%u [Box64 dynarec risk — verify on device]",
         (unsigned long)addr, len);

    /* 8. Verify the write (best-effort, non-fatal under SMC races) */
    uint8_t verify[TRAINER_PATCH_MAX_LEN];
    ssize_t vn = pread(g_mem_fd, verify, len, (off_t)addr);
    if (vn != (ssize_t)len || memcmp(verify, patchbuf, len) != 0) {
        LOGE("trainer: PATCH verify readback mismatch for 0x%lx len=%u (racy under SMC, non-fatal)",
             (unsigned long)addr, len);
        /* Non-fatal: the write may still be live; continue */
    }

    /* 9. Best-effort restore to r-x */
    if (mprotect((void *)page_start, span, PROT_READ | PROT_EXEC) < 0) {
        LOGE("trainer: PATCH mprotect(r-x) restore 0x%lx failed (non-fatal): %s",
             (unsigned long)page_start, strerror(errno));
        /* Non-fatal: patch is already applied */
    }

    /* 10. Record in patch table (patch is live; log warning if no slot but still succeed) */
    pthread_mutex_lock(&g_patch_mutex);

    /* If addr already has an active entry, don't overwrite orig (keep very-first) */
    for (uint32_t i = 0; i < TRAINER_PATCH_CAP; i++) {
        if (g_patches[i].active && g_patches[i].addr == (uint64_t)addr) {
            g_patches[i].len = len; /* update len in case it changed */
            pthread_mutex_unlock(&g_patch_mutex);
            goto patch_recorded;
        }
    }

    /* Find a free slot */
    {
        bool recorded = false;
        for (uint32_t i = 0; i < TRAINER_PATCH_CAP; i++) {
            if (!g_patches[i].active) {
                g_patches[i].addr   = (uint64_t)addr;
                g_patches[i].len    = len;
                memcpy(g_patches[i].orig, orig, len);
                g_patches[i].active = true;
                recorded = true;
                break;
            }
        }
        pthread_mutex_unlock(&g_patch_mutex);

        if (!recorded) {
            /* Patch is already live — just can't restore it later. Report success. */
            LOGE("trainer: PATCH table full (cap %u) — patch is LIVE but cannot be restored via TCMD_RESTORE",
                 TRAINER_PATCH_CAP);
        }
    }

patch_recorded:;
    /* 11. Emit DIAG log: pid, cmdline, addr, len, patch bytes (hex), orig bytes (hex) */
    {
        char dbgcmd[128]; dbgcmd[0] = '\0';
        int cfd = open("/proc/self/cmdline", O_RDONLY);
        if (cfd >= 0) {
            ssize_t cn = read(cfd, dbgcmd, sizeof(dbgcmd) - 1);
            if (cn > 0) dbgcmd[cn] = '\0';
            close(cfd);
        }

        char hexbuf_patch[TRAINER_PATCH_MAX_LEN * 3 + 1];
        char hexbuf_orig [TRAINER_PATCH_MAX_LEN * 3 + 1];
        hexbuf_patch[0] = hexbuf_orig[0] = '\0';
        for (uint32_t k = 0; k < len && k < TRAINER_PATCH_MAX_LEN; k++) {
            snprintf(hexbuf_patch + k * 3, 4, "%02x ", patchbuf[k]);
            snprintf(hexbuf_orig  + k * 3, 4, "%02x ", orig[k]);
        }

        LOGI("trainer: [DIAG] PATCH pid=%d cmdline='%s' addr=0x%lx len=%u"
             " patch=[%s] orig=[%s]",
             getpid(), dbgcmd, (unsigned long)addr, len,
             hexbuf_patch, hexbuf_orig);
    }

    resp_set_status(TST_READY);
    resp_bump();
}

/*
 * TCMD_RESTORE
 * Restore bytes saved by a prior TCMD_PATCH.
 * addr==0 means restore ALL active patches; else restore the single matching one.
 * Idempotent: if no matching active entry exists, still returns TST_READY.
 */
static void cmd_restore(uintptr_t addr)
{
    /* Snapshot the patch table under the mutex */
    patch_entry_t snap[TRAINER_PATCH_CAP];
    pthread_mutex_lock(&g_patch_mutex);
    memcpy(snap, g_patches, sizeof(snap));
    pthread_mutex_unlock(&g_patch_mutex);

    long page_sz = sysconf(_SC_PAGESIZE);
    if (page_sz <= 0) page_sz = 4096;

    uint32_t restored = 0;
    bool     found    = false;

    for (uint32_t i = 0; i < TRAINER_PATCH_CAP; i++) {
        if (!snap[i].active) continue;
        if (addr != 0 && snap[i].addr != (uint64_t)addr) continue;

        found = true;
        uintptr_t tgt  = (uintptr_t)snap[i].addr;
        uint32_t  len  = snap[i].len;
        uintptr_t page = tgt & ~(uintptr_t)(page_sz - 1);
        size_t    span = (tgt + len) - page;

        /* mprotect(rwx) */
        if (mprotect((void *)page, span, PROT_READ | PROT_WRITE | PROT_EXEC) < 0) {
            LOGE("trainer: RESTORE[%u]: mprotect(rwx) 0x%lx failed: %s",
                 i, (unsigned long)tgt, strerror(errno));
            continue;
        }

        /* Restore original bytes */
        memcpy((void *)tgt, snap[i].orig, len);

        /* I-cache flush — same dynarec risk as TCMD_PATCH */
        __builtin___clear_cache((char *)tgt, (char *)(tgt + len));

        /* Best-effort restore to r-x */
        if (mprotect((void *)page, span, PROT_READ | PROT_EXEC) < 0) {
            LOGE("trainer: RESTORE[%u]: mprotect(r-x) restore 0x%lx failed (non-fatal): %s",
                 i, (unsigned long)tgt, strerror(errno));
        }

        /* Mark inactive in the live table */
        pthread_mutex_lock(&g_patch_mutex);
        g_patches[i].active = false;
        pthread_mutex_unlock(&g_patch_mutex);

        restored++;
    }

    if (addr != 0 && !found) {
        LOGI("trainer: RESTORE addr=0x%lx — no active patch found (idempotent ok)",
             (unsigned long)addr);
    }

    LOGI("trainer: [DIAG] RESTORE addr=0x%lx restored=%u", (unsigned long)addr, restored);
    resp_set_status(TST_READY);
    resp_bump();
}

static void cmd_reset(void)
{
    matches_free();

    pthread_mutex_lock(&g_freeze_mutex);
    memset(g_freezes, 0, sizeof(g_freezes));
    pthread_mutex_unlock(&g_freeze_mutex);

    /* Also restore all active code patches so a reset cleanly un-patches game code */
    patches_restore_all_nolock();

    atomic_store_explicit(
        (atomic_uint *)&g_shm->resp_count, 0u, memory_order_relaxed);
    for (uint32_t i = 0; i < TRAINER_RESULT_CAP; i++) g_shm->results[i] = 0;
    resp_set_status(TST_READY);
    resp_bump();
    LOGI("trainer: RESET done");
}

/* ---- worker thread ------------------------------------------------------- */

/*
 * The worker waits on cmd_seq (the Android-side bumps it after writing a
 * command). On wake, the worker reads cmd + params, dispatches, then bumps
 * resp_seq so the Android side can unblock.
 *
 * Handshake:
 *   1. Android writes cmd fields (MappedByteBuffer — no FUTEX_WAKE possible).
 *   2. Android bumps cmd_seq (getInt + 1 + putInt at OFF_CMD_SEQ).
 *   3. Worker wakes from its TIMED futex_wait (≤20 ms), sees cmd_seq changed,
 *      reads cmd, dispatches.  NOTE: Android cannot call FUTEX_WAKE from pure
 *      Kotlin/Java; the timed poll is the ONLY wakeup mechanism.
 *   4. Worker writes results, sets resp_status.
 *   5. Worker atomic_fetch_add(resp_seq, 1) + FUTEX_WAKE (to resp_seq).
 *   6. Android's busy-poll sees curRespSeq != oldRespSeq, reads results.
 *
 * The worker tracks last_cmd_seq to detect missed wakes (spurious or
 * multi-command bursts).
 */
static void *worker_thread(void *arg)
{
    (void)arg;
    LOGI("trainer: worker thread started (PID %d tid %ld)",
         (int)getpid(), (long)syscall(SYS_gettid));

    uint32_t last_seq = atomic_load_explicit(
        (atomic_uint *)&g_shm->cmd_seq, memory_order_acquire);

    for (;;) {
        /* Wait until cmd_seq changes */
        uint32_t cur = atomic_load_explicit(
            (atomic_uint *)&g_shm->cmd_seq, memory_order_acquire);

        if (cur == last_seq) {
            /* Nothing new — block */
            futex_wait((atomic_uint *)&g_shm->cmd_seq, last_seq);
            cur = atomic_load_explicit(
                (atomic_uint *)&g_shm->cmd_seq, memory_order_acquire);
        }

        if (cur == last_seq) continue; /* spurious wake */
        last_seq = cur;

        /* Read command and parameters with acquire fence */
        uint32_t cmd    = atomic_load_explicit(
            (atomic_uint *)&g_shm->cmd,       memory_order_acquire);
        uint32_t vtype  = atomic_load_explicit(
            (atomic_uint *)&g_shm->cmd_vtype,  memory_order_relaxed);
        uint32_t filter = atomic_load_explicit(
            (atomic_uint *)&g_shm->cmd_filter, memory_order_relaxed);
        uint64_t addr   = g_shm->cmd_addr;   /* 64-bit, plain read after acquire above */
        uint64_t value  = g_shm->cmd_value;

        LOGD("trainer: dispatching cmd=%u vtype=%u addr=0x%llx value=0x%llx",
             cmd, vtype,
             (unsigned long long)addr, (unsigned long long)value);

        switch (cmd) {
            case TCMD_NOP:
                break;
            case TCMD_PING:
                cmd_ping();
                break;
            case TCMD_SCAN_NEW:
                cmd_scan_new(vtype, value);
                break;
            case TCMD_SCAN_NEXT:
                cmd_scan_next(vtype, filter, value);
                break;
            case TCMD_READ:
                cmd_read((uintptr_t)addr, vtype);
                break;
            case TCMD_WRITE:
                cmd_write((uintptr_t)addr, vtype, value);
                break;
            case TCMD_FREEZE:
                cmd_freeze((uintptr_t)addr, vtype, value);
                break;
            case TCMD_UNFREEZE:
                cmd_unfreeze((uintptr_t)addr);
                break;
            case TCMD_RESET:
                cmd_reset();
                break;
            case TCMD_AOB_SCAN:
                cmd_aob_scan((int64_t)addr);
                break;
            case TCMD_PATCH:
                cmd_patch((uintptr_t)addr, (uint32_t)g_shm->cmd_patch_len);
                break;
            case TCMD_RESTORE:
                cmd_restore((uintptr_t)addr);
                break;
            default:
                LOGE("trainer: unknown cmd %u — ignoring", cmd);
                resp_set_status(TST_ERROR); resp_set_error(ENOSYS); resp_bump();
                break;
        }
    }

    return NULL; /* unreachable */
}

/* ---- freeze thread ------------------------------------------------------- */

/*
 * Runs every FREEZE_INTERVAL_NS (~50 ms).
 * Re-writes each active frozen address via /proc/self/mem.
 * Holds the mutex only while copying the snapshot; writes happen unlocked
 * so we don't stall the worker on slow pwrite calls.
 */
static void *freeze_thread(void *arg)
{
    (void)arg;
    LOGI("trainer: freeze thread started");

    struct timespec ts = {
        .tv_sec  = FREEZE_INTERVAL_NS / 1000000000L,
        .tv_nsec = FREEZE_INTERVAL_NS % 1000000000L,
    };

    freeze_entry_t snap[TRAINER_FREEZE_CAP];

    for (;;) {
        nanosleep(&ts, NULL);

        /* Heartbeat: prove the owner is alive so a future relaunch can tell a
         * stale (dead-PID) shm from a live one and reclaim only the dead one. */
        if (g_shm) {
            atomic_fetch_add_explicit(
                (atomic_uint *)&g_shm->owner_heartbeat, 1u, memory_order_relaxed);
        }

        /* Snapshot the freeze table */
        pthread_mutex_lock(&g_freeze_mutex);
        memcpy(snap, g_freezes, sizeof(snap));
        pthread_mutex_unlock(&g_freeze_mutex);

        for (uint32_t i = 0; i < TRAINER_FREEZE_CAP; i++) {
            if (!snap[i].active) continue;
            if (!mem_write_value((uintptr_t)snap[i].addr, snap[i].vtype, snap[i].value)) {
                /* Address may have been unmapped; don't spam the log every tick */
                LOGD("trainer: freeze pwrite 0x%llx failed: %s",
                     (unsigned long long)snap[i].addr, strerror(errno));
            }
        }
    }

    return NULL;
}

/* ---- shared-memory setup ------------------------------------------------ */

/*
 * Derive the trainer shm directory path using the same logic as evshim's
 * build_gamepad_dir: prefer TRAINER_BASE_PATH env var, fall back to
 * /proc/self/cmdline package derivation, then a hardcoded IIC fallback.
 */
static void build_trainer_dir(char *out, size_t size)
{
    const char *base = getenv("TRAINER_BASE_PATH");

    if (!base || !*base) {
        static char derived_base[PATH_MAX];
        derived_base[0] = '\0';

        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            char cmdline[256];
            ssize_t n = read(fd, cmdline, sizeof(cmdline) - 1);
            close(fd);
            if (n > 0) {
                cmdline[n] = '\0';
                char *colon = strchr(cmdline, ':');
                if (colon) *colon = '\0';
                if (strchr(cmdline, '.') && cmdline[0] != '\0') {
                    snprintf(derived_base, sizeof(derived_base),
                             "/data/data/%s/files", cmdline);
                    base = derived_base;
                    LOGI("trainer: derived base from cmdline: %s", derived_base);
                }
            }
        }

        if (!base || !*base) {
            base = "/data/data/app.gamenative.iic/files";
            LOGI("trainer: using hardcoded fallback base: %s", base);
        }
    }

    snprintf(out, size, "%s/trainer_shm", base);
}

/*
 * Set up the mmap'd shm file, open /proc/self/mem, and spawn threads.
 * Returns true on success, false on any error (caller suppresses further init).
 */
static bool setup_trainer(void)
{
    char dir[PATH_MAX];
    build_trainer_dir(dir, sizeof(dir));

    /* Create directory 0700 (owner-only, matching evshim) */
    struct stat st;
    if (stat(dir, &st) == 0) {
        if (!S_ISDIR(st.st_mode)) {
            LOGE("trainer: %s exists but is not a directory", dir);
            return false;
        }
    } else {
        if (mkdir(dir, 0700) < 0 && errno != EEXIST) {
            LOGE("trainer: mkdir '%s' failed: %s", dir, strerror(errno));
            return false;
        }
    }

    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/trainer.mem", dir);

    int fd = open(path, O_RDWR | O_CREAT, 0600);
    if (fd < 0) {
        LOGE("trainer: open '%s' failed: %s", path, strerror(errno));
        return false;
    }

    if (ftruncate(fd, (off_t)TRAINER_SHM_SIZE) < 0) {
        LOGE("trainer: ftruncate failed: %s", strerror(errno));
        close(fd);
        return false;
    }

    g_shm_map_size = TRAINER_SHM_SIZE;
    g_shm = mmap(NULL, g_shm_map_size,
                 PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (g_shm == MAP_FAILED) {
        LOGE("trainer: mmap failed: %s", strerror(errno));
        g_shm = NULL;
        close(fd);
        return false;
    }

    /* Log inode+device for cross-side debugging (mirrors evshim) */
    struct stat shm_st;
    fstat(fd, &shm_st);
    LOGI("trainer: mmap'd inode=%lu dev=%lu addr=%p size=%u",
         (unsigned long)shm_st.st_ino, (unsigned long)shm_st.st_dev,
         (void *)g_shm, TRAINER_SHM_SIZE);

    close(fd);  /* fd no longer needed after mmap */

    /* ---- SINGLE-OWNER ARBITRATION (the real "no cheat works" fix) ----
     * MANY libtrainer copies are LD_PRELOAD'd across the Wine/Box64 tree and ALL
     * reach here and mmap the SAME MAP_SHARED file. If each one blindly
     * memset()s the region and answers commands, they race the cmd_seq/resp_seq
     * handshake and the Android side times out on EVERY command (observed live:
     * 3 workers all "PING ok", then reset() times out). So exactly ONE worker
     * must own the shm. We CAS owner_pid 0->getpid(): the winner is the sole
     * responder; losers munmap and bail (passive no-op, game unaffected).
     *
     * CRITICAL ORDERING: do NOT memset before claiming — a blanket memset would
     * wipe another worker's owner_pid claim and proto_version. We only ever zero
     * the control block as the WINNER, and even then field-by-field, never a
     * full-page memset that could clobber a concurrent claimant.
     *
     * Stale-owner reclaim: if owner_pid is set but that PID is dead (a previous
     * game session that didn't clean up), reclaim it. kill(pid,0)==-1/ESRCH =>
     * dead. This lets a relaunch take over a leftover trainer.mem. */
    {
        uint32_t me = (uint32_t)getpid();
        atomic_uint *owner = (atomic_uint *)&g_shm->owner_pid;

        /* CAS 0 -> me. atomic_compare_exchange_strong takes the EXPECTED value by
         * pointer and overwrites it with the observed value on failure. */
        uint32_t expected = 0u;
        bool won = atomic_compare_exchange_strong_explicit(
            owner, &expected, me, memory_order_acq_rel, memory_order_acquire);
        if (!won) {
            /* `expected` now holds the current owner value. */
            uint32_t cur = expected;
            if (cur == me) {
                won = true;  /* already ours (re-entry) */
            } else if (cur != 0 && kill((pid_t)cur, 0) != 0 && errno == ESRCH) {
                /* Existing owner PID is dead — try to steal the slot. */
                uint32_t expect_dead = cur;
                won = atomic_compare_exchange_strong_explicit(
                    owner, &expect_dead, me, memory_order_acq_rel, memory_order_acquire);
                if (won) {
                    LOGI("trainer: reclaimed stale shm from dead owner pid=%u (now pid=%u)",
                         cur, me);
                }
            }
        }

        if (!won) {
            uint32_t cur = atomic_load_explicit(owner, memory_order_acquire);
            LOGI("trainer: [DIAG] NOT shm owner (owner_pid=%u, me=%u) — passive no-op, munmap",
                 cur, me);
            munmap(g_shm, g_shm_map_size);
            g_shm = NULL;
            return false;
        }

        LOGI("trainer: [DIAG] claimed shm ownership pid=%u", me);
    }

    /* We are the sole owner. Initialise the control block field-by-field (NOT a
     * full-page memset — that would race a late claimant's owner_pid). Preserve
     * owner_pid (already ours). Clear the command/response/result/pattern state. */
    atomic_store_explicit((atomic_uint *)&g_shm->cmd_seq,       0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->resp_seq,      0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->cmd,           0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->resp_status,   0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->resp_error,    0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->resp_progress, 0u, memory_order_relaxed);
    atomic_store_explicit((atomic_uint *)&g_shm->resp_count,    0u, memory_order_relaxed);
    g_shm->cmd_vtype = 0; g_shm->cmd_filter = 0;
    g_shm->cmd_addr = 0;  g_shm->cmd_value = 0; g_shm->resp_value = 0;
    g_shm->cmd_pattern_len = 0; g_shm->cmd_patch_len = 0;
    for (uint32_t i = 0; i < TRAINER_RESULT_CAP; i++) g_shm->results[i] = 0;
    g_shm->owner_heartbeat = 0;
    g_shm->proto_version = TRAINER_PROTO_VERSION;

    /* Open /proc/self/mem for safe in-process read/write */
    g_mem_fd = open("/proc/self/mem", O_RDWR);
    if (g_mem_fd < 0) {
        LOGE("trainer: open /proc/self/mem failed: %s", strerror(errno));
        munmap(g_shm, g_shm_map_size);
        g_shm = NULL;
        return false;
    }

    /* Initial status: ready */
    resp_set_status(TST_READY);
    resp_bump();  /* bump resp_seq so Android can detect the lib is alive */

    /* Spawn worker thread */
    pthread_t worker_tid;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&worker_tid, &attr, worker_thread, NULL) != 0) {
        LOGE("trainer: pthread_create worker failed: %s", strerror(errno));
        pthread_attr_destroy(&attr);
        close(g_mem_fd); g_mem_fd = -1;
        munmap(g_shm, g_shm_map_size); g_shm = NULL;
        return false;
    }
    pthread_attr_destroy(&attr);

    /* Spawn freeze thread */
    pthread_t freeze_tid;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    if (pthread_create(&freeze_tid, &attr, freeze_thread, NULL) != 0) {
        /* Non-fatal: scanning still works; just log the failure */
        LOGE("trainer: pthread_create freeze failed: %s — freezing disabled", strerror(errno));
    }
    pthread_attr_destroy(&attr);

    LOGI("trainer: init complete at '%s'", path);
    return true;
}

/* ---- constructor --------------------------------------------------------- */

/*
 * Called automatically when the .so is loaded via LD_PRELOAD.
 *
 * Gate: only activates if TRAINER_ENABLED=1 is present in the environment.
 * This means:
 *   - Games launched normally (without the Android trainer UI setting the env)
 *     never pay any cost at all from this library.
 *   - If setup fails for any reason we log an error and return silently; the
 *     game continues running normally.
 *   - We never crash the host: setup_trainer() checks every syscall return.
 */
/* ---- process-identity gate (FIX 1) -------------------------------------- */

/*
 * maps_has_module — true iff /proc/self/maps contains a file-mapped region whose
 * basename case-insensitively equals `target` (e.g. "dmc3.exe").
 *
 * This is far more reliable than matching /proc/self/cmdline: the game's PE is
 * file-mapped into the process that actually runs it (Wine maps the .exe and its
 * DLLs as files), so a maps hit on dmc3.exe is AUTHORITATIVE that this address
 * space holds the game — regardless of what argv looks like (the launcher argv
 * is "wine explorer ..." and contains deny-listed tokens like "explorer").
 */
static bool maps_has_module(const char *target)
{
    if (!target || !target[0]) return false;

    /* lowercase target basename */
    char tgt[256];
    size_t tl = strlen(target);
    if (tl >= sizeof(tgt)) tl = sizeof(tgt) - 1;
    for (size_t i = 0; i < tl; i++) tgt[i] = (char)tolower((unsigned char)target[i]);
    tgt[tl] = '\0';

    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return false;

    char line[MAPS_LINE_MAX];
    bool found = false;
    while (fgets(line, sizeof(line), f)) {
        /* The pathname is the last whitespace-delimited field (if present). */
        char *path = strrchr(line, ' ');
        if (!path) continue;
        path++;                       /* skip the space */
        size_t L = strlen(path);
        while (L && (path[L-1] == '\n' || path[L-1] == '\r')) path[--L] = '\0';
        if (L == 0 || path[0] == '[') continue;   /* anon / [heap] / [stack] */

        /* basename */
        char *base = strrchr(path, '/');
        base = base ? base + 1 : path;

        /* case-insensitive compare to target */
        size_t bl = strlen(base);
        if (bl != tl) continue;
        bool eq = true;
        for (size_t i = 0; i < bl; i++) {
            if ((char)tolower((unsigned char)base[i]) != tgt[i]) { eq = false; break; }
        }
        if (eq) { found = true; break; }
    }
    fclose(f);
    return found;
}

/*
 * Determine whether THIS process should activate the trainer.
 *
 * LD_PRELOAD is inherited by every child in the Wine/Box64 process tree:
 * wineserver, explorer.exe, services.exe, plugplay.exe, rpcss.exe, etc.
 * Without this gate EVERY one of those processes would spawn a worker thread
 * and race to answer scan commands — each scanning their own /proc/self/mem
 * instead of the game's.  The gate admits only the actual game process.
 *
 * Strategy:
 *  1. Read /proc/self/cmdline.  Replace embedded NUL bytes with spaces and
 *     lowercase the result to get a single string to match against.
 *  2. DENY if the cmdline contains any well-known Wine helper exe name.
 *  3. If TRAINER_TARGET_EXE is set, REQUIRE the cmdline to contain that string.
 *     If unset, fall back: require ".exe" to appear (any Windows guest exe) and
 *     not be in the deny list.
 *  4. If cmdline cannot be read, DENY (conservative: the game always has a
 *     readable cmdline, helper processes sometimes don't — don't activate blindly).
 *
 * Returns true if this process should activate, false if it should skip.
 */
static bool should_activate_trainer(void)
{
    /* Read /proc/self/cmdline */
    char raw[512];
    raw[0] = '\0';
    int cfd = open("/proc/self/cmdline", O_RDONLY);
    if (cfd < 0) {
        LOGI("trainer: [DIAG] cannot open /proc/self/cmdline (errno=%d) — DENY", errno);
        return false;
    }
    ssize_t n = read(cfd, raw, sizeof(raw) - 1);
    close(cfd);
    if (n <= 0) {
        LOGI("trainer: [DIAG] /proc/self/cmdline read returned %zd — DENY", n);
        return false;
    }
    raw[n] = '\0';

    /* Replace NUL separators with spaces so we can do simple substring searches */
    for (ssize_t i = 0; i < n; i++) {
        if (raw[i] == '\0') raw[i] = ' ';
    }

    /* Lowercase the whole thing */
    char cmd[512];
    for (ssize_t i = 0; i <= n; i++) {
        cmd[i] = (char)tolower((unsigned char)raw[i]);
    }

    /* --- Deny-list: known Wine helper / infrastructure processes --- */
    static const char * const deny_list[] = {
        "wineserver",
        "services.exe",
        "explorer.exe",
        "winedevice.exe",
        "plugplay.exe",
        "rpcss.exe",
        "svchost.exe",
        "conhost.exe",
        "winemenubuilder.exe",
        "start.exe",
        "rundll32.exe",
        "wineboot",
        "winhandler",
        NULL,
    };

    int denied = 0;
    for (int i = 0; deny_list[i]; i++) {
        if (strstr(cmd, deny_list[i])) {
            denied = 1;
            break;
        }
    }

    /* --- Target match --- */
    const char *target_env = getenv("TRAINER_TARGET_EXE");

    if (target_env && target_env[0] != '\0') {
        /* TRAINER_TARGET_EXE is set. PRIMARY signal: is the game's PE actually
         * file-mapped into THIS address space (/proc/self/maps)? That is
         * authoritative — it means the game runs here — and it is NOT vetoed by
         * the deny-list, because the launcher argv legitimately contains
         * "explorer"/"winhandler" even in the real game process.
         *
         * The earlier bug was: `if (denied || !is_target) return false;` with
         * is_target derived ONLY from a cmdline substring. The game's argv is
         * "wine explorer /desktop=shell,... <game>" so `denied` (explorer) was
         * true and the game process was wrongly skipped — OR, conversely, the
         * generic "wine" launcher passed and MULTIPLE processes activated and
         * raced. The maps check fixes both; owner_pid CAS is the final backstop. */
        char target_lower[256];
        size_t tlen = strlen(target_env);
        if (tlen >= sizeof(target_lower)) tlen = sizeof(target_lower) - 1;
        for (size_t i = 0; i < tlen; i++)
            target_lower[i] = (char)tolower((unsigned char)target_env[i]);
        target_lower[tlen] = '\0';

        bool maps_hit = maps_has_module(target_lower);
        bool cmd_hit  = (strstr(cmd, target_lower) != NULL);

        LOGI("trainer: [DIAG] skip-gate pid=%d cmdline='%s' TRAINER_TARGET_EXE='%s' maps_hit=%d cmd_hit=%d denied=%d",
             getpid(), cmd, target_env, maps_hit, cmd_hit, denied);

        /* A maps hit is authoritative — activate regardless of deny-list tokens.
         * If we only have a cmdline hit (no maps), still honour the deny-list to
         * avoid activating in a helper that merely mentions the target name. */
        if (maps_hit) {
            return true;
        }
        if (!cmd_hit || denied) {
            LOGI("trainer: [DIAG] skip activation pid=%d (no maps hit; cmd_hit=%d denied=%d)",
                 getpid(), cmd_hit, denied);
            return false;
        }
        /* cmd_hit && !denied && !maps_hit — weak signal, allow (CAS still gates). */
    } else {
        /* TRAINER_TARGET_EXE is unset: fall back to heuristic —
         * activate only if cmdline contains ".exe" (some Windows guest process)
         * AND is not in the deny list. */
        int has_exe = (strstr(cmd, ".exe") != NULL);

        LOGI("trainer: [DIAG] skip-gate pid=%d cmdline='%s' (no TRAINER_TARGET_EXE) denied=%d has_exe=%d",
             getpid(), cmd, denied, has_exe);

        if (denied || !has_exe) {
            LOGI("trainer: [DIAG] skip activation pid=%d cmdline='%s' (denied=%d has_exe=%d)",
                 getpid(), cmd, denied, has_exe);
            return false;
        }
    }

    return true;
}

__attribute__((constructor))
static void trainer_init(void)
{
    const char *enabled = getenv("TRAINER_ENABLED");
    if (!enabled || enabled[0] != '1') {
        /* [DIAG] Log the no-op case so we can see when the trainer was NOT enabled
         * at game-launch time (the #1 cause of in-game "SCAN ERROR": the user
         * enabled the trainer AFTER launching, so this .so is a dead no-op and the
         * Android side talks to a shm no worker is listening on). */
        char dbgcmd[128]; dbgcmd[0] = '\0';
        int cfd = open("/proc/self/cmdline", O_RDONLY);
        if (cfd >= 0) { ssize_t cn = read(cfd, dbgcmd, sizeof(dbgcmd)-1); if (cn > 0) dbgcmd[cn] = '\0'; close(cfd); }
        LOGI("trainer: [DIAG] NOT enabled (TRAINER_ENABLED=%s) in pid=%d cmdline='%s' — no-op",
             enabled ? enabled : "(null)", (int)getpid(), dbgcmd);
        return;
    }

    /* FIX 1: Gate activation to the actual game process only.
     * Every process in the Wine/Box64 tree inherits LD_PRELOAD, so this
     * constructor runs in wineserver, explorer, services, etc.  We must
     * only activate the worker in the one process that holds the game's
     * address space.  should_activate_trainer() encodes the deny-list +
     * target-exe logic; if it returns false we are NOT the game. */
    if (!should_activate_trainer()) {
        /* Already logged inside should_activate_trainer() */
        return;
    }

    LOGI("trainer: TRAINER_ENABLED=1 detected, starting up (PID %d)", (int)getpid());

    if (!setup_trainer()) {
        LOGE("trainer: setup failed — running in passthrough mode (game unaffected)");
    }
}
