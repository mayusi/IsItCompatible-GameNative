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
class ProxyCtrl private constructor(
    /**
     * PRIMARY IPC dir — the reliable channel for the round-trip handshake/ack.
     * This is the BACKSTOP dir (<container.rootDir>/.wine/drive_c/ProxyCheat) when a
     * container root was supplied, else the install root. The DLL mirrors all of its
     * ctrl/out interaction here regardless of which subfolder the exe launched from,
     * so reads (handshake, scan replies, acks) always come from this dir.
     */
    private val primaryDir: File,
    /**
     * SECONDARY IPC dir — the DLL's next-to-exe location, when we could resolve it
     * (install root + exe subfolder), else null. Commands are mirrored here too so a
     * root-exe game (DMC3) — whose next-to-exe dir IS the install root and may differ
     * from the backstop — still receives them. Never used for reads.
     */
    private val secondaryDir: File?,
) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * STATUS flag — true once [connect] has observed the in-game DLL's "[cheat] engine
     * up" line in EITHER IPC out file. This is a DIAGNOSTIC SIGNAL the UI can surface
     * ("cheat engine detected ✓ / not detected"), NOT a gate: commands ([writeCmd],
     * scans, freezes) are sent regardless of its value, because connect() can miss the
     * engine-up line (timing / path edge / wrong subfolder) even when the DLL is live.
     *
     * It is also used to COLOUR a scan's failure reason after a genuine timeout —
     * NOT_CONNECTED when we were never connected (likely the DLL isn't loaded) vs.
     * TIMEOUT when connected but the scan produced nothing.
     *
     * History: this was once optimistically true the moment the game dir was writable;
     * a prior fix flipped it to false-until-handshake AND wired it as a hard gate, which
     * silently no-op'd speed-hack/freeze/scan whenever the handshake was missed. The gate
     * is now removed — commands always fire; this stays purely informational.
     */
    @Volatile
    var available: Boolean = false
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
     * Set the game speed multiplier (the SPEED-HACK feature).
     *
     * Writes `speed=<multiplier>` to the control file. The in-DLL time hook scales
     * the Windows timing functions (QueryPerformanceCounter / GetTickCount(64) /
     * timeGetTime) the game imports, anchoring the scaled clock so changes stay
     * monotonic. `1.0` = normal speed; `< 1.0` = slow-mo; `> 1.0` = fast-forward.
     *
     * DEFAULT-SAFETY: the DLL only patches the game's IAT the first time it sees a
     * non-1.0 value, so a value of exactly 1.0 (and any launch where the slider is
     * never touched) leaves game startup completely unaffected.
     *
     * The multiplier is clamped to [MULTIPLIER_MIN]..[MULTIPLIER_MAX] before the
     * write (mirrors the DLL's own SPEED_MIN..SPEED_MAX clamp).
     *
     * Issued via [writeCmdUnique] because the DLL de-dupes commands by file content;
     * the appended nonce makes repeated/identical multiplier writes distinct while the
     * DLL's prefix-matched `atof` ignores the trailing bytes.
     *
     * @return true if written successfully; false on IO error or unavailability.
     */
    @Deprecated(
        "The in-Wine speed-hack DLL bridge is dead on arm64ec (the proxy DLL never loads), " +
            "so this writes a control file nothing reads. The runtime speed hack is being " +
            "rebuilt host-side; SpeedSection no longer calls this. Do not wire new code to it.",
    )
    suspend fun setSpeed(multiplier: Float): Boolean {
        val clamped = multiplier.coerceIn(MULTIPLIER_MIN, MULTIPLIER_MAX)
        Log.d(TAG, "setSpeed: $clamped")
        return writeCmdUnique("speed=$clamped")
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
     * Why a scan did not return results — lets the UI say WHY instead of a blind
     * "timed out". Minimal by design (no per-call connect retry, no parse self-test).
     */
    enum class ScanFailReason {
        /** Round-trip succeeded — results (possibly zero) were read. */
        OK,
        /**
         * The scan was sent and polled but timed out, AND [connect] had never seen the
         * DLL's "engine up" line — most likely the DLL isn't loaded for this game. (The
         * scan is still attempted regardless of connect state; this only DESCRIBES the
         * timeout, it no longer short-circuits the attempt.)
         */
        NOT_CONNECTED,
        /** Engine WAS detected live but no result line appeared in the poll window (genuinely empty or slow). */
        TIMEOUT,
    }

    /**
     * Result of a DIY scan/narrow step.
     * @property count      Total matches the DLL reported.
     * @property addresses  Up to 12 candidate addresses the DLL dumped (only when count is small).
     * @property ok         False if the command couldn't be written or no result appeared in time.
     * @property reason     Coded outcome so the UI can distinguish "DLL not loaded" from "timed out".
     */
    data class ScanReply(
        val count: Int,
        val addresses: List<Long>,
        val ok: Boolean,
        val reason: ScanFailReason = if (ok) ScanFailReason.OK else ScanFailReason.TIMEOUT,
    )

    /**
     * Detect whether the in-game DLL is LIVE, for diagnostics (NOT as a command gate).
     *
     * Polls BOTH out files — the BACKSTOP (cheat_out.txt under C:\ProxyCheat ↔
     * <rootDir>/.wine/drive_c/ProxyCheat) AND the next-to-exe dir — for the DLL's
     * "[cheat] engine up" startup line. The DLL writes the line to both, but timing and
     * path edges can land it in one first (or only one), so checking either is what makes
     * detection reliable. Flips [available] true once the line is seen in EITHER. Mirrors
     * how TrainerShm.connect() does its PING handshake.
     *
     * Idempotent — returns true immediately if already connected. Safe to re-call
     * each time the menu opens (the DLL may need a moment after launch to start its
     * thread and write the line).
     *
     * @return true if the engine-up line was observed within the connect window.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        // Idempotent: returns true fast if already connected, else re-polls. Safe to
        // re-call on every menu open — a game that loaded the DLL slowly connects on a
        // later attempt.
        if (available) return@withContext true
        // Check BOTH out files: the backstop (primaryDir) and the next-to-exe dir
        // (secondaryDir). The DLL writes its "[cheat] engine up" line to both, but
        // timing/path edges mean the line can land in one before the other (or only in
        // one, for a root-exe game whose exe-dir IS the install root). If EITHER shows it,
        // the engine is live — flip available.
        val outFiles = listOfNotNull(
            File(primaryDir, OUT_FILE),
            secondaryDir?.let { File(it, OUT_FILE) },
        )
        repeat(CONNECT_ATTEMPTS) {
            val hit = outFiles.firstOrNull { engineUpSeen(it) }
            if (hit != null) {
                available = true
                Log.d(TAG, "connect: engine up — DLL live (out=${hit.absolutePath})")
                return@withContext true
            }
            kotlinx.coroutines.delay(CONNECT_POLL_MS)
        }
        Log.w(TAG, "connect: no '[cheat] engine up' in ${outFiles.joinToString { it.absolutePath }} — DLL not loaded")
        false
    }

    /** Tail-read [outFile] and report whether the DLL's "[cheat] engine up" line is present. */
    private fun engineUpSeen(outFile: File): Boolean = try {
        if (outFile.exists() && outFile.length() > 0L) {
            val raf = java.io.RandomAccessFile(outFile, "r")
            try {
                val total = raf.length()
                val start = (total - MAX_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((total - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8).lineSequence().any {
                    it.startsWith("[cheat] engine up")
                }
            } finally { raf.close() }
        } else false
    } catch (e: Exception) { false }

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
     * Freeze an absolute address into a DEDICATED DLL chain slot via `freezeabs=`.
     *
     * Unlike [freezeIntAddr]/[freezeFloatAddr] (which share the single legacy
     * g_freeze_addr — so only the LAST of N addresses actually freezes), this routes
     * each address through the DLL's multi-slot chain table using a DIY-reserved
     * slot index, so every selected address is held simultaneously.
     *
     * Wire format: `freezeabs=<hexaddr>=<val>=<slot>` where `<val>` is a decimal int
     * (i32) or a float-with-dot (f32), and `<slot>` is a per-address index.
     *
     * Because the DLL polls cheat_ctrl.txt every ~400 ms and [writeCmd] OVERWRITES
     * the file, issuing N freezes back-to-back would lose all but the last to the
     * poll. We therefore await the DLL's `[chain] freezeabs` ack for this slot before
     * returning, so the caller can safely issue the next address sequentially.
     *
     * @param addr   Absolute target address.
     * @param valStr Value token already formatted for the wire (decimal int, or
     *               float-with-dot for f32 — a '.' selects f32 on the DLL side).
     * @param slot   Per-address slot index (0-based); maps to DIY_SLOT_BASE+slot.
     * @return true if written and acknowledged within the ack window; false otherwise.
     */
    suspend fun freezeAbs(addr: Long, valStr: String, slot: Int): Boolean = withContext(Dispatchers.IO) {
        // No `available` gate — send-then-await regardless (status, not gate). If the DLL
        // isn't loaded the ack simply never arrives and we time out after ACK_TIMEOUT_MS;
        // if it IS live but connect() missed the engine-up line, the freeze still lands.
        val outFile = File(primaryDir, OUT_FILE)
        val before = try { if (outFile.exists()) outFile.length() else 0L } catch (e: IOException) { 0L }

        val cmd = "freezeabs=${addr.toString(16)}=$valStr=$slot"
        Log.d(TAG, "freezeAbs: $cmd")
        if (!writeCmd(cmd)) return@withContext false

        // Await the DLL's "[chain] freezeabs ... (id=<DIY_SLOT_BASE+slot>)" ack so the
        // next sequential write doesn't overwrite this command before the poll reads it.
        val wantId = DIY_SLOT_BASE + slot
        val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(SCAN_POLL_MS)
            val text = try {
                if (!outFile.exists() || outFile.length() <= before) continue
                val raf = java.io.RandomAccessFile(outFile, "r")
                try {
                    raf.seek(before)
                    val len = (raf.length() - before).coerceAtMost(MAX_TAIL_BYTES).toInt()
                    if (len <= 0) continue
                    val bytes = ByteArray(len)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                } finally { raf.close() }
            } catch (e: Exception) { continue }

            val acked = text.lineSequence().any {
                it.startsWith("[chain] freezeabs") && it.contains("(id=$wantId)")
            }
            if (acked) return@withContext true
        }
        Log.w(TAG, "freezeAbs: timed out waiting for ack of slot $slot (id=$wantId)")
        false
    }

    /**
     * AOB (array-of-bytes / signature) scan — resolve a runtime address by scanning
     * committed/readable game memory for a byte pattern with wildcards, then applying
     * a signed byte offset. This is what imported Cheat-Engine `.CT` AOB/AA-script
     * cheats need: CE finds an address by signature (because Box64 ASLR makes raw
     * static addresses unreliable), then offsets to the value.
     *
     * Writes `aobscan=<id>|<pattern>|<offset>` to the control file and polls
     * cheat_out.txt for the DLL's reply on the SAME round-trip channel the value
     * scanner uses:
     *     [aob] id=<id> found=0x<addr>     -> returns the resolved address
     *     [aob] id=<id> notfound           -> returns null (no match)
     *
     * The [id] echoes back in the reply so a specific request can be matched even if
     * several aob scans are issued. Keep it short and content-distinct per call (it
     * doubles as the de-dup discriminator — the DLL prefix-matches the command, so a
     * different id makes the whole command line content-unique without a nonce).
     *
     * Mirrors [runScan]'s connected/timeout/reason machinery exactly: returns null
     * fast (NOT_CONNECTED) when the DLL never announced itself, polls the BACKSTOP
     * out file for the tagged line until [SCAN_TIMEOUT_MS], and tolerates a huge out
     * file by truncating + resetting the baseline.
     *
     * @param id      Short request id, echoed in the reply (e.g. the cheat id).
     * @param pattern CE-style AOB hex string with `??` / `?` / `*` wildcards
     *                (e.g. "48 8B 05 ?? ?? ?? ?? 89").
     * @param offset  Signed byte offset added to the match base to get the value addr.
     * @return The resolved absolute address, or null if not found / not connected / timed out.
     */
    suspend fun aobScan(id: String, pattern: String, offset: Long): Long? = withContext(Dispatchers.IO) {
        // `available` is a STATUS, not a gate — send and poll regardless. The DLL may be
        // live even if connect() never saw the engine-up line, so we must give the scan a
        // real chance. `wasConnected` is only logged to explain a genuine timeout (DLL not
        // loaded vs. live-but-no-match); aobScan's null return is reason-less by contract.
        val wasConnected = available
        // Sanitise the id: no '|' (the field separator) and no whitespace/newlines
        // (the DLL reads a single line and splits on '|'). Falls back to a stable token.
        val safeId = id.replace(Regex("[|\\s]"), "_").ifBlank { "aob" }

        val outFile = File(primaryDir, OUT_FILE)
        var before = try { if (outFile.exists()) outFile.length() else 0L } catch (e: IOException) { 0L }
        if (before > MAX_OUT_BYTES) {
            try { java.io.FileOutputStream(outFile).close(); before = 0L } catch (e: IOException) { /* best-effort */ }
        }

        // Wire: aobscan=<id>|<pattern>|<offset>. The id makes the line content-distinct
        // per request, so the DLL's content de-dup won't drop back-to-back aob scans.
        val cmd = "aobscan=$safeId|$pattern|$offset"
        Log.d(TAG, "aobScan: $cmd")
        if (!writeCmd(cmd)) return@withContext null

        val wantPrefix = "[aob] id=$safeId "
        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(SCAN_POLL_MS)
            val text = try {
                if (!outFile.exists() || outFile.length() <= before) continue
                val raf = java.io.RandomAccessFile(outFile, "r")
                try {
                    raf.seek(before)
                    val len = (raf.length() - before).toInt()
                    if (len <= 0) continue
                    val bytes = ByteArray(len)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                } finally { raf.close() }
            } catch (e: Exception) { continue }

            // Match the reply line for THIS id; "found=0x..." resolves, "notfound" is a miss.
            val line = text.lineSequence().firstOrNull { it.startsWith(wantPrefix) } ?: continue
            if (line.contains("notfound")) {
                Log.d(TAG, "aobScan: '$safeId' not found")
                return@withContext null
            }
            val addr = Regex("found=0x([0-9a-fA-F]+)").find(line)
                ?.groupValues?.get(1)?.toLongOrNull(16)
            if (addr != null) {
                Log.d(TAG, "aobScan: '$safeId' resolved 0x${addr.toString(16)}")
                return@withContext addr
            }
            // Line matched the prefix but neither token parsed — keep polling.
        }
        Log.w(TAG, "aobScan: timed out waiting for [aob] id=$safeId (connected=$wasConnected)")
        null
    }

    /**
     * Write a scan command, then poll cheat_out.txt until the result line tagged
     * [resultTag] appears (or timeout). Returns the parsed count + candidate addrs.
     *
     * The DLL de-dupes by content, so each distinct command must differ — we append
     * a short unique nonce comment the DLL's prefix-matched parser ignores (it uses
     * strncmp on the command keyword, so trailing chars after the value are safe).
     */
    private suspend fun runScan(cmd: String, resultTag: String): ScanReply = withContext(Dispatchers.IO) {
        // `available` is a STATUS, not a gate — we ALWAYS send and poll, because the DLL
        // may be live even if connect() never observed the engine-up line (the line went
        // only to the exe-dir, or connect ran before the DLL's thread started). Capturing
        // it here lets us colour the FAILURE reason after a genuine timeout: if we were
        // never connected, NOT_CONNECTED (most likely the DLL isn't loaded); if we were
        // connected, TIMEOUT (engine live but the scan produced no result in the window).
        val wasConnected = available
        val timeoutReason = if (wasConnected) ScanFailReason.TIMEOUT else ScanFailReason.NOT_CONNECTED
        // Snapshot the current out-file length so we only read NEW result lines.
        // Reads come from the BACKSTOP (primaryDir) — the channel the DLL is
        // guaranteed to write to regardless of the exe subfolder.
        val outFile = File(primaryDir, OUT_FILE)
        var before = try { if (outFile.exists()) outFile.length() else 0L } catch (e: IOException) { 0L }

        // If the out-file has grown huge, truncating it keeps tail reads cheap and
        // resets our offset baseline; the DLL re-opens with FILE_APPEND so this is safe.
        if (before > MAX_OUT_BYTES) {
            try { java.io.FileOutputStream(outFile).close(); before = 0L } catch (e: IOException) { /* best-effort */ }
        }

        // Make the command content-unique (DLL prefix-matches the keyword, ignores the rest).
        val nonce = (before xor System.identityHashCode(cmd).toLong()) and 0xFFFFL
        if (!writeCmd("$cmd #$nonce")) return@withContext ScanReply(0, emptyList(), false, timeoutReason)

        // Poll for a new line starting with resultTag (e.g. "[scan]"/"[next]"/"[delta]").
        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(SCAN_POLL_MS)
            // RandomAccessFile tail read of ONLY the bytes appended since `before`
            // (the old readText().substring(before.toInt()) read the whole file AND
            // truncated the Long offset to Int, corrupting reads past 2 GB of output).
            val text = try {
                if (!outFile.exists() || outFile.length() <= before) continue
                val raf = java.io.RandomAccessFile(outFile, "r")
                try {
                    raf.seek(before)
                    val len = (raf.length() - before).toInt()
                    if (len <= 0) continue
                    val bytes = ByteArray(len)
                    raf.readFully(bytes)
                    String(bytes, Charsets.UTF_8)
                } finally { raf.close() }
            } catch (e: Exception) { continue }

            // Find the summary line for this result kind.
            val summary = text.lineSequence().firstOrNull {
                it.startsWith(resultTag) && (it.contains("found=") || it.contains("remaining="))
            } ?: continue

            val count = Regex("(?:found|remaining)=(\\d+)").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val addrs = Regex("cand\\[\\d+]=0x([0-9a-fA-F]+)").findAll(text)
                .mapNotNull { it.groupValues[1].toLongOrNull(16) }
                .take(12).toList()
            return@withContext ScanReply(count, addrs, true, ScanFailReason.OK)
        }
        Log.w(TAG, "runScan: timed out waiting for $resultTag after '$cmd' (connected=$wasConnected)")
        ScanReply(0, emptyList(), false, timeoutReason)
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
        val outFile = File(primaryDir, OUT_FILE)
        try {
            if (!outFile.exists()) return@withContext null
            // Tail read only the last ~4 KB instead of readLines() over the whole file
            // (which on a long-running session loads megabytes just to get one line).
            val raf = java.io.RandomAccessFile(outFile, "r")
            val text = try {
                val total = raf.length()
                if (total == 0L) return@withContext null
                val start = (total - MAX_TAIL_BYTES).coerceAtLeast(0L)
                raf.seek(start)
                val bytes = ByteArray((total - start).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
            } finally { raf.close() }
            text.lineSequence().lastOrNull { it.isNotBlank() }
        } catch (e: IOException) {
            Log.w(TAG, "readAck: failed to read cheat_out.txt — ${e.message}")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Overwrite the control file with [line] (UTF-8, no BOM) in BOTH IPC dirs.
     *
     * The DLL reads the whole file and de-dupes by content (globally across both of
     * its ctrl files), so a full overwrite (not an append) is the correct write
     * strategy and writing the same content to both dirs executes the command once.
     *
     * Writing to BOTH the backstop and the next-to-exe dir is what guarantees the two
     * sides meet: the backstop is reliable regardless of exe layout, and the next-to-exe
     * mirror keeps DMC3-style root exes (whose next-to-exe dir is the install root)
     * working. Success is reported if AT LEAST ONE write lands.
     *
     * FAIL-OPEN — this NEVER consults [available] before attempting the write. Writing
     * cheat_ctrl.txt is harmless even if the DLL isn't loaded (it just becomes an unread
     * file), whereas gating on [available] silently no-ops every command — speed-hack,
     * freeze, scan — whenever [connect] failed to observe the engine-up line for timing
     * or path-edge reasons even though the DLL is actually live. So we always write;
     * [available] is a STATUS, not a gate. We only flip [available] false here as a
     * SIGNAL when ALL writes fail (a real filesystem error), never to block the attempt.
     *
     * Runs entirely on [Dispatchers.IO]; never touches the main thread.
     *
     * @return true if at least one control file was written; false otherwise.
     */
    private suspend fun writeCmd(line: String): Boolean = withContext(Dispatchers.IO) {
        val targets = listOfNotNull(primaryDir, secondaryDir)
        var anyOk = false
        for (dir in targets) {
            val ctrl = File(dir, CTRL_FILE)
            try {
                dir.mkdirs()
                ctrl.writeText(line, Charsets.UTF_8)
                anyOk = true
            } catch (e: IOException) {
                Log.w(TAG, "writeCmd: failed to write '$line' to ${ctrl.absolutePath} — ${e.message}")
            }
        }
        if (!anyOk) {
            Log.e(TAG, "writeCmd: '$line' failed on all IPC dirs")
            available = false
        }
        anyOk
    }

    // -------------------------------------------------------------------------
    // Companion — factory
    // -------------------------------------------------------------------------

    companion object {

        private const val TAG       = "ProxyCtrl"
        private const val CTRL_FILE = CheatPaths.CTRL_FILE
        private const val OUT_FILE  = CheatPaths.OUT_FILE

        // connect() handshake — poll the backstop out file ~5×300ms for the DLL's
        // "[cheat] engine up" line before declaring the engine available.
        private const val CONNECT_ATTEMPTS = 5
        private const val CONNECT_POLL_MS  = 300L

        // Speed-hack multiplier bounds — mirror SPEED_MIN..SPEED_MAX in cheat.c.
        const val MULTIPLIER_MIN: Float = 0.1f
        const val MULTIPLIER_MAX: Float = 10.0f

        // DIY scanner result polling — a full-process scan can take a couple seconds.
        private const val SCAN_POLL_MS    = 200L
        private const val SCAN_TIMEOUT_MS = 12_000L

        // freezeAbs ack window — the DLL polls cheat_ctrl.txt every ~400 ms, so allow a
        // few poll cycles for the "[chain] freezeabs" ack before giving up on a slot.
        private const val ACK_TIMEOUT_MS  = 3_000L

        // Tail-read cap for ack/last-line reads so we never load the whole file.
        private const val MAX_TAIL_BYTES  = 4_096L

        // If cheat_out.txt grows past this, truncate it locally and reset the read
        // baseline so scan tail-reads stay cheap (the DLL re-appends from empty).
        private const val MAX_OUT_BYTES   = 4L * 1024 * 1024

        // Mirrors DIY_SLOT_BASE in cheat.c — DIY absolute freezes occupy id range
        // [DIY_SLOT_BASE, DIY_SLOT_BASE+MAX_DIY_FREEZE) in the DLL chain table.
        private const val DIY_SLOT_BASE   = 0x10000

        /**
         * Create a [ProxyCtrl] wired to BOTH the deterministic backstop channel and
         * the next-to-exe channel, so the bridge is guaranteed to agree with the DLL
         * regardless of which subfolder the game exe launched from.
         *
         * PRIMARY (reads + writes) — the BACKSTOP dir when [containerRootDir] is given:
         *   <containerRootDir>/.wine/drive_c/ProxyCheat  ↔  Wine C:\ProxyCheat
         * This is the channel the DLL always mirrors to and the only one guaranteed to
         * be computable on both sides without knowing the exe subfolder. If no container
         * root is supplied (or its drive_c is missing), the primary falls back to the
         * exe-dir, then the install root.
         *
         * SECONDARY (writes only) — the DLL's next-to-exe dir, resolved from
         * [installRoot] + [executablePath] (e.g. install-root + "release/NSGP.exe" →
         * ".../release"). For a root exe (DMC3) this equals the install root. Mirrored so
         * those games still receive commands.
         *
         * Returns null only if NO writable IPC dir can be established.
         *
         * @param installRoot      The game's install ROOT (SteamService.getAppDirPath).
         * @param containerRootDir The container's rootDir (container.getRootDir()), used to
         *                         compute the backstop path. Null degrades to exe-dir/root.
         * @param executablePath   The container's executablePath (e.g. "release/NSGP.exe"),
         *                         used to resolve the next-to-exe dir. Empty/null → install root.
         * @return A ready [ProxyCtrl] (not yet connected — call [connect]), or null.
         */
        fun create(
            installRoot: File,
            containerRootDir: File? = null,
            executablePath: String? = null,
        ): ProxyCtrl? {
            if (!installRoot.exists() || !installRoot.isDirectory) {
                Log.w(TAG, "create: install root missing/not a dir — ${installRoot.absolutePath}")
                return null
            }

            // SECONDARY: next-to-exe dir (always under the writable install root).
            val exeDir = CheatPaths.exeDir(installRoot, executablePath)

            // PRIMARY: backstop dir, if the container's drive_c exists (it always does
            // for a launched container). Create the ProxyCheat subdir so Kotlin can write
            // the ctrl file before the DLL's own CreateDirectoryA runs.
            val backstop = CheatPaths.backstopDir(containerRootDir)?.let { dir ->
                val driveC = dir.parentFile
                if (driveC != null && driveC.isDirectory) {
                    runCatching { dir.mkdirs() }
                    if (dir.isDirectory && dir.canWrite()) dir else null
                } else null
            }

            // Choose the primary read/write channel, preferring the backstop.
            val primary = when {
                backstop != null -> backstop
                exeDir.isDirectory && exeDir.canWrite() -> exeDir
                installRoot.canWrite() -> installRoot
                else -> {
                    Log.w(TAG, "create: no writable IPC dir for ${installRoot.absolutePath}")
                    return null
                }
            }
            // Secondary mirror — only when it's a real, distinct, writable dir.
            val secondary = exeDir.takeIf {
                it.absolutePath != primary.absolutePath && it.isDirectory && it.canWrite()
            }

            Log.d(TAG, "create: primary=${primary.absolutePath} secondary=${secondary?.absolutePath}")
            return ProxyCtrl(primary, secondary)
        }

        /**
         * Back-compat overload: a single game dir is used as BOTH the install root and
         * (when [containerRootDir] omitted) the only IPC dir. Prefer the 3-arg overload
         * so the backstop channel is wired up.
         */
        fun create(gameDir: File): ProxyCtrl? =
            create(installRoot = gameDir, containerRootDir = null, executablePath = null)

        /**
         * Convenience overload that accepts a [String] install-root path.
         *
         * @see create
         */
        fun create(gameDirPath: String): ProxyCtrl? = create(File(gameDirPath))
    }
}
