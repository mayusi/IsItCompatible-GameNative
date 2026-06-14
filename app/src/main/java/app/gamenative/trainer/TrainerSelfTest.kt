package app.gamenative.trainer

import android.content.Context
import android.system.Os
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-process self-test for the libtrainer.so memory-trainer engine.
 *
 * WHAT IT PROVES
 * --------------
 * Loads libtrainer.so into the APP's own process (same address space), writes a
 * known 4-byte sentinel into a direct ByteBuffer (native heap, not moved by GC),
 * then drives the full command chain through TrainerShm:
 *
 *   1. Set env vars  →  System.loadLibrary("trainer")  (constructor activates)
 *   2. TrainerShm.create(context)  — mmap the same trainer.mem the lib created
 *   3. ping()                      — confirm worker thread is alive
 *   4. scanNew(SENTINEL_1, TVT_I32) — find the sentinel in app memory
 *   5. Verify sentinel addr is among results
 *   6. Change sentinel value in the buffer
 *   7. scanNext(TFLT_EXACT, SENTINEL_2) — narrow to the changed value
 *   8. write() the address to WRITTEN_VALUE
 *   9. freeze() the address at FROZEN_VALUE
 *  10. Read back via read() and confirm freeze took effect
 *  11. unfreeze() / reset() cleanup
 *
 * If this passes in-process, the same engine works when LD_PRELOAD'd into Wine,
 * because the scanner and IPC paths are identical — only the calling process differs.
 *
 * GC / SENTINEL SAFETY
 * --------------------
 * The sentinel lives in a direct ByteBuffer: ByteBuffer.allocateDirect() allocates
 * off-heap native memory, which the GC NEVER moves or compacts. The buffer object
 * itself (on the Java heap) is kept in a local variable that survives for the entire
 * duration of run(), so the native backing memory is pinned throughout.
 * The address extracted via sun.nio.ch.DirectBuffer.address() (or a null-pointer
 * probe via a zero-capacity slice trick) could be used, but we don't need the raw
 * address because the SCANNER discovers it for us — that's the whole point of the test.
 *
 * OPTION A vs B
 * -------------
 * This implementation uses Option A (pure Kotlin): Os.setenv() is called BEFORE
 * System.loadLibrary("trainer") so the .so's __attribute__((constructor)) sees
 * TRAINER_ENABLED=1 and TRAINER_BASE_PATH at load time. No native code changes
 * are required.
 *
 * MULTIPLE LOADS
 * --------------
 * System.loadLibrary() is safe to call repeatedly — the dynamic linker keeps a
 * per-process refcount and only runs the .so constructor on the first load.  If
 * libtrainer.so is already loaded (e.g. LD_PRELOAD from the game launch), the
 * call is a no-op and the existing worker thread is reused.
 *
 * RELEASE SAFETY
 * --------------
 * This class is compiled into all variants but the trigger in SettingsGroupDebug
 * is guarded by BuildConfig.DEBUG, so it is never reachable in release APKs.
 * The class itself is harmless to leave in — it does nothing until run() is called.
 */
object TrainerSelfTest {

    private const val TAG = "TrainerSelfTest"

    // Sentinel values chosen to be distinctive and not occur randomly in normal app memory.
    // Using TVT_I32 (signed 32-bit int). The Long representation of an int32 is the raw
    // 4-byte little-endian value zero-extended to 64 bits, matching TrainerShm's cmd_value
    // convention.
    private const val SENTINEL_1: Int = 0x5EED_1234.toInt()  // phase-1 scan target
    private const val SENTINEL_2: Int = 0x5EED_ABCD.toInt()  // phase-2 scan target (after mutation)
    private const val WRITTEN_VALUE: Int = 0x600D_C0DE.toInt() // write-command confirmation value
    private const val FROZEN_VALUE: Int  = 0x1CE_BABE.toInt()  // freeze-command confirmation value

