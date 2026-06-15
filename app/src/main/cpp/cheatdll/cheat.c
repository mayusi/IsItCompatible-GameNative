/*
 * cheat.c — in-process cheat engine for the proxy DLL (runs INSIDE dmc3.exe).
 *
 * Memory access is plain in-process pointers — no RPM/WPM, no ptrace, no shm.
 *
 * Modes (driven by a command file the Android side / adb writes):
 *   - SCAN:   find every writable f32 in the process equal to a target value
 *             (Cheat-Engine "new scan"), then narrow on a second value.
 *   - FREEZE: continuously write a value to a resolved address.
 *
 * Command file:  <game_dir>\cheat_ctrl.txt   (game folder is writable + not wiped)
 *   line "scan=<float>"        -> fresh scan for that f32 value across writable mem
 *   line "next=<float>"        -> narrow the current candidate set to those now == <float>
 *   line "freeze=<hexaddr>=<float>"  -> freeze a specific address to a value
 *   line "freezeoff"           -> stop all freezes
 * The DLL ALSO reads the file's first token only once per change (tracks size+first line).
 *
 * Output:  <game_dir>\cheat_out.txt   (status + candidate addresses)
 */
#include <windows.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#define POLL_MS      400
#define FREEZE_MS    16
/* Big enough to snapshot every plausible counter cell across the process.
 * Memory cost = MAX_CAND * (8 + 4 + 4) = 16 bytes each → 4M * 16 = 64MB, fine
 * inside the game process. The old 4096 cap filled in the first 3 regions and
 * missed everything else — that was why the snapshot found nothing useful. */
#define MAX_CAND     (4u * 1024u * 1024u)

/* The control/output files live in the GAME folder (writable, not temp-wiped).
 * We resolve the game dir from the host exe path at startup. */
static char g_ctrl_path[MAX_PATH];
static char g_out_path[MAX_PATH];

/* candidate set from the last scan (heap-allocated lazily — 64MB at full cap) */
static uintptr_t *g_cand   = NULL;
static int32_t   *g_snap_i = NULL;
static float     *g_snap_f = NULL;
static uint32_t   g_cand_n = 0;
static int        g_cand_is_int = 0;   /* 1 = candidates are i32, 0 = f32 */

