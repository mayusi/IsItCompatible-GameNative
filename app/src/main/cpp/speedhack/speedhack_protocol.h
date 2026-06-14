/*
 * speedhack_protocol.h — shared-memory IPC contract between the Android (Kotlin)
 * UI side and libspeedhack.so (LD_PRELOAD'd into the Box64/Wine host process).
 *
 * DESIGN
 * ------
 * The Kotlin side (SpeedHackShm.kt) writes a float multiplier and bumps
 * config_seq; libspeedhack.so checks config_seq on every clock_gettime call
 * (atomic load, no lock) and re-anchors its time-scaling state only when the
 * sequence has changed.  This keeps the fast path to a single atomic load.
 *
 * FILE PATH
 *   <SPEEDHACK_BASE_PATH>/speedhack_shm/speedhack.mem   (0600, dir 0700)
 * where SPEEDHACK_BASE_PATH = context.getFilesDir() on the Android side.
 *
 * MULTIPLIER ENCODING
 *   The float multiplier is stored as its raw IEEE-754 bit pattern in a uint32.
 *   Using an integer word lets the C side read it with a single 32-bit load;
 *   on ARM64 aligned 32-bit loads are naturally atomic at the hardware level,
 *   and we protect consistency with the seq counter anyway.
 *
 * KOTLIN OFFSET TABLE (mirror EXACTLY in SpeedHackShm.kt):
 *   OFF_MAGIC        = 0   (uint32)
 *   OFF_VERSION      = 4   (uint32)
 *   OFF_CONFIG_SEQ   = 8   (uint32, atomic — bumped by Kotlin after writing multiplier)
 *   OFF_MULTIPLIER   = 12  (uint32, IEEE-754 float bits — 1.0f = 0x3F800000)
 *   [16..4095]       = reserved / padding to fill one page
 *
 * CLOCK_REALTIME DECISION
 *   libspeedhack scales CLOCK_MONOTONIC and CLOCK_MONOTONIC_RAW only.
 *   CLOCK_REALTIME (wall-clock) is passed through unchanged.  Rationale:
 *     - Wine's QPC/GetTickCount/timeGetTime are backed by CLOCK_MONOTONIC.
 *     - Scaling CLOCK_REALTIME risks breaking TLS, certificate validation,
 *       network retries, and anything else that compares against the wall clock.
 *     - The libspeedhack / clock_gettime_override references for Wine+GTA IV
 *       confirm monotonic-only scaling is sufficient for game timing.
 *
 * SAFETY
 *   All failures (dlsym, open, mmap) fall through to the real clock_gettime.
 *   When SPEEDHACK_ENABLED != "1", the override is a pure passthrough.
 */
#ifndef SPEEDHACK_PROTOCOL_H
#define SPEEDHACK_PROTOCOL_H

#include <stdint.h>

/* Bump when the wire layout changes; SpeedHackShm.kt checks this. */
#define SPEEDHACK_PROTO_MAGIC   0x53504843u  /* "SPHC" */
#define SPEEDHACK_PROTO_VERSION 1u

/* The mmap'd region is one page (4096 bytes). */
#define SPEEDHACK_SHM_SIZE 4096u

/*
 * Multiplier limits enforced by the Kotlin side before writing.
 * The C side trusts whatever value is in the shm (it won't crash from any
 * float), but these bounds define what the UI will allow.
 */
#define SPEEDHACK_MULTIPLIER_MIN 0.05f   /* 5% speed — near-freeze slow-mo  */
#define SPEEDHACK_MULTIPLIER_MAX 10.0f  /* 10× speed — extreme fast-forward */
#define SPEEDHACK_MULTIPLIER_DEFAULT 1.0f

/*
 * Shared layout — exactly 16 bytes of live fields, rest is reserved.
 *
 * NOTE: the C side uses _Atomic uint32_t for config_seq so the compiler
 * generates proper load-acquire / store-release instructions on ARM64.
 * On the Kotlin side this is a plain little-endian int32 in a MappedByteBuffer
 * with a volatile read/write discipline (MappedByteBuffer.force() after writes).
 *
 * The multiplier field is NOT itself atomic; its consistency is guaranteed by
 * the config_seq ordering rule: Kotlin writes multiplier THEN bumps config_seq
 * (release); the C side loads config_seq (acquire) THEN reads multiplier.
 */
struct speedhack_shm {
    uint32_t magic;          /* off  0 : SPEEDHACK_PROTO_MAGIC, written by C on init */
    uint32_t version;        /* off  4 : SPEEDHACK_PROTO_VERSION */
    uint32_t config_seq;     /* off  8 : Kotlin bumps after writing multiplier */
    uint32_t multiplier_bits;/* off 12 : IEEE-754 float32 bits of the multiplier */
    /* off 16..4095 : reserved, zero-initialised */
};

/*
 * Kotlin offset table (mirror EXACTLY in SpeedHackShm.kt):
 *   OFF_MAGIC         = 0   (int32, read-only from Kotlin)
 *   OFF_VERSION       = 4   (int32, read-only from Kotlin)
 *   OFF_CONFIG_SEQ    = 8   (int32, Kotlin writes then reads to verify)
 *   OFF_MULTIPLIER    = 12  (int32, raw IEEE-754 float bits)
 */

#endif /* SPEEDHACK_PROTOCOL_H */