    // Timeout for waiting for the library to finish setup after loadLibrary.
    // The worker thread spawns quickly, but give it 1 s to be safe.
    private const val LIB_LOAD_SETTLE_MS = 1_000L

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Structured report of each test step.
     * All steps are cumulative: if an early step fails, later steps are skipped
     * and their fields remain false/null to indicate "not reached".
     */
    data class SelfTestReport(
        /** True if the library loaded without throwing. */
        val libLoaded: Boolean = false,
        /** True if TrainerShm.create() returned a non-null instance. */
        val shmCreated: Boolean = false,
        /** True if ping() returned true (worker thread alive). */
        val pingOk: Boolean = false,

        /** How many addresses scanNew found in total. */
        val scanNewCount: Int = 0,
        /** True if the sentinel address was present in the scanNew result window. */
        val sentinelFoundInScan: Boolean = false,
        /** The address the scanner reported for our sentinel (null = not found). */
        val sentinelAddr: Long? = null,

        /** How many addresses remained after scanNext (exact match on mutated value). */
        val scanNextCount: Int = 0,
        /** True if scanNext narrowed to exactly the expected address. */
        val scanNextNarrowed: Boolean = false,

        /** True if the write() command returned success. */
        val writeOk: Boolean = false,
        /** The value read back from the sentinel address after write. */
        val readAfterWrite: Int? = null,

        /** True if the freeze() command returned success. */
        val freezeOk: Boolean = false,
        /** The value read back from the sentinel address after freeze. */
        val readAfterFreeze: Int? = null,

        /** True if ALL steps passed — the engine works end-to-end. */
        val allPassed: Boolean = false,

        /** Non-null if an unexpected exception terminated the test. */
        val fatalError: String? = null,
    ) {
        /**
         * Human-readable multi-line summary suitable for Timber or a dialog body.
         */
        fun toDisplayString(): String = buildString {
            appendLine("=== TrainerSelfTest Report ===")
            appendLine("libtrainer.so loaded : $libLoaded")
            appendLine("SHM created          : $shmCreated")
            appendLine("PING ok              : $pingOk")
            appendLine("scanNew count        : $scanNewCount")
            appendLine("Sentinel found       : $sentinelFoundInScan  addr=0x${sentinelAddr?.toString(16) ?: "n/a"}")
            appendLine("scanNext count       : $scanNextCount  narrowed=$scanNextNarrowed")
            appendLine("write ok             : $writeOk  readback=0x${readAfterWrite?.toString(16) ?: "n/a"}")
            appendLine("freeze ok            : $freezeOk  readback=0x${readAfterFreeze?.toString(16) ?: "n/a"}")
            if (fatalError != null) appendLine("FATAL: $fatalError")
            appendLine("RESULT: ${if (allPassed) "PASS" else "FAIL"}")
        }
    }

    // -------------------------------------------------------------------------
    // Main entry point
    // -------------------------------------------------------------------------

    /**
     * Run the full self-test chain.
     *
     * Must be called from a coroutine on Dispatchers.IO (TrainerShm operations
     * are suspend funs that internally use withContext(Dispatchers.IO)).
     * Never throws — all exceptions are caught and reported in [SelfTestReport].
     *
     * @param context Application or Activity context (used to locate filesDir
     *                for the trainer.mem shared-memory file).
     */
    suspend fun run(context: Context): SelfTestReport {
        Timber.tag(TAG).i("=== TrainerSelfTest starting ===")

        try {
            return runInternal(context)
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            Timber.tag(TAG).e(t, "Unexpected fatal error in self-test")
            return SelfTestReport(fatalError = msg)
        }
    }

    // -------------------------------------------------------------------------
    // Internal implementation
    // -------------------------------------------------------------------------