/* Allocate the candidate arrays on first use. Returns 1 on success. */
static int ensure_cand(void)
{
    if (g_cand) return 1;
    g_cand   = (uintptr_t *)VirtualAlloc(NULL, (SIZE_T)MAX_CAND * sizeof(uintptr_t),
                                         MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    g_snap_i = (int32_t *)VirtualAlloc(NULL, (SIZE_T)MAX_CAND * sizeof(int32_t),
                                       MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    g_snap_f = (float *)VirtualAlloc(NULL, (SIZE_T)MAX_CAND * sizeof(float),
                                     MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    return (g_cand && g_snap_i && g_snap_f);
}

/* active single-address freeze */
static volatile uintptr_t g_freeze_addr = 0;
static volatile LONG      g_freeze_bits = 0;   /* raw 32-bit value (float or int) */
static volatile LONG      g_freeze_on   = 0;
static volatile LONG      g_freeze_is_int = 0;

/* DMC3 live-actor HP/DT freeze (re-resolves the actor chain each tick).
 * Chain (verified current, ddmk/Crimson 2024-2026):
 *   actorPool = *(base + 0xC90E28)
 *   actor     = *(actorPool + 0x18)   // slot [3]
 *   HP  f32 @ actor + 0x411C   (max @ +0x40EC)
 *   DT  f32 @ actor + 0x3EB8   (max @ +0x3EBC)
 */
static volatile LONG g_dmc3_hp_on = 0;
static volatile LONG g_dmc3_dt_on = 0;

static uintptr_t dmc3_actor(void);   /* fwd decl (defined below) */

/* ===========================================================================
 * GENERIC POINTER-CHAIN FREEZE TABLE — the catalog-driven engine.
 *
 * The Android catalog (registry.json) describes each one-tap cheat as a chain:
 *   module + base offset, a list of deref offsets, a final value offset, a
 *   value type, and either a literal freeze value OR "write the max field".
 * The Kotlin side sends one line per active cheat; the DLL re-resolves the
 * chain EVERY tick (so it survives struct reallocation between missions) and
 * writes the value. This is what makes the engine work for ANY game, not just
 * a hardcoded DMC3 handler.
 *
 * Wire format (one command line):
 *   chain=<id>|<module>|<basehex>|<off1,off2,...>|<valoffhex>|<vtype>|<mode>|<valbits>
 *     id       : small int slot key (so toggling the same cheat updates in place)
 *     module   : exe/dll name, e.g. dmc3.exe   (empty => host exe)
 *     basehex  : hex offset from module base to the first pointer
 *     offs     : comma list of hex deref offsets (may be empty)
 *     valoffhex: hex offset from the final resolved pointer to the value
 *     vtype    : i32 | u32 | f32
 *     mode     : lit (freeze to valbits) | max (write the f32/i32 at value+maxoff)
 *                for "max" mode valbits carries the maxoff (hex) instead of a value
 *     valbits  : raw 32-bit value bits (for lit) OR hex maxoff (for max)
 *   chainoff=<id>   : disable the slot with that id
 *   chainclear      : disable all chain slots
 * =========================================================================== */
#define CHAIN_CAP        32u
#define CHAIN_OFFS_MAX   8u

typedef struct {
    int      active;
    char     module[64];
    uintptr_t base;
    uintptr_t offs[CHAIN_OFFS_MAX];
    uint32_t  n_offs;
    uintptr_t valoff;
    int       vtype;        /* 0=i32, 1=u32, 2=f32 */
    int       use_max;      /* 1 => write value at (valuePtr - valoff + maxoff) */
    uintptr_t maxoff;       /* when use_max */
    LONG      litbits;      /* raw value bits when !use_max */
    int       id;
} chain_slot_t;

static chain_slot_t g_chains[CHAIN_CAP];
static CRITICAL_SECTION g_chain_cs;
static int g_chain_cs_init = 0;

static void chain_lock(void)   { if (g_chain_cs_init) EnterCriticalSection(&g_chain_cs); }
static void chain_unlock(void) { if (g_chain_cs_init) LeaveCriticalSection(&g_chain_cs); }

/* fwd decls (defined below cheat_thread) */
static void chain_install(char *args);
static void chain_remove(int id);
static void chains_clear(void);

static void out_log(const char *msg)
{
    HANDLE h = CreateFileA(g_out_path, FILE_APPEND_DATA, FILE_SHARE_READ,
                           NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    SetFilePointer(h, 0, NULL, FILE_END);
    DWORD w = 0; WriteFile(h, msg, (DWORD)strlen(msg), &w, NULL);
    CloseHandle(h);
}

/* ---- safe accessors (MinGW: no SEH; use IsBad* probes) ---- */
static int rd_f32(uintptr_t a, float *o)
{
    if (a < 0x10000) return 0;
    if (IsBadReadPtr((const void *)a, 4)) return 0;
    *o = *(volatile float *)a; return 1;
}
static int wr_f32(uintptr_t a, float v)
{
    if (a < 0x10000) return 0;
    if (IsBadWritePtr((void *)a, 4)) return 0;
    *(volatile float *)a = v; return 1;
}
static int rd_i32(uintptr_t a, int32_t *o)
{
    if (a < 0x10000) return 0;
    if (IsBadReadPtr((const void *)a, 4)) return 0;
    *o = *(volatile int32_t *)a; return 1;
}
static int rd_ptr(uintptr_t a, uintptr_t *o)
{
    if (a < 0x10000) return 0;
    if (IsBadReadPtr((const void *)a, sizeof(uintptr_t))) return 0;
    *o = *(volatile uintptr_t *)a; return 1;
}
static int wr_i32(uintptr_t a, int32_t v)
{
    if (a < 0x10000) return 0;
    if (IsBadWritePtr((void *)a, 4)) return 0;
    *(volatile int32_t *)a = v; return 1;
}

/* ---- memory region walk (VirtualQuery) ---- */
/* Fresh scan: every writable, committed, non-guard region; collect 4-byte cells
 * equal to the target. is_int selects i32 vs f32 interpretation.
 *
 * PERF: VirtualQuery already tells us the region is committed + readable, so we
 * scan each region with a PLAIN pointer loop — NO per-cell IsBadReadPtr (which
 * is a syscall per 4 bytes and made the first version appear hung over a multi-GB
 * space). We cap total bytes scanned and region size to stay responsive, and skip
 * giant regions (>256MB, almost always GPU/asset buffers, not gameplay state). */
static void do_scan(int is_int, int32_t iv, float fv)
{
    if (!ensure_cand()) { out_log("[scan] alloc failed\r\n"); return; }
    g_cand_n = 0;
    g_cand_is_int = is_int;
    SYSTEM_INFO si; GetSystemInfo(&si);
    uintptr_t addr = (uintptr_t)si.lpMinimumApplicationAddress;
    uintptr_t end  = (uintptr_t)si.lpMaximumApplicationAddress;
    uint32_t total = 0;
    uint64_t scanned = 0;
    uint32_t regions = 0;

    const size_t REGION_MAX = (size_t)256 * 1024 * 1024;   /* skip huge buffers */

    MEMORY_BASIC_INFORMATION mbi;
    while (addr < end && g_cand_n < MAX_CAND) {
        if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi)) break;
        uintptr_t base = (uintptr_t)mbi.BaseAddress;
        size_t    rsz  = mbi.RegionSize;

        int writable = (mbi.State == MEM_COMMIT) &&
                       (mbi.Protect & (PAGE_READWRITE | PAGE_WRITECOPY |
                                       PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)) &&
                       !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS));
        if (writable && rsz <= REGION_MAX) {
            regions++;
            const unsigned char *q = (const unsigned char *)base;
            size_t n = (rsz >= 4) ? (rsz - 4) : 0;
            scanned += rsz;
            for (size_t off = 0; off <= n && g_cand_n < MAX_CAND; off += 4) {
                int hit;
                if (is_int) { int32_t x; __builtin_memcpy(&x, q + off, 4); hit = (x == iv); }
                else        { float   x; __builtin_memcpy(&x, q + off, 4); hit = (x == fv); }
                if (hit) { g_cand[g_cand_n++] = base + off; total++; }
            }
        }
        addr = base + rsz;
        if (rsz == 0) break;
    }

    char m[200];
    if (is_int) snprintf(m, sizeof(m), "[scan] i32 target=%d found=%u regions=%u scannedMB=%llu (capped=%d)\r\n",
                         iv, total, regions, (unsigned long long)(scanned >> 20), (g_cand_n >= MAX_CAND));
    else        snprintf(m, sizeof(m), "[scan] f32 target=%.3f found=%u regions=%u scannedMB=%llu (capped=%d)\r\n",
                         fv, total, regions, (unsigned long long)(scanned >> 20), (g_cand_n >= MAX_CAND));
    out_log(m);
    uint32_t dump = g_cand_n < 12 ? g_cand_n : 12;
    for (uint32_t i = 0; i < dump; i++) {
        snprintf(m, sizeof(m), "[scan]   cand[%u]=0x%llx\r\n", i, (unsigned long long)g_cand[i]);
        out_log(m);
    }
}

