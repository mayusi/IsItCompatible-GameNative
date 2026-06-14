/*
 * macro_protocol.h — shared-memory IPC contract for the Turbo-Fire + Input-Macro
 * feature between the Android UI (Kotlin / MacroShm.kt) and libevshim.so (evshim.c).
 *
 * ACTIVATION GATE
 *   evshim reads MACRO_ENABLED from the environment at constructor time.
 *   When MACRO_ENABLED != "1" the entire macro subsystem is a COMPLETE NO-OP:
 *   no file is created, no mmap is performed, no transform is applied.
 *   This is critical — evshim.so is load-bearing for ALL controller input, so
 *   any code path new to this feature must be unreachable unless explicitly opted in.
 *
 * FILE PATH
 *   <MACRO_BASE_PATH>/macro_shm/macro.mem   (directory 0700, file 0600)
 *   where MACRO_BASE_PATH = context.getFilesDir().getAbsolutePath() (same convention
 *   as EVSHIM_BASE_PATH and TRAINER_BASE_PATH).
 *
 * ACCESS PATTERN
 *   The Android UI is the WRITER (sets config, then bumps config_seq so the shim
 *   re-reads). evshim is the READER (reads config lazily on config_seq change).
 *   No lock is needed: the shim reads config fields only in the vjoy_updater thread
 *   once per tick; the UI writes infrequently and config_seq is the fence.
 *
 *   config_seq is a plain uint32 (not a futex target) because the UI cannot
 *   FUTEX_WAKE from Kotlin, matching the trainer_protocol.h design decision.
 *   The shim samples it every loop tick (~8-16ms) — polling is fine here.
 *
 * MEMORY LAYOUT (all fields are little-endian; ARM64 + x86 are both LE)
 *   The whole block is < 4096 bytes (one page). The Kotlin offset table below
 *   (OFF_*) must be mirrored EXACTLY in MacroShm.kt.
 *
 * BUTTON INDEX MAPPING (btn[0..14] — SDL_CONTROLLER_BUTTON_* order, Xbox 360 layout)
 *   Index  SDL name                      Xbox 360 label
 *   -----  ----------------------------  ---------------
 *     0    SDL_CONTROLLER_BUTTON_A       A
 *     1    SDL_CONTROLLER_BUTTON_B       B
 *     2    SDL_CONTROLLER_BUTTON_X       X
 *     3    SDL_CONTROLLER_BUTTON_Y       Y
 *     4    SDL_CONTROLLER_BUTTON_BACK    Back (Select)
 *     5    SDL_CONTROLLER_BUTTON_GUIDE   Guide (Xbox logo)
 *     6    SDL_CONTROLLER_BUTTON_START   Start
 *     7    SDL_CONTROLLER_BUTTON_LEFTSTICK   LS / L3
 *     8    SDL_CONTROLLER_BUTTON_RIGHTSTICK  RS / R3
 *     9    SDL_CONTROLLER_BUTTON_LEFTSHOULDER  LB
 *    10    SDL_CONTROLLER_BUTTON_RIGHTSHOULDER RB
 *    11    SDL_CONTROLLER_BUTTON_DPAD_UP     DPad Up
 *    12    SDL_CONTROLLER_BUTTON_DPAD_DOWN   DPad Down
 *    13    SDL_CONTROLLER_BUTTON_DPAD_LEFT   DPad Left
 *    14    SDL_CONTROLLER_BUTTON_DPAD_RIGHT  DPad Right
 */
#ifndef MACRO_PROTOCOL_H
#define MACRO_PROTOCOL_H

#include <stdint.h>

/* Bump this when the wire layout changes; Kotlin side checks it. */
#define MACRO_PROTO_VERSION  1u

/* Page-bounded region (one mmap page = 4096 bytes). */
#define MACRO_SHM_SIZE       4096u

/* Controller button count (matches evshim's btn[15]). */
#define MACRO_BTN_COUNT      15u

/* Maximum number of macro slots. */
#define MACRO_SLOT_COUNT     4u

/* Maximum steps per macro slot. */
#define MACRO_MAX_STEPS      16u

/* -------------------------------------------------------------------------
 * Turbo configuration
 *
 * turbo_mask   — bitmask of buttons that have turbo enabled.
 *                Bit N set = btn[N] has turbo. (uint16_t: bits 0-14 used)
 * turbo_rate_hz — shared fire rate in Hz for all turboed buttons (1..120).
 *                 The shim uses: half_period_ticks = TICK_HZ / (2 * turbo_rate_hz)
 *                 to toggle the button on/off at the requested frequency.
 *                 Clamped to [1, 120] by the shim.
 * ------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------
 * Macro step
 *
 * btn_mask    — bitmask of buttons to assert in this step (bit N = btn[N]).
 *               Bits 0-14 only. The shim ORs this over the base snapshot's
 *               btn[] array, so non-macro buttons still pass through.
 *               Set btn_mask = 0 for a "release all macro buttons" delay step.
 * duration_ms — how long (milliseconds) to hold this step before advancing.
 *               0 is treated as 1ms (minimum). Capped to 5000ms by the shim.
 * ------------------------------------------------------------------------- */
