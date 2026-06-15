package app.gamenative.trainer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Android (Kotlin) side of the trainer IPC bridge.
 *
 * Mirrors the layout defined in trainer_protocol.h EXACTLY.  The shared file is
 * mmap'd with READ_WRITE + LITTLE_ENDIAN, following the proven gamepad_shm
 * pattern in WinHandler.java (RandomAccessFile → setLength → getChannel().map).
 *
 * RESPONSE WAITING — busy-poll choice:
 *   The native libtrainer.so worker bumps resp_seq + FUTEX_WAKEs after every
 *   command.  The Kotlin side could FUTEX_WAIT via JNI, but that would require
 *   a new exported symbol in libtrainer.  Instead we use a coroutine busy-poll
 *   that checks resp_seq every ~50 ms.  For a UI-driven trainer (one command at
 *   a time, initiated by the user) this is perfectly acceptable: the overhead is
 *   one extra MappedByteBuffer.getInt() per 50 ms tick, and the poll loop runs
 *   on Dispatchers.IO so it never blocks the main thread.  Timeouts: 30 s for
 *   scans (can be slow), 2 s for all other commands.
 *
 * THREAD SAFETY — a Mutex serialises all commands so two UI actions cannot
 *   interleave writes/reads on the single shared-memory channel.
 *
 * GRACEFUL DEGRADATION — if the file or mmap fails (libtrainer.so not loaded,
 *   feature disabled, first launch before the worker process starts), [available]
 *   is false and all suspend functions return null/false without throwing.
 */
