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

static void cmd_reset(void)
{
    matches_free();

    pthread_mutex_lock(&g_freeze_mutex);
    memset(g_freezes, 0, sizeof(g_freezes));
    pthread_mutex_unlock(&g_freeze_mutex);

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

    /* Zero the region and write the protocol version */
    memset(g_shm, 0, TRAINER_SHM_SIZE);
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
    int is_target = 0;

    if (target_env && target_env[0] != '\0') {
        /* TRAINER_TARGET_EXE is set: require an exact (lowercased) substring match */
        char target_lower[256];
        size_t tlen = strlen(target_env);
        if (tlen >= sizeof(target_lower)) tlen = sizeof(target_lower) - 1;
        for (size_t i = 0; i < tlen; i++)
            target_lower[i] = (char)tolower((unsigned char)target_env[i]);
        target_lower[tlen] = '\0';

        if (strstr(cmd, target_lower)) {
            is_target = 1;
        }

        LOGI("trainer: [DIAG] skip-gate pid=%d cmdline='%s' TRAINER_TARGET_EXE='%s' target_lower='%s' denied=%d is_target=%d",
             getpid(), cmd, target_env, target_lower, denied, is_target);

        if (denied || !is_target) {
            LOGI("trainer: [DIAG] skip activation pid=%d cmdline='%s' (denied=%d is_target=%d)",
                 getpid(), cmd, denied, is_target);
            return false;
        }
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
        is_target = 1;
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
