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
#include "crt_msvcrt_shim.h"  /* route libc onto Wine-builtin msvcrt.dll (no UCRT apisets / _CRT_INIT) */

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

/* DETERMINISTIC BACKSTOP channel (the reliable channel the Kotlin side uses).
 *
 * The next-to-exe paths above only work when the Android side knows the exe's
 * subfolder (e.g. ".../Game/release/"). It usually does NOT, because most games
 * launch their exe from a subdir (bin/x64/release/Binaries) while Kotlin only
 * holds the install ROOT — so the two sides never meet and every scan times out.
 *
 * To make BOTH sides agree WITHOUT Kotlin guessing the exe subdir, we ALSO mirror
 * the whole control/output interaction into a fixed Wine path: C:\ProxyCheat\.
 * That maps to a STABLE Android path the Kotlin side can always compute from the
 * container alone:
 *     Wine    C:\ProxyCheat\cheat_ctrl.txt
 *     Android <container.rootDir>/.wine/drive_c/ProxyCheat/cheat_ctrl.txt
 * No exe-subdir knowledge required on either side.
 *
 * poll_ctrl reads BOTH the next-to-exe ctrl file AND the backstop ctrl file
 * (de-duped globally by content), and out_log/scan summaries append to BOTH out
 * files. The next-to-exe path is kept so DMC3-style root-exe games keep working
 * exactly as before; the backstop is what makes subfolder-exe games work. */
#define BACKSTOP_DIR  "C:\\ProxyCheat"
static char g_ctrl_path2[MAX_PATH];
static char g_out_path2[MAX_PATH];

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
 *   freezeabs=<hexaddr>=<val>=<slot> : DIY multi-slot freeze — stores an
 *                     already-resolved absolute-address freeze into a chain slot
 *                     (id = DIY_SLOT_BASE+slot) so every selected address freezes.
 * =========================================================================== */
#define CHAIN_CAP        64u
#define CHAIN_OFFS_MAX   8u
/* DIY absolute-address freezes are routed through the SAME chain table using a
 * reserved id range so the existing chain_lock/chains_apply/chains_clear multi-slot
 * machinery freezes every selected address each tick (the old single g_freeze_addr
 * only held the last of N). DIY_SLOT_BASE+index keeps them clear of catalog ids
 * (which derive from a string hash masked to [0,0x7FFF]). */
#define DIY_SLOT_BASE    0x10000

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
    int       is_abs;       /* 1 => already-resolved: write litbits to abs_addr each tick */
    uintptr_t abs_addr;     /* absolute target address when is_abs */
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

/* Append msg to a single out file (best-effort; missing path is a no-op). */
static void out_log_to(const char *path, const char *msg)
{
    if (!path || path[0] == '\0') return;
    HANDLE h = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ,
                           NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return;
    SetFilePointer(h, 0, NULL, FILE_END);
    DWORD w = 0; WriteFile(h, msg, (DWORD)strlen(msg), &w, NULL);
    CloseHandle(h);
}

/* Append to BOTH the next-to-exe out file AND the fixed backstop out file, so the
 * Android side sees every ack/scan-summary on whichever channel it can reach
 * (the backstop is the reliable one when the exe lives in a subfolder). */
static void out_log(const char *msg)
{
    out_log_to(g_out_path,  msg);
    out_log_to(g_out_path2, msg);
}

