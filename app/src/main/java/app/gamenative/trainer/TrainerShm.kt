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
     */
    private suspend fun <T> runCommand(
        cmd: Int,
        vtype: Int = TrainerProto.TVT_NONE,
        filter: Int = TrainerProto.TFLT_EXACT,
        addr: Long = 0L,
        value: Long = 0L,
        timeoutMs: Long,
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
        // results[0]     @ 64 (int64[TRAINER_RESULT_CAP])
        private const val OFF_RESULTS = 64

        /**
         * Create a [TrainerShm] instance for the given app [context].
         *
         * Returns null if the mmap fails (feature disabled, lib not loaded yet,
         * permissions issue).  Callers should treat null as "feature unavailable"
         * rather than an unrecoverable error.
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
    const val TRAINER_PROTO_VERSION: Int = 1
    const val TRAINER_SHM_SIZE: Int = 4096
    const val TRAINER_RESULT_CAP: Int = 64
    const val TRAINER_FREEZE_CAP: Int = 32

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
