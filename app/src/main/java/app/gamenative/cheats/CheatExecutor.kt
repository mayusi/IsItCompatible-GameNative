package app.gamenative.cheats

import android.util.Log
import app.gamenative.trainer.ScanResult
import app.gamenative.trainer.TrainerProto
import app.gamenative.trainer.TrainerShm

/**
 * Guided cheat executor — drives TrainerShm's scan / freeze API using a
 * step-by-step recipe from a [Cheat] entry.
 *
 * STATE MACHINE (per cheat):
 *   Idle -> AwaitingInitialValue -> Scanning
 *        -> AwaitingNarrowValue  -> Narrowing  (loop until count is small)
 *        -> ReadyToFreeze        -> Frozen
 *
 * IMPORTANT: TrainerShm maintains ONE global result set.  Only one cheat
 * can be mid-scan at a time.  This class is intentionally stateless across
 * cheats — the owning UI (CheatTab) is responsible for enforcing the
 * single-active-scan constraint.
 *
 * Freezing IS independent of the scan result set: multiple cheats can be
 * frozen simultaneously because freeze entries are tracked by address
 * server-side and this executor tracks frozen addresses per cheat instance.
 */
object CheatExecutor {

    private const val TAG = "CheatExecutor"

    /**
     * Result returned from each executor step.
     *
     * @property count     Number of scan matches (0 if an error or freeze step).
     * @property addresses Addresses returned by the last scan (empty when tooMany or error).
     * @property tooMany   True when the engine still has more matches than it can return.
     * @property frozen    True once [applyFreeze] succeeds.
     * @property error     Non-null when the step failed; contains a human-readable message.
     */
    data class ExecResult(
        val count: Int = 0,
        val addresses: LongArray = LongArray(0),
        val tooMany: Boolean = false,
        val frozen: Boolean = false,
        val error: String? = null,
    ) {
        val success: Boolean get() = error == null

        companion object {
            fun error(msg: String) = ExecResult(error = msg)
        }
    }

    // -------------------------------------------------------------------------
    // Step 1 — first scan
    // -------------------------------------------------------------------------

    /**
     * Reset the engine's result set and perform an exact scan for [enteredValue].
     *
     * The caller should only call this once the engine's result set is empty /
     * has been reset.  The UI enforces that no other cheat is mid-scan before
     * calling this.
     *
     * @param trainerShm  The shared-memory bridge to the trainer worker.
     * @param cheat       The cheat whose recipe drives the scan.
     * @param enteredValue User-supplied text representing the current in-game value.
     * @return [ExecResult] with match count, or [ExecResult.error] on failure.
     */
    suspend fun firstScan(
        trainerShm: TrainerShm,
        cheat: Cheat,
        enteredValue: String,
    ): ExecResult {
        // vtypeToTvt is the package-level function defined in CheatTable.kt
        val tvt = vtypeToTvt(cheat.vtype)
        val encoded = encodeValue(enteredValue, cheat.vtype)
            ?: return ExecResult.error("Invalid value for type ${cheat.vtype}: \"$enteredValue\"")

        // Always reset before a fresh scan so we don't contaminate a previous result set.
        val resetOk = trainerShm.reset()
        if (!resetOk) {
            Log.w(TAG, "reset() returned false — proceeding anyway")
        }

        val result: ScanResult = trainerShm.scanNew(encoded, tvt)
        return result.toExecResult()
    }

    // -------------------------------------------------------------------------
    // Step 2 — narrow (exact re-scan)
    // -------------------------------------------------------------------------

    /**
     * Refine the existing result set with a new exact-match narrow scan.
     *
     * Called in a loop (Step 2 in the guided flow) until the match count drops
     * to a manageable number (the UI decides the threshold, typically <= 8).
     *
     * @param trainerShm  The shared-memory bridge.
     * @param cheat       The cheat whose vtype drives encoding.
     * @param enteredValue User-supplied text representing the *current* in-game value
     *                    (which should have changed since the previous scan).
     * @return [ExecResult] with updated match count, or [ExecResult.error] on failure.
     */
    suspend fun narrow(
        trainerShm: TrainerShm,
        cheat: Cheat,
        enteredValue: String,
    ): ExecResult {
        val encoded = encodeValue(enteredValue, cheat.vtype)
            ?: return ExecResult.error("Invalid value for type ${cheat.vtype}: \"$enteredValue\"")

        val result: ScanResult = trainerShm.scanNext(TrainerProto.TFLT_EXACT, encoded)
        return result.toExecResult()
    }