/* ===========================================================================
 * SPEED HACK — in-process IAT time hook (runs INSIDE the game).
 *
 * The OLD LD_PRELOAD libspeedhack.so approach deadlocked the Box64/Android
 * dynamic linker at launch (the linker calls clock_gettime before init), so it
 * was deleted. This is the correct, proven approach: hook the Windows timing
 * functions the game already imports, from INSIDE the already-loaded dinput8.dll.
 *
 * WHAT WE HOOK — the host EXE's Import Address Table (IAT) entries for:
 *     QueryPerformanceCounter   (kernel32)  — the modern high-res timer
 *     GetTickCount64            (kernel32)
 *     GetTickCount              (kernel32)
 *     timeGetTime               (winmm)
 * We walk GetModuleHandle(NULL)'s PE import descriptors, find those thunks by
 * name, VirtualProtect the IAT page writable, and overwrite the function pointer
 * with our wrapper. The game then calls OUR function for every timing read.
 *
 * TIME SCALING (monotonic anchor) — when the multiplier last changed we capture
 * an anchor pair (base_real, base_virtual). Each call returns:
 *     virtual = base_virtual + (real_now - base_real) * multiplier
 * Because base_virtual carries forward the already-elapsed scaled time, changing
 * the multiplier never makes the clock jump backwards — scaled time stays
 * MONOTONIC across multiplier changes. QPC is scaled in its own counter units;
 * the tick timers are scaled in milliseconds.
 *
 * DEFAULT-LAUNCH SAFETY (the paramount constraint) — the hook is GATED behind
 * the first time speed != 1.0 is requested. A default launch (speed never
 * touched, or set to exactly 1.0) installs NOTHING: the IAT is byte-for-byte
 * unchanged and game startup cannot be affected. Once installed at multiplier
 * 1.0 the wrappers are still an exact pass-through (virtual == real).
 *
 * DEFENSIVE — if the host IAT imports ZERO of these timers (some DX12/UE/Unity
 * titles resolve them dynamically via GetProcAddress) we log one line and
 * silently no-op. We never crash or block the game. No GetProcAddress/loaded-DLL
 * fallback this pass (out of scope).
 * =========================================================================== */

#define SPEED_MIN  0.1f
#define SPEED_MAX  10.0f

/* Real (unhooked) function pointers, captured from the IAT slot we overwrite so
 * the wrappers always call the genuine implementation. */
typedef BOOL    (WINAPI *pfnQPC)(LARGE_INTEGER *);
typedef ULONGLONG (WINAPI *pfnGTC64)(void);
typedef DWORD   (WINAPI *pfnGTC)(void);
typedef DWORD   (WINAPI *pfnTGT)(void);

static pfnQPC   g_real_qpc   = NULL;
static pfnGTC64 g_real_gtc64 = NULL;
static pfnGTC   g_real_gtc   = NULL;
static pfnTGT   g_real_tgt   = NULL;

/* Multiplier + per-clock monotonic anchors. The poll thread updates these under
 * g_speed_cs; the wrappers (called from the game's own threads) read them under
 * the same lock. clock_gettime-style anchoring keeps each clock independent. */
static CRITICAL_SECTION g_speed_cs;
static int     g_speed_cs_init = 0;
static volatile LONG g_speed_installed = 0;   /* hook installed? (gate) */
static float   g_speed_mult = 1.0f;

/* QPC anchors are in raw counter units; tick anchors are in milliseconds. */
static LONGLONG   g_qpc_base_real = 0, g_qpc_base_virt = 0;
static ULONGLONG  g_t64_base_real = 0, g_t64_base_virt = 0;
static DWORD      g_tgt_base_real = 0, g_tgt_base_virt = 0;   /* timeGetTime */
static DWORD      g_gtc_base_real = 0, g_gtc_base_virt = 0;   /* GetTickCount */

static void speed_lock(void)   { if (g_speed_cs_init) EnterCriticalSection(&g_speed_cs); }
static void speed_unlock(void) { if (g_speed_cs_init) LeaveCriticalSection(&g_speed_cs); }

/* ---- the wrappers the IAT will point at ---- */
static BOOL WINAPI hook_QPC(LARGE_INTEGER *out)
{
    if (!g_real_qpc) return FALSE;
    LARGE_INTEGER real; real.QuadPart = 0;
    BOOL ok = g_real_qpc(&real);
    if (!ok) return ok;
    speed_lock();
    LONGLONG virt = g_qpc_base_virt + (LONGLONG)((double)(real.QuadPart - g_qpc_base_real) * (double)g_speed_mult);
    speed_unlock();
    if (out) out->QuadPart = virt;
    return ok;
}

