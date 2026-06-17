package app.gamenative.cheats

import android.util.Log
import app.gamenative.trainer.TrainerProto
import app.gamenative.trainer.TrainerShm

/**
 * Synthesises an ASLR-proof, one-tap [Cheat] from a single scanned address.
 *
 * The DIY scanner resolves a concrete address in the GAME's address space, but
 * that address is NOT stable across relaunches (Box64 ASLR re-bases everything).
 * To make a saved cheat survive a relaunch we fingerprint the bytes AROUND the
 * value with an array-of-bytes (AOB) signature: a code/data window whose bytes
 * are constant across runs, with the volatile value bytes wildcarded. Next
 * session [CheatExecutor.aobActivate] re-scans for that signature and re-derives
 * the address via the signed offset — exactly the one-tap path saved cheats use.
 *
 * ALGORITHM (capture):
 *   1. Read a window centred on the value (32 bytes before + the value + after).
 *   2. Wildcard the value's own bytes (its vtype width) plus ~2 trailing bytes,
 *      since those are the parts most likely to change between/within sessions.
 *   3. Build a CE-style pattern string + a signed offset (= -windowBefore) so the
 *      pattern base resolves back to the value address.
 *   4. VERIFY-AND-TIGHTEN: aobScan the synthesised signature. Accept only when it
 *      resolves to EXACTLY the original address (count == 1, addresses[0] == addr).
 *      If 0 hits, retry with fewer trailing wildcards; if >1, grow the window
 *      (48 → 64 → 96) until it is unique. Cap the attempts.
 *
 * Returns a [Cheat] whose shape matches what [CheatExecutor.aobActivate] consumes
 * (kind = "aob_freeze", a populated [AobPattern], and a freeze value), or null
 * when no unique signature can be found (caller falls back to a guided cheat).
 */
object AobCapture {

    private const val TAG = "AobCapture"

    /** Bytes captured BEFORE the value (the AOB offset is -this). */
    private const val WINDOW_BEFORE = 32

    /** Window total sizes tried, smallest first, to disambiguate >1 hits. */
    private val WINDOW_SIZES = intArrayOf(48, 64, 96)

    /** Extra trailing wildcard counts tried (after the value bytes), to recover 0 hits. */
    private val TRAILING_WILDCARDS = intArrayOf(2, 0)

    /**
     * Capture an AOB-backed one-tap cheat for [addr].
     *
     * @param trainerShm The host-side engine bridge (targets the game pid).
     * @param addr       The resolved address from the DIY scan (count == 1).
     * @param vtype      Value-type token at [addr] ("i32", "f32", ...).
     * @param name       Human-readable cheat name (used for the id slug + display).
     * @param freezeValueRaw The raw-bits freeze value to store in the recipe (already
     *                   encoded for [vtype]); null is allowed but the saved cheat then
     *                   has no freeze value (the one-tap path needs one to activate).
     * @return a verified [Cheat], or null if no unique signature was found.
     */
    suspend fun capture(
        trainerShm: TrainerShm,
        addr: Long,
        vtype: String,
        name: String,
        freezeValueRaw: Long?,
    ): Cheat? {
        val tvt = vtypeToTvt(vtype)
        val valueSize = tvtSize(tvt)
        if (valueSize == 0) {
            Log.w(TAG, "capture: unknown value size for vtype='$vtype'")
            return null
        }

        for (windowTotal in WINDOW_SIZES) {
            // The value sits at WINDOW_BEFORE within the window. Ensure the window can
            // hold the value + at least a couple of trailing bytes.
            if (WINDOW_BEFORE + valueSize >= windowTotal) continue

            val windowStart = addr - WINDOW_BEFORE
            val bytes = trainerShm.readBytes(windowStart, windowTotal) ?: continue
            if (bytes.size < WINDOW_BEFORE + valueSize) continue

            for (trailing in TRAILING_WILDCARDS) {
                // Build a mask: 0xFF = must-match, 0x00 = wildcard. Start all-match,
                // then wildcard the value bytes and `trailing` bytes right after them.
                val mask = ByteArray(bytes.size) { 0xFF.toByte() }
                val wildStart = WINDOW_BEFORE
                val wildEnd = (WINDOW_BEFORE + valueSize + trailing).coerceAtMost(bytes.size)
                for (i in wildStart until wildEnd) mask[i] = 0x00.toByte()

                // Don't synthesise an all-wildcard or near-empty signature.
                val concrete = mask.count { it == 0xFF.toByte() }
                if (concrete < 4) continue

                val pattern = bytes.copyOf()
                // Zero the wildcarded pattern bytes for a clean CE-style string.
                for (i in wildStart until wildEnd) pattern[i] = 0x00.toByte()

                // dataOffset: from the pattern base (windowStart) back to addr.
                val dataOffset = WINDOW_BEFORE.toLong()

                val scan = trainerShm.aobScan(pattern, mask, dataOffset, tvt)
                when {
                    !scan.success && scan.status != TrainerProto.TST_NO_MATCH -> {
                        // Real engine error — abort this window/trailing combo.
                        Log.w(TAG, "capture: aobScan error: ${scan.errorMsg}")
                    }
                    scan.count == 1 && scan.addresses.isNotEmpty() && scan.addresses[0] == addr -> {
                        // Unique + resolves to the original address — accept.
                        val patternStr = toCePattern(pattern, mask)
                        Log.i(TAG, "capture: unique signature (window=$windowTotal trailing=$trailing) for 0x${addr.toString(16)}")
                        return buildCheat(name, vtype, patternStr, dataOffset.toInt(), freezeValueRaw)
                    }
                    scan.count == 0 -> {
                        // Too tight — try fewer trailing wildcards (loop continues).
                    }
                    else -> {
                        // count > 1 (or matched a different address) — too loose;
                        // grow the window (outer loop continues).
                    }
                }
            }
        }

        Log.w(TAG, "capture: no unique signature found for 0x${addr.toString(16)}")
        return null
    }