    // -------------------------------------------------------------------------
    // Step 3 — freeze
    // -------------------------------------------------------------------------

    /**
     * Freeze all candidate addresses at [recipe.freezeValue].
     *
     * The caller supplies [addresses] (from the last [ExecResult]).  Typically
     * there is a single match at this point; if a few remain (up to
     * [MAX_FREEZE_CANDIDATES]) we freeze all of them — a pragmatic "apply to
     * all candidates" approach that works for most straightforward value cheats.
     * More than [MAX_FREEZE_CANDIDATES] addresses should not reach this step;
     * the UI gates "Activate" behind a small match count.
     *
     * @return The [FreezeResult] with addresses that were successfully frozen,
     *         or an error result on total failure.
     */
    suspend fun applyFreeze(
        trainerShm: TrainerShm,
        cheat: Cheat,
        addresses: LongArray,
    ): FreezeResult {
        val tvt = vtypeToTvt(cheat.vtype)
        val freezeValue = cheat.recipe.freezeValue
            ?: return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "No freeze value defined for cheat \"${cheat.name}\"",
            )

        val frozen = mutableListOf<Long>()
        var lastError: String? = null

        for (addr in addresses) {
            val ok = trainerShm.freeze(addr, freezeValue, tvt)
            if (ok) {
                frozen.add(addr)
            } else {
                lastError = "freeze() returned false for address 0x${addr.toString(16).uppercase()}"
                Log.w(TAG, lastError!!)
            }
        }

        return if (frozen.isNotEmpty()) {
            FreezeResult(success = true, frozenAddresses = frozen.toLongArray())
        } else {
            FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = lastError ?: "All freeze calls failed",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Deactivate — unfreeze
    // -------------------------------------------------------------------------

    /**
     * Unfreeze all [frozenAddresses] associated with a cheat.
     *
     * Call this when the user hits "Deactivate" or "Start over".
     *
     * @return true if all unfreeze calls succeeded; false if any failed
     *         (partial unfreeze is still a partial success from UX perspective).
     */
    suspend fun deactivate(
        trainerShm: TrainerShm,
        frozenAddresses: LongArray,
    ): Boolean {
        var allOk = true
        for (addr in frozenAddresses) {
            val ok = trainerShm.unfreeze(addr)
            if (!ok) {
                Log.w(TAG, "unfreeze(0x${addr.toString(16).uppercase()}) returned false")
                allOk = false
            }
        }
        return allOk
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Encode a user-supplied string as raw 64-bit bits, using the cheat's declared vtype.
     *
     * Integer types:  parse as Long directly (the worker truncates to the
     *                 declared width; signed interpretation is the worker's job).
     * "f32" / "float": parse as Float, pack low 32 bits via floatToRawBits.
     * "f64" / "double": parse as Double, pack all 64 bits via doubleToRawBits.
     *
     * Returns null if the text cannot be parsed for the given type.
     */
    private fun encodeValue(text: String, vtype: String): Long? {
        val trimmed = text.trim()
        return when (vtype.lowercase()) {
            "f32" -> trimmed.toFloatOrNull()
                ?.let { TrainerProto.floatToRawBits(it) }
            "f64" -> trimmed.toDoubleOrNull()
                ?.let { TrainerProto.doubleToRawBits(it) }
            else -> trimmed.toLongOrNull()
        }
    }

    /** Convert a [ScanResult] from TrainerShm into an [ExecResult] for the UI. */
    private fun ScanResult.toExecResult(): ExecResult = when {
        !success -> ExecResult.error(errorMsg ?: "Scan failed (code $errorCode)")
        else -> ExecResult(
            count = count,
            addresses = addresses,
            tooMany = tooMany,
        )
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Maximum number of candidates we will freeze in a single [applyFreeze] call.
     * The UI should gate "Activate" behind count <= this value.
     */
    const val MAX_FREEZE_CANDIDATES = 8
}

/**
 * Result of a [CheatExecutor.applyFreeze] call.
 *
 * [frozenAddresses] is the subset that was actually frozen — persist this so
 * [CheatExecutor.deactivate] knows which addresses to unfreeze.
 */
data class FreezeResult(
    val success: Boolean,
    val frozenAddresses: LongArray,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FreezeResult) return false
        return success == other.success &&
            frozenAddresses.contentEquals(other.frozenAddresses) &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + frozenAddresses.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}
