package app.gamenative.cheats

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Host-side control for the UNIVERSAL game speed-hack.
 *
 * HOW IT WORKS
 * ------------
 * GameNative IIC ships a PATCHED Wine unix ntdll.so (built from proton-wine with a small
 * `gn_scale_ticks` change to `dlls/ntdll/unix/sync.c`). That patch scales
 * `monotonic_counter()` — the single counter that QueryPerformanceCounter, GetTickCount(64),
 * the interrupt time, and Wine's timers all derive from — by a live multiplier. Because every
 * frame-pacing time source funnels through that one function, scaling it speeds up or slows
 * down the WHOLE game uniformly, with no per-game patching and no in-Wine code injection
 * (which is dead on arm64ec — the loader maps a DLL but never runs its code).
 *
 * The patched ntdll reads the multiplier from a control FILE whose absolute path is passed to
 * the game process in the `GN_TIME_SCALE_FILE` environment variable (set in
 * BionicProgramLauncherComponent). The file holds a single ASCII float. The ntdll re-reads it
 * at most ~4x/sec and re-anchors its virtual clock on any change so time stays monotonic across
 * speed changes (no backward jumps, no discontinuities).
 *
 * THIS OBJECT is the Android writer for that file. The SpeedSection UI calls [setMultiplier]
 * when the user moves the slider; the next time the ntdll re-reads the file, the game speed
 * follows. [clear]/[setMultiplier] with 1.0 restores normal speed (the ntdll treats 1.0 — and
 * a missing/empty/garbage file — as the near-zero-cost passthrough).
 *
 * SAFETY
 * ------
 * - Writes are atomic (temp file + rename) so the ntdll never reads a half-written value.
 * - The value is clamped to [MIN_SCALE]..[MAX_SCALE], matching the ntdll's own clamp, so a UI
 *   bug can never drive the clock to 0 (which would freeze time) or an absurd multiplier.
 * - All IO is best-effort and swallows IOException: failing to write the file just leaves the
 *   speed unchanged, never crashes the caller.
 */
object SpeedhackControl {

    private const val TAG = "SpeedhackControl"

    /** Subdir under filesDir holding the control file. Pre-created by the app on first launch. */
    private const val SHM_DIR = "speedhack_shm"

    /** The control file name. MUST match GN_TIME_SCALE_FILE built in BionicProgramLauncherComponent. */
    private const val SCALE_FILE = "scale"

    /**
     * Clamp bounds — MUST mirror GN_SCALE_MIN/GN_SCALE_MAX in the ntdll patch
     * (dlls/ntdll/unix/sync.c). 0 is rejected by the ntdll (it would freeze time); we never
     * write below MIN_SCALE so the floor is enforced on both sides.
     */
    const val MIN_SCALE = 0.05f
    const val MAX_SCALE = 16.0f

    /**
     * Absolute path of the control file the ntdll reads. Used by BOTH this writer and the env
     * injector (GN_TIME_SCALE_FILE) so the two always agree. Does NOT create the file.
     */
    fun scaleFile(context: Context): File =
        File(File(context.filesDir, SHM_DIR), SCALE_FILE)

    /**
     * Set the live game-speed multiplier. 1.0 = normal, <1 = slow-mo, >1 = fast-forward.
     * The value is clamped to [MIN_SCALE]..[MAX_SCALE] and written atomically. The running game
     * picks it up within ~250 ms (the ntdll's re-read throttle).
     *
     * @return true if the control file was written, false on IO error.
     */
    fun setMultiplier(context: Context, multiplier: Float): Boolean {
        val clamped = when {
            multiplier.isNaN() -> 1.0f
            multiplier < MIN_SCALE -> MIN_SCALE
            multiplier > MAX_SCALE -> MAX_SCALE
            else -> multiplier
        }
        return writeAtomic(context, clamped.toString())
    }

    /**
     * Restore normal speed by writing 1.0. Equivalent to [setMultiplier] with 1.0 — kept as a
     * named call so reset paths read clearly. (We write "1.0" rather than deleting the file so a
     * mid-game reset is picked up by the ntdll's re-read immediately; a deleted file is also
     * treated as 1.0 but only after the next fopen miss.)
     */
    fun reset(context: Context): Boolean = writeAtomic(context, "1.0")

    /** Atomic write: temp file in the same dir + rename over the target. */
    private fun writeAtomic(context: Context, value: String): Boolean {
        return try {
            val target = scaleFile(context)
            val dir = target.parentFile
            if (dir != null && !dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$SCALE_FILE.tmp")
            tmp.writeText(value, Charsets.US_ASCII)
            // rename is atomic on the same filesystem; if it fails, fall back to a direct write.
            if (!tmp.renameTo(target)) {
                target.writeText(value, Charsets.US_ASCII)
                tmp.delete()
            }
            Log.d(TAG, "speed multiplier -> $value (${target.absolutePath})")
            true
        } catch (e: Exception) {
            Log.w(TAG, "failed to write speed control file: ${e.message}")
            false
        }
    }
}