/* SNAPSHOT scan: record every writable 4-byte cell whose value (interpreted as
 * BOTH a plausible small int AND read as float) looks like a candidate counter,
 * and store its current value. This is the "unknown value" entry point: we don't
 * need to know the exact displayed number (the HUD animates). Afterwards, call
 * do_delta(+1) to keep cells that INCREASED, -1 for decreased, 0 for changed.
 *
 * To stay within MAX_CAND we only snapshot cells whose int value is a plausible
 * counter: 0 < v < 100000000 (covers orbs/HP/etc.), which prunes pointers/floats. */
static void do_snapshot(void)
{
    if (!ensure_cand()) { out_log("[snap] alloc failed\r\n"); return; }
    g_cand_n = 0;
    g_cand_is_int = 1;   /* we track the int interpretation for delta compares */
    SYSTEM_INFO si; GetSystemInfo(&si);
    uintptr_t addr = (uintptr_t)si.lpMinimumApplicationAddress;
    uintptr_t end  = (uintptr_t)si.lpMaximumApplicationAddress;
    const size_t REGION_MAX = (size_t)256 * 1024 * 1024;
    uint32_t regions = 0;
    MEMORY_BASIC_INFORMATION mbi;

    while (addr < end && g_cand_n < MAX_CAND) {
        if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi)) break;
        uintptr_t base = (uintptr_t)mbi.BaseAddress;
        size_t    rsz  = mbi.RegionSize;
        int writable = (mbi.State == MEM_COMMIT) &&
                       (mbi.Protect & (PAGE_READWRITE | PAGE_WRITECOPY |
                                       PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY)) &&
                       !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS));
        if (writable && rsz <= REGION_MAX) {
            regions++;
            const unsigned char *q = (const unsigned char *)base;
            size_t n = (rsz >= 4) ? (rsz - 4) : 0;
            for (size_t off = 0; off <= n && g_cand_n < MAX_CAND; off += 4) {
                int32_t x; __builtin_memcpy(&x, q + off, 4);
                if (x > 0 && x < 100000000) {
                    float f; __builtin_memcpy(&f, q + off, 4);
                    g_cand[g_cand_n]   = base + off;
                    g_snap_i[g_cand_n] = x;
                    g_snap_f[g_cand_n] = f;
                    g_cand_n++;
                }
            }
        }
        addr = base + rsz;
        if (rsz == 0) break;
    }
    char m[160];
    snprintf(m, sizeof(m), "[snap] captured=%u regions=%u (capped=%d)\r\n",
             g_cand_n, regions, (g_cand_n >= MAX_CAND));
    out_log(m);
}

