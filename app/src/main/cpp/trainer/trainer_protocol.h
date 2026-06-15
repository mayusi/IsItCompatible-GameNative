/*
 * trainer_protocol.h — shared-memory IPC contract between the Android (Kotlin)
 * UI side and libtrainer.so (LD_PRELOAD'd into the Box64/Wine host process).
 *
 * Design mirrors evshim's mmap+seq pattern, with one key difference: the Android
 * side is pure Kotlin (MappedByteBuffer) and CANNOT issue a FUTEX_WAKE syscall,
 * so the worker must NOT do an indefinite FUTEX_WAIT on cmd_seq — it uses a SHORT
 * TIMED wait (~20ms) and re-checks cmd_seq each iteration, effectively polling.
 * The Android UI is the COMMANDER (writes params, then bumps cmd_seq) and
 * libtrainer is the WORKER (executes in its own address space, writes results,
 * bumps resp_seq). The worker never blocks the game: scans run on a worker thread.
 * The commander snapshots resp_seq BEFORE bumping cmd_seq, then polls resp_seq
 * (~50ms) until it changes — it likewise cannot rely on being FUTEX_WOKEN.
 *
 * The file lives at  <TRAINER_BASE_PATH>/trainer_shm/trainer.mem  (0600, 0700 dir),
 * where TRAINER_BASE_PATH = context.getFilesDir() (same convention as
 * EVSHIM_BASE_PATH). One file per running game session.
 *
 * IMPORTANT: this header is compiled by BOTH the C side (libtrainer.so) and is
 * the source of truth for the Kotlin offsets. If you change the layout, update
 * TrainerShm.kt offsets to match EXACTLY. All multi-byte fields are
 * little-endian (ARM64 + x86 are both LE, so no swapping needed).
 *
 * ORDERING RULE (both sides MUST obey): the commander writes ALL command params
 * (cmd, cmd_vtype, cmd_filter, cmd_addr, cmd_value) BEFORE bumping cmd_seq; the
 * worker acquires cmd_seq (memory_order_acquire) before reading those params.
 * Symmetrically the worker writes all response fields before bumping resp_seq,
 * and the commander reads them only after observing a resp_seq change. cmd_seq /
 * resp_seq are the sole synchronisation fences — the param/response fields are
 * plain (non-atomic) and rely on this happens-before edge.
 */
#ifndef TRAINER_PROTOCOL_H
#define TRAINER_PROTOCOL_H

#include <stdint.h>

/* Bump when the wire layout changes; the Kotlin side checks this.
 * v2: added TCMD_AOB_SCAN (+ reserved TCMD_PATCH/TCMD_RESTORE) and the pattern
 *     param block (cmd_pattern/cmd_mask/cmd_pattern_len/cmd_patch_len) at off 768.
 * v3: added owner_pid/owner_heartbeat at off 576 for single-owner arbitration
 *     (fixes the multi-worker race where every command timed out). */
#define TRAINER_PROTO_VERSION 3u

/* The mmap'd region is one page (4096) — plenty for the control block plus a
 * bounded result window. Results beyond RESULT_CAP are summarised by count. */
#define TRAINER_SHM_SIZE       4096u
#define TRAINER_RESULT_CAP     64u   /* max result addresses surfaced per scan */

/* ---- AOB (Array-Of-Bytes) pattern scanning (one-tap cheats) ----
 * cmd_pattern holds up to TRAINER_PATTERN_CAP signature bytes; cmd_mask holds a
 * per-byte wildcard mask (0xFF = must match this byte, 0x00 = wildcard / "??").
 * cmd_pattern_len gives the active length (<= TRAINER_PATTERN_CAP). For an
 * AOB_SCAN, cmd_addr carries the SIGNED data offset added to each match's base
 * (the pattern's start address) to yield the address surfaced in results[].
 * cmd_patch_len is reserved for TCMD_PATCH (deferred). The pattern block lives in
 * the page tail at OFF_CMD_PATTERN so the header + results[] layout is unchanged. */
#define TRAINER_PATTERN_CAP    256u  /* max signature/patch bytes */

/* ---- value types the scanner understands ---- */
enum trainer_vtype {
    TVT_NONE  = 0,
    TVT_I8    = 1,
    TVT_U8    = 2,
    TVT_I16   = 3,
    TVT_U16   = 4,
    TVT_I32   = 5,
    TVT_U32   = 6,
    TVT_I64   = 7,
    TVT_U64   = 8,
    TVT_F32   = 9,
    TVT_F64   = 10,
};