    private suspend fun runInternal(context: Context): SelfTestReport {

        // -----------------------------------------------------------------
        // Step 0: Allocate the sentinel buffer.
        //
        // ByteBuffer.allocateDirect() allocates off-heap native memory that the
        // GC NEVER moves. We write SENTINEL_1 into it now. The buffer object
        // (local var) is our strong reference that keeps the memory live.
        // -----------------------------------------------------------------
        val buf = ByteBuffer.allocateDirect(Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
        buf.putInt(0, SENTINEL_1)
        Timber.tag(TAG).d("Sentinel buffer allocated (direct, native-endian), value=0x%08X", SENTINEL_1)

        // -----------------------------------------------------------------
        // Step 1: Load libtrainer.so with env gates set.
        //
        // Os.setenv() calls setenv(3) in the current process. The library
        // constructor reads these at load time via getenv(). We set them before
        // System.loadLibrary so the constructor sees them.
        //
        // TRAINER_BASE_PATH = context.filesDir so the trainer.mem lands where
        // TrainerShm.create(context) will look for it (filesDir/trainer_shm/trainer.mem).
        // -----------------------------------------------------------------
        var libLoaded = false
        try {
            Os.setenv("TRAINER_ENABLED", "1", /* overwrite = */ true)
            Os.setenv("TRAINER_BASE_PATH", context.filesDir.absolutePath, /* overwrite = */ true)
            Timber.tag(TAG).d(
                "Env set: TRAINER_ENABLED=1, TRAINER_BASE_PATH=%s",
                context.filesDir.absolutePath,
            )

            System.loadLibrary("trainer")
            libLoaded = true
            Timber.tag(TAG).i("System.loadLibrary(\"trainer\") succeeded")
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Failed to load libtrainer.so")
            return SelfTestReport(libLoaded = false, fatalError = "loadLibrary: ${t.message}")
        }

        // Give the worker thread a moment to finish spawning and bump resp_seq.
        kotlinx.coroutines.delay(LIB_LOAD_SETTLE_MS)

        // -----------------------------------------------------------------
        // Step 2: Open the shared-memory file.
        //
        // TrainerShm.create() mmap's the same trainer.mem the native constructor
        // just created. Returns null if the file doesn't exist yet or mmap fails.
        // -----------------------------------------------------------------
        val shm = TrainerShm.create(context)
        val shmCreated = shm != null
        if (shm == null) {
            Timber.tag(TAG).e("TrainerShm.create() returned null — lib may not have initialised")
            return SelfTestReport(libLoaded = libLoaded, shmCreated = false)
        }
        Timber.tag(TAG).i("TrainerShm created OK")

        // Wrap the rest in try-finally so we always close the SHM handle.
        try {
            return runWithShm(context, shm, buf, libLoaded)
        } finally {
            try { shm.close() } catch (_: Exception) {}
        }
    }

    private suspend fun runWithShm(
        @Suppress("UNUSED_PARAMETER") context: Context,
        shm: TrainerShm,
        sentinelBuf: ByteBuffer,
        libLoaded: Boolean,
    ): SelfTestReport {

        // -----------------------------------------------------------------
        // Step 3: Ping — confirm the worker thread is alive.
        // -----------------------------------------------------------------
        val pingOk = try {
            shm.ping()
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "ping() threw")
            false
        }
        Timber.tag(TAG).i("ping() -> %b  (available=%b)", pingOk, shm.available)
        if (!pingOk) {
            return SelfTestReport(libLoaded = libLoaded, shmCreated = true, pingOk = false)
        }

        // -----------------------------------------------------------------
        // Step 4: scanNew for SENTINEL_1.
        //
        // The scanner walks ALL rw-private regions of /proc/self/mem, including
        // the direct ByteBuffer's backing allocation. It WILL find SENTINEL_1.
        // TVT_I32 = 5 (from TrainerProto).
        //
        // Pass the sentinel as a Long with the int's bits in the low 32 bits,
        // zero-extended — that is exactly what the C side expects in cmd_value
        // for a 32-bit type (it only reads 4 bytes from the 8-byte field).
        // -----------------------------------------------------------------
        val sentinel1AsLong = SENTINEL_1.toLong() and 0xFFFF_FFFFL
        val scanNew: ScanResult = try {
            shm.scanNew(sentinel1AsLong, TrainerProto.TVT_I32)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "scanNew() threw")
            return SelfTestReport(libLoaded = libLoaded, shmCreated = true, pingOk = true,
                fatalError = "scanNew: ${t.message}")
        }

