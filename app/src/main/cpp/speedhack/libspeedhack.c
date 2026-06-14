/*
 * libspeedhack.c — LD_PRELOAD stub for the GameNative speed-hack feature.
 *
 * CLOCK INTERPOSITION REMOVED — LAUNCH-SAFE VERSION
 * --------------------------------------------------
 * The previous implementation exported clock_gettime and gettimeofday as
 * globally-visible (T) symbols so the dynamic linker would interpose the
 * libc versions.  On Android/Box64, this caused the guest process to HANG
 * inside linker64 at launch: the dynamic linker itself (and early C-runtime
 * init) calls clock_gettime before anything is ready, and the interposed
 * version deadlocked or blocked the linker.  The game showed "Launching
 * game..." forever.
 *
 * FIX: the exported clock_gettime and gettimeofday definitions are gone.
 * This .so no longer provides those symbols at all, so the dynamic linker
 * has nothing to interpose and the launch hang is impossible.
 *
 * The SpeedHackShm protocol structs are kept so the build stays consistent
 * with SpeedHackShm.kt, but they have no effect — the multiplier is never
 * applied to any timing call.
 *
 * The speed-hack feature needs to be reimplemented using a Wine-side hook
 * (e.g. patching ntdll!RtlQueryPerformanceCounter from within the Wine
 * address space) that does not touch linker-level symbol interposition.
 *
 * BUILD REQUIREMENTS
 * ------------------
 * -fvisibility=hidden (no interposed symbols, nothing needs to be exported)
 * Link with -llog (for Android logging).
 * -ldl is no longer required but kept in CMakeLists for future use.
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <android/log.h>

#include "speedhack_protocol.h"

#define LOG_TAG "libspeedhack"
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

/*
 * Constructor — runs when the .so is loaded via LD_PRELOAD.
 * Does nothing except log that the stub is loaded.  No dlsym, no shm,
 * no mutex init, no clock_gettime interposition — nothing that can block
 * or deadlock the dynamic linker.
 */
__attribute__((constructor))
static void speedhack_init(void)
{
    LOGI("libspeedhack loaded (clock interposition DISABLED — "
         "speed hack is being reworked; game launches are unaffected)");
}