/* ---- commands the Android UI issues (written to cmd, then cmd_seq bumped) ---- */
enum trainer_cmd {
    TCMD_NOP        = 0,
    TCMD_SCAN_NEW   = 1,  /* fresh scan of all rw regions for cmd_value (cmd_vtype) */
    TCMD_SCAN_NEXT  = 2,  /* refine previous result set using cmd_filter + cmd_value */
    TCMD_READ       = 3,  /* read one address (cmd_addr) -> resp_value */
    TCMD_WRITE      = 4,  /* write cmd_value to cmd_addr once */
    TCMD_FREEZE     = 5,  /* add/replace a frozen address (cmd_addr=cmd_value, cmd_vtype) */
    TCMD_UNFREEZE   = 6,  /* remove a frozen address (cmd_addr); cmd_addr==0 => clear all */
    TCMD_RESET      = 7,  /* drop the current result set + all freezes */
    TCMD_PING       = 8,  /* liveness: worker just bumps resp_seq with TST_READY */
    TCMD_AOB_SCAN   = 9,  /* one-tap: scan r-x + rw regions for cmd_pattern[/cmd_mask],
                           * apply cmd_addr as a SIGNED offset to each hit, surface the
                           * resolved data addresses in results[]. cmd_vtype sets how the
                           * caller will later READ/FREEZE the resolved address. */
    TCMD_PATCH      = 10, /* code-patch: write cmd_patch_len bytes (from cmd_pattern[]) to
                           * cmd_addr via mprotect(rwx)→write→mprotect(restore). Snapshots the
                           * original bytes into the patch table so TCMD_RESTORE can undo it.
                           * Used for NOP-the-instruction cheats (e.g. NOP an HP-drain subss).
                           * resp_status=TST_READY on success, TST_ERROR (resp_error=errno) on
                           * mprotect/verify failure. */
    TCMD_RESTORE    = 11, /* restore bytes saved by a prior PATCH at cmd_addr; cmd_addr==0 =>
                           * restore ALL outstanding patches. resp_status=TST_READY. */
};

/* ---- SCAN_NEXT filter modes (cmd_filter) ---- */
enum trainer_filter {
    TFLT_EXACT      = 0,  /* equals cmd_value */
    TFLT_INCREASED  = 1,  /* value > previous snapshot */
    TFLT_DECREASED  = 2,  /* value < previous snapshot */
    TFLT_CHANGED    = 3,  /* value != previous snapshot */
    TFLT_UNCHANGED  = 4,  /* value == previous snapshot */
};

/* ---- worker status (resp_status) ---- */
enum trainer_status {
    TST_IDLE     = 0,
    TST_READY    = 1,  /* worker alive, last command done OK */
    TST_SCANNING = 2,  /* a scan is in progress (resp_progress 0..100) */
    TST_ERROR    = 3,  /* resp_error holds an errno-ish/code */
    TST_TOO_MANY = 4,  /* match count > RESULT_CAP: results[] holds first CAP, resp_count = total */
    TST_NO_MATCH = 5,  /* AOB_SCAN found zero matches (resp_count == 0) */
};

#define TRAINER_FREEZE_CAP 32u  /* max simultaneously frozen addresses */

/*
 * Shared layout. atomic_uint seq words use C11 atomics on the .so side and are
 * plain int32 little-endian to Kotlin. The Kotlin side must mirror these byte
 * offsets EXACTLY (see the OFF_* table comment below).
 */
struct trainer_shm {
    /* --- header (offset 0) --- */
    uint32_t proto_version;   /* off 0  : worker writes TRAINER_PROTO_VERSION on init */
    uint32_t cmd_seq;         /* off 4  : FUTEX word — UI bumps after writing a command */
    uint32_t resp_seq;        /* off 8  : FUTEX word — worker bumps after writing a response */
    uint32_t cmd;             /* off 12 : enum trainer_cmd */

    /* --- command params (UI writes) --- */
    uint32_t cmd_vtype;       /* off 16 : enum trainer_vtype */
    uint32_t cmd_filter;      /* off 20 : enum trainer_filter (SCAN_NEXT) */
    uint64_t cmd_addr;        /* off 24 : target address (READ/WRITE/FREEZE/UNFREEZE) */
    uint64_t cmd_value;       /* off 32 : raw bits of the value (interpret by cmd_vtype) */