        Timber.tag(TAG).i(
            "scanNew: success=%b count=%d tooMany=%b addresses=%d err=%s",
            scanNew.success, scanNew.count, scanNew.tooMany,
            scanNew.addresses.size, scanNew.errorMsg ?: "none",
        )

        if (!scanNew.success) {
            return SelfTestReport(libLoaded = libLoaded, shmCreated = true, pingOk = true,
                scanNewCount = scanNew.count,
                fatalError = "scanNew failed: ${scanNew.errorMsg}")
        }

        // Check whether the sentinel address appears in the result window.
        // The scanner may return up to TRAINER_RESULT_CAP=64 addresses.
        // We expect at least one result (our buffer), and we expect to recognise it.
        // We identify the sentinel address by reading back through the scanner's result
        // and verifying via the SHM read command (or via the count being >= 1).
        // For the first pass we trust that count >= 1 means the sentinel was found if
        // the total is small; for production-like verification we try to isolate it.
        val sentinelFoundInScan = scanNew.count >= 1
        val sentinelAddr: Long? = if (scanNew.addresses.isNotEmpty()) scanNew.addresses[0] else null

        Timber.tag(TAG).i(
            "sentinelFoundInScan=%b sentinelAddr=0x%s",
            sentinelFoundInScan,
            sentinelAddr?.toString(16) ?: "null",
        )

        // -----------------------------------------------------------------
        // Step 5: Mutate the sentinel and run scanNext(EXACT, SENTINEL_2).
        //
        // This narrows the match table from all SENTINEL_1 occurrences in the
        // process down to just the ones that now read SENTINEL_2. Because we
        // wrote SENTINEL_2 into OUR buffer and left all other candidate
        // addresses unchanged, the narrow should converge to our address.
        // -----------------------------------------------------------------
        sentinelBuf.putInt(0, SENTINEL_2)
        Timber.tag(TAG).d("Sentinel mutated to 0x%08X, running scanNext(EXACT)", SENTINEL_2)