static ULONGLONG WINAPI hook_GTC64(void)
{
    if (!g_real_gtc64) return 0;
    ULONGLONG real = g_real_gtc64();
    speed_lock();
    ULONGLONG virt = g_t64_base_virt + (ULONGLONG)((double)(real - g_t64_base_real) * (double)g_speed_mult);
    speed_unlock();
    return virt;
}

static DWORD WINAPI hook_GTC(void)
{
    if (!g_real_gtc) return 0;
    DWORD real = g_real_gtc();
    speed_lock();
    /* DWORD subtraction wraps correctly modulo 2^32, matching GetTickCount semantics. */
    DWORD virt = g_gtc_base_virt + (DWORD)((double)(real - g_gtc_base_real) * (double)g_speed_mult);
    speed_unlock();
    return virt;
}

static DWORD WINAPI hook_TGT(void)
{
    if (!g_real_tgt) return 0;
    DWORD real = g_real_tgt();
    speed_lock();
    DWORD virt = g_tgt_base_virt + (DWORD)((double)(real - g_tgt_base_real) * (double)g_speed_mult);
    speed_unlock();
    return virt;
}

/* Re-anchor every clock to "now" so that virtual time is continuous when the
 * multiplier changes (no backward jump). Must be called with g_speed_cs held. */
static void speed_reanchor_locked(void)
{
    if (g_real_qpc)   { LARGE_INTEGER li; li.QuadPart = 0; if (g_real_qpc(&li)) {
                          g_qpc_base_virt += (LONGLONG)((double)(li.QuadPart - g_qpc_base_real) * (double)g_speed_mult);
                          g_qpc_base_real  = li.QuadPart; } }
    if (g_real_gtc64) { ULONGLONG r = g_real_gtc64();
                          g_t64_base_virt += (ULONGLONG)((double)(r - g_t64_base_real) * (double)g_speed_mult);
                          g_t64_base_real  = r; }
    if (g_real_gtc)   { DWORD r = g_real_gtc();
                          g_gtc_base_virt += (DWORD)((double)(r - g_gtc_base_real) * (double)g_speed_mult);
                          g_gtc_base_real  = r; }
    if (g_real_tgt)   { DWORD r = g_real_tgt();
                          g_tgt_base_virt += (DWORD)((double)(r - g_tgt_base_real) * (double)g_speed_mult);
                          g_tgt_base_real  = r; }
}

/* Patch one IAT slot: if *slot currently equals one of the timer functions we
 * care about, save the real pointer and overwrite the slot with our wrapper.
 * Returns 1 if this slot was a hooked timer. Matching is BY NAME (passed in). */
static int speed_patch_slot(const char *impname, void **slot)
{
    void *wrapper = NULL;
    if      (strcmp(impname, "QueryPerformanceCounter") == 0) { g_real_qpc   = (pfnQPC)*slot;   wrapper = (void*)hook_QPC; }
    else if (strcmp(impname, "GetTickCount64")          == 0) { g_real_gtc64 = (pfnGTC64)*slot; wrapper = (void*)hook_GTC64; }
    else if (strcmp(impname, "GetTickCount")            == 0) { g_real_gtc   = (pfnGTC)*slot;   wrapper = (void*)hook_GTC; }
    else if (strcmp(impname, "timeGetTime")             == 0) { g_real_tgt   = (pfnTGT)*slot;   wrapper = (void*)hook_TGT; }
    else return 0;

    DWORD oldp = 0;
    if (VirtualProtect(slot, sizeof(void*), PAGE_READWRITE, &oldp)) {
        *slot = wrapper;
        VirtualProtect(slot, sizeof(void*), oldp, &oldp);
    }
    return 1;
}

/* Walk the host EXE's PE import table and hook every matching timer thunk.
 * Returns the number of slots hooked (0 => no timer imports found). */
