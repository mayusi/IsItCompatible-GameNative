package app.gamenative.trainer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Android (Kotlin) side of the speed-hack IPC bridge.
 *
 * Mirrors the layout defined in speedhack_protocol.h EXACTLY.
 * The shared file is mmap'd with READ_WRITE + LITTLE_ENDIAN,
 * following the same pattern as TrainerShm (RandomAccessFile → setLength
 * → getChannel().map).
 *
 * PROTOCOL SUMMARY
 *   1. Kotlin writes [multiplier_bits] (raw IEEE-754 float bits).
 *   2. Kotlin bumps [config_seq] (release barrier via force()).
 *   3. libspeedhack.so reads config_seq (acquire) and re-anchors its
 *      time-scaling state on the next clock_gettime call.
 *
 * THREAD SAFETY
 *   All writes are serialised by the caller (the UI runs on the main thread
 *   and this class is used from a single coroutine scope).  MappedByteBuffer
 *   is not thread-safe, so callers must not share the instance across threads
 *   without external synchronisation.
 *
 * GRACEFUL DEGRADATION
 *   If the mmap fails (libspeedhack.so not loaded, feature disabled, first
 *   launch before the native process starts), [available] is false and
 *   [setMultiplier] is a silent no-op.  No exceptions are ever thrown.
 */
class SpeedHackShm private constructor(
    private val shm: MappedByteBuffer,
    private val raf: RandomAccessFile,
) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * True once the shm file was successfully mmap'd AND the C side has
     * written the magic value into the header (confirming libspeedhack.so
     * is alive in the Wine/Box64 process).
     */
    val available: Boolean
        get() {
            return try {
                shm.getInt(OFF_MAGIC) == SPEEDHACK_PROTO_MAGIC
            } catch (_: Exception) {
                false
            }
        }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Set the speed multiplier.
     *
     * [multiplier] is clamped to [MULTIPLIER_MIN]..[MULTIPLIER_MAX].
     * 1.0 = normal speed.  0.5 = half speed (slow-mo).  2.0 = 2× fast.
     *
     * The write order is:
     *   1. Write multiplier_bits (non-seq field, written first).
     *   2. force() to push to shared mapping.
     *   3. Bump config_seq (the "doorbell").
     *   4. force() again so the C side sees the bumped seq.
     *
     * This matches the ordering rule in speedhack_protocol.h: multiplier
     * is written before seq is bumped, so the C side can read multiplier
     * safely after observing the seq change (memory_order_acquire).
     */
    fun setMultiplier(multiplier: Float) {
        try {
            val clamped = multiplier.coerceIn(MULTIPLIER_MIN, MULTIPLIER_MAX)
            val bits = java.lang.Float.floatToRawIntBits(clamped)

            // Write the multiplier first, then bump seq
            shm.putInt(OFF_MULTIPLIER, bits)
            shm.force()

            val newSeq = shm.getInt(OFF_CONFIG_SEQ) + 1
            shm.putInt(OFF_CONFIG_SEQ, newSeq)
            shm.force()

            Log.d(TAG, "setMultiplier: ${clamped}x (bits=0x${bits.toString(16)}, seq=$newSeq)")
        } catch (e: Exception) {
            Log.w(TAG, "setMultiplier failed (lib may not be loaded yet): ${e.message}")
        }
    }

    /**
     * Read back the multiplier currently stored in shm.
     * Useful for restoring slider state when the UI is recreated.
     */
    fun getMultiplier(): Float {
        return try {
            val bits = shm.getInt(OFF_MULTIPLIER)
            val value = java.lang.Float.intBitsToFloat(bits)
            if (value < MULTIPLIER_MIN || value > MULTIPLIER_MAX) MULTIPLIER_DEFAULT
            else value
        } catch (_: Exception) {
            MULTIPLIER_DEFAULT
        }
    }

    // -------------------------------------------------------------------------
    // Clean-up
    // -------------------------------------------------------------------------

    fun close() {
        try { raf.close() } catch (_: Exception) {}
    }

    // -------------------------------------------------------------------------
    // Companion — factory + constants
    // -------------------------------------------------------------------------

    companion object {

        private const val TAG = "SpeedHackShm"

        /* Wire constants — must match speedhack_protocol.h */
        const val SPEEDHACK_PROTO_MAGIC: Int    = 0x53504843.toInt()   // "SPHC"
        const val SPEEDHACK_PROTO_VERSION: Int  = 1
        const val SPEEDHACK_SHM_SIZE: Long      = 4096L

        /* Multiplier limits exposed to the UI */
        const val MULTIPLIER_MIN: Float     = 0.05f
        const val MULTIPLIER_MAX: Float     = 10.0f
        const val MULTIPLIER_DEFAULT: Float = 1.0f

        /* Byte offsets — must mirror speedhack_protocol.h EXACTLY */
        private const val OFF_MAGIC       = 0   // uint32 — written by C side on init
        private const val OFF_VERSION     = 4   // uint32
        private const val OFF_CONFIG_SEQ  = 8   // uint32 — Kotlin bumps after writing multiplier
        private const val OFF_MULTIPLIER  = 12  // uint32 — IEEE-754 float32 bits

        /**
         * Create a [SpeedHackShm] instance for the given app [context].
         *
         * Returns null if the mmap fails.  Callers should treat null as
         * "feature unavailable" rather than an error — the most common cause
         * is libspeedhack.so not being LD_PRELOAD'd (feature disabled by pref).
         */
        fun create(context: Context): SpeedHackShm? {
            return try {
                val dir = File(context.filesDir, "speedhack_shm")
                if (!dir.exists()) dir.mkdirs()

                val memFile = File(dir, "speedhack.mem")
                val raf = RandomAccessFile(memFile, "rw")
                raf.setLength(SPEEDHACK_SHM_SIZE)
                val buf = raf.channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0L,
                    SPEEDHACK_SHM_SIZE,
                )
                buf.order(ByteOrder.LITTLE_ENDIAN)

                // Pre-populate multiplier_bits with 1.0 if the file is fresh
                // (avoids the C side seeing 0.0 before Kotlin writes a value)
                if (buf.getInt(OFF_MULTIPLIER) == 0) {
                    val defaultBits = java.lang.Float.floatToRawIntBits(MULTIPLIER_DEFAULT)
                    buf.putInt(OFF_MULTIPLIER, defaultBits)
                    buf.force()
                }

                SpeedHackShm(buf, raf)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeedHack shm — feature disabled", e)
                null
            }
        }
    }
}
