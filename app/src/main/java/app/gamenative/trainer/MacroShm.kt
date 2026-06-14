package app.gamenative.trainer

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Android (Kotlin) side of the Turbo-Fire + Input-Macro IPC bridge.
 *
 * Mirrors the layout defined in macro_protocol.h EXACTLY.  The shared file is
 * mmap'd with READ_WRITE + LITTLE_ENDIAN, following the same pattern as
 * TrainerShm / WinHandler gamepad_shm.
 *
 * WRITE ORDERING — the UI writes all config fields BEFORE bumping [config_seq].
 * The shim re-reads config only when it observes a config_seq change, so this
 * is the sole synchronisation fence (matches trainer_protocol.h's design).
 *
 * THREAD SAFETY — MacroShm is designed to be used from a single coroutine /
 * thread (the UI).  All public setters issue their writes and then bump
 * config_seq in one call, which is effectively atomic at the granularity the
 * shim cares about (the shim re-reads on the NEXT tick after it sees the
 * bumped seq, which is always after the entire setter returns).
 *
 * GRACEFUL DEGRADATION — if mmap fails (feature disabled, first launch, etc.),
 * [available] is false and all setters are no-ops without throwing.
 *
 * Button index mapping (btn[0..14], SDL_CONTROLLER_BUTTON_* order, Xbox 360):
 *   0  A          5  Guide      10  RB
 *   1  B          6  Start      11  DPad Up
 *   2  X          7  L3 / LS    12  DPad Down
 *   3  Y          8  R3 / RS    13  DPad Left
 *   4  Back       9  LB         14  DPad Right
 */
class MacroShm private constructor(
    private val buf: MappedByteBuffer,
    private val raf: RandomAccessFile,
) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * True when the mmap succeeded and the magic sentinel is present.
     * UI code should gate macro/turbo UI on this value.
     */
    val available: Boolean get() = _available

    @Volatile
    private var _available: Boolean = false

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Enable or disable turbo for [buttonIndex] (0-14) at [rateHz] (1-120 Hz).
     * Clears the turbo if [enabled] is false (rate is ignored when disabled).
     * No-op if [available] is false or [buttonIndex] is out of range.
     */
    fun setTurbo(buttonIndex: Int, enabled: Boolean, rateHz: Int = 10) {
        if (!_available) return
        if (buttonIndex < 0 || buttonIndex >= BTN_COUNT) return

        val clampedRate = rateHz.coerceIn(1, 120).toByte()

        /* Read-modify-write the turbo_mask */
        val mask = buf.getShort(OFF_TURBO_MASK).toInt() and 0xFFFF
        val newMask = if (enabled) {
            mask or (1 shl buttonIndex)
        } else {
            mask and (1 shl buttonIndex).inv()
        }
        buf.putShort(OFF_TURBO_MASK, newMask.toShort())
        buf.put(OFF_TURBO_RATE_HZ, clampedRate)

        bumpConfigSeq()
    }

    /**
     * Configure a macro [slot] (0-3).
     *
     * @param slot          Macro slot index (0..MACRO_SLOT_COUNT-1).
     * @param triggerButton Button index (0-14) that starts the macro on press;
     *                      use [TRIGGER_DISABLED] (0xFF) to disable the slot.
     * @param steps         List of (buttonMask: Int, durationMs: Int) pairs — up to
     *                      [MACRO_MAX_STEPS] entries. Extra entries are silently dropped.
     *                      buttonMask is a bitmask over btn[0..14].
     *                      durationMs is clamped to [1, 5000] by the shim.
     *
     * No-op if [available] is false or any index is out of range.
     */
    fun setMacro(slot: Int, triggerButton: Int, steps: List<Pair<Int, Int>>) {
        if (!_available) return
        if (slot < 0 || slot >= MACRO_SLOT_COUNT) return
        if (triggerButton != TRIGGER_DISABLED &&
                (triggerButton < 0 || triggerButton >= BTN_COUNT)) return

        val slotBase = slotOffset(slot)
        val stepCount = steps.size.coerceAtMost(MACRO_MAX_STEPS)

        buf.put(slotBase + SLOT_OFF_TRIGGER, triggerButton.toByte())
        buf.put(slotBase + SLOT_OFF_STEP_COUNT, stepCount.toByte())

        for (i in 0 until stepCount) {
            val (btnMask, durMs) = steps[i]
            val stepBase = slotBase + SLOT_OFF_STEPS + i * STEP_SIZE
            buf.putShort(stepBase + STEP_OFF_BTN_MASK, (btnMask and 0x7FFF).toShort())
            buf.putShort(stepBase + STEP_OFF_DURATION, durMs.toShort())
        }
        /* Zero out any remaining step slots to avoid stale data */
        for (i in stepCount until MACRO_MAX_STEPS) {
            val stepBase = slotBase + SLOT_OFF_STEPS + i * STEP_SIZE
            buf.putShort(stepBase + STEP_OFF_BTN_MASK, 0)
            buf.putShort(stepBase + STEP_OFF_DURATION, 0)
        }

        bumpConfigSeq()
    }

    /**
     * Disable a specific macro [slot] without touching other slots.
     * No-op if [available] is false.
     */
    fun clearSlot(slot: Int) {
        if (!_available) return
        if (slot < 0 || slot >= MACRO_SLOT_COUNT) return
        val slotBase = slotOffset(slot)
        buf.put(slotBase + SLOT_OFF_TRIGGER, TRIGGER_DISABLED.toByte())
        buf.put(slotBase + SLOT_OFF_STEP_COUNT, 0)
        bumpConfigSeq()
    }

    /**
     * Clear all turbo and macro configuration (zero everything, disable all slots).
     * No-op if [available] is false.
     */
    fun clearAll() {
        if (!_available) return
        buf.putShort(OFF_TURBO_MASK, 0)
        buf.put(OFF_TURBO_RATE_HZ, 10.toByte())
        for (slot in 0 until MACRO_SLOT_COUNT) {
            val base = slotOffset(slot)
            buf.put(base + SLOT_OFF_TRIGGER, TRIGGER_DISABLED.toByte())
            buf.put(base + SLOT_OFF_STEP_COUNT, 0)
        }
        bumpConfigSeq()
    }

    /** Read current turbo_mask (bitmask of buttons with turbo enabled). */
    fun getTurboMask(): Int {
        if (!_available) return 0
        return buf.getShort(OFF_TURBO_MASK).toInt() and 0xFFFF
    }

    /** Read current shared turbo rate (Hz). */
    fun getTurboRateHz(): Int {
        if (!_available) return 10
        return buf.get(OFF_TURBO_RATE_HZ).toInt() and 0xFF
    }

    /** Release resources. */
    fun close() {
        try { raf.close() } catch (_: Exception) {}
        _available = false
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun bumpConfigSeq() {
        val seq = buf.getInt(OFF_CONFIG_SEQ)
        buf.putInt(OFF_CONFIG_SEQ, seq + 1)
    }

    private fun slotOffset(slot: Int): Int = OFF_SLOTS + slot * SLOT_SIZE

    // -------------------------------------------------------------------------
    // Companion — constants + factory
    // -------------------------------------------------------------------------

    companion object {

        private const val TAG = "MacroShm"

        /* ---- Wire constants (mirror macro_protocol.h) ---- */
        const val MACRO_SHM_MAGIC: Int     = 0x4D43524F   // "MCRO"
        const val MACRO_PROTO_VERSION: Int = 1
        const val MACRO_SHM_SIZE: Int      = 4096
        const val BTN_COUNT: Int           = 15
        const val MACRO_SLOT_COUNT: Int    = 4
        const val MACRO_MAX_STEPS: Int     = 16
        const val TRIGGER_DISABLED: Int    = 0xFF

        /* ---- Top-level field offsets ---- */
        private const val OFF_MAGIC         = 0    // int32
        private const val OFF_PROTO_VERSION = 4    // int32
        private const val OFF_CONFIG_SEQ    = 8    // int32  — UI writes LAST after config
        private const val OFF_TURBO_MASK    = 12   // int16 (uint16)
        private const val OFF_TURBO_RATE_HZ = 14   // int8  (uint8)
        private const val OFF_SLOTS         = 16   // array of MACRO_SLOT_COUNT slots

        /* ---- Per-slot layout (relative to slot base) ---- */
        private const val SLOT_SIZE             = 68   // sizeof(macro_slot)
        private const val SLOT_OFF_TRIGGER      = 0    // uint8
        private const val SLOT_OFF_STEP_COUNT   = 1    // uint8
        // _pad[2] at offset 2-3
        private const val SLOT_OFF_STEPS        = 4    // array of MACRO_MAX_STEPS steps

        /* ---- Per-step layout (relative to step base within slot) ---- */
        private const val STEP_SIZE             = 4    // sizeof(macro_step)
        private const val STEP_OFF_BTN_MASK     = 0    // uint16
        private const val STEP_OFF_DURATION     = 2    // uint16

        /**
         * Create a [MacroShm] instance for the given app [context].
         *
         * Returns null if the mmap fails (feature disabled, lib not loaded, permissions).
         * Callers treat null as "feature unavailable".
         *
         * NOTE: The shim creates the macro_shm directory and file when MACRO_ENABLED=1;
         * Kotlin opens the same file.  If the shim hasn't run yet (file doesn't exist),
         * we create it here so the UI can at least write config that the shim will pick
         * up when it starts.
         */
        fun create(context: Context): MacroShm? {
            return try {
                val dir = File(context.filesDir, "macro_shm")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val memFile = File(dir, "macro.mem")
                val raf = RandomAccessFile(memFile, "rw")
                raf.setLength(MACRO_SHM_SIZE.toLong())
                val buf = raf.channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0L,
                    MACRO_SHM_SIZE.toLong(),
                )
                buf.order(ByteOrder.LITTLE_ENDIAN)

                val instance = MacroShm(buf, raf)

                /* Check magic — if zero the file is fresh (shim hasn't written yet);
                 * that's fine, we write our config and the shim will read it on start. */
                val magic = buf.getInt(OFF_MAGIC)
                instance._available = (magic == 0 || magic == MACRO_SHM_MAGIC)

                if (!instance._available) {
                    Log.w(TAG, "macro.mem magic mismatch (0x${magic.toString(16)}); " +
                            "expected 0x${MACRO_SHM_MAGIC.toString(16)} or 0")
                    raf.close()
                    return null
                }

                Log.d(TAG, "MacroShm ready (magic=${if (magic == 0) "fresh" else "MCRO"})")
                instance
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create macro shm — macro feature disabled", e)
                null
            }
        }
    }
}