static int speed_install_hooks(void)
{
    HMODULE base = GetModuleHandleW(NULL);
    if (!base) return 0;
    BYTE *image = (BYTE *)base;

    IMAGE_DOS_HEADER *dos = (IMAGE_DOS_HEADER *)image;
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return 0;
    IMAGE_NT_HEADERS *nt = (IMAGE_NT_HEADERS *)(image + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return 0;

    IMAGE_DATA_DIRECTORY imp = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT];
    if (imp.VirtualAddress == 0 || imp.Size == 0) return 0;

    IMAGE_IMPORT_DESCRIPTOR *desc = (IMAGE_IMPORT_DESCRIPTOR *)(image + imp.VirtualAddress);
    int hooked = 0;
    for (; desc->Name != 0; desc++) {
        /* OriginalFirstThunk holds the name table (by-name vs by-ordinal flags);
         * FirstThunk is the IAT we patch. Walk them in lockstep. */
        IMAGE_THUNK_DATA *oft = desc->OriginalFirstThunk
            ? (IMAGE_THUNK_DATA *)(image + desc->OriginalFirstThunk)
            : (IMAGE_THUNK_DATA *)(image + desc->FirstThunk);
        IMAGE_THUNK_DATA *ft = (IMAGE_THUNK_DATA *)(image + desc->FirstThunk);
        for (; oft->u1.AddressOfData != 0; oft++, ft++) {
            if (oft->u1.Ordinal & IMAGE_ORDINAL_FLAG) continue;   /* by-ordinal: no name to match */
            IMAGE_IMPORT_BY_NAME *ibn = (IMAGE_IMPORT_BY_NAME *)(image + oft->u1.AddressOfData);
            hooked += speed_patch_slot((const char *)ibn->Name, (void **)&ft->u1.Function);
        }
    }
    return hooked;
}

/* Apply a requested multiplier. Installs the hook ON FIRST non-1.0 request only
 * (the gate); thereafter just re-anchors so the change is monotonic. Safe to call
 * with mult == 1.0 before any install — that is a complete no-op (the paramount
 * default-launch safety guarantee). */
static void speed_set(float mult)
{
    if (mult < SPEED_MIN) mult = SPEED_MIN;
    if (mult > SPEED_MAX) mult = SPEED_MAX;

    /* GATE: never touch the game's IAT until the user actually asks for a
     * non-default speed. A 1.0 request before install stays a pure no-op. */
    if (!InterlockedCompareExchange(&g_speed_installed, 0, 0)) {
        if (mult == 1.0f) {
            out_log("[speed] 1.0 requested before install — no-op (default-safe)\r\n");
            return;
        }
        int n = speed_install_hooks();
        char m[160];
        if (n == 0) {
            out_log("[speed] no QPC imports found, hook inactive\r\n");
            /* Mark installed so we don't re-walk the IAT every change; the
             * wrappers were never written, so this remains a no-op. */
            InterlockedExchange(&g_speed_installed, 1);
            return;
        }
        snprintf(m, sizeof(m), "[speed] hook installed (%d timer import(s) patched)\r\n", n);
        out_log(m);
        InterlockedExchange(&g_speed_installed, 1);
    }

    speed_lock();
    /* Re-anchor at the OLD multiplier so already-elapsed virtual time carries
     * forward, THEN switch to the new multiplier. */
    speed_reanchor_locked();
    g_speed_mult = mult;
    speed_unlock();

    char m[96];
    snprintf(m, sizeof(m), "[speed] multiplier = %.3f\r\n", mult);
    out_log(m);
}

/* ---- safe accessors ----
 * MinGW has no SEH, so the old IsBadReadPtr/IsBadWritePtr probes FAULT (instead of
 * returning a safe non-zero) when the page is being torn down — which crashed the
 * game during mission-transition reallocs. We replace them with a VirtualQuery range
 * check that mirrors the writable-region filter already used inside do_scan: require
 * MEM_COMMIT, require a readable/writable protection, and exclude PAGE_GUARD /
 * PAGE_NOACCESS. VirtualQuery never faults, so this is SEH-free-safe. */