class TrainerShm private constructor(
    private val shm: MappedByteBuffer,
    private val raf: RandomAccessFile,
) {

    // -------------------------------------------------------------------------
    // Public state
    // -------------------------------------------------------------------------

    /**
     * True once the worker has written TRAINER_PROTO_VERSION into the shm header
     * AND a PING command succeeds.  UI code should gate trainer features on this.
     */
    @Volatile
    var available: Boolean = false
        private set

    /** Live scan progress (0..100) updated while a SCAN_NEW / SCAN_NEXT is in flight. */
    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private val mutex = Mutex()

    // -------------------------------------------------------------------------
    // Public suspend API
    // -------------------------------------------------------------------------

    /**
     * TCMD_PING — ask the worker if it is alive.
     * Updates [available] on success.
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_PING,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        }.also { ok ->
            if (ok == true) available = true
        } ?: false
    }

    /**
     * Establish availability by pinging the native worker with retries.
     *
     * Must be called after [create] — the worker may take a moment to claim
     * ownership and start its worker thread after game launch, so a single
     * immediate ping often fails.  This function retries up to
     * [CONNECT_ATTEMPTS] times with [CONNECT_RETRY_DELAY_MS] between each
     * attempt, giving the worker time to initialise and write
     * TRAINER_PROTO_VERSION into the shm header.
     *
     * Sets and returns [available].  If all attempts fail, [available] remains
     * false and all other suspend calls will time out gracefully.
     *
     * Typical caller:
     * ```kotlin
     * val shm = TrainerShm.create(context) ?: return
     * val ready = shm.connect()
     * if (!ready) { /* show "trainer unavailable" UI */ }
     * ```
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        repeat(CONNECT_ATTEMPTS) { attempt ->
            if (ping()) return@withContext true
            if (attempt < CONNECT_ATTEMPTS - 1) {
                Log.d(TAG, "connect: ping attempt ${attempt + 1}/$CONNECT_ATTEMPTS failed — retrying in ${CONNECT_RETRY_DELAY_MS}ms")
                delay(CONNECT_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "connect: worker did not respond after $CONNECT_ATTEMPTS attempts")
        false
    }

    /**
     * TCMD_SCAN_NEW — fresh scan of all rw regions.
     * [value] is raw bits (use TrainerProto helpers for float/double).
     */
    suspend fun scanNew(value: Long, vtype: Int): ScanResult = withContext(Dispatchers.IO) {
        _progress.value = 0
        runScanCommand(
            cmd = TrainerProto.TCMD_SCAN_NEW,
            vtype = vtype,
            filter = TrainerProto.TFLT_EXACT,
            addr = 0L,
            value = value,
        )
    }

    /**
     * TCMD_SCAN_NEXT — refine the previous result set.
     * [filter] is one of the TFLT_* constants.
     */
    suspend fun scanNext(filter: Int, value: Long): ScanResult = withContext(Dispatchers.IO) {
        _progress.value = 0
        runScanCommand(
            cmd = TrainerProto.TCMD_SCAN_NEXT,
            vtype = TrainerProto.TVT_NONE,
            filter = filter,
            addr = 0L,
            value = value,
        )
    }

    /**
     * TCMD_AOB_SCAN — scan r-x and rw memory regions for [pattern] (with [mask]).
     *
     * Each byte in [mask] controls matching: 0xFF means "this byte must equal the
     * corresponding [pattern] byte"; 0x00 means wildcard (the "??" token in
     * CheatEngine notation).
     *
     * [dataOffset] is the SIGNED byte offset added to each hit's start address to
     * yield the data address surfaced in [ScanResult.addresses].  A value of 0L means
     * "surface the pattern's own start address".
     *
     * [vtype] is stored in cmd_vtype so the caller can later READ/FREEZE the resolved
     * addresses without having to supply the type again.
     *
     * Use [TrainerProto.parseAob] to convert a CheatEngine-style AOB string into
     * the (pattern, mask) pair before calling this function.
     *
     * Returns a [ScanResult] in all cases:
     * - success == true  → TST_READY or TST_TOO_MANY (as with normal scans)
     * - TST_NO_MATCH     → count == 0, addresses is empty; success == false, but NOT an error
     * - TST_ERROR        → errorMsg is set
     * - timeout / unavailable → errorMsg is set
     */
    suspend fun aobScan(
        pattern: ByteArray,
        mask: ByteArray,
        dataOffset: Long,
        vtype: Int,
    ): ScanResult = withContext(Dispatchers.IO) {
        if (pattern.size != mask.size || pattern.isEmpty() || pattern.size > TrainerProto.TRAINER_PATTERN_CAP) {
            return@withContext ScanResult.error("Invalid AOB pattern length")
        }
        _progress.value = 0
        runAobScanCommand(
            pattern = pattern,
            mask = mask,
            dataOffset = dataOffset,
            vtype = vtype,
        )
    }

    /**
     * TCMD_READ — read one address.
     * Returns the raw bits, or null on error / unavailable.
     */
    suspend fun read(addr: Long, vtype: Int): Long? = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_READ,
            vtype = vtype,
            addr = addr,
            value = 0L,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        }?.let { _ ->
            shm.getLong(OFF_RESP_VALUE)
        }
    }

    /**
     * TCMD_WRITE — write [value] (raw bits) to [addr] once.
     */
    suspend fun write(addr: Long, value: Long, vtype: Int): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_WRITE,
            vtype = vtype,
            addr = addr,
            value = value,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    /**
     * TCMD_FREEZE — add/replace a frozen address (worker continuously re-writes the value).
     */
    suspend fun freeze(addr: Long, value: Long, vtype: Int): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_FREEZE,
            vtype = vtype,
            addr = addr,
            value = value,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    /**
     * TCMD_UNFREEZE — remove a frozen address.
     * Pass [addr] == 0L to clear all frozen addresses.
     */
    suspend fun unfreeze(addr: Long): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_UNFREEZE,
            addr = addr,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    /**
     * TCMD_PATCH — write [patchBytes] to [addr] in the target process and snapshot
     * the original bytes so they can be restored later via [restore].
     *
     * Typical use-case: NOP out an instruction, flip a branch, or zero a register
     * save site.  The worker saves the original bytes keyed on [addr]; a subsequent
     * [restore] call with the same address will undo the patch exactly.
     *
     * [patchBytes] must be non-empty and no longer than 32 bytes.  Larger patches
     * should be split into 32-byte chunks and applied sequentially.
     *
     * Returns true on TST_READY (patch applied); false on TST_ERROR, timeout, or
     * validation failure.
     */
    suspend fun patch(addr: Long, patchBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (patchBytes.isEmpty() || patchBytes.size > 32) {
            Log.w(TAG, "patch: invalid patchBytes length ${patchBytes.size} (must be 1..32)")
            return@withContext false
        }
        runCommand(
            cmd = TrainerProto.TCMD_PATCH,
            addr = addr,
            timeoutMs = TIMEOUT_SHORT_MS,
            patchLen = patchBytes.size,
            patchBytes = patchBytes,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    /**
     * TCMD_RESTORE — restore the original bytes at [addr] that were saved by a
     * prior [patch] command.
     *
     * Pass [addr] == 0L to restore ALL patches recorded by the worker in one shot.
     *
     * Returns true on TST_READY (restore applied); false on TST_ERROR or timeout.
     */
    suspend fun restore(addr: Long): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_RESTORE,
            addr = addr,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    /**
     * TCMD_RESET — drop the current result set and all frozen addresses.
     */
    suspend fun reset(): Boolean = withContext(Dispatchers.IO) {
        runCommand(
            cmd = TrainerProto.TCMD_RESET,
            timeoutMs = TIMEOUT_SHORT_MS,
        ) { status ->
            status == TrainerProto.TST_READY
        } ?: false
    }

    // -------------------------------------------------------------------------
    // Clean-up
    // -------------------------------------------------------------------------

    fun close() {
        try {
            raf.close()
        } catch (_: Exception) {}
        available = false
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Core command dispatch + response wait (serialised by [mutex]).
     *
     * Writes all command fields, bumps cmd_seq, then busy-polls resp_seq
     * every POLL_INTERVAL_MS until it changes or [timeoutMs] elapses.
     *
     * [predicate] receives resp_status and must return true for a "success"
     * result; the function returns the predicate result, or null on timeout /
     * unavailability.
     *
     * [patchLen] and [patchBytes] are optional — supply them only for
     * TCMD_PATCH.  When non-null/non-zero they are written into the
     * cmd_patch_len (OFF_CMD_PATCH_LEN) and cmd_pattern (OFF_CMD_PATTERN)
     * fields BEFORE cmd_seq is bumped, so the worker sees them atomically.
     * All existing callers omit these params (defaults: 0 / null) and are
     * therefore completely unaffected.
     */
    private suspend fun <T> runCommand(
        cmd: Int,
        vtype: Int = TrainerProto.TVT_NONE,
        filter: Int = TrainerProto.TFLT_EXACT,
        addr: Long = 0L,
        value: Long = 0L,
        timeoutMs: Long,
        patchLen: Int = 0,
        patchBytes: ByteArray? = null,
        predicate: (Int) -> T,
    ): T? = mutex.withLock {
        // Bail out early if the mmap failed at init time
        val buf = shm

        // Verify proto version is present (worker has initialised the region)
        val version = buf.getInt(OFF_PROTO_VERSION)
        if (version != 0 && version != TrainerProto.TRAINER_PROTO_VERSION) {
            Log.w(TAG, "Proto version mismatch: got $version, expected ${TrainerProto.TRAINER_PROTO_VERSION}")
            return@withLock null
        }

        // Snapshot current resp_seq before issuing the command
        val oldRespSeq = buf.getInt(OFF_RESP_SEQ)

        // Write command params — order matters: write fields before bumping cmd_seq
        buf.putInt(OFF_CMD, cmd)
        buf.putInt(OFF_CMD_VTYPE, vtype)
        buf.putInt(OFF_CMD_FILTER, filter)
        buf.putLong(OFF_CMD_ADDR, addr)
        buf.putLong(OFF_CMD_VALUE, value)

        // Write patch-block fields when supplied (TCMD_PATCH only).
        // Absolute-index puts do not alter the buffer's position marker.
        if (patchBytes != null) {
            buf.putInt(OFF_CMD_PATCH_LEN, patchLen)
            for (i in patchBytes.indices) {
                buf.put(OFF_CMD_PATTERN + i, patchBytes[i])
            }
        }

        // Bump cmd_seq — this is the "ring the doorbell" step for the worker
        val newCmdSeq = buf.getInt(OFF_CMD_SEQ) + 1
        buf.putInt(OFF_CMD_SEQ, newCmdSeq)

        // Busy-poll resp_seq until it changes or we time out
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val curRespSeq = buf.getInt(OFF_RESP_SEQ)
            if (curRespSeq != oldRespSeq) {
                val status = buf.getInt(OFF_RESP_STATUS)
                return@withLock predicate(status)
            }
        }

        Log.w(TAG, "Timeout waiting for resp_seq after cmd=$cmd (${timeoutMs}ms)")
        null
    }

    /**
     * Specialised scan dispatcher that also updates [_progress] while polling.
     */
    private suspend fun runScanCommand(
        cmd: Int,
        vtype: Int,
        filter: Int,
        addr: Long,
        value: Long,
    ): ScanResult = mutex.withLock {
        val buf = shm

        val version = buf.getInt(OFF_PROTO_VERSION)
        if (version != 0 && version != TrainerProto.TRAINER_PROTO_VERSION) {
            return@withLock ScanResult.error("Proto version mismatch")
        }

        val oldRespSeq = buf.getInt(OFF_RESP_SEQ)

        buf.putInt(OFF_CMD, cmd)
        buf.putInt(OFF_CMD_VTYPE, vtype)
        buf.putInt(OFF_CMD_FILTER, filter)
        buf.putLong(OFF_CMD_ADDR, addr)
        buf.putLong(OFF_CMD_VALUE, value)

        val newCmdSeq = buf.getInt(OFF_CMD_SEQ) + 1
        buf.putInt(OFF_CMD_SEQ, newCmdSeq)

        val deadline = System.currentTimeMillis() + TIMEOUT_SCAN_MS
        // The worker bumps resp_seq repeatedly during a scan with status
        // TST_SCANNING (so we can render a progress bar). Those are NOT the
        // terminal response — keep waiting through them and only return on a
        // terminal status. Track the last seq we observed so each new bump is
        // detected without treating an intermediate progress tick as the result.
        var lastSeq = oldRespSeq
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            // Update the progress flow so UI can display a progress bar
            _progress.value = buf.getInt(OFF_RESP_PROGRESS)

            val curRespSeq = buf.getInt(OFF_RESP_SEQ)
            if (curRespSeq != lastSeq) {
                lastSeq = curRespSeq
                val status = buf.getInt(OFF_RESP_STATUS)

                // Intermediate progress tick — not the final response. Keep waiting.
                if (status == TrainerProto.TST_SCANNING) {
                    continue
                }

                val count = buf.getInt(OFF_RESP_COUNT)
                val errCode = buf.getInt(OFF_RESP_ERROR)

                _progress.value = 100

                return@withLock when (status) {
                    TrainerProto.TST_READY, TrainerProto.TST_TOO_MANY -> {
                        val cap = minOf(count, TrainerProto.TRAINER_RESULT_CAP)
                        val addresses = LongArray(cap) { i ->
                            buf.getLong(OFF_RESULTS + i * Long.SIZE_BYTES)
                        }
                        ScanResult(
                            status = status,
                            count = count,
                            addresses = addresses,
                            tooMany = status == TrainerProto.TST_TOO_MANY,
                            errorCode = 0,
                        )
                    }
                    TrainerProto.TST_ERROR -> ScanResult.error("Worker error code $errCode", errCode)
                    else -> ScanResult.error("Unexpected status $status")
                }
            }
        }

        _progress.value = 0
        ScanResult.error("Timeout waiting for scan response (${TIMEOUT_SCAN_MS}ms)")
    }

    /**
     * Specialised AOB-scan dispatcher — mirrors [runScanCommand] exactly but also
     * writes the pattern / mask block (OFF_CMD_PATTERN_LEN, OFF_CMD_PATTERN,
     * OFF_CMD_MASK) before bumping cmd_seq.
     *
     * Terminal status handling:
     * - TST_READY / TST_TOO_MANY — resolved addresses are read from results[], same as a
     *   regular scan.
     * - TST_NO_MATCH — returned as a normal (non-error) ScanResult with count == 0 so the
     *   caller can distinguish "no hits" from a protocol error.
     * - TST_SCANNING — intermediate progress tick; keep waiting (same as runScanCommand).
     * - TST_ERROR / anything else — treated as error.
     *
     * [MappedByteBuffer.put(index, byte)] does NOT alter the buffer position and is safe
     * to call from the coroutine's IO thread while the mutex is held.
     */
    private suspend fun runAobScanCommand(
        pattern: ByteArray,
        mask: ByteArray,
        dataOffset: Long,
        vtype: Int,
    ): ScanResult = mutex.withLock {
        val buf = shm

        val version = buf.getInt(OFF_PROTO_VERSION)
        if (version != 0 && version != TrainerProto.TRAINER_PROTO_VERSION) {
            return@withLock ScanResult.error("Proto version mismatch")
        }

        val oldRespSeq = buf.getInt(OFF_RESP_SEQ)

        // Write standard command params
        buf.putInt(OFF_CMD, TrainerProto.TCMD_AOB_SCAN)
        buf.putInt(OFF_CMD_VTYPE, vtype)
        buf.putInt(OFF_CMD_FILTER, TrainerProto.TFLT_EXACT)
        buf.putLong(OFF_CMD_ADDR, dataOffset)  // doubles as signed data offset for AOB_SCAN
        buf.putLong(OFF_CMD_VALUE, 0L)

        // Write AOB pattern block (off 768+) — absolute index puts, position-safe
        buf.putInt(OFF_CMD_PATTERN_LEN, pattern.size)
        // cmd_patch_len left as-is (reserved, worker ignores for AOB_SCAN)
        for (i in pattern.indices) {
            buf.put(OFF_CMD_PATTERN + i, pattern[i])
        }
        for (i in mask.indices) {
            buf.put(OFF_CMD_MASK + i, mask[i])
        }

        // Bump cmd_seq — "ring the doorbell" — must happen AFTER all param writes
        val newCmdSeq = buf.getInt(OFF_CMD_SEQ) + 1
        buf.putInt(OFF_CMD_SEQ, newCmdSeq)

        val deadline = System.currentTimeMillis() + TIMEOUT_SCAN_MS
        // Track the last resp_seq we observed so we detect each new bump from the worker
        // (including intermediate TST_SCANNING progress ticks).
        var lastSeq = oldRespSeq
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            // Update the progress flow so UI can display a progress bar
            _progress.value = buf.getInt(OFF_RESP_PROGRESS)

            val curRespSeq = buf.getInt(OFF_RESP_SEQ)
            if (curRespSeq != lastSeq) {
                lastSeq = curRespSeq
                val status = buf.getInt(OFF_RESP_STATUS)

                // Intermediate progress tick — not the final response. Keep waiting.
                if (status == TrainerProto.TST_SCANNING) {
                    continue
                }

                val count = buf.getInt(OFF_RESP_COUNT)
                val errCode = buf.getInt(OFF_RESP_ERROR)

                _progress.value = 100

                return@withLock when (status) {
                    TrainerProto.TST_READY, TrainerProto.TST_TOO_MANY -> {
                        val cap = minOf(count, TrainerProto.TRAINER_RESULT_CAP)
                        val addresses = LongArray(cap) { i ->
                            buf.getLong(OFF_RESULTS + i * Long.SIZE_BYTES)
                        }
                        ScanResult(
                            status = status,
                            count = count,
                            addresses = addresses,
                            tooMany = status == TrainerProto.TST_TOO_MANY,
                            errorCode = 0,
                        )
                    }
                    TrainerProto.TST_NO_MATCH -> {
                        // Zero matches — valid terminal state, not an error
                        ScanResult(
                            status = TrainerProto.TST_NO_MATCH,
                            count = 0,
                            addresses = LongArray(0),
                            tooMany = false,
                            errorCode = 0,
                        )
                    }
                    TrainerProto.TST_ERROR -> ScanResult.error("Worker error code $errCode", errCode)
                    else -> ScanResult.error("Unexpected status $status")
                }
            }
        }

        _progress.value = 0
        ScanResult.error("Timeout waiting for AOB scan response (${TIMEOUT_SCAN_MS}ms)")
    }

    // -------------------------------------------------------------------------
    // Companion — factory
    // -------------------------------------------------------------------------

    companion object {

        private const val TAG = "TrainerShm"

        // Busy-poll tick rate — coarse enough to be cheap, fine enough to feel responsive
        private const val POLL_INTERVAL_MS = 50L

        // Scans can take several seconds on large game processes
        private const val TIMEOUT_SCAN_MS = 30_000L

        // All other commands are fast (read/write/freeze/unfreeze/reset/ping)
        private const val TIMEOUT_SHORT_MS = 2_000L

        // connect() retry policy — the native worker may take a moment to claim
        // ownership and write proto_version after game launch; retry patiently.
        private const val CONNECT_ATTEMPTS = 5
        private const val CONNECT_RETRY_DELAY_MS = 200L

        // ---- Byte offsets mirrored from trainer_protocol.h (OFF_* table) ----
        // proto_version  @ 0  (int32)
        private const val OFF_PROTO_VERSION = 0
        // cmd_seq        @ 4  (int32, futex)
        private const val OFF_CMD_SEQ = 4
        // resp_seq       @ 8  (int32, futex)
        private const val OFF_RESP_SEQ = 8
        // cmd            @ 12 (int32)
        private const val OFF_CMD = 12
        // cmd_vtype      @ 16 (int32)
        private const val OFF_CMD_VTYPE = 16
        // cmd_filter     @ 20 (int32)
        private const val OFF_CMD_FILTER = 20
        // cmd_addr       @ 24 (int64)
        private const val OFF_CMD_ADDR = 24
        // cmd_value      @ 32 (int64)
        private const val OFF_CMD_VALUE = 32
        // resp_status    @ 40 (int32)
        private const val OFF_RESP_STATUS = 40
        // resp_error     @ 44 (int32)
        private const val OFF_RESP_ERROR = 44
        // resp_progress  @ 48 (int32)
        private const val OFF_RESP_PROGRESS = 48
        // resp_count     @ 52 (int32)
        private const val OFF_RESP_COUNT = 52
        // resp_value     @ 56 (int64)
        private const val OFF_RESP_VALUE = 56
        // results[0]     @ 64 (int64[TRAINER_RESULT_CAP])  ; ends at 576
        private const val OFF_RESULTS = 64
        // owner_pid      @ 576 (int32) — native CAS's 0->pid for single-owner arbitration (v3)
        internal const val OFF_OWNER_PID = 576
        // owner_heartbeat @ 580 (int32) — native increments every ~50ms while alive (v3)
        internal const val OFF_OWNER_HEARTBEAT = 580
        // cmd_pattern_len @ 768 (int32) — active AOB signature length (<= TRAINER_PATTERN_CAP)
        private const val OFF_CMD_PATTERN_LEN = 768
        // cmd_patch_len   @ 772 (int32) — reserved for TCMD_PATCH
        private const val OFF_CMD_PATCH_LEN = 772
        // cmd_pattern     @ 776 (uint8[256]) — AOB signature bytes ; ends at 1032
        private const val OFF_CMD_PATTERN = 776
        // cmd_mask        @ 1032 (uint8[256]) — 0xFF=must-match, 0x00=wildcard ; ends at 1288
        private const val OFF_CMD_MASK = 1032

        /**
         * Create a [TrainerShm] instance for the given app [context].
         *
         * Returns null only on a real mmap / IO failure (feature disabled, lib not
         * loaded, permissions issue).  Callers should treat null as "feature
         * unavailable" rather than an unrecoverable error.
         *
         * **Stale-proto-version handling:** if a trainer.mem file was left on disk
         * from a previous app version with an older proto_version (e.g. 1 or 2) the
         * runCommand gate (`version != 0 && version != TRAINER_PROTO_VERSION`) would
         * reject every command with the new version (3).  To prevent this we reset
         * proto_version to 0 when the on-disk value is present but does NOT match
         * the current version.  The native worker overwrites it with 3 once it
         * claims ownership, so the gate will then accept commands normally.
         *
         * Note: only proto_version is zeroed here.  The native side owns
         * owner_pid / owner_heartbeat arbitration; Android MUST NOT write those.
         *
         * After create(), callers MUST call [TrainerShm.connect] to establish
         * availability and wait for the native worker to initialise.
         */
        fun create(context: Context): TrainerShm? {
            return try {
                val dir = File(context.filesDir, "trainer_shm")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val memFile = File(dir, "trainer.mem")
                val raf = RandomAccessFile(memFile, "rw")
                raf.setLength(TrainerProto.TRAINER_SHM_SIZE.toLong())
                val buf = raf.channel.map(
                    FileChannel.MapMode.READ_WRITE,
                    0L,
                    TrainerProto.TRAINER_SHM_SIZE.toLong(),
                )
                buf.order(ByteOrder.LITTLE_ENDIAN)

                // Stale proto-version check: if the on-disk value is non-zero but does
                // not match the current protocol version, zero it so the command gate
                // treats the header as "uninitialised — wait for the native worker to
                // set it" rather than "version mismatch — reject all commands".
                // Do NOT touch owner_pid or owner_heartbeat — the native side owns those.
                val onDiskVersion = buf.getInt(OFF_PROTO_VERSION)
                if (onDiskVersion != 0 && onDiskVersion != TrainerProto.TRAINER_PROTO_VERSION) {
                    Log.i(TAG, "Stale proto_version $onDiskVersion on disk; resetting to 0 so worker can re-claim")
                    buf.putInt(OFF_PROTO_VERSION, 0)
                }

                TrainerShm(buf, raf)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create trainer shm — trainer feature disabled", e)
                null
            }
        }
    }
}

// =============================================================================
// Protocol constants — mirror EXACTLY from trainer_protocol.h
// =============================================================================

/**
 * All constants from trainer_protocol.h re-declared for Kotlin callers.
 *
 * Value-type helpers [floatToRawBits] / [doubleToRawBits] / [rawBitsToFloat] /
 * [rawBitsToDouble] convert between Kotlin primitive values and the raw 64-bit
 * field expected by cmd_value / returned in resp_value.
 */
object TrainerProto {

    // ---- Wire constants ----
    const val TRAINER_PROTO_VERSION: Int = 3
    const val TRAINER_SHM_SIZE: Int = 4096
    const val TRAINER_RESULT_CAP: Int = 64
    const val TRAINER_FREEZE_CAP: Int = 32
    /** Max bytes in an AOB pattern / mask (mirrors TRAINER_PATTERN_CAP in trainer_protocol.h). */
    const val TRAINER_PATTERN_CAP: Int = 256

    // ---- enum trainer_vtype ----
    const val TVT_NONE: Int = 0
    const val TVT_I8: Int = 1
    const val TVT_U8: Int = 2
    const val TVT_I16: Int = 3
    const val TVT_U16: Int = 4
    const val TVT_I32: Int = 5
    const val TVT_U32: Int = 6
    const val TVT_I64: Int = 7
    const val TVT_U64: Int = 8
    const val TVT_F32: Int = 9
    const val TVT_F64: Int = 10

    // ---- enum trainer_cmd ----
    const val TCMD_NOP: Int = 0
    const val TCMD_SCAN_NEW: Int = 1
    const val TCMD_SCAN_NEXT: Int = 2
    const val TCMD_READ: Int = 3
    const val TCMD_WRITE: Int = 4
    const val TCMD_FREEZE: Int = 5
    const val TCMD_UNFREEZE: Int = 6
    const val TCMD_RESET: Int = 7
    const val TCMD_PING: Int = 8
    /** Scan r-x + rw regions for a byte-pattern; cmd_addr is a signed data offset applied
     *  to each hit to yield the address surfaced in results[]. */
    const val TCMD_AOB_SCAN: Int = 9
    /** RESERVED — write cmd_patch_len bytes (cmd_pattern) to cmd_addr (snapshot for RESTORE). */
    const val TCMD_PATCH: Int = 10
    /** RESERVED — restore bytes saved by a prior PATCH; cmd_addr == 0 restores all. */
    const val TCMD_RESTORE: Int = 11

    // ---- enum trainer_filter ----
    const val TFLT_EXACT: Int = 0
    const val TFLT_INCREASED: Int = 1
    const val TFLT_DECREASED: Int = 2
    const val TFLT_CHANGED: Int = 3
    const val TFLT_UNCHANGED: Int = 4

    // ---- enum trainer_status ----
    const val TST_IDLE: Int = 0
    const val TST_READY: Int = 1
    const val TST_SCANNING: Int = 2
    const val TST_ERROR: Int = 3
    const val TST_TOO_MANY: Int = 4
    /** AOB_SCAN found zero matches (resp_count == 0). Not an error — the caller checks count. */
    const val TST_NO_MATCH: Int = 5

    // ---- AOB parsing helper ----

    /**
     * Parse a CheatEngine-style AOB string into a (pattern, mask) pair ready for
     * [TrainerShm.aobScan].
     *
     * Format: space-separated tokens. Each token is one of:
     * - A two-hex-digit byte: `"48"`, `"8B"`, `"0f"` (upper- or lower-case).
     *   Produces pattern byte = parsed value, mask byte = 0xFF.
     * - A wildcard: `"?"`, `"??"`, or `"*"`.
     *   Produces pattern byte = 0x00, mask byte = 0x00.
     *
     * Multiple consecutive spaces and surrounding whitespace are tolerated.
     *
     * Returns null if:
     * - Any token does not match a two-hex-digit byte or a recognised wildcard.
     * - The resulting sequence is empty.
     * - The resulting sequence exceeds [TRAINER_PATTERN_CAP] bytes.
     *
     * Example:
     * ```
     * val (pat, mask) = TrainerProto.parseAob("48 8B 05 ?? ?? ?? ?? 48") ?: return
     * ```
     */
    fun parseAob(aob: String): Pair<ByteArray, ByteArray>? {
        val tokens = aob.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > TRAINER_PATTERN_CAP) return null

        val patternBytes = ByteArray(tokens.size)
        val maskBytes = ByteArray(tokens.size)

        for ((i, token) in tokens.withIndex()) {
            when {
                token == "?" || token == "??" || token == "*" -> {
                    patternBytes[i] = 0x00.toByte()
                    maskBytes[i] = 0x00.toByte()
                }
                token.length == 2 && token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> {
                    patternBytes[i] = token.toInt(16).toByte()
                    maskBytes[i] = 0xFF.toByte()
                }
                else -> return null  // malformed token
            }
        }

        return Pair(patternBytes, maskBytes)
    }

    // ---- Value encoding helpers ----

    /**
     * Pack a Float into the raw 64-bit cmd_value field.
     * The worker interprets the low 32 bits as IEEE 754 single precision.
     */
    fun floatToRawBits(value: Float): Long =
        java.lang.Float.floatToRawIntBits(value).toLong() and 0xFFFFFFFFL

    /**
     * Pack a Double into the raw 64-bit cmd_value field (full 64 bits used).
     */
    fun doubleToRawBits(value: Double): Long =
        java.lang.Double.doubleToRawLongBits(value)

    /**
     * Decode a raw 64-bit resp_value as Float (low 32 bits only).
     */
    fun rawBitsToFloat(raw: Long): Float =
        java.lang.Float.intBitsToFloat((raw and 0xFFFFFFFFL).toInt())

    /**
     * Decode a raw 64-bit resp_value as Double.
     */
    fun rawBitsToDouble(raw: Long): Double =
        java.lang.Double.longBitsToDouble(raw)
}

// =============================================================================
// Result types
// =============================================================================

/**
 * The result of a scan command (TCMD_SCAN_NEW or TCMD_SCAN_NEXT).
 *
 * @property status     Raw TST_* status from the worker.
 * @property count      Total match count (may exceed TRAINER_RESULT_CAP).
 * @property addresses  Up to TRAINER_RESULT_CAP addresses from the result window.
 * @property tooMany    True when the total count exceeded TRAINER_RESULT_CAP.
 * @property errorCode  Non-zero when [status] == TST_ERROR.
 * @property errorMsg   Human-readable error description, non-null only on failure.
 */
data class ScanResult(
    val status: Int,
    val count: Int,
    val addresses: LongArray,
    val tooMany: Boolean,
    val errorCode: Int = 0,
    val errorMsg: String? = null,
) {
    val success: Boolean get() =
        status == TrainerProto.TST_READY || status == TrainerProto.TST_TOO_MANY

    companion object {
        fun error(msg: String, code: Int = 0) = ScanResult(
            status = TrainerProto.TST_ERROR,
            count = 0,
            addresses = LongArray(0),
            tooMany = false,
            errorCode = code,
            errorMsg = msg,
        )
    }

    // LongArray breaks the default equals/hashCode from data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanResult) return false
        return status == other.status &&
            count == other.count &&
            addresses.contentEquals(other.addresses) &&
            tooMany == other.tooMany &&
            errorCode == other.errorCode &&
            errorMsg == other.errorMsg
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + count
        result = 31 * result + addresses.contentHashCode()
        result = 31 * result + tooMany.hashCode()
        result = 31 * result + errorCode
        result = 31 * result + (errorMsg?.hashCode() ?: 0)
        return result
    }
}