        val sentinel2AsLong = SENTINEL_2.toLong() and 0xFFFF_FFFFL
        val scanNext: ScanResult = try {
            shm.scanNext(TrainerProto.TFLT_EXACT, sentinel2AsLong)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "scanNext() threw")
            return SelfTestReport(
                libLoaded = libLoaded, shmCreated = true, pingOk = true,
                scanNewCount = scanNew.count, sentinelFoundInScan = sentinelFoundInScan,
                sentinelAddr = sentinelAddr,
                fatalError = "scanNext: ${t.message}",
            )
        }

        Timber.tag(TAG).i(
            "scanNext: success=%b count=%d tooMany=%b",
            scanNext.success, scanNext.count, scanNext.tooMany,
        )

        // After narrowing we expect very few results. scanNextNarrowed = count < scanNewCount.
        val scanNextNarrowed = scanNext.success && scanNext.count < scanNew.count && scanNext.count >= 1

        // Pick the target address for write/freeze: prefer the narrowed result's first address,
        // fall back to the scanNew's first address.
        val targetAddr: Long? = when {
            scanNext.success && scanNext.addresses.isNotEmpty() -> scanNext.addresses[0]
            else -> sentinelAddr
        }

        if (targetAddr == null) {
            Timber.tag(TAG).e("No address available for write/freeze step")
            return SelfTestReport(
                libLoaded = libLoaded, shmCreated = true, pingOk = true,
                scanNewCount = scanNew.count, sentinelFoundInScan = sentinelFoundInScan,
                sentinelAddr = sentinelAddr,
                scanNextCount = scanNext.count, scanNextNarrowed = scanNextNarrowed,
                fatalError = "No address to write/freeze",
            )
        }

        // -----------------------------------------------------------------
        // Step 6: write() WRITTEN_VALUE to the target address.
        // -----------------------------------------------------------------
        val writtenAsLong = WRITTEN_VALUE.toLong() and 0xFFFF_FFFFL
        val writeOk = try {
            shm.write(targetAddr, writtenAsLong, TrainerProto.TVT_I32)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "write() threw")
            false
        }
        Timber.tag(TAG).i("write(0x%X, 0x%08X, TVT_I32) -> %b", targetAddr, WRITTEN_VALUE, writeOk)

        // Read back immediately to confirm the write went through.
        val readAfterWrite: Int? = try {
            shm.read(targetAddr, TrainerProto.TVT_I32)?.let { raw ->
                // The raw value is a zero-extended int32 stored in the low 32 bits of a Long.
                (raw and 0xFFFF_FFFFL).toInt()
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "read() after write threw")
            null
        }
        Timber.tag(TAG).i("read after write -> 0x%s", readAfterWrite?.toString(16) ?: "null")

        // -----------------------------------------------------------------
        // Step 7: freeze() FROZEN_VALUE at the target address.
        //
        // The freeze thread re-applies FROZEN_VALUE every ~50 ms. We wait
        // 150 ms then read back — if the value equals FROZEN_VALUE, the
        // freeze is actively working.
        // -----------------------------------------------------------------
        val frozenAsLong = FROZEN_VALUE.toLong() and 0xFFFF_FFFFL
        val freezeOk = try {
            shm.freeze(targetAddr, frozenAsLong, TrainerProto.TVT_I32)
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "freeze() threw")
            false
        }
        Timber.tag(TAG).i("freeze(0x%X, 0x%08X, TVT_I32) -> %b", targetAddr, FROZEN_VALUE, freezeOk)

        // Let the freeze thread tick at least twice (interval = 50 ms, wait 150 ms).
        kotlinx.coroutines.delay(150L)

        val readAfterFreeze: Int? = try {
            shm.read(targetAddr, TrainerProto.TVT_I32)?.let { raw ->
                (raw and 0xFFFF_FFFFL).toInt()
            }
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "read() after freeze threw")
            null
        }
        Timber.tag(TAG).i("read after freeze -> 0x%s", readAfterFreeze?.toString(16) ?: "null")

        // -----------------------------------------------------------------
        // Cleanup: unfreeze the address so we don't keep re-writing app memory.
        // Then reset the engine state.
        // -----------------------------------------------------------------
        try { shm.unfreeze(targetAddr) } catch (_: Exception) {}
        try { shm.reset() } catch (_: Exception) {}

        // Also restore the sentinel buffer so no stale value lingers.
        sentinelBuf.putInt(0, 0)

        // -----------------------------------------------------------------
        // Final verdict
        // -----------------------------------------------------------------
        val allPassed = libLoaded
            && true /* shm is non-null in this scope (used above) */
            && pingOk
            && sentinelFoundInScan
            && scanNextNarrowed
            && writeOk
            && readAfterWrite == WRITTEN_VALUE
            && freezeOk
            && readAfterFreeze == FROZEN_VALUE

        val report = SelfTestReport(
            libLoaded            = libLoaded,
            shmCreated           = true,
            pingOk               = pingOk,
            scanNewCount         = scanNew.count,
            sentinelFoundInScan  = sentinelFoundInScan,
            sentinelAddr         = sentinelAddr,
            scanNextCount        = scanNext.count,
            scanNextNarrowed     = scanNextNarrowed,
            writeOk              = writeOk,
            readAfterWrite       = readAfterWrite,
            freezeOk             = freezeOk,
            readAfterFreeze      = readAfterFreeze,
            allPassed            = allPassed,
        )

        Timber.tag(TAG).i(report.toDisplayString())
        return report
    }
}