    /**
     * Build a guided fallback [Cheat] for when [capture] cannot find a unique
     * signature. The cheat re-resolves next session by the user re-typing their
     * current value and the engine re-scanning for it (kind = "guided_known").
     */
    fun buildGuidedFallback(name: String, vtype: String, freezeValueRaw: Long?): Cheat {
        val id = slug(name)
        return Cheat(
            id = id,
            name = name,
            category = "user",
            vtype = vtype,
            recipe = CheatRecipe(
                kind = "guided_known",
                prompt = "Enter your current $name value",
                narrowPrompt = "Change $name in-game, then enter the new value",
                freezeValue = freezeValueRaw,
            ),
            aob = null,
            chain = null,
        )
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private fun buildCheat(
        name: String,
        vtype: String,
        patternStr: String,
        offset: Int,
        freezeValueRaw: Long?,
    ): Cheat = Cheat(
        id = slug(name),
        name = name,
        category = "user",
        vtype = vtype,
        recipe = CheatRecipe(
            kind = "aob_freeze",
            prompt = "Saved from your scan.",
            narrowPrompt = null,
            freezeValue = freezeValueRaw,
        ),
        aob = AobPattern(
            pattern = patternStr,
            offset = offset,
            vtype = vtype,
        ),
        chain = null,
    )

    /** A stable, filesystem/id-safe slug from the name + a timestamp for uniqueness. */
    private fun slug(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "cheat" }
        return "user_${base}_${System.currentTimeMillis()}"
    }

    /** Convert (pattern, mask) into a CE-style space-separated AOB string ("?? " wildcards). */
    private fun toCePattern(pattern: ByteArray, mask: ByteArray): String =
        pattern.indices.joinToString(" ") { i ->
            if (mask[i] == 0x00.toByte()) "??"
            else "%02X".format(pattern[i].toInt() and 0xFF)
        }

    /** Byte width of a TVT_* value type (0 for unknown / TVT_NONE). */
    private fun tvtSize(tvt: Int): Int = when (tvt) {
        TrainerProto.TVT_I8, TrainerProto.TVT_U8 -> 1
        TrainerProto.TVT_I16, TrainerProto.TVT_U16 -> 2
        TrainerProto.TVT_I32, TrainerProto.TVT_U32, TrainerProto.TVT_F32 -> 4
        TrainerProto.TVT_I64, TrainerProto.TVT_U64, TrainerProto.TVT_F64 -> 8
        else -> 0
    }
}