static int addr_ok(uintptr_t addr, size_t n)
{
    if (addr < 0x10000) return 0;
    uintptr_t a   = addr;
    uintptr_t end = addr + n;          /* one past the last byte we will touch */
    while (a < end) {
        MEMORY_BASIC_INFORMATION mbi;
        if (VirtualQuery((LPCVOID)a, &mbi, sizeof(mbi)) != sizeof(mbi)) return 0;
        /* Mirror do_scan's region filter: committed, readable/writable, not guard. */
        int ok = (mbi.State == MEM_COMMIT) &&
                 (mbi.Protect & (PAGE_READONLY | PAGE_READWRITE | PAGE_WRITECOPY |
                                 PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE |
                                 PAGE_EXECUTE_WRITECOPY)) &&
                 !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS));
        if (!ok) return 0;
        uintptr_t region_end = (uintptr_t)mbi.BaseAddress + mbi.RegionSize;
        if (region_end <= a) return 0;   /* paranoia: no forward progress */
        a = region_end;
    }
    return 1;
}
static int rd_f32(uintptr_t a, float *o)
{
    if (!addr_ok(a, 4)) return 0;
    *o = *(volatile float *)a; return 1;
}
static int wr_f32(uintptr_t a, float v)
{
    if (!addr_ok(a, 4)) return 0;
    *(volatile float *)a = v; return 1;
}
static int rd_i32(uintptr_t a, int32_t *o)
{
    if (!addr_ok(a, 4)) return 0;
    *o = *(volatile int32_t *)a; return 1;
}
static int rd_ptr(uintptr_t a, uintptr_t *o)
{
    if (!addr_ok(a, sizeof(uintptr_t))) return 0;
    *o = *(volatile uintptr_t *)a; return 1;
}
static int wr_i32(uintptr_t a, int32_t v)
{
    if (!addr_ok(a, 4)) return 0;
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

/* ===========================================================================
 * AOB (array-of-bytes / signature) SCAN — the engine gap CT tables need.
 *
 * Cheat-Engine .CT "AOB"/AA-script cheats locate an address by scanning the
 * process for a byte signature with wildcards (e.g. "48 8B 05 ?? ?? ?? ?? 89"),
 * then applying a signed offset. We expose this as:
 *
 *   aobscan=<id>|<pattern-hex-with-??-wildcards>|<offset>
 *
 * <pattern> is space-separated hex bytes; "??" / "?" / "*" is a wildcard byte
 * (any value). <offset> is a signed decimal byte offset added to the match base
 * to produce the resolved address (CE applies the same +offset after the AA
 * "aobscanmodule" finds the signature).
 *
 * REGION WALK — this REUSES the exact VirtualQuery region-enumeration + filter
 * that do_scan/do_snapshot use (committed + readable + non-guard, skip >256MB
 * buffers), so we don't introduce a second, divergent enumerator. The only
 * difference vs do_scan is the inner test (byte-pattern + mask match instead of
 * a 4-byte == compare) and that we read EXECUTABLE pages too (code signatures
 * live in PAGE_EXECUTE_READ regions), which addr_ok already permits.
 *
 * SAFETY — bounded exactly like do_scan: cap total scanned regions, skip giant
 * (>256MB) regions, and never read past a region end. VirtualQuery told us the
 * region is committed+readable so a plain pointer compare loop within it is safe
 * (no per-byte fault probe needed — same reasoning as do_scan's memcpy loop).
 *
 * RESULT — written to cheat_out.txt (BOTH channels via out_log) as a line the
 * Kotlin side polls, mirroring the [scan]/[next] round-trip:
 *     [aob] id=<id> found=0x<addr>     (first match, +offset applied)
 *     [aob] id=<id> notfound           (no match in any scanned region)
 * The id echoes the caller's token so concurrent/parallel aob requests are
 * disambiguated on the read side.
 * =========================================================================== */
#define AOB_PATTERN_MAX 256u   /* max signature length in bytes */

/* Parse a space-separated hex pattern with wildcards into pat[] + mask[].
 * mask byte 0xFF = must-match, 0x00 = wildcard. Returns the byte count, or 0 on
 * a malformed/empty/oversized pattern. */
static uint32_t aob_parse(const char *s, unsigned char *pat, unsigned char *mask)
{
    uint32_t n = 0;
    const char *p = s;
    while (*p && n < AOB_PATTERN_MAX) {
        while (*p == ' ' || *p == '\t') p++;   /* skip whitespace between tokens */
        if (*p == '\0') break;
        /* read one token up to the next whitespace */
        char tok[8]; int t = 0;
        while (*p && *p != ' ' && *p != '\t' && t < (int)sizeof(tok) - 1) tok[t++] = *p++;
        tok[t] = '\0';
        if (t == 0) break;
        if (tok[0] == '?' || tok[0] == '*') {
            pat[n] = 0x00; mask[n] = 0x00;     /* wildcard byte */
        } else {
            /* require exactly two hex digits */
            char *endp = NULL;
            long v = strtol(tok, &endp, 16);
            if (endp == tok || *endp != '\0' || v < 0 || v > 0xFF) return 0;
            pat[n] = (unsigned char)v; mask[n] = 0xFF;
        }
        n++;
    }
    return n;
}

/* Scan committed/readable regions for the first match of (pat,mask), applying the
 * signed offset. Writes the [aob] result line(s). REUSES do_scan's region walk. */
static void do_aobscan(const char *id, const char *pattern, long offset)
{
    unsigned char pat[AOB_PATTERN_MAX], mask[AOB_PATTERN_MAX];
    uint32_t plen = aob_parse(pattern, pat, mask);
    char m[256];
    if (plen == 0) {
        snprintf(m, sizeof(m), "[aob] id=%s notfound (bad pattern)\r\n", id);
        out_log(m);
        return;
    }

    SYSTEM_INFO si; GetSystemInfo(&si);
    uintptr_t addr = (uintptr_t)si.lpMinimumApplicationAddress;
    uintptr_t end  = (uintptr_t)si.lpMaximumApplicationAddress;
    const size_t REGION_MAX = (size_t)256 * 1024 * 1024;   /* same cap as do_scan */
    const uint32_t REGIONS_MAX = 200000u;                  /* bound region count too */
    uint32_t regions = 0;
    uint64_t scanned = 0;
    uintptr_t found = 0;

    MEMORY_BASIC_INFORMATION mbi;
    while (addr < end && !found && regions < REGIONS_MAX) {
        if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi)) break;
        uintptr_t base = (uintptr_t)mbi.BaseAddress;
        size_t    rsz  = mbi.RegionSize;

        /* Mirror do_scan's filter, but ALSO accept readable/executable code pages
         * (signatures usually point at code) — addr_ok permits the same set. */
        int readable = (mbi.State == MEM_COMMIT) &&
                       (mbi.Protect & (PAGE_READONLY | PAGE_READWRITE | PAGE_WRITECOPY |
                                       PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE |
                                       PAGE_EXECUTE_WRITECOPY)) &&
                       !(mbi.Protect & (PAGE_GUARD | PAGE_NOACCESS));
        if (readable && rsz >= plen && rsz <= REGION_MAX) {
            regions++;
            scanned += rsz;
            const unsigned char *q = (const unsigned char *)base;
            size_t last = rsz - plen;            /* last start offset that still fits */
            for (size_t off = 0; off <= last; off++) {
                uint32_t k = 0;
                for (; k < plen; k++) {
                    if (mask[k] && q[off + k] != pat[k]) break;
                }
                if (k == plen) {                 /* full match */
                    found = base + off;
                    break;
                }
            }
        }
        addr = base + rsz;
        if (rsz == 0) break;
    }

    if (found) {
        uintptr_t resolved = (uintptr_t)((intptr_t)found + offset);
        snprintf(m, sizeof(m), "[aob] id=%s found=0x%llx (match=0x%llx off=%ld regions=%u scannedMB=%llu)\r\n",
                 id, (unsigned long long)resolved, (unsigned long long)found, offset,
                 regions, (unsigned long long)(scanned >> 20));
        out_log(m);
    } else {
        snprintf(m, sizeof(m), "[aob] id=%s notfound (regions=%u scannedMB=%llu)\r\n",
                 id, regions, (unsigned long long)(scanned >> 20));
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

/* Execute one command line (already newline-trimmed). Factored out of poll_ctrl
 * so it can be driven from BOTH the next-to-exe ctrl file and the backstop ctrl
 * file by the same dispatcher. */
static void exec_cmd(char *buf)
{
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
    } else if (strncmp(buf, "aobscan=", 8) == 0) {
        /* aobscan=<id>|<pattern-hex-with-??>|<offset>
         * Find the first match of <pattern> across committed/readable memory
         * (reusing do_scan's region walk), apply the signed byte <offset>, and
         * write "[aob] id=<id> found=0x<addr>" / "[aob] id=<id> notfound" to the
         * out file so the Kotlin side can resolve a CT-table AOB cheat's address. */
        char *p = buf + 8;
        char *bar1 = strchr(p, '|');
        char *bar2 = bar1 ? strchr(bar1 + 1, '|') : NULL;
        if (bar1 && bar2) {
            *bar1 = '\0'; *bar2 = '\0';
            const char *id      = p;             /* caller's id token */
            const char *pattern = bar1 + 1;      /* hex bytes + ?? wildcards */
            long offset         = strtol(bar2 + 1, NULL, 10);  /* signed decimal */
            do_aobscan(id, pattern, offset);
        } else {
            out_log("[aob] id=? notfound (malformed aobscan command)\r\n");
        }
    } else if (strncmp(buf, "chain=", 6) == 0) {
        chain_install(buf + 6);
    } else if (strncmp(buf, "chainoff=", 9) == 0) {
        chain_remove((int)strtol(buf + 9, NULL, 10));
        out_log("[chain] removed\r\n");
    } else if (strncmp(buf, "freezeabs=", 10) == 0) {
        /* freezeabs=<hexaddr>=<val>=<slot> : DIY multi-slot freeze. Stores an
         * absolute-address freeze into a chain slot marked already-resolved, so
         * chains_apply writes <val> to <hexaddr> each tick. <val> is decimal for
         * i32 or a float literal "12.5" for f32 (a '.' selects f32). */
        char *p = buf + 10;
        unsigned long long a = strtoull(p, &p, 16);
        const char *vstr = (*p == '=') ? p + 1 : p;
        chain_slot_t c; memset(&c, 0, sizeof(c));
        if (strchr(vstr, '.')) {
            c.vtype = 2;
            union { float f; LONG l; } u; u.f = (float)atof(vstr); c.litbits = u.l;
        } else {
            c.vtype = 0;
            c.litbits = (LONG)strtol(vstr, NULL, 10);
        }
        /* slot index is the trailing =<slot> field */
        const char *eq = strrchr(vstr, '=');
        int idx = eq ? (int)strtol(eq + 1, NULL, 10) : 0;
        c.id = DIY_SLOT_BASE + idx;
        c.is_abs = 1;
        c.abs_addr = (uintptr_t)a;
        c.active = 1;
        chain_lock();
        int slot = -1;
        for (uint32_t i = 0; i < CHAIN_CAP; i++) if (g_chains[i].active && g_chains[i].id == c.id) { slot = (int)i; break; }
        if (slot < 0) for (uint32_t i = 0; i < CHAIN_CAP; i++) if (!g_chains[i].active) { slot = (int)i; break; }
        if (slot >= 0) g_chains[slot] = c;
        chain_unlock();
        snprintf(m, sizeof(m), "[chain] freezeabs addr=0x%llx vtype=%d slot=%d (id=%d)\r\n",
                 a, c.vtype, slot, c.id);
        out_log(m);
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
    } else if (strncmp(buf, "speed=", 6) == 0) {
        /* speed=<float> : scale the game's clock by <float>. The hook is GATED —
         * it only patches the IAT the first time a non-1.0 value arrives, so a
         * default launch (speed never set, or set to 1.0) leaves the game's
         * startup byte-for-byte unchanged. */
        speed_set((float)atof(buf + 6));
    } else if (strncmp(buf, "freezeoff", 9) == 0) {
        InterlockedExchange(&g_freeze_on, 0);
        InterlockedExchange(&g_dmc3_hp_on, 0);
        InterlockedExchange(&g_dmc3_dt_on, 0);
        out_log("[ctrl] freeze off (all)\r\n");
    }
}