/* DELTA narrow: dir=+1 keep cells whose int value INCREASED vs snapshot, -1
 * decreased, 0 changed. Re-snapshots survivors so you can chain deltas. */
static void do_delta(int dir)
{
    uint32_t keep = 0;
    for (uint32_t i = 0; i < g_cand_n; i++) {
        int32_t cur;
        if (!rd_i32(g_cand[i], &cur)) continue;
        int32_t old = g_snap_i[i];
        int ok = (dir > 0) ? (cur > old) : (dir < 0) ? (cur < old) : (cur != old);
        if (ok) {
            g_cand[keep]   = g_cand[i];
            g_snap_i[keep] = cur;       /* re-snapshot for the next delta */
            g_snap_f[keep] = 0;
            keep++;
        }
    }
    g_cand_n = keep;
    char m[160];
    snprintf(m, sizeof(m), "[delta] dir=%d remaining=%u\r\n", dir, g_cand_n);
    out_log(m);
    uint32_t dump = g_cand_n < 16 ? g_cand_n : 16;
    for (uint32_t i = 0; i < dump; i++) {
        snprintf(m, sizeof(m), "[delta]   cand[%u]=0x%llx val=%d\r\n",
                 i, (unsigned long long)g_cand[i], g_snap_i[i]);
        out_log(m);
    }
}

/* Narrow: keep candidates now equal to value (same type as the prior scan).
 * Candidate list is small, so the per-cell IsBad* guard here is fine. */
static void do_next(int32_t iv, float fv)
{
    uint32_t keep = 0;
    for (uint32_t i = 0; i < g_cand_n; i++) {
        int hit;
        if (g_cand_is_int) { int32_t x; hit = rd_i32(g_cand[i], &x) && x == iv; }
        else               { float   x; hit = rd_f32(g_cand[i], &x) && x == fv; }
        if (hit) g_cand[keep++] = g_cand[i];
    }
    g_cand_n = keep;
    char m[160];
    if (g_cand_is_int) snprintf(m, sizeof(m), "[next] i32 value=%d remaining=%u\r\n", iv, g_cand_n);
    else               snprintf(m, sizeof(m), "[next] f32 value=%.3f remaining=%u\r\n", fv, g_cand_n);
    out_log(m);
    uint32_t dump = g_cand_n < 12 ? g_cand_n : 12;
    for (uint32_t i = 0; i < dump; i++) {
        snprintf(m, sizeof(m), "[next]   cand[%u]=0x%llx\r\n", i, (unsigned long long)g_cand[i]);
        out_log(m);
    }
}

/* ---- command file handling ---- */
static char g_last_cmd[256] = {0};

