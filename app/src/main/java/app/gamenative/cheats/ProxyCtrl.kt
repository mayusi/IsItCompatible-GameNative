package app.gamenative.cheats

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Android (Kotlin) bridge that drives the in-game proxy DLL via a text command file.
 *
 * Architecture overview:
 *   The x86_64 Windows proxy DLL (dinput8.dll) is injected into the running game
 *   inside a Wine prefix.  It polls `<gameDir>/cheat_ctrl.txt` every ~400 ms,
 *   reads the entire file, de-duplicates by content, and executes one command per
 *   distinct write.  Acknowledgements are written to `<gameDir>/cheat_out.txt`.
 *
 *   This class is the Android side of that contract: it formats command strings per
 *   the wire format and overwrites cheat_ctrl.txt atomically via [writeCmd].
 *
 * Wire format (one command per file write):
 * ```
 *   chain=<id>|<module>|<basehex>|<off1,off2,...>|<valoffhex>|<vtype>|<mode>|<valbits>
 *   chainoff=<id>
 *   chainclear
 * ```
 *
 *   - All hex values are written WITHOUT the "0x" prefix (DLL uses strtoull base 16).
 *   - `mode=lit`: valbits is a decimal int (i32/u32) or a float decimal-with-dot (f32).
 *   - `mode=max`: valbits is the maxOffset hex (DLL tracks the live max of the field
 *     at baseAddr+chain+valoff and writes it at baseAddr+chain+maxOffset).
 *
 * GRACEFUL DEGRADATION — [create] returns null if the game directory is absent or
 *   unwritable.  All suspend functions catch IOException and return false/null
 *   without throwing, so callers need no try/catch.
 *
 * THREAD SAFETY — each suspend function dispatches its file IO to [Dispatchers.IO].
 *   The DLL de-dupes commands by file content, so a partial overwrite race between
 *   two concurrent Android writes is the only real hazard; the caller (CheatStager /
 *   CheatExecutor) is responsible for serialising commands if order matters.
 */
