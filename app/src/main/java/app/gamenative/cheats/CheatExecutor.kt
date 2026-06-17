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

    /**
     * Convert a [ProxyCtrl.ScanReply] into a [DiyResult], mapping the reason code to a
     * user-facing message so the UI can distinguish "engine not loaded" (no point
     * retrying — the DLL never injected for this game) from a real "no matches / slow"
     * timeout. ok=true carries no error.
     */
    private fun ProxyCtrl.ScanReply.toDiyResult(): DiyResult = DiyResult(
        count     = count,
        addresses = addresses.toLongArray(),
        ok        = ok,
        error     = when (reason) {
            ProxyCtrl.ScanFailReason.OK            -> null
            ProxyCtrl.ScanFailReason.NOT_CONNECTED -> "Cheat engine not loaded for this game"
            ProxyCtrl.ScanFailReason.TIMEOUT       -> "Scan timed out (no matches or engine busy)"
        },
    )

    // -------------------------------------------------------------------------
    // One-tap AOB cheat
    // -------------------------------------------------------------------------

    /**
     * Perform a one-tap "find + freeze" for a cheat whose recipe is driven by an
     * AOB (array-of-bytes) signature rather than a user-entered value scan.
     *
     * The full flow:
     *   1. Validate that [cheat.aob] and [cheat.recipe.freezeValue] are both present.
     *   2. Parse the hex signature string via [TrainerProto.parseAob].
     *   3. Reset the engine result set (mirrors [firstScan]'s reset pattern).
     *   4. Run [TrainerShm.aobScan] with the parsed pattern, mask, byte offset,
     *      and value type.
     *   5. Interpret the scan result — no match, real error, or too many matches
     *      all produce a descriptive [FreezeResult] with success=false.
     *   6. Freeze each resolved address (1..[MAX_AOB_RESULTS]) at [recipe.freezeValue],
     *      accumulating successes exactly as [applyFreeze] does.
     *
     * Deactivation reuses [deactivate] unchanged — it unfreezes by address and
     * does not need to know whether the cheat was guided or AOB-resolved.
     *
     * @param trainerShm  The shared-memory bridge to the trainer worker.
     * @param cheat       The cheat entry; must have [Cheat.aob] and
     *                    [CheatRecipe.freezeValue] populated.
     * @return [FreezeResult] with the set of frozen addresses on success, or an
     *         error result (success=false, frozenAddresses empty) on any failure.
     */
    suspend fun aobActivate(
        trainerShm: TrainerShm,
        cheat: Cheat,
    ): FreezeResult {
        // Step 1 — require both AOB descriptor and a freeze value.
        val aob = cheat.aob
            ?: return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "No AOB signature defined for cheat \"${cheat.name}\"",
            )
        val freezeValue = cheat.recipe.freezeValue
            ?: return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "No freeze value defined for cheat \"${cheat.name}\"",
            )

        // Step 2 — parse the hex signature into pattern + mask byte arrays.
        val (pattern, mask) = TrainerProto.parseAob(aob.pattern)
            ?: return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "Bad AOB signature for '${cheat.name}'",
            )

        // Step 3 — determine the value type at the resolved address.
        // The AOB block's own vtype takes precedence over the parent cheat vtype
        // when it is non-blank, allowing the signature to point at a different
        // width/encoding than the cheat's primary type.
        val resolvedVtype = if (aob.vtype.isNotBlank()) aob.vtype else cheat.vtype
        val tvt = vtypeToTvt(resolvedVtype)

        // Step 4 — reset the engine result set before the AOB scan.
        val resetOk = trainerShm.reset()
        if (!resetOk) {
            Log.w(TAG, "reset() returned false before AOB scan for '${cheat.name}' — proceeding anyway")
        }

        // Step 5 — run the AOB scan.
        val scan: ScanResult = trainerShm.aobScan(pattern, mask, aob.offset.toLong(), tvt)

        // Step 6 — interpret the scan result.
        if (scan.status == TrainerProto.TST_NO_MATCH || scan.count == 0) {
            return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "Signature not found in memory — the game version may differ, " +
                    "or it isn't loaded yet. Try again after the value is on-screen.",
            )
        }
        if (!scan.success) {
            return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = scan.errorMsg ?: "AOB scan failed (code ${scan.errorCode})",
            )
        }
        if (scan.tooMany || scan.count > MAX_AOB_RESULTS) {
            return FreezeResult(
                success = false,
                frozenAddresses = LongArray(0),
                error = "Signature matched ${scan.count} locations (expected 1) — " +
                    "too ambiguous to auto-apply safely.",
            )
        }

        // Step 7 — freeze each resolved address, accumulating successes.
        val frozen = mutableListOf<Long>()
        var lastError: String? = null

        for (addr in scan.addresses) {
            val ok = trainerShm.freeze(addr, freezeValue, tvt)
            if (ok) {
                frozen.add(addr)
            } else {
                lastError = "freeze() returned false for address 0x${addr.toString(16).uppercase()}"
                Log.w(TAG, lastError!!)
            }
        }

        return FreezeResult(
            success = frozen.isNotEmpty(),
            frozenAddresses = frozen.toLongArray(),
            error = if (frozen.isEmpty()) lastError else null,
        )
    }

    // -------------------------------------------------------------------------
    // One-tap AOB code-patch cheat
    // -------------------------------------------------------------------------

    /**
     * Perform a one-tap "find + NOP" for a cheat whose recipe kind is "aob_patch".
     *
     * Unlike [aobActivate] (which resolves an address and freezes a value), this
     * function writes a concrete sequence of bytes — typically NOPs — directly over
     * the matched code instruction(s), causing them to stop executing.  The native
     * bridge snapshots the original bytes on the first [TrainerShm.patch] call so
     * that [undoPatch] / [TrainerShm.restore] can reverse the change exactly.
     *
     * The full flow:
     *   1. Validate that [cheat.aob] is present and carries a non-blank [AobPattern.patchBytes].
     *   2. Parse the hex signature string via [TrainerProto.parseAob].
     *   3. Parse the patch-byte string via [parsePatchBytes].
     *   4. Reset the engine result set (mirrors [firstScan]'s reset pattern).
     *   5. Run [TrainerShm.aobScan] with TVT_NONE — the value type is irrelevant for
     *      a code patch; we only care about the instruction address.
     *   6. Interpret the scan result — no match, real error, or too many matches all
     *      produce a descriptive [PatchResult] with success=false.
     *   7. Patch each resolved address (up to [MAX_PATCH_SITES]), accumulating
     *      successfully-patched addresses exactly as [applyFreeze] accumulates freezes.
     *
     * Deactivation uses [undoPatch], which calls [TrainerShm.restore] per address
     * rather than a blanket restore(0) so that other active patches are untouched.
     *
     * @param trainerShm  The shared-memory bridge to the trainer worker.
     * @param cheat       The cheat entry; must have [Cheat.aob] with a non-blank
     *                    [AobPattern.patchBytes] field populated.
     * @return [PatchResult] with the set of patched addresses on success, or an
     *         error result (success=false, patchedAddresses empty) on any failure.
     */
    suspend fun aobPatch(
        trainerShm: TrainerShm,
        cheat: Cheat,
    ): PatchResult {
        // Step 1 — require AOB descriptor and a patch-bytes string.
        val aob = cheat.aob
            ?: return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "No code-patch defined for '${cheat.name}'",
            )
        if (aob.patchBytes.isNullOrBlank()) {
            return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "No code-patch defined for '${cheat.name}'",
            )
        }

        // Step 2 — parse the hex signature into pattern + mask byte arrays.
        val (pattern, mask) = TrainerProto.parseAob(aob.pattern)
            ?: return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "Bad AOB signature for '${cheat.name}'",
            )

        // Step 3 — parse the concrete patch bytes (no wildcards allowed).
        val patchBytes = parsePatchBytes(aob.patchBytes!!)
            ?: return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "Bad patch bytes for '${cheat.name}'",
            )

        // Step 4 — reset the engine result set before the AOB scan.
        val resetOk = trainerShm.reset()
        if (!resetOk) {
            Log.w(TAG, "reset() returned false before AOB patch-scan for '${cheat.name}' — proceeding anyway")
        }

        // Step 5 — run the AOB scan; vtype is irrelevant for a code patch.
        val scan: ScanResult = trainerShm.aobScan(pattern, mask, aob.offset.toLong(), TrainerProto.TVT_NONE)

        // Step 6 — interpret the scan result.
        if (scan.status == TrainerProto.TST_NO_MATCH || scan.count == 0) {
            return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "Code signature not found — game version may differ, or not loaded yet.",
            )
        }
        if (!scan.success) {
            return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = scan.errorMsg ?: "AOB scan failed (code ${scan.errorCode})",
            )
        }
        if (scan.tooMany || scan.count > MAX_PATCH_SITES) {
            return PatchResult(
                success = false,
                patchedAddresses = LongArray(0),
                error = "Signature matched ${scan.count} sites (max $MAX_PATCH_SITES) — too ambiguous to patch safely.",
            )
        }

        // Step 7 — patch each resolved address, accumulating successes.
        val patched = mutableListOf<Long>()
        var lastError: String? = null

        for (addr in scan.addresses) {
            val ok = trainerShm.patch(addr, patchBytes)
            if (ok) {
                patched.add(addr)
            } else {
                lastError = "patch() returned false for address 0x${addr.toString(16).uppercase()}"
                Log.w(TAG, lastError!!)
            }
        }

        return PatchResult(
            success = patched.isNotEmpty(),
            patchedAddresses = patched.toLongArray(),
            error = if (patched.isEmpty()) lastError else null,
        )
    }

    /**
     * Restore the original bytes at each address that was previously patched by [aobPatch].
     *
     * Calls [TrainerShm.restore] per address rather than a blanket restore(0L), so that
     * other cheats' active patches are left untouched.
     *
     * @param trainerShm      The shared-memory bridge to the trainer worker.
     * @param patchedAddresses The addresses returned in [PatchResult.patchedAddresses].
     * @return true if every restore call succeeded; false if any failed (a partial
     *         restore is still better than none — the caller should surface the failure).
     */
    suspend fun undoPatch(
        trainerShm: TrainerShm,
        patchedAddresses: LongArray,
    ): Boolean {
        var allOk = true
        for (addr in patchedAddresses) {
            val ok = trainerShm.restore(addr)
            if (!ok) {
                Log.w(TAG, "restore(0x${addr.toString(16).uppercase()}) returned false")
                allOk = false
            }
        }
        return allOk
    }

    // -------------------------------------------------------------------------
    // Pointer-chain / static freeze  (recipe kinds "pointer_chain" and "static")
    // -------------------------------------------------------------------------

    /**
     * Derive a stable, positive small-int DLL slot id from the cheat's string id.
     *
     * The DLL matches freeze slots by this id, so toggling the same cheat twice
     * reuses the same slot rather than leaking a new one.  The result is bounded
     * to [0, 0x7FFF] so it stays well inside the DLL's CHAIN_CAP range and is
     * always positive even when [String.hashCode] returns a negative value.
     */
    private fun slotIdFor(cheat: Cheat): Int = (cheat.id.hashCode() and 0x7FFF)

    /**
     * Normalize the cheat's vtype string to one of the three DLL-accepted tokens:
     * `"i32"`, `"u32"`, or `"f32"`.
     *
     * Accepts mixed-case input and common aliases ("float" → "f32", "int" → "i32").
     * Unrecognised types default to `"i32"`.
     */
    private fun normVtype(v: String): String = when (v.lowercase().trim()) {
        "f32", "float" -> "f32"
        "u32"          -> "u32"
        else           -> "i32"   // "i32", "int", and anything unrecognised
    }

    /**
     * Activate a pointer-chain (or static) freeze via the in-game DLL proxy.
     *
     * This covers both recipe kinds:
     * - **`"pointer_chain"`** — the DLL walks [ChainSpec.offsets] from the resolved
     *   module base, then writes/freezes the value at [ChainSpec.valueOffset].
     * - **`"static"`** — a chain with an empty offsets list; the resolved address IS
     *   the target.  No separate function is needed; pass an empty offsets list in
     *   [ChainSpec] and this function handles it identically.
     *
     * freeze-value encoding (when [ChainSpec.writeMax] is false):
     * - `"f32"` / `"float"` — [recipe.freezeValue] is interpreted as a Float; its raw
     *   IEEE-754 bit pattern is passed as [freezeValueBits].  ProxyCtrl will call
     *   `intBitsToFloat(freezeValueBits.toInt())` and format it as a decimal with a
     *   dot before sending it to the DLL.
     * - integer types — [recipe.freezeValue] is passed directly as-is (the DLL reads
     *   it as a decimal integer).
     *
     * When [ChainSpec.writeMax] is true the DLL ignores [freezeValueBits] entirely and
     * writes the in-memory max from [ChainSpec.maxOffset] — [recipe.freezeValue] is
     * not required in that case.
     *
     * The slot id is derived from [cheat.id] via [slotIdFor]; toggling the same cheat
     * repeatedly reuses the same DLL slot.
     *
     * @param proxy  The [ProxyCtrl] bridge to the in-game DLL.
     * @param cheat  The cheat entry; must have [Cheat.chain] populated.
     * @return [ChainResult] on success, or an error result when validation fails or
     *         the DLL call returns false.
     */
    suspend fun applyChainFreeze(
        proxy: ProxyCtrl,
        cheat: Cheat,
    ): ChainResult {
        // Require a populated ChainSpec.
        val chain = cheat.chain
            ?: return ChainResult(
                success = false,
                slotId  = 0,
                error   = "No chain spec for '${cheat.name}'",
            )

        // Encode the freeze value (or 0 when the DLL will derive it from maxOffset).
        val freezeValueBits: Long
        if (chain.writeMax) {
            // DLL writes the in-memory max; literal bits are ignored.
            freezeValueBits = 0L
        } else {
            val rawValue = cheat.recipe.freezeValue
                ?: return ChainResult(
                    success = false,
                    slotId  = 0,
                    error   = "No freeze value defined for cheat '${cheat.name}'",
                )
            freezeValueBits = when (normVtype(cheat.vtype)) {
                "f32" -> java.lang.Float.floatToRawIntBits(rawValue.toFloat()).toLong() and 0xFFFFFFFFL
                else  -> rawValue   // i32 / u32 — pass decimal bits as-is
            }
        }

        val id = slotIdFor(cheat)
        val ok = proxy.freezeChain(
            id           = id,
            module       = chain.module,
            base         = chain.base,
            offsets      = chain.offsets,
            valueOffset  = chain.valueOffset,
            vtype        = normVtype(cheat.vtype),
            writeMax     = chain.writeMax,
            freezeValueBits = freezeValueBits,
            maxOffset    = chain.maxOffset,
        )

        if (!ok) {
            Log.w(TAG, "freezeChain returned false for '${cheat.name}' (slot $id)")
        }

        return ChainResult(
            success = ok,
            slotId  = id,
            error   = if (ok) null else "Failed to send chain freeze for '${cheat.name}'",
        )
    }

    /**
     * Remove a pointer-chain freeze previously activated by [applyChainFreeze].
     *
     * The DLL slot is derived from [cheat.id] via [slotIdFor], matching the slot
     * that was registered during activation.
     *
     * @param proxy  The [ProxyCtrl] bridge to the in-game DLL.
     * @param cheat  The same cheat instance that was passed to [applyChainFreeze].
     * @return true if the DLL acknowledged the removal; false on failure.
     */
    suspend fun removeChainFreeze(
        proxy: ProxyCtrl,
        cheat: Cheat,
    ): Boolean = proxy.removeChain(slotIdFor(cheat))

    // -------------------------------------------------------------------------
    // DIY (make-your-own-cheat) scan flow — ProxyCtrl scanner only
    // -------------------------------------------------------------------------
    //
    // These functions drive ProxyCtrl's scanner API for a free-form user scan.
    // They intentionally do NOT touch TrainerShm, the guided-scan helpers
    // (firstScan / narrow), or any AOB / chain path.  The owning UI is
    // responsible for enforcing the single-active-scan constraint, exactly as it
    // does for the existing guided flow.
    //
    // Freeze-value requirement: ProxyCtrl's freeze primitives require a concrete
    // value to write.  There is currently no "read current value" primitive on
    // ProxyCtrl, so a "freeze at current" shortcut is not implementable without
    // a round-trip extension on the DLL side.  The UI must always collect a
    // freeze value from the user before calling diyFreeze.  This is documented
    // here so a future maintainer knows why diyFreezeAtCurrent is absent.
    // -------------------------------------------------------------------------

    /**
     * Start a fresh exact-value scan via ProxyCtrl.
     *
     * Parses [enteredValue] according to [vtype] ("i32" -> Int, "f32" -> Float).
     * An unparseable value returns [DiyResult] with ok=false and a parse error
     * without touching the scanner state.
     *
     * @param proxy        The [ProxyCtrl] bridge to the in-game DLL scanner.
     * @param vtype        Value type token: "i32" or "f32".
     * @param enteredValue User-supplied text representing the current in-game value.
     * @return [DiyResult] with the match count and up to 12 candidate addresses,
     *         or ok=false on parse failure or scanner IO/timeout.
     */
    @Deprecated(
        "ProxyCtrl in-Wine scanner is dead on arm64ec (DLL never loads). The DIY " +
            "scanner UI now drives the host-side engine — use diyFirstScan(trainerShm, ...).",
        ReplaceWith("diyFirstScan(trainerShm, vtype, enteredValue)"),
    )
    suspend fun diyFirstScan(
        proxy: ProxyCtrl,
        vtype: String,
        enteredValue: String,
    ): DiyResult {
        val reply = when (vtype.trim().lowercase()) {
            "f32" -> {
                val v = enteredValue.trim().toFloatOrNull()
                    ?: return DiyResult(0, LongArray(0), false, "Invalid value")
                proxy.scanFloat(v)
            }
            else -> {
                val v = enteredValue.trim().toIntOrNull()
                    ?: return DiyResult(0, LongArray(0), false, "Invalid value")
                proxy.scanInt(v)
            }
        }
        return reply.toDiyResult()
    }

    /**
     * Narrow the current scan result set to addresses whose value equals
     * [enteredValue] (exact re-scan).
     *
     * Parses [enteredValue] per [vtype] before calling ProxyCtrl.  An unparseable
     * value returns [DiyResult] with ok=false without touching the scanner state.
     *
     * @param proxy        The [ProxyCtrl] bridge.
     * @param vtype        "i32" or "f32".
     * @param enteredValue The value the target variable now holds.
     * @return Updated [DiyResult]; count should decrease toward 1 with each call.
     */
    @Deprecated(
        "ProxyCtrl in-Wine scanner is dead on arm64ec. Use diyNarrow(trainerShm, ...).",
        ReplaceWith("diyNarrow(trainerShm, vtype, enteredValue)"),
    )
    suspend fun diyNarrow(
        proxy: ProxyCtrl,
        vtype: String,
        enteredValue: String,
    ): DiyResult {
        val reply = when (vtype.trim().lowercase()) {
            "f32" -> {
                val v = enteredValue.trim().toFloatOrNull()
                    ?: return DiyResult(0, LongArray(0), false, "Invalid value")
                proxy.narrowFloat(v)
            }
            else -> {
                val v = enteredValue.trim().toIntOrNull()
                    ?: return DiyResult(0, LongArray(0), false, "Invalid value")
                proxy.narrowInt(v)
            }
        }
        return reply.toDiyResult()
    }

    /**
     * Take a memory snapshot for an unknown-value (changed/increased/decreased) flow.
     *
     * Call this before the user causes the in-game value to change, then call
     * [diyNarrowDirection] to keep only addresses whose value moved in the
     * expected direction.
     *
     * @param proxy The [ProxyCtrl] bridge.
     * @return true if the snapshot was acknowledged by the DLL; false on failure.
     */
    @Deprecated(
        "ProxyCtrl in-Wine scanner is dead on arm64ec. The host engine has no " +
            "all-memory snapshot primitive — see diySnapshot(trainerShm).",
        ReplaceWith("diySnapshot(trainerShm)"),
    )
    suspend fun diySnapshot(proxy: ProxyCtrl): Boolean = proxy.snapshot()

    /**
     * Narrow the current result set to addresses whose value changed in the
     * direction indicated by [dir] since the last [diySnapshot].
     *
     * Accepted [dir] values (case-insensitive):
     * - "up"      — keep addresses where the value increased
     * - "down"    — keep addresses where the value decreased
     * - "changed" — keep addresses where the value changed in any direction
     *
     * An unrecognised direction string returns [DiyResult] with ok=false and
     * does not touch the scanner state.
     *
     * @param proxy The [ProxyCtrl] bridge.
     * @param dir   Direction token: "up", "down", or "changed".
     * @return Updated [DiyResult].
     */
    @Deprecated(
        "ProxyCtrl in-Wine scanner is dead on arm64ec. Use diyNarrowDirection(trainerShm, ...).",
        ReplaceWith("diyNarrowDirection(trainerShm, dir)"),
    )
    suspend fun diyNarrowDirection(
        proxy: ProxyCtrl,
        dir: String,
    ): DiyResult {
        val reply = when (dir.trim().lowercase()) {
            "up"      -> proxy.narrowIncreased()
            "down"    -> proxy.narrowDecreased()
            "changed" -> proxy.narrowChanged()
            else      -> return DiyResult(
                count     = 0,
                addresses = LongArray(0),
                ok        = false,
                error     = "Unknown direction \"$dir\" — expected \"up\", \"down\", or \"changed\"",
            )
        }
        return reply.toDiyResult()
    }

    /**
     * Freeze a set of candidate addresses at an explicit [freezeValue].
     *
     * At most [MAX_DIY_FREEZE] addresses are processed; excess entries are
     * silently ignored.  The UI should gate the "Freeze" action behind a
     * candidate count <= [MAX_DIY_FREEZE] to match this cap.
     *
     * Parses [freezeValue] per [vtype] before sending freeze commands.  An
     * unparseable value returns false immediately without issuing any freeze.
     *
     * @param proxy        The [ProxyCtrl] bridge.
     * @param vtype        "i32" or "f32".
     * @param addresses    Candidate addresses from the last [DiyResult].
     * @param freezeValue  The value to write and hold at each address.
     * @return true if at least one freeze command was acknowledged; false if
     *         parsing failed or every freeze call returned false.
     */
    @Deprecated(
        "ProxyCtrl in-Wine scanner is dead on arm64ec. Use diyFreeze(trainerShm, ...).",
        ReplaceWith("diyFreeze(trainerShm, vtype, addresses, freezeValue)"),
    )
    suspend fun diyFreeze(
        proxy: ProxyCtrl,
        vtype: String,
        addresses: LongArray,
        freezeValue: String,
    ): Boolean {
        val capped = addresses.take(MAX_DIY_FREEZE)
        var anyOk = false

        // Format the value once for the wire. The DLL's freezeabs= parser selects f32
        // when the value token contains a '.', so f32 must always carry a dot.
        val valStr: String = when (vtype.trim().lowercase()) {
            "f32" -> {
                val v = freezeValue.trim().toFloatOrNull()
                if (v == null) {
                    Log.w(TAG, "diyFreeze: invalid f32 freeze value \"$freezeValue\"")
                    return false
                }
                // Ensure a decimal point so the DLL treats it as f32 (e.g. "5" -> "5.0").
                v.toString().let { if (it.contains('.')) it else "$it.0" }
            }
            else -> {
                val v = freezeValue.trim().toIntOrNull()
                if (v == null) {
                    Log.w(TAG, "diyFreeze: invalid i32 freeze value \"$freezeValue\"")
                    return false
                }
                v.toString()
            }
        }

        // Route every address through its OWN DLL chain slot (freezeabs) so ALL of
        // them freeze simultaneously — the legacy single g_freeze_addr path only held
        // the last of N. Await each slot's ack so a sequential overwrite of the
        // command file doesn't lose an earlier address to the ~400 ms DLL poll.
        for ((slot, addr) in capped.withIndex()) {
            val ok = proxy.freezeAbs(addr, valStr, slot)
            if (ok) {
                anyOk = true
            } else {
                Log.w(TAG, "diyFreeze: freezeAbs(0x${addr.toString(16).uppercase()}, slot=$slot) returned false")
            }
        }

        return anyOk
    }

    // -------------------------------------------------------------------------
    // DIY (make-your-own-cheat) scan flow — TrainerShm (host-side engine) ★ LIVE
    // -------------------------------------------------------------------------
    //
    // These overloads mirror the ProxyCtrl-based diy* signatures above but drive
    // the WORKING host-side TrainerShm engine (which reads /proc/<gamepid>/mem
    // cross-process). The DIY scanner UI (DiyCheatMaker) calls THESE — the
    // ProxyCtrl variants are dead (the in-Wine DLL never loads on arm64ec) and are
    // retained only for the one-tap pointer_chain path, which is out of scope here.
    //
    // RESULT-SHAPE PARITY: every overload returns the SAME DiyResult type the UI
    // already consumes, so DiyCheatMaker's call sites change only the first arg.
    //
    // UNKNOWN-VALUE / SNAPSHOT FLOW — IMPORTANT ENGINE CONSTRAINT:
    //   The native engine's SCAN_NEW only collects addresses whose value EQUALS
    //   the entered value (it stores prev_value per match), and SCAN_NEXT's
    //   directional filters (INCREASED/DECREASED/CHANGED) operate ONLY on that
    //   existing match set (see libtrainer.c cmd_scan_next: it bails with count==0
    //   when g_matches is empty). There is NO all-memory snapshot primitive in the
    //   protocol — unlike the old ProxyCtrl.snapshot() which snapshotted ALL
    //   memory. So a true "I can't see a number" unknown-INITIAL-value scan is not
    //   supported by the host engine: diySnapshot returns false with no side
    //   effect. Directional narrowing IS supported once a value-based first scan
    //   has built the candidate set, and diyNarrowDirection maps cleanly onto
    //   scanNext(TFLT_INCREASED/DECREASED/CHANGED).
    // -------------------------------------------------------------------------

    /**
     * Start a fresh exact-value DIY scan via the host TrainerShm engine.
     *
     * Resets the engine's result set, then runs an exact SCAN_NEW for the entered
     * value (reusing the same value-encoding the guided [firstScan] uses). The
     * encoded vtype maps "i32"/"f32"/etc through [vtypeToTvt].
     *
     * @param trainerShm   The host-side engine bridge (targets the game pid).
     * @param vtype        Value-type token ("i32", "f32", "u32", ...).
     * @param enteredValue User-supplied text representing the current in-game value.
     * @return [DiyResult] with the match count + candidate addresses, or ok=false
     *         on parse failure / scan error.
     */
    suspend fun diyFirstScan(
        trainerShm: TrainerShm,
        vtype: String,
        enteredValue: String,
    ): DiyResult {
        val tvt = vtypeToTvt(vtype)
        val encoded = encodeValue(enteredValue, vtype)
            ?: run {
                Log.w(TAG, "diyFirstScan[DIAG]: encodeValue('$enteredValue', '$vtype') returned NULL — scan NOT issued")
                return DiyResult(0, LongArray(0), false, "Invalid value")
            }
        Log.i(TAG, "diyFirstScan[DIAG]: issuing SCAN_NEW value='$enteredValue' encoded=$encoded tvt=$tvt available=${trainerShm.available}")

        // NOTE: no pre-scan reset() — cmd_scan_new() already frees the prior match
        // set on the native side (libtrainer.c matches_free at SCAN_NEW entry), so a
        // separate RESET round-trip is redundant. Issue SCAN_NEW directly.
        val result = trainerShm.scanNew(encoded, tvt)
        Log.i(TAG, "diyFirstScan[DIAG]: SCAN_NEW returned success=${result.success} count=${result.count} err=${result.errorMsg}")
        return result.toDiyResult()
    }

    /**
     * Narrow the current DIY result set to addresses whose value now equals
     * [enteredValue] (exact SCAN_NEXT). Mirrors the guided [narrow].
     *
     * @param trainerShm   The host-side engine bridge.
     * @param vtype        Value-type token (used only to encode [enteredValue];
     *                     the engine narrows with the type captured at SCAN_NEW).
     * @param enteredValue The value the target variable now holds.
     * @return Updated [DiyResult]; count should decrease toward 1 with each call.
     */
    suspend fun diyNarrow(
        trainerShm: TrainerShm,
        vtype: String,
        enteredValue: String,
    ): DiyResult {
        val encoded = encodeValue(enteredValue, vtype)
            ?: return DiyResult(0, LongArray(0), false, "Invalid value")

        val result = trainerShm.scanNext(TrainerProto.TFLT_EXACT, encoded)
        return result.toDiyResult()
    }

    /**
     * Unknown-INITIAL-value snapshot for the host engine — NOT SUPPORTED.
     *
     * The native protocol has no all-memory snapshot primitive (see the section
     * comment above): SCAN_NEXT's directional filters require an existing match
     * set built by a value-based SCAN_NEW. There is therefore nothing to snapshot
     * before the user has done a first scan. Returns false with no side effect so
     * the UI can surface a clear "type the on-screen value first" message rather
     * than silently entering a direction mode that would always yield 0 matches.
     *
     * Directional narrowing of an EXISTING result set is still available via
     * [diyNarrowDirection].
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun diySnapshot(trainerShm: TrainerShm): Boolean = false

    /**
     * Narrow the current DIY result set to addresses whose value moved in [dir]
     * since the last scan pass. Maps onto SCAN_NEXT directional filters, which the
     * engine evaluates against each match's stored prev_value.
     *
     * Accepted [dir] (case-insensitive): "up" -> TFLT_INCREASED, "down" ->
     * TFLT_DECREASED, "changed" -> TFLT_CHANGED. The cmd_value is ignored by the
     * engine for these filters, so 0 is passed.
     *
     * Requires a prior value-based [diyFirstScan] to have built the candidate set;
     * if none exists the engine returns count==0 (handled as a normal empty result).
     *
     * @param trainerShm The host-side engine bridge.
     * @param dir        Direction token: "up", "down", or "changed".
     * @return Updated [DiyResult]; ok=false only on an unrecognised direction or a
     *         real engine error.
     */
    suspend fun diyNarrowDirection(
        trainerShm: TrainerShm,
        dir: String,
    ): DiyResult {
        val filter = when (dir.trim().lowercase()) {
            "up"      -> TrainerProto.TFLT_INCREASED
            "down"    -> TrainerProto.TFLT_DECREASED
            "changed" -> TrainerProto.TFLT_CHANGED
            else      -> return DiyResult(
                count     = 0,
                addresses = LongArray(0),
                ok        = false,
                error     = "Unknown direction \"$dir\" — expected \"up\", \"down\", or \"changed\"",
            )
        }
        val result = trainerShm.scanNext(filter, 0L)
        return result.toDiyResult()
    }

    /**
     * Freeze a set of DIY candidate addresses at an explicit [freezeValue] via the
     * host engine. At most [MAX_DIY_FREEZE] addresses are processed; excess entries
     * are ignored. Mirrors the guided [applyFreeze] freeze loop but takes a raw
     * address list + a user-entered value string (the DIY scanner has no [Cheat]).
     *
     * Parses [freezeValue] per [vtype] (reusing [encodeValue]); an unparseable
     * value returns false immediately without issuing any freeze.
     *
     * @param trainerShm  The host-side engine bridge.
     * @param vtype       Value-type token ("i32", "f32", ...).
     * @param addresses   Candidate addresses from the last [DiyResult].
     * @param freezeValue The value to write and hold at each address.
     * @return true if at least one freeze command was acknowledged; false on parse
     *         failure or if every freeze call returned false.
     */
    suspend fun diyFreeze(
        trainerShm: TrainerShm,
        vtype: String,
        addresses: LongArray,
        freezeValue: String,
    ): Boolean {
        val tvt = vtypeToTvt(vtype)
        val encoded = encodeValue(freezeValue, vtype)
        if (encoded == null) {
            Log.w(TAG, "diyFreeze: invalid $vtype freeze value \"$freezeValue\"")
            return false
        }

        val capped = addresses.take(MAX_DIY_FREEZE)
        var anyOk = false
        for (addr in capped) {
            val ok = trainerShm.freeze(addr, encoded, tvt)
            if (ok) {
                anyOk = true
            } else {
                Log.w(TAG, "diyFreeze: freeze(0x${addr.toString(16).uppercase()}) returned false")
            }
        }
        return anyOk
    }

    /**
     * One-shot SET: write [value] to each narrowed [addresses] entry once (no freeze).
     * Mirrors [diyFreeze]'s cap and parse contract but issues a single WRITE per
     * address instead of a continuous freeze, so the value can keep changing in-game
     * afterwards (handy for "give me 999 now, then keep playing normally").
     *
     * Caps at [MAX_DIY_FREEZE] addresses. Returns the number of addresses written.
     *
     * @param trainerShm The host-side engine bridge.
     * @param addresses  Candidate addresses from the last [DiyResult].
     * @param vtype      Value-type token ("i32", "f32", ...).
     * @param value      The value to write once at each address.
     */
    suspend fun diySet(
        trainerShm: TrainerShm,
        addresses: LongArray,
        vtype: String,
        value: String,
    ): DiyResult {
        val tvt = vtypeToTvt(vtype)
        val encoded = encodeValue(value, vtype)
            ?: return DiyResult(0, LongArray(0), false, "Invalid value for type $vtype: \"$value\"")

        val capped = addresses.take(MAX_DIY_FREEZE)
        val written = mutableListOf<Long>()
        for (addr in capped) {
            if (trainerShm.write(addr, encoded, tvt)) {
                written.add(addr)
            } else {
                Log.w(TAG, "diySet: write(0x${addr.toString(16).uppercase()}) returned false")
            }
        }
        return DiyResult(
            count = written.size,
            addresses = written.toLongArray(),
            ok = written.isNotEmpty(),
            error = if (written.isEmpty()) "No address could be written" else null,
        )
    }

    /**
     * One-shot ADJUST: read each address's CURRENT value, add or subtract [delta],
     * and write the result back. For [isIncrease] == true the delta is added;
     * otherwise it is subtracted.
     *
     * Per-address: read the current value via [TrainerShm.read]; if the read
     * returns null (genuine read failure — see the read()-null-on-error fix) the
     * address is SKIPPED rather than written with garbage. For f32 the Long bits
     * are decoded to Float, adjusted, then re-encoded to the low-32 raw bits; for
     * integer types a plain Long add/sub is used. Caps at [MAX_DIY_FREEZE].
     *
     * @param trainerShm The host-side engine bridge.
     * @param addresses  Candidate addresses from the last [DiyResult].
     * @param vtype      Value-type token ("i32", "f32", ...).
     * @param delta      The amount to add/subtract (parsed per [vtype]).
     * @param isIncrease true to add [delta], false to subtract it.
     * @return [DiyResult] whose count is the number of addresses successfully adjusted.
     */
    suspend fun diyAdjust(
        trainerShm: TrainerShm,
        addresses: LongArray,
        vtype: String,
        delta: String,
        isIncrease: Boolean,
    ): DiyResult {
        val tvt = vtypeToTvt(vtype)
        val isFloat = vtype.trim().lowercase() in setOf("f32", "float")

        // Parse delta per type (float vs integer).
        val deltaFloat: Float
        val deltaLong: Long
        if (isFloat) {
            deltaFloat = delta.trim().toFloatOrNull()
                ?: return DiyResult(0, LongArray(0), false, "Invalid delta for type $vtype: \"$delta\"")
            deltaLong = 0L
        } else {
            deltaLong = delta.trim().toLongOrNull()
                ?: return DiyResult(0, LongArray(0), false, "Invalid delta for type $vtype: \"$delta\"")
            deltaFloat = 0f
        }

        val capped = addresses.take(MAX_DIY_FREEZE)
        val adjusted = mutableListOf<Long>()
        for (addr in capped) {
            val current = trainerShm.read(addr, tvt)
            if (current == null) {
                // Genuine read failure — DON'T write garbage; skip this address.
                Log.w(TAG, "diyAdjust: read(0x${addr.toString(16).uppercase()}) returned null — skipping")
                continue
            }
            val newEncoded: Long = if (isFloat) {
                val cur = TrainerProto.rawBitsToFloat(current)
                val next = if (isIncrease) cur + deltaFloat else cur - deltaFloat
                TrainerProto.floatToRawBits(next)
            } else {
                val next = if (isIncrease) current + deltaLong else current - deltaLong
                next
            }
            if (trainerShm.write(addr, newEncoded, tvt)) {
                adjusted.add(addr)
            } else {
                Log.w(TAG, "diyAdjust: write(0x${addr.toString(16).uppercase()}) returned false")
            }
        }
        return DiyResult(
            count = adjusted.size,
            addresses = adjusted.toLongArray(),
            ok = adjusted.isNotEmpty(),
            error = if (adjusted.isEmpty()) "No address could be adjusted" else null,
        )
    }

    /**
     * Clear ALL DIY engine state: drop the result set and remove every frozen
     * address. Mirrors the old ProxyCtrl.clearAll() the UI's "Stop / Start over"
     * button used. unfreeze(0L) clears all freezes; reset() drops the result set.
     */
    suspend fun diyClearAll(trainerShm: TrainerShm): Boolean {
        val unfrozen = trainerShm.unfreeze(0L)   // 0 = clear all frozen addresses
        val reset = trainerShm.reset()
        return unfrozen && reset
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Convert a [ScanResult] from TrainerShm into a [DiyResult] for the DIY UI. */
    private fun ScanResult.toDiyResult(): DiyResult = when {
        !success -> DiyResult(
            count     = 0,
            addresses = LongArray(0),
            ok        = false,
            error     = errorMsg ?: "Scan failed (code $errorCode)",
        )
        else -> DiyResult(
            count     = count,
            addresses = addresses,
            ok        = true,
            error     = null,
        )
    }

    /**
     * Parse a space-separated hex patch string into a [ByteArray].
     *
     * Format: two-hex-digit tokens separated by whitespace, e.g. `"90 90 90 F3 0F"`.
     * Wildcards are not permitted — every byte in a patch must be concrete.
     *
     * Returns null if:
     * - the string is blank or produces zero tokens,
     * - any token is not exactly two valid hex digits,
     * - the resulting array would exceed 32 bytes.
     *
     * Leading/trailing whitespace and extra internal whitespace are tolerated.
     * Upper- and lowercase hex digits are both accepted.
     */
    private fun parsePatchBytes(s: String): ByteArray? {
        val tokens = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.size > 32) return null
        val bytes = ByteArray(tokens.size)
        for ((i, token) in tokens.withIndex()) {
            if (token.length != 2) return null
            val value = token.toIntOrNull(16) ?: return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /**
     * Maximum number of candidates we will freeze in a single [applyFreeze] call.
     * The UI should gate "Activate" behind count <= this value.
     */
    const val MAX_FREEZE_CANDIDATES = 8

    /**
     * Maximum number of addresses an AOB scan may resolve before we treat the
     * signature as too ambiguous to auto-apply.  A well-formed signature targets
     * exactly one location; more than [MAX_AOB_RESULTS] matches indicates a
     * bad or insufficiently unique signature that could corrupt unrelated game
     * state if frozen blindly.
     */
    const val MAX_AOB_RESULTS = 4

    /**
     * Maximum number of code sites an AOB patch scan may resolve before we treat
     * the signature as too ambiguous to NOP safely.  A code signature may
     * legitimately match a small handful of sites (e.g. two drain-tick instructions
     * that should both be disabled for a single cheat), but beyond [MAX_PATCH_SITES]
     * the risk of silently corrupting unrelated code is too high.
     */
    const val MAX_PATCH_SITES = 8

    /**
     * Maximum number of DIY candidate addresses that [diyFreeze] will process in a
     * single call.  The UI should gate the "Freeze" action behind a candidate count
     * <= this value, mirroring the [MAX_FREEZE_CANDIDATES] gate on the guided flow.
     * Capping at 8 prevents accidentally freezing a large candidate set that the
     * user has not yet narrowed sufficiently.
     */
    const val MAX_DIY_FREEZE = 8
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

/**
 * Result of a [CheatExecutor.aobPatch] call.
 *
 * [patchedAddresses] is the subset of scanned addresses that were actually
 * patched — persist this so [CheatExecutor.undoPatch] knows which addresses to
 * restore, without touching other cheats' patch sites.
 */
data class PatchResult(
    val success: Boolean,
    val patchedAddresses: LongArray,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PatchResult) return false
        return success == other.success &&
            patchedAddresses.contentEquals(other.patchedAddresses) &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = success.hashCode()
        result = 31 * result + patchedAddresses.contentHashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of a [CheatExecutor.applyChainFreeze] call.
 *
 * [slotId] is the DLL slot assigned to this cheat (derived from [Cheat.id] via
 * [CheatExecutor.slotIdFor]).  Persist it so that [CheatExecutor.removeChainFreeze]
 * can target the correct slot — though in practice it re-derives the same id from
 * the cheat's id, so this field is primarily informational / useful for logging.
 *
 * Unlike [FreezeResult] and [PatchResult] this class does not carry a [LongArray],
 * so no custom [equals] / [hashCode] is required; the default data-class
 * implementations are correct.
 */
data class ChainResult(
    val success: Boolean,
    val slotId: Int,
    val error: String? = null,
)

/**
 * Result of a DIY scan step ([CheatExecutor.diyFirstScan], [CheatExecutor.diyNarrow],
 * [CheatExecutor.diyNarrowDirection]).
 *
 * @property count     Total number of matches in the scanner's current result set.
 * @property addresses Up to 12 candidate addresses (populated when [count] is small;
 *                     empty when there are too many matches to enumerate usefully).
 * @property ok        false when the ProxyCtrl call failed due to IO or timeout.
 * @property error     Human-readable failure message when [ok] is false or when the
 *                     user-supplied value could not be parsed.  Null on success.
 *
 * Custom [equals] / [hashCode] are provided because [addresses] is a [LongArray]
 * (a JVM primitive array whose structural equality is not handled by data-class
 * synthesis), following the same pattern as [FreezeResult] and [PatchResult].
 */
data class DiyResult(
    val count: Int,
    val addresses: LongArray,
    val ok: Boolean,
    val error: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiyResult) return false
        return count == other.count &&
            addresses.contentEquals(other.addresses) &&
            ok == other.ok &&
            error == other.error
    }

    override fun hashCode(): Int {
        var result = count.hashCode()
        result = 31 * result + addresses.contentHashCode()
        result = 31 * result + ok.hashCode()
        result = 31 * result + (error?.hashCode() ?: 0)
        return result
    }
}