    /* --- response (worker writes) --- */
    uint32_t resp_status;     /* off 40 : enum trainer_status */
    uint32_t resp_error;      /* off 44 : error code when resp_status==TST_ERROR */
    uint32_t resp_progress;   /* off 48 : 0..100 during TST_SCANNING */
    uint32_t resp_count;      /* off 52 : total match count (may exceed RESULT_CAP) */
    uint64_t resp_value;      /* off 56 : raw bits returned by TCMD_READ */

    /* --- bounded result window (worker writes; addresses of current matches) --- */
    uint64_t results[TRAINER_RESULT_CAP];   /* off 64 : first resp_count (capped) match addrs */
                                            /* results[] ends at 64 + 64*8 = 576 */

    /* --- single-owner arbitration (worker writes) ---
     * Multiple libtrainer copies are LD_PRELOAD'd across the Wine/Box64 process
     * tree and ALL try to mmap this one file. Without arbitration they race the
     * cmd_seq/resp_seq handshake and the Android side times out (the real
     * "no cheat works" bug). The FIRST worker to CAS owner_pid 0->getpid() wins
     * and is the SOLE responder; losers munmap and no-op. owner_heartbeat lets a
     * relaunch reclaim a stale owner (dead PID). Carved from the reserved gap so
     * the AOB block at off 768 and every prior offset are UNCHANGED. */
    uint32_t owner_pid;                      /* off 576 : CAS 0->pid; sole responder */
    uint32_t owner_heartbeat;                /* off 580 : monotonic tick from the owner */

    /* --- AOB pattern param block (UI writes; off fixed at OFF_CMD_PATTERN=768) --- */
    uint8_t  _pad_to_pattern[768u - 584u];  /* off 584 : reserved gap (184 bytes) */
    uint32_t cmd_pattern_len;               /* off 768 : active signature length (<= CAP) */
    uint32_t cmd_patch_len;                 /* off 772 : reserved (TCMD_PATCH) */
    uint8_t  cmd_pattern[TRAINER_PATTERN_CAP]; /* off 776 : signature/patch bytes */
    uint8_t  cmd_mask[TRAINER_PATTERN_CAP];    /* off 1032: 0xFF=match, 0x00=wildcard */
                                            /* cmd_mask ends at 1032 + 256 = 1288 */

    /* tail padding fills the page to TRAINER_SHM_SIZE */
};

/*
 * Kotlin offset table (mirror EXACTLY in TrainerShm.kt):
 *   OFF_PROTO_VERSION = 0   (int32)
 *   OFF_CMD_SEQ       = 4   (int32, futex)
 *   OFF_RESP_SEQ      = 8   (int32, futex)
 *   OFF_CMD           = 12  (int32)
 *   OFF_CMD_VTYPE     = 16  (int32)
 *   OFF_CMD_FILTER    = 20  (int32)
 *   OFF_CMD_ADDR      = 24  (int64)
 *   OFF_CMD_VALUE     = 32  (int64)
 *   OFF_RESP_STATUS   = 40  (int32)
 *   OFF_RESP_ERROR    = 44  (int32)
 *   OFF_RESP_PROGRESS = 48  (int32)
 *   OFF_RESP_COUNT    = 52  (int32)
 *   OFF_RESP_VALUE    = 56  (int64)
 *   OFF_RESULTS       = 64  (int64[TRAINER_RESULT_CAP])  ; ends at 576
 *   OFF_OWNER_PID       = 576  (int32)  ; CAS 0->pid arbitration
 *   OFF_OWNER_HEARTBEAT = 580  (int32)
 *   OFF_CMD_PATTERN_LEN = 768  (int32)
 *   OFF_CMD_PATCH_LEN   = 772  (int32)
 *   OFF_CMD_PATTERN     = 776  (uint8[TRAINER_PATTERN_CAP=256])  ; ends at 1032
 *   OFF_CMD_MASK        = 1032 (uint8[TRAINER_PATTERN_CAP=256])  ; ends at 1288
 *
 * NOTE: cmd_addr (off 24) doubles as the SIGNED data offset for TCMD_AOB_SCAN.
 */

#endif /* TRAINER_PROTOCOL_H */