static void poll_ctrl(void)
{
    HANDLE h = CreateFileA(g_ctrl_path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;

    char buf[256]; DWORD got = 0;
    BOOL ok = ReadFile(h, buf, sizeof(buf) - 1, &got, NULL);
    CloseHandle(h);
    if (!ok || got == 0) return;
    buf[got] = '\0';

    /* De-dupe by CONTENT, not size — "scani=43" and "nexti=68" are both 8 bytes,
     * so a size-only check silently dropped the narrow command (real bug). */
    if (strcmp(buf, g_last_cmd) == 0) return;   /* unchanged content */
    strncpy(g_last_cmd, buf, sizeof(g_last_cmd) - 1);
    g_last_cmd[sizeof(g_last_cmd) - 1] = '\0';

    /* trim trailing newline */
    char *nl = strpbrk(buf, "\r\n"); if (nl) *nl = '\0';

    char m[200];
    snprintf(m, sizeof(m), "[ctrl] got: %s\r\n", buf);
    out_log(m);

    if (strncmp(buf, "snap", 4) == 0) {
        do_snapshot();
    } else if (strncmp(buf, "inc", 3) == 0) {
        do_delta(+1);
    } else if (strncmp(buf, "dec", 3) == 0) {
        do_delta(-1);
    } else if (strncmp(buf, "chg", 3) == 0) {
        do_delta(0);
    } else if (strncmp(buf, "scani=", 6) == 0) {
        do_scan(1, (int32_t)strtol(buf + 6, NULL, 10), 0.0f);
    } else if (strncmp(buf, "scanf=", 6) == 0) {
        do_scan(0, 0, (float)atof(buf + 6));
    } else if (strncmp(buf, "nexti=", 6) == 0) {
        do_next((int32_t)strtol(buf + 6, NULL, 10), 0.0f);
    } else if (strncmp(buf, "nextf=", 6) == 0) {
        do_next(0, (float)atof(buf + 6));
    } else if (strncmp(buf, "freezei=", 8) == 0) {
        /* freezei=<hexaddr>=<int> */
        char *p = buf + 8;
        unsigned long long a = strtoull(p, &p, 16);
        int32_t v = (*p == '=') ? (int32_t)strtol(p + 1, NULL, 10) : 0;
        InterlockedExchange(&g_freeze_bits, (LONG)v);
        InterlockedExchange(&g_freeze_is_int, 1);
        g_freeze_addr = (uintptr_t)a;
        InterlockedExchange(&g_freeze_on, 1);
        snprintf(m, sizeof(m), "[ctrl] freeze i32 addr=0x%llx val=%d\r\n", a, v);
        out_log(m);
    } else if (strncmp(buf, "freezef=", 8) == 0) {
        /* freezef=<hexaddr>=<float> */
        char *p = buf + 8;
        unsigned long long a = strtoull(p, &p, 16);
        float v = (*p == '=') ? (float)atof(p + 1) : 0.0f;
        union { float f; LONG l; } u; u.f = v;
        InterlockedExchange(&g_freeze_bits, u.l);
        InterlockedExchange(&g_freeze_is_int, 0);
        g_freeze_addr = (uintptr_t)a;
        InterlockedExchange(&g_freeze_on, 1);
        snprintf(m, sizeof(m), "[ctrl] freeze f32 addr=0x%llx val=%.3f\r\n", a, v);
        out_log(m);
    } else if (strncmp(buf, "readrel=", 8) == 0) {
        /* readrel=<hexoffset>  -> read u32+f32 at dmc3.exe base + offset */
        unsigned long long off = strtoull(buf + 8, NULL, 16);
        HMODULE b = GetModuleHandleW(L"dmc3.exe"); if (!b) b = GetModuleHandleW(NULL);
        uintptr_t a = (uintptr_t)b + (uintptr_t)off;
        int32_t iv = 0; float fv = 0;
        int ri = rd_i32(a, &iv), rf = rd_f32(a, &fv);
        snprintf(m, sizeof(m), "[readrel] base+0x%llx = addr 0x%llx  u32=%d (ok=%d)  f32=%.3f (ok=%d)\r\n",
                 off, (unsigned long long)a, iv, ri, fv, rf);
        out_log(m);
    } else if (strncmp(buf, "readabs=", 8) == 0) {
        /* readabs=<hexaddr> */
        unsigned long long a = strtoull(buf + 8, NULL, 16);
        int32_t iv = 0; float fv = 0;
        int ri = rd_i32((uintptr_t)a, &iv), rf = rd_f32((uintptr_t)a, &fv);
        snprintf(m, sizeof(m), "[readabs] 0x%llx  u32=%d (ok=%d)  f32=%.3f (ok=%d)\r\n",
                 a, iv, ri, fv, rf);
        out_log(m);
    } else if (strncmp(buf, "freezereli=", 11) == 0) {
        /* freezereli=<hexoffset>=<int>  -> freeze i32 at dmc3.exe base + offset */
        char *p = buf + 11;
        unsigned long long off = strtoull(p, &p, 16);
        int32_t v = (*p == '=') ? (int32_t)strtol(p + 1, NULL, 10) : 0;
        HMODULE b = GetModuleHandleW(L"dmc3.exe"); if (!b) b = GetModuleHandleW(NULL);
        InterlockedExchange(&g_freeze_bits, (LONG)v);
        InterlockedExchange(&g_freeze_is_int, 1);
        g_freeze_addr = (uintptr_t)b + (uintptr_t)off;
        InterlockedExchange(&g_freeze_on, 1);
        snprintf(m, sizeof(m), "[ctrl] freeze i32 base+0x%llx (addr 0x%llx) val=%d\r\n",
                 off, (unsigned long long)g_freeze_addr, v);
        out_log(m);
    } else if (strncmp(buf, "orbscan=", 8) == 0) {
        /* orbscan=<int> : scan the ACTOR struct + SessionData neighbourhood for a u32
         * == value. Far more targeted than a full-process scan: orbs live in a known
         * struct family. We scan the actor struct (+/- 64KB) AND walk the master
         * pointer SessionData. Logs every hit with its offset-from-actor. */
        int32_t want = (int32_t)strtol(buf + 8, NULL, 10);
        uintptr_t actor = dmc3_actor();
        HMODULE b = GetModuleHandleW(L"dmc3.exe"); if (!b) b = GetModuleHandleW(NULL);
        uintptr_t base = (uintptr_t)b;
        int hits = 0;
        /* (a) window around the actor struct */
        if (actor) {
            for (uintptr_t a = actor; a < actor + 0x8000; a += 4) {
                int32_t v; if (rd_i32(a, &v) && v == want) {
                    snprintf(m, sizeof(m), "[orbscan] actor+0x%llx (0x%llx) = %d\r\n",
                             (unsigned long long)(a - actor), (unsigned long long)a, v);
                    out_log(m); if (++hits >= 24) break;
                }
            }
        }
        /* (b) master/SessionData pointer chain: base+0xC8F970 +0x1478 +0x10 -> +0x18 +0x10 */
        uintptr_t p1=0,p2=0,sd=0;
        if (rd_ptr(base + 0xC8F970 + 0x1478 + 0x10, &p1) && p1 &&
            rd_ptr(p1 + 0x18 + 0x10, &sd) && sd) {
            for (uintptr_t a = sd; a < sd + 0x2000 && hits < 48; a += 4) {
                int32_t v; if (rd_i32(a, &v) && v == want) {
                    snprintf(m, sizeof(m), "[orbscan] sessiondata+0x%llx (0x%llx) = %d\r\n",
                             (unsigned long long)(a - sd), (unsigned long long)a, v);
                    out_log(m); hits++;
                }
            }
        }
        (void)p2;
        snprintf(m, sizeof(m), "[orbscan] want=%d actor=0x%llx sd=0x%llx hits=%d\r\n",
                 want, (unsigned long long)actor, (unsigned long long)sd, hits);
        out_log(m);
    } else if (strncmp(buf, "chain=", 6) == 0) {
        chain_install(buf + 6);
    } else if (strncmp(buf, "chainoff=", 9) == 0) {
        chain_remove((int)strtol(buf + 9, NULL, 10));
        out_log("[chain] removed\r\n");
    } else if (strncmp(buf, "chainclear", 10) == 0) {
        chains_clear();
        out_log("[chain] cleared all\r\n");
    } else if (strncmp(buf, "dmc3diag", 8) == 0) {
        uintptr_t actor = dmc3_actor();
        float hp = -1, mhp = -1, dt = -1, mdt = -1;
        if (actor) { rd_f32(actor + 0x411C, &hp); rd_f32(actor + 0x40EC, &mhp);
                     rd_f32(actor + 0x3EB8, &dt); rd_f32(actor + 0x3EBC, &mdt); }
        snprintf(m, sizeof(m), "[dmc3diag] actor=0x%llx HP=%.1f/%.1f DT=%.1f/%.1f\r\n",
                 (unsigned long long)actor, hp, mhp, dt, mdt);
        out_log(m);
    } else if (strncmp(buf, "dmc3hp=", 7) == 0) {
        InterlockedExchange(&g_dmc3_hp_on, buf[7] == '1' ? 1 : 0);
        snprintf(m, sizeof(m), "[ctrl] dmc3 HP freeze = %c\r\n", buf[7]);
        out_log(m);
    } else if (strncmp(buf, "dmc3dt=", 7) == 0) {
        InterlockedExchange(&g_dmc3_dt_on, buf[7] == '1' ? 1 : 0);
        snprintf(m, sizeof(m), "[ctrl] dmc3 DT freeze = %c\r\n", buf[7]);
        out_log(m);
    } else if (strncmp(buf, "freezeoff", 9) == 0) {
        InterlockedExchange(&g_freeze_on, 0);
        InterlockedExchange(&g_dmc3_hp_on, 0);
        InterlockedExchange(&g_dmc3_dt_on, 0);
        out_log("[ctrl] freeze off (all)\r\n");
    }
}

/* Resolve the DMC3 live player actor base via the verified chain.
 * Returns 0 if not resolvable (menus / between missions). */
static uintptr_t dmc3_actor(void)
{
    HMODULE b = GetModuleHandleW(L"dmc3.exe");
    if (!b) b = GetModuleHandleW(NULL);
    if (!b) return 0;
    uintptr_t base = (uintptr_t)b;
    uintptr_t pool = 0, actor = 0;
    if (!rd_ptr(base + 0xC90E28, &pool) || !pool) return 0;
    if (!rd_ptr(pool + 0x18, &actor) || !actor) return 0;   /* slot [3] = +0x18 */
    return actor;
}

/* Resolve a chain slot to the final VALUE address (0 if not resolvable now).
 *
 * Semantics (matches the proven dmc3_actor + standard Cheat-Engine chains):
 *   ptr = *(moduleBase + base)               // first dereference
 *   for each off in offsets:  ptr = *(ptr + off)   // deref at each level
 *   valueAddr = ptr + valueOffset            // final value (NOT dereferenced)
 *
 * Example DMC3 HP: base=0xC90E28, offsets=[0x18], valoff=0x411C
 *   pool  = *(modbase + 0xC90E28)
 *   actor = *(pool + 0x18)
 *   hpAddr = actor + 0x411C
 * (The OLD version added the last offset WITHOUT the trailing deref, landing at
 *  pool+0x18+0x411C instead of *(pool+0x18)+0x411C — that's why the freeze had
 *  no effect through the generic path even though dmc3hp worked.)
 */
static uintptr_t chain_resolve(const chain_slot_t *c)
{
    HMODULE b = c->module[0] ? GetModuleHandleA(c->module) : GetModuleHandleW(NULL);
    if (!b) b = GetModuleHandleW(NULL);
    if (!b) return 0;

    uintptr_t ptr = 0;
    if (!rd_ptr((uintptr_t)b + c->base, &ptr) || !ptr) return 0;   /* first deref */
    for (uint32_t i = 0; i < c->n_offs; i++) {
        if (!rd_ptr(ptr + c->offs[i], &ptr) || !ptr) return 0;     /* deref each level */
    }
    return ptr + c->valoff;
}

/* Apply all active chain slots once (called each freeze tick). */
static void chains_apply(void)
{
    chain_lock();
    for (uint32_t i = 0; i < CHAIN_CAP; i++) {
        chain_slot_t *c = &g_chains[i];
        if (!c->active) continue;
        uintptr_t addr = chain_resolve(c);
        if (!addr) continue;
        if (c->use_max) {
            /* write current = max field (max sits at addr - valoff + maxoff) */
            uintptr_t maxaddr = addr - c->valoff + c->maxoff;
            if (c->vtype == 2) { float mx; if (rd_f32(maxaddr, &mx) && mx > 0) wr_f32(addr, mx); }
            else               { int32_t mx; if (rd_i32(maxaddr, &mx) && mx > 0) wr_i32(addr, mx); }
        } else {
            if (c->vtype == 2) { union { float f; LONG l; } u; u.l = c->litbits; wr_f32(addr, u.f); }
            else               { wr_i32(addr, (int32_t)c->litbits); }
        }
    }
    chain_unlock();
}

/* Parse the chain= command and install/replace a slot. */
static void chain_install(char *args)  /* args points just past "chain=" */
{
    /* fields: id|module|basehex|offs|valoffhex|vtype|mode|valbits */
    char *f[8] = {0}; int nf = 0;
    char *p = args;
    f[nf++] = p;
    while (*p && nf < 8) { if (*p == '|') { *p = '\0'; f[nf++] = p + 1; } p++; }
    if (nf < 8) return;

    chain_slot_t c; memset(&c, 0, sizeof(c));
    c.id = (int)strtol(f[0], NULL, 10);
    strncpy(c.module, f[1], sizeof(c.module) - 1);
    c.base = (uintptr_t)strtoull(f[2], NULL, 16);
    /* offsets: comma list of hex (f[3] may be empty) */
    if (f[3][0]) {
        char *o = f[3];
        while (*o && c.n_offs < CHAIN_OFFS_MAX) {
            c.offs[c.n_offs++] = (uintptr_t)strtoull(o, &o, 16);
            if (*o == ',') o++;
        }
    }
    c.valoff = (uintptr_t)strtoull(f[4], NULL, 16);
    c.vtype  = (strcmp(f[5], "f32") == 0) ? 2 : (strcmp(f[5], "u32") == 0) ? 1 : 0;
    c.use_max = (strcmp(f[6], "max") == 0) ? 1 : 0;
    if (c.use_max) c.maxoff  = (uintptr_t)strtoull(f[7], NULL, 16);
    else           c.litbits = (LONG)strtoul(f[7], NULL, 10);  /* decimal value -> bits via wr_i32, or float-bits below */
    /* For f32 lit, the caller passes the float's raw bits as a decimal uint; accept hex 0x too */
    if (!c.use_max && c.vtype == 2) {
        /* allow either decimal-bits or a float literal "20000.0" */
        if (strchr(f[7], '.')) { union { float fl; LONG l; } u; u.fl = (float)atof(f[7]); c.litbits = u.l; }
    }
    c.active = 1;

    chain_lock();
    /* replace slot with same id, else first free */
    int slot = -1;
    for (uint32_t i = 0; i < CHAIN_CAP; i++) if (g_chains[i].active && g_chains[i].id == c.id) { slot = (int)i; break; }
    if (slot < 0) for (uint32_t i = 0; i < CHAIN_CAP; i++) if (!g_chains[i].active) { slot = (int)i; break; }
    if (slot >= 0) g_chains[slot] = c;
    chain_unlock();

    char m[256];
    snprintf(m, sizeof(m), "[chain] install id=%d mod=%s base=0x%llx noff=%u valoff=0x%llx vtype=%d max=%d slot=%d\r\n",
             c.id, c.module, (unsigned long long)c.base, c.n_offs,
             (unsigned long long)c.valoff, c.vtype, c.use_max, slot);
    out_log(m);
}

static void chain_remove(int id)
{
    chain_lock();
    for (uint32_t i = 0; i < CHAIN_CAP; i++)
        if (g_chains[i].active && g_chains[i].id == id) g_chains[i].active = 0;
    chain_unlock();
}

static void chains_clear(void)
{
    chain_lock();
    memset(g_chains, 0, sizeof(g_chains));
    chain_unlock();
}

static DWORD WINAPI cheat_thread(LPVOID arg)
{
    (void)arg;

    /* Resolve game dir from host exe path → control/output files next to it. */
    char exe[MAX_PATH]; exe[0] = '\0';
    GetModuleFileNameA(NULL, exe, sizeof(exe));
    char *slash = strrchr(exe, '\\');
    if (slash) *slash = '\0';
    snprintf(g_ctrl_path, sizeof(g_ctrl_path), "%s\\cheat_ctrl.txt", exe);
    snprintf(g_out_path,  sizeof(g_out_path),  "%s\\cheat_out.txt",  exe);

    if (!g_chain_cs_init) { InitializeCriticalSection(&g_chain_cs); g_chain_cs_init = 1; }

    char m[256];
    snprintf(m, sizeof(m), "[cheat] engine up pid=%lu gamedir=%s\r\n",
             (unsigned long)GetCurrentProcessId(), exe);
    out_log(m);

    int poll_accum = 0;
    for (;;) {
        poll_accum += FREEZE_MS;
        if (poll_accum >= POLL_MS) { poll_ctrl(); poll_accum = 0; }

        if (InterlockedCompareExchange(&g_freeze_on, 0, 0)) {
            LONG bits = InterlockedCompareExchange(&g_freeze_bits, 0, 0);
            if (InterlockedCompareExchange(&g_freeze_is_int, 0, 0)) {
                wr_i32(g_freeze_addr, (int32_t)bits);
            } else {
                union { float f; LONG l; } u; u.l = bits;
                wr_f32(g_freeze_addr, u.f);
            }
        }

        /* DMC3 live HP/DT freeze — re-resolve the actor chain every tick and
         * write current = max (so the bar is full but not glitched out of range). */
        if (InterlockedCompareExchange(&g_dmc3_hp_on, 0, 0) ||
            InterlockedCompareExchange(&g_dmc3_dt_on, 0, 0)) {
            uintptr_t actor = dmc3_actor();
            if (actor) {
                if (InterlockedCompareExchange(&g_dmc3_hp_on, 0, 0)) {
                    float mx; if (rd_f32(actor + 0x40EC, &mx) && mx > 0) wr_f32(actor + 0x411C, mx);
                }
                if (InterlockedCompareExchange(&g_dmc3_dt_on, 0, 0)) {
                    float mx; if (rd_f32(actor + 0x3EBC, &mx) && mx > 0) wr_f32(actor + 0x3EB8, mx);
                }
            }
        }

        /* Generic catalog-driven pointer-chain freezes (the real engine). */
        chains_apply();

        Sleep(FREEZE_MS);
    }
    /* not reached */
}

void cheat_engine_start(void)
{
    HANDLE t = CreateThread(NULL, 0, cheat_thread, NULL, 0, NULL);
    if (t) CloseHandle(t);
}