struct macro_step {
    uint16_t btn_mask;       /* buttons to assert (bitmask over btn[0..14]) */
    uint16_t duration_ms;    /* hold time in milliseconds */
};

/* -------------------------------------------------------------------------
 * Macro slot
 *
 * trigger_btn — button index (0-14) that starts this macro when it transitions
 *               from released to pressed. 0xFF = slot disabled.
 * step_count  — number of valid steps in steps[] (0 = slot disabled).
 * steps[]     — the sequence to play (capped at MACRO_MAX_STEPS).
 *
 * Active playback consumes the trigger button: the trigger is suppressed from
 * normal output while the macro is running (so holding L3 doesn't move the
 * stick cursor while the macro fires). After the last step, the trigger button
 * resumes normal pass-through.
 * ------------------------------------------------------------------------- */
struct macro_slot {
    uint8_t  trigger_btn;                    /* 0-14: trigger button index; 0xFF = disabled */
    uint8_t  step_count;                     /* 0 = disabled; 1..MACRO_MAX_STEPS = active */
    uint8_t  _pad[2];                        /* align struct to 4 bytes */
    struct macro_step steps[MACRO_MAX_STEPS]; /* sequence of steps */
};
/* sizeof(macro_slot) = 1+1+2 + 16*(2+2) = 4 + 64 = 68 bytes */

/* -------------------------------------------------------------------------
 * Top-level shared control block
 *
 * Layout (offsets listed for Kotlin OFF_* table):
 *
 *  off   size  field
 *  ----  ----  -----
 *     0     4  magic           (uint32, = MACRO_SHM_MAGIC)
 *     4     4  proto_version   (uint32, shim writes MACRO_PROTO_VERSION at open)
 *     8     4  config_seq      (uint32, UI increments after each config write)
 *    12     2  turbo_mask      (uint16, bitmask of turbo-enabled buttons)
 *    14     1  turbo_rate_hz   (uint8,  shared rate, 1..120 Hz)
 *    15     1  _pad            (uint8,  reserved/pad)
 *    16    68  macro_slot[0]
 *    84    68  macro_slot[1]
 *   152    68  macro_slot[2]
 *   220    68  macro_slot[3]
 *   288  3808  (remaining page bytes unused / reserved)
 *
 * Total used: 288 bytes (well within the 4096-byte page limit).
 * ------------------------------------------------------------------------- */

#define MACRO_SHM_MAGIC  0x4D43524Fu  /* "MCRO" */

struct macro_shm {
    uint32_t magic;                             /* off   0: MACRO_SHM_MAGIC sentinel */
    uint32_t proto_version;                     /* off   4: MACRO_PROTO_VERSION */
    uint32_t config_seq;                        /* off   8: UI bumps; shim re-reads on change */
    uint16_t turbo_mask;                        /* off  12: turbo-enabled button bitmask */
    uint8_t  turbo_rate_hz;                     /* off  14: shared turbo rate, 1-120 Hz */
    uint8_t  _pad;                              /* off  15: reserved */
    struct macro_slot slots[MACRO_SLOT_COUNT];  /* off  16: macro slots [0..3] */
    /* off 288..4095: zeroed / reserved */
};

_Static_assert(sizeof(struct macro_shm) <= MACRO_SHM_SIZE, "macro_shm exceeds page size");

/*
 * Kotlin offset table — mirror EXACTLY in MacroShm.kt:
 *
 *   OFF_MAGIC           =   0   (int32 / uint32, expect MACRO_SHM_MAGIC = 0x4D43524F)
 *   OFF_PROTO_VERSION   =   4   (int32 / uint32)
 *   OFF_CONFIG_SEQ      =   8   (int32 / uint32; UI writes this LAST after config)
 *   OFF_TURBO_MASK      =  12   (int16 / uint16)
 *   OFF_TURBO_RATE_HZ   =  14   (int8  / uint8 )
 *   -- per-slot base = 16 + slot * 68 --
 *   OFF_SLOT_TRIGGER    =   0   (int8  / uint8 ; relative to slot base)
 *   OFF_SLOT_STEP_COUNT =   1   (int8  / uint8 ; relative to slot base)
 *   -- per-step base within slot = 4 + step * 4 --
 *   OFF_STEP_BTN_MASK   =   0   (int16 / uint16; relative to step base within slot)
 *   OFF_STEP_DURATION   =   2   (int16 / uint16; relative to step base within slot)
 *
 * Constants:
 *   MACRO_SHM_MAGIC     = 0x4D43524F   ("MCRO")
 *   MACRO_PROTO_VERSION = 1
 *   MACRO_SHM_SIZE      = 4096
 *   MACRO_BTN_COUNT     = 15
 *   MACRO_SLOT_COUNT    = 4
 *   MACRO_MAX_STEPS     = 16
 *   MACRO_SLOT_SIZE     = 68           (sizeof macro_slot)
 *   MACRO_TRIGGER_DISABLED = 0xFF
 */

#endif /* MACRO_PROTOCOL_H */