class ProxyCtrl private constructor(private val gameDir: File) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * True after [create] verifies the game directory exists and is writable.
     * UI code should gate cheat features on this flag.
     */
    @Volatile
    var available: Boolean = true
        private set

    // -------------------------------------------------------------------------
    // Public suspend API
    // -------------------------------------------------------------------------

    /**
     * Write a pointer-chain freeze command (the primary DLL command).
     *
     * The DLL walks the pointer chain:
     *   base = moduleBase + [base]; then deref each offset in [offsets]; then
     *   read/write at finalPtr + [valueOffset].
     *
     * @param id           Slot key (small integer); replaces any existing slot with the same id.
     * @param module       Exe or DLL name whose module base is used (e.g. "dmc3.exe"),
     *                     or empty string to use an absolute address.
     * @param base         Base offset from module (or absolute addr if [module] is empty),
     *                     encoded as hex without "0x".
     * @param offsets      Pointer-chain dereference offsets; empty list is allowed (no derefs).
     * @param valueOffset  Final offset from the last pointer to the value field, hex without "0x".
     * @param vtype        Value type token: "i32", "u32", or "f32".
     * @param writeMax     If true, use `mode=max` — DLL continuously writes the running maximum
     *                     of the field back to itself (useful for HP/MP bars).
     *                     If false, use `mode=lit` — DLL continuously writes the literal value.
     * @param freezeValueBits  Raw bits of the freeze value (ignored when [writeMax] is true):
     *                         - For "f32": the IEEE-754 bits as a Long (use
     *                           [java.lang.Float.floatToRawIntBits]).
     *                         - For "i32"/"u32": the integer value as a Long.
     * @param maxOffset    Only used when [writeMax] is true.  Offset (from the chain's resolved
     *                     pointer) at which the DLL writes the running-max copy; encoded hex.
     * @return true if the command was written successfully; false on IO error or unavailability.
     */
    suspend fun freezeChain(
        id: Int,
        module: String,
        base: Long,
        offsets: List<Long>,
        valueOffset: Long,
        vtype: String,
        writeMax: Boolean,
        freezeValueBits: Long,
        maxOffset: Long,
    ): Boolean {
        val basehex     = base.toString(16)
        val offsField   = if (offsets.isEmpty()) "" else offsets.joinToString(",") { it.toString(16) }
        val valoffhex   = valueOffset.toString(16)
        val mode        = if (writeMax) "max" else "lit"
        val valbitsField = when {
            writeMax         -> maxOffset.toString(16)
            vtype == "f32"   -> java.lang.Float.intBitsToFloat(freezeValueBits.toInt()).toString()
            else             -> freezeValueBits.toString()   // decimal for i32/u32
        }

        val line = "chain=$id|$module|$basehex|$offsField|$valoffhex|$vtype|$mode|$valbitsField"
        Log.d(TAG, "freezeChain: $line")
        return writeCmd(line)
    }

    /**
     * Disable a single freeze slot identified by [id].
     *
     * The DLL stops writing to that slot's address but leaves other slots active.
     *
     * @return true if written successfully; false on IO error or unavailability.
     */
    suspend fun removeChain(id: Int): Boolean {
        val line = "chainoff=$id"
        Log.d(TAG, "removeChain: $line")
        return writeCmd(line)
    }

    /**
     * Disable ALL active freeze slots in the DLL.
     *
     * Equivalent to calling [removeChain] for every slot but cheaper — a single
     * file write clears the entire slot table on the DLL side.
     *
     * @return true if written successfully; false on IO error or unavailability.
     */
    suspend fun clearAll(): Boolean {
        Log.d(TAG, "clearAll")
        return writeCmd("chainclear")
    }

    // -------------------------------------------------------------------------
    // DIY value-scanner API (the "make your own cheat" path for games with no
    // pre-made table). Wraps the DLL's universal scanner commands. The DLL writes
    // results to cheat_out.txt as:
    //   [scan] i32 target=N found=M ...      / [scan]   cand[i]=0xADDR
    //   [next] i32 value=N remaining=M       / [next]   cand[i]=0xADDR
    //   [delta] dir=D remaining=M            / [delta]  cand[i]=0xADDR val=V
    // We poll cheat_out.txt until the matching result line for THIS command shows
    // up (a scan over a big process can take a couple seconds). Returns the match
    // count and (when small) the candidate addresses.
    // -------------------------------------------------------------------------

    /**
     * Result of a DIY scan/narrow step.
     * @property count      Total matches the DLL reported.
     * @property addresses  Up to 12 candidate addresses the DLL dumped (only when count is small).
     * @property ok         False if the command couldn't be written or no result appeared in time.
     */
    data class ScanReply(val count: Int, val addresses: List<Long>, val ok: Boolean)

    /** Fresh exact scan for an integer value across all writable game memory. */
    suspend fun scanInt(value: Int): ScanReply = runScan("scani=$value", "[scan]")

    /** Fresh exact scan for a float value. */
    suspend fun scanFloat(value: Float): ScanReply = runScan("scanf=$value", "[scan]")

    /** Narrow the current candidate set to those now equal to [value] (int). */
    suspend fun narrowInt(value: Int): ScanReply = runScan("nexti=$value", "[next]")

    /** Narrow the current candidate set to those now equal to [value] (float). */
    suspend fun narrowFloat(value: Float): ScanReply = runScan("nextf=$value", "[next]")

    /** Snapshot all writable memory (entry point for the unknown-value flow). */
    suspend fun snapshot(): Boolean = writeCmdUnique("snap")

    /** Keep candidates whose value increased / decreased / changed since the last snapshot. */
    suspend fun narrowIncreased(): ScanReply = runScan("inc", "[delta]")
    suspend fun narrowDecreased(): ScanReply = runScan("dec", "[delta]")
    suspend fun narrowChanged(): ScanReply = runScan("chg", "[delta]")

    /** Freeze a resolved absolute address to a value (int). */
    suspend fun freezeIntAddr(addr: Long, value: Int): Boolean =
        writeCmdUnique("freezei=${addr.toString(16)}=$value")

    /** Freeze a resolved absolute address to a value (float). */
    suspend fun freezeFloatAddr(addr: Long, value: Float): Boolean =
        writeCmdUnique("freezef=${addr.toString(16)}=$value")

    /**
     * Write a scan command, then poll cheat_out.txt until the result line tagged
     * [resultTag] appears (or timeout). Returns the parsed count + candidate addrs.
     *
     * The DLL de-dupes by content, so each distinct command must differ — we append
     * a short unique nonce comment the DLL's prefix-matched parser ignores (it uses
     * strncmp on the command keyword, so trailing chars after the value are safe).
     */
    private suspend fun runScan(cmd: String, resultTag: String): ScanReply = withContext(Dispatchers.IO) {
        if (!available) return@withContext ScanReply(0, emptyList(), false)
        // Snapshot the current out-file length so we only read NEW result lines.
        val outFile = File(gameDir, OUT_FILE)
        val before = try { if (outFile.exists()) outFile.length() else 0L } catch (e: IOException) { 0L }

        // Make the command content-unique (DLL prefix-matches the keyword, ignores the rest).
        val nonce = (before xor System.identityHashCode(cmd).toLong()) and 0xFFFFL
        if (!writeCmd("$cmd #$nonce")) return@withContext ScanReply(0, emptyList(), false)

        // Poll for a new line starting with resultTag (e.g. "[scan]"/"[next]"/"[delta]").
        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(SCAN_POLL_MS)
            val text = try {
                if (!outFile.exists() || outFile.length() <= before) continue
                outFile.readText(Charsets.UTF_8).substring(before.toInt().coerceAtMost(outFile.length().toInt()))
            } catch (e: Exception) { continue }

            // Find the summary line for this result kind.
            val summary = text.lineSequence().firstOrNull {
                it.startsWith(resultTag) && (it.contains("found=") || it.contains("remaining="))
            } ?: continue

            val count = Regex("(?:found|remaining)=(\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val addrs = Regex("cand\\[\\d+]=0x([0-9a-fA-F]+)").findAll(text)
                .mapNotNull { it.groupValues[1].toLongOrNull(16) }
                .take(12).toList()
            return@withContext ScanReply(count, addrs, true)
        }
        Log.w(TAG, "runScan: timed out waiting for $resultTag after '$cmd'")
        ScanReply(0, emptyList(), false)
    }

    /** Write a command made content-unique with a nonce so repeats aren't de-duped away. */
    private suspend fun writeCmdUnique(cmd: String): Boolean {
        val nonce = (System.nanoTime() and 0xFFFFL)
        return writeCmd("$cmd #$nonce")
    }

    /**
     * Read the last acknowledgement line from `cheat_out.txt` (best-effort).
     *
     * The DLL appends one line per executed command; this returns the last line
     * of the file, or null if the file is absent, empty, or unreadable.
     *
     * Callers should not block on this — it is informational only.
     *
     * @return Last line of cheat_out.txt, or null.
     */
    suspend fun readAck(): String? = withContext(Dispatchers.IO) {
        val outFile = File(gameDir, "cheat_out.txt")
        try {
            if (!outFile.exists()) return@withContext null
            outFile.readLines(Charsets.UTF_8).lastOrNull { it.isNotBlank() }
        } catch (e: IOException) {
            Log.w(TAG, "readAck: failed to read cheat_out.txt — ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Overwrite `<gameDir>/cheat_ctrl.txt` with [line] (UTF-8, no BOM).
     *
     * The DLL reads the whole file and de-dupes by content, so a full overwrite
     * (not an append) is the correct write strategy.
     *
     * Runs entirely on [Dispatchers.IO]; never touches the main thread.
     *
     * @return true on success; false if [available] is false or an [IOException] occurs.
     */
    private suspend fun writeCmd(line: String): Boolean = withContext(Dispatchers.IO) {
        if (!available) {
            Log.w(TAG, "writeCmd: bridge unavailable — skipping '$line'")
            return@withContext false
        }
        val ctrl = File(gameDir, CTRL_FILE)
        try {
            ctrl.writeText(line, Charsets.UTF_8)
            true
        } catch (e: IOException) {
            Log.e(TAG, "writeCmd: failed to write '$line' to ${ctrl.absolutePath} — ${e.message}")
            available = false
            false
        }
    }

    // -------------------------------------------------------------------------
    // Companion — factory
    // -------------------------------------------------------------------------

    companion object {

        private const val TAG       = "ProxyCtrl"
        private const val CTRL_FILE = "cheat_ctrl.txt"
        private const val OUT_FILE  = "cheat_out.txt"

        // DIY scanner result polling — a full-process scan can take a couple seconds.
        private const val SCAN_POLL_MS    = 200L
        private const val SCAN_TIMEOUT_MS = 12_000L

        /**
         * Create a [ProxyCtrl] for the given [gameDir].
         *
         * Returns null (and logs a warning) if [gameDir] does not exist or is not
         * writable, so callers can gracefully degrade without try/catch.
         *
         * Typical usage:
         * ```kotlin
         * val ctrl = ProxyCtrl.create(gameDirFile) ?: run {
         *     Log.w(TAG, "ProxyCtrl unavailable — game dir not writable")
         *     return
         * }
         * ctrl.freezeChain(...)
         * ```
         *
         * @param gameDir  Absolute path to the game's install folder inside the Wine
         *                 prefix (e.g. `context.filesDir/imagefs/drive_c/Program Files/DMC3`).
         *                 Must be a directory the app can write to.
         * @return A ready [ProxyCtrl] instance, or null on failure.
         */
        fun create(gameDir: File): ProxyCtrl? {
            if (!gameDir.exists()) {
                Log.w(TAG, "create: gameDir does not exist — ${gameDir.absolutePath}")
                return null
            }
            if (!gameDir.isDirectory) {
                Log.w(TAG, "create: gameDir is not a directory — ${gameDir.absolutePath}")
                return null
            }
            // Probe writability by attempting a canWrite check AND a probe write.
            if (!gameDir.canWrite()) {
                Log.w(TAG, "create: gameDir is not writable — ${gameDir.absolutePath}")
                return null
            }
            return ProxyCtrl(gameDir)
        }

        /**
         * Convenience overload that accepts a [String] path.
         *
         * @see create
         */
        fun create(gameDirPath: String): ProxyCtrl? = create(File(gameDirPath))
    }
}