/* Read one ctrl file, applying the GLOBAL content de-dup, and dispatch it.
 * Returns 1 if a NEW command was consumed (so the caller can skip the other
 * file this tick — both channels carry the same command, we want it once). */
static int poll_ctrl_path(const char *path)
{
    if (!path || path[0] == '\0') return 0;
    HANDLE h = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (h == INVALID_HANDLE_VALUE) return 0;

    char buf[256]; DWORD got = 0;
    BOOL ok = ReadFile(h, buf, sizeof(buf) - 1, &got, NULL);
    CloseHandle(h);
    if (!ok || got == 0) return 0;
    buf[got] = '\0';

    /* De-dupe by CONTENT, not size — "scani=43" and "nexti=68" are both 8 bytes,
     * so a size-only check silently dropped the narrow command (real bug).
     * The de-dup key is GLOBAL across both ctrl files, so identical content in
     * the next-to-exe file and the backstop file is executed exactly once. */
    if (strcmp(buf, g_last_cmd) == 0) return 0;   /* unchanged content */
    strncpy(g_last_cmd, buf, sizeof(g_last_cmd) - 1);
    g_last_cmd[sizeof(g_last_cmd) - 1] = '\0';

    /* trim trailing newline */
    char *nl = strpbrk(buf, "\r\n"); if (nl) *nl = '\0';

    exec_cmd(buf);
    return 1;
}

static void poll_ctrl(void)
{
    /* Backstop FIRST: it is the reliable channel the Android side writes to
     * regardless of the exe subdir. If it carried a new command we're done for
     * this tick; otherwise fall back to the next-to-exe file (DMC3-style root
     * exes, or any path the Android side resolved directly). */
    if (poll_ctrl_path(g_ctrl_path2)) return;
    poll_ctrl_path(g_ctrl_path);
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
        /* DIY freezes are already-resolved: write litbits straight to abs_addr. */
        uintptr_t addr = c->is_abs ? c->abs_addr : chain_resolve(c);
        if (!addr) continue;
        /* Gate the write on addr_ok: if the resolved cell is not currently a
         * committed, writable, non-guard page (e.g. mid-realloc on a mission
         * transition) skip this tick — the chain re-resolves next tick. */
        if (!addr_ok(addr, 4)) continue;
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

    /* Resolve the DETERMINISTIC BACKSTOP paths (C:\ProxyCheat\). This is the
     * channel the Android side relies on because it can compute the matching
     * Android path (<container.rootDir>/.wine/drive_c/ProxyCheat/) WITHOUT knowing
     * which subfolder the exe launched from. CreateDirectoryA is harmless if it
     * already exists. If creation fails we leave g_*_path2 empty so out_log_to /
     * poll_ctrl_path treat the backstop as absent and only the next-to-exe path
     * is used (preserving the old DMC3-style behaviour). */
    if (CreateDirectoryA(BACKSTOP_DIR, NULL) || GetLastError() == ERROR_ALREADY_EXISTS) {
        snprintf(g_ctrl_path2, sizeof(g_ctrl_path2), "%s\\cheat_ctrl.txt", BACKSTOP_DIR);
        snprintf(g_out_path2,  sizeof(g_out_path2),  "%s\\cheat_out.txt",  BACKSTOP_DIR);
    } else {
        g_ctrl_path2[0] = '\0';
        g_out_path2[0]  = '\0';
    }

    if (!g_chain_cs_init) { InitializeCriticalSection(&g_chain_cs); g_chain_cs_init = 1; }
    if (!g_speed_cs_init) { InitializeCriticalSection(&g_speed_cs); g_speed_cs_init = 1; }

    /* The "[cheat] engine up" line is the LIVENESS HANDSHAKE the Android side
     * polls for (on the backstop out file) before declaring the engine available.
     * Record both the exe gamedir and the chosen backstop dir so failures are
     * diagnosable from either out file. out_log writes to BOTH out files. */
    char m[256];
    snprintf(m, sizeof(m), "[cheat] engine up pid=%lu gamedir=%s backstop=%s\r\n",
             (unsigned long)GetCurrentProcessId(), exe,
             g_out_path2[0] ? BACKSTOP_DIR : "(none)");
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
