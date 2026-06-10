package app.gamenative.autotuner

import android.content.Context
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.IntentLaunchManager
import com.winlator.container.ContainerData
import com.winlator.core.ProcessHelper
import com.winlator.widget.FrameRating
import com.winlator.xenvironment.ImageFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File

// =============================================================================
//  PUBLIC API CONTRACT — READ THIS BEFORE WIRING THE UI
// =============================================================================
//
//  STEP 1 — Create the engine:
//      val engine = AutoTunerEngine(context, appId, goal, mode, measurementMode)
//
//  STEP 2 — Observe progress flow (collect on a lifecycle-aware scope):
//      engine.progressFlow.collect { progress -> updateUI(progress) }
//
//  STEP 3 — Feed lifecycle events back into the engine as they happen in XServerScreen:
//
//      a) When the game window appears (XServerScreen.onWindowMapped callback):
//           engine.trialController.onWindowMapped()
//
//      b) When a FrameRating instance is available for reading (pass it once per trial):
//           engine.trialController.provideFrameRating(frameRating)
//
//      c) When GuestProgramTerminated event fires:
//           engine.trialController.onGuestTerminated()
//
//      d) When the game session fully exits and cleanup is complete:
//           engine.trialController.onExitComplete()
//
//      e) For MANUAL measurement mode ONLY — when the user taps "Stop Recording":
//           engine.trialController.onManualStopRecording()
//
//  STEP 4 — Await the outcome:
//      val outcome = engine.awaitOutcome()   // suspend; returns after all trials
//
//  STEP 5 — To apply the winner (call from UI after user confirms):
//      engine.applyWinner(context)
//
//  STEP 6 — To cancel mid-sweep:
//      engine.cancel()
//
//  NOTE: The engine does NOT navigate or launch anything.  The UI layer owns
//  navigation.  The engine emits TunerProgress.READY_TO_LAUNCH events when it
//  needs the next trial launched, and waits for trialController callbacks before
//  proceeding.  The flow of control per trial is:
//
//    engine emits READY_TO_LAUNCH
//    → UI launches XServerScreen with trialConfig via applyAutoConfigOverride
//    → XServerScreen.onWindowMapped fires → call trialController.onWindowMapped()
//    → engine enters WARMUP (15 s)
//    → engine enters MEASURING (45 s auto / until manual stop)
//    → GuestProgramTerminated fires (optional) → trialController.onGuestTerminated()
//    → engine asks for teardown → UI pops XServerScreen → call trialController.onExitComplete()
//    → engine runs teardown, cooldown → emits next READY_TO_LAUNCH
// =============================================================================

/**
 * Measurement mode: how FPS data is collected during each trial.
 *
 * [AUTO]   : Engine reads fps_session.json after writing by FrameRating.writeSessionSummary().
 *            Hands-off; game runs at menu/early-game.
 * [MANUAL] : Engine records while the user plays; stops when user taps "Stop Recording".
 *            FrameRating must be passed via trialController.provideFrameRating().
 */
enum class MeasurementMode { AUTO, MANUAL }

/** Progress events emitted by AutoTunerEngine on progressFlow. */
sealed class TunerProgress {
    /** Sweep initialised; total trial count known. */
    data class Started(val total: Int, val estimatedMinutes: Int) : TunerProgress()

    /** Engine is preparing configuration for trial [trialIndex]. */
    data class Preparing(
        val trialIndex: Int,
        val total: Int,
        val description: String,
    ) : TunerProgress()

    /**
     * Config applied; UI MUST launch XServerScreen now, then call
     * trialController.onWindowMapped() when the game window appears.
     */
    data class ReadyToLaunch(
        val trialIndex: Int,
        val total: Int,
        val description: String,
        val trialConfig: ContainerData,
    ) : TunerProgress()

    /** Warmup period (discard initial FPS readings). */
    data class Warmup(
        val trialIndex: Int,
        val total: Int,
        val remainingSeconds: Int,
    ) : TunerProgress()

    /** Active measurement window. */
    data class Measuring(
        val trialIndex: Int,
        val total: Int,
        val remainingSeconds: Int,
        val currentFps: Float,
    ) : TunerProgress()

    /** Trial aborted early (crash/hang detected). */
    data class TrialAborted(
        val trialIndex: Int,
        val reason: TunerResult.TrialStatus,
    ) : TunerProgress()

    /** Cooldown between trials; optional GPU temp annotation. */
    data class Cooldown(
        val trialIndex: Int,
        val total: Int,
        val remainingSeconds: Int,
        val gpuTempC: Int,
    ) : TunerProgress()

    /** One trial completed; includes the result. */
    data class TrialComplete(
        val result: TunerResult,
        val bestSoFar: TunerResult?,
    ) : TunerProgress()

    /** All trials done; winner elected. */
    data class SweepComplete(val outcome: TunerOutcome) : TunerProgress()

    /** Unrecoverable error (sweep aborted). */
    data class Error(val message: String, val cause: Throwable? = null) : TunerProgress()
}

/** Final outcome returned once the sweep is complete. */
data class TunerOutcome(
    val goal: TunerGoal,
    val winner: TunerResult?,
    val allResults: List<TunerResult>,
    val rankedResults: List<TunerResult>,
    val totalTrials: Int,
    val completedTrials: Int,
)

/**
 * Callback interface that the UI layer (XServerScreen host) implements
 * to feed lifecycle events back to the engine.
 *
 * All methods are safe to call from any thread; the engine channels them
 * to the coroutine that is suspended waiting for each event.
 */
interface TunerTrialController {
    /** Call when XServerScreen.onWindowMapped fires (game window appeared). */
    fun onWindowMapped()

    /** Call when GuestProgramTerminated event fires. */
    fun onGuestTerminated()

    /**
     * Call when the game session fully exits and Wine cleanup is complete.
     * After this the engine will run its own teardown.
     */
    fun onExitComplete()

    /**
     * Provide the FrameRating instance once available.
     * For AUTO mode: call once per trial; the engine will invoke writeSessionSummary() itself.
     * For MANUAL mode: call to register the live FrameRating so the engine can poll it.
     */
    fun provideFrameRating(fr: FrameRating)

    /**
     * MANUAL mode only: user tapped "Stop Recording".
     * Engine will end the measurement window and proceed to teardown.
     */
    fun onManualStopRecording()

    /**
     * Called by the engine when it wants the currently running trial game session to be
     * closed programmatically.  The UI layer must invoke the same exit path that the
     * in-game overlay bar's "Exit Game" button uses (forceResumeIfSuspended + exit()),
     * so Wine teardown is clean and identical to a manual close.
     *
     * After the exit completes the UI must call [onExitComplete] as usual.
     */
    fun requestTrialExit()
}

// =============================================================================
//  GPU TEMPERATURE HELPER
// =============================================================================

private object GpuTemp {
    private const val KGSL_TEMP_PATH = "/sys/class/kgsl/kgsl-3d0/temp"

    /**
     * Reads GPU temperature from the kgsl sysfs node.
     * Returns -1 if the file is unreadable or the value is out of range.
     * The raw value is in milli-degrees C on most kernels; divide by 1000.
     * Guard: if raw < 1000 assume it was already in degrees C.
     */
    fun readTempC(): Int {
        return try {
            val raw = File(KGSL_TEMP_PATH).readText().trim().toLongOrNull() ?: return -1
            val degrees = if (raw >= 1000L) (raw / 1000L).toInt() else raw.toInt()
            if (degrees in 0..120) degrees else -1
        } catch (_: Exception) {
            -1
        }
    }
}

// =============================================================================
//  FPS SESSION FILE READER
// =============================================================================

private object FpsSessionReader {
    /**
     * Reads fps_session.json written by FrameRating.writeSessionSummary().
     * Returns null if the file does not exist or is unparseable.
     */
    fun read(context: Context): FpsSessionData? {
        return try {
            val imageFs = ImageFs.find(context)
            val file = File(imageFs.getTmpDir(), "fps_session.json")
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            FpsSessionData(
                avgFps = json.optDouble("avg_fps", 0.0).toFloat(),
                maxFps = json.optInt("max_fps", 0).toFloat(),
                minFps = json.optInt("min_fps", 0).toFloat(),
                readings = json.optInt("readings", 0),
                lengthSec = json.optDouble("length_sec", 0.0).toFloat(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context) {
        try {
            val imageFs = ImageFs.find(context)
            File(imageFs.getTmpDir(), "fps_session.json").delete()
        } catch (_: Exception) { /* best-effort */ }
    }
}

private data class FpsSessionData(
    val avgFps: Float,
    val maxFps: Float,
    val minFps: Float,
    val readings: Int,
    val lengthSec: Float,
)

// =============================================================================
//  ENGINE
// =============================================================================

/**
 * AutoTunerEngine — coordinate-descent config sweep engine.
 *
 * @param context         Application context (for file/container access).
 * @param appId           Game app ID (e.g. "STEAM_12345").
 * @param goal            Optimisation objective.
 * @param mode            Sweep depth (Quick / Standard / Thorough).
 * @param measurementMode AUTO (hands-off FPS read) or MANUAL (user plays + stops).
 */
class AutoTunerEngine(
    private val context: Context,
    private val appId: String,
    private val goal: TunerGoal,
    private val mode: SweepMode,
    private val measurementMode: MeasurementMode = MeasurementMode.AUTO,
) {
    private val TAG = "AutoTunerEngine"

    // -------------------------------------------------------------------------
    // Timing constants (all in seconds)
    // -------------------------------------------------------------------------
    private val WARMUP_SEC = 15
    private val AUTO_MEASURE_SEC = 45
    private val WINDOW_WAIT_TIMEOUT_SEC = 90L   // max seconds to wait for game window
    private val LAUNCH_HANG_THRESHOLD_SEC = 20L // early abort if crash within this window
    private val TEARDOWN_WAIT_SEC = 10L
    private val BETWEEN_TRIAL_COOLDOWN_SEC = 15
    private val THERMAL_GATE_TEMP_C = 80
    private val THERMAL_COOLDOWN_SEC = 30

    // -------------------------------------------------------------------------
    // Trial controller (UI wires into this)
    // -------------------------------------------------------------------------
    private val windowMappedChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private val guestTerminatedChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private val exitCompleteChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private val frameRatingChannel = Channel<FrameRating>(capacity = Channel.CONFLATED)
    private val manualStopChannel = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * The [TunerTrialController] that the UI/XServerScreen host must call into.
     * Assign callbacks from this object — see interface documentation.
     */
    val trialController: TunerTrialController = object : TunerTrialController {
        override fun onWindowMapped() {
            windowMappedChannel.trySend(Unit)
        }
        override fun onGuestTerminated() {
            guestTerminatedChannel.trySend(Unit)
        }
        override fun onExitComplete() {
            exitCompleteChannel.trySend(Unit)
        }
        override fun provideFrameRating(fr: FrameRating) {
            frameRatingChannel.trySend(fr)
        }
        override fun onManualStopRecording() {
            manualStopChannel.trySend(Unit)
        }
        override fun requestTrialExit() {
            // Implemented by the UI layer (AutoTunerViewModel / PluviaMain).
            // The default no-op here is never called because AutoTunerViewModel
            // replaces trialController.requestTrialExit with a real implementation
            // via the requestExitCallback set below.
        }
    }

    // -------------------------------------------------------------------------
    // Auto-exit callback — set by the UI layer after construction.
    // The engine calls this when it wants the currently-running trial game to be
    // closed programmatically (measurement window expired, crash detected, or
    // manual-stop in MANUAL mode).  The UI must implement it using the SAME exit
    // path as the in-game overlay bar's "Exit Game" button so Wine teardown is
    // identical to a manual close.  After the exit completes the UI must call
    // trialController.onExitComplete() as usual.
    // -------------------------------------------------------------------------
    var onRequestTrialExit: (() -> Unit)? = null

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------
    private var cancelled = false
    private var outcomeResult: TunerOutcome? = null
    private val allResults = mutableListOf<TunerResult>()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the full sweep as a [Flow] of [TunerProgress] events.
     *
     * Collect this flow from a coroutine scope that owns the sweep lifetime.
     * The flow completes (or throws [CancellationException]) when the sweep ends.
     *
     * The final event is always [TunerProgress.SweepComplete] (on success) or
     * [TunerProgress.Error] (on unrecoverable failure).
     */
    val progressFlow: Flow<TunerProgress> = flow {
        try {
            runSweep { progress -> emit(progress) }
        } catch (ce: CancellationException) {
            Timber.tag(TAG).w("Sweep cancelled")
            throw ce
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Sweep failed with exception")
            emit(TunerProgress.Error("Sweep failed: ${e.message}", e))
        }
    }

    /** Cancels the in-progress sweep. Safe to call from any thread. */
    fun cancel() {
        cancelled = true
        // Request the UI to close any currently-running trial game.
        onRequestTrialExit?.invoke()
        // Unblock any suspended channels so the sweep coroutine can exit
        windowMappedChannel.trySend(Unit)
        guestTerminatedChannel.trySend(Unit)
        exitCompleteChannel.trySend(Unit)
        manualStopChannel.trySend(Unit)
    }

    /**
     * Asks the UI to programmatically close the running trial.
     * Safe to call from any thread; only fires when the callback is registered.
     */
    private fun triggerTrialExit() {
        Timber.tag(TAG).d("triggerTrialExit — invoking onRequestTrialExit callback")
        onRequestTrialExit?.invoke()
    }

    /**
     * Applies the winning config to the container (saveToDisk=true) and stores
     * the result summary in the container's extraData under key "autotuner_result_v1".
     *
     * Call this only after the user confirms — from the UI, after [progressFlow] completes.
     * Must be called on a background thread (performs disk I/O).
     */
    suspend fun applyWinner(context: Context) {
        val outcome = outcomeResult ?: return
        val winner = outcome.winner ?: return
        withContext(Dispatchers.IO) {
            try {
                val container = ContainerUtils.getContainer(context, appId)
                ContainerUtils.applyToContainer(context, container, winner.config, saveToDisk = true)

                // Persist summary in extraData["autotuner_result_v1"]
                val summary = JSONObject().apply {
                    put("goal", outcome.goal.name)
                    put("mode", mode.name)
                    put("description", winner.description)
                    put("avgFps", winner.avgFps.toDouble())
                    put("minFps", winner.minFps.toDouble())
                    put("maxFps", winner.maxFps.toDouble())
                    put("fpsStdDev", winner.fpsStdDev.toDouble())
                    put("gpuTempStartC", winner.gpuTempStartC)
                    put("gpuTempEndC", winner.gpuTempEndC)
                    put("throttleSuspect", winner.throttleSuspect)
                    put("totalTrials", outcome.totalTrials)
                    put("completedTrials", outcome.completedTrials)
                    put("timestamp", System.currentTimeMillis())
                }
                container.putExtra("autotuner_result_v1", summary.toString())
                container.saveData()
                Timber.tag(TAG).i("Winner applied and stored in extraData for $appId")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to apply winner for $appId")
                throw e
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal sweep orchestration
    // -------------------------------------------------------------------------

    private suspend fun runSweep(emit: suspend (TunerProgress) -> Unit) {
        val totalEstimated = SweepPlan.estimatedTrialCount(mode)
        val estimatedMinutes = when (mode) {
            SweepMode.QUICK -> 10
            SweepMode.STANDARD -> 20
            SweepMode.THOROUGH -> 35
        }
        emit(TunerProgress.Started(totalEstimated, estimatedMinutes))

        // Baseline from device defaults (DeviceProfileDetector already ran)
        val baseline = ContainerUtils.getDefaultContainerData()
        var currentBest: ContainerData = baseline
        var currentBestResult: TunerResult? = null

        val dimensions = SweepPlan.dimensionsForMode(mode)
        var globalTrialIndex = 0

        // --- First pass: iterate each dimension ---
        for (dim in dimensions) {
            checkCancelled()
            val dimTrials = SweepPlan.buildDimensionTrials(dim, currentBest, passIndex = 0)
            val dimResults = mutableListOf<TunerResult>()

            for (trial in dimTrials) {
                checkCancelled()
                val result = runTrial(
                    trialDef = trial,
                    trialIndex = globalTrialIndex,
                    total = totalEstimated,
                    emit = emit,
                )
                allResults.add(result)
                dimResults.add(result)
                globalTrialIndex++

                if (result.isUsable) {
                    val ranked = rankResults(dimResults.filter { it.isUsable }, goal)
                    if (ranked.isNotEmpty()) {
                        currentBestResult = ranked.first()
                        currentBest = currentBestResult.config
                    }
                }
                emit(TunerProgress.TrialComplete(result, currentBestResult))
            }
            // After dimension: update currentBest from all stable results so far
            val stableSoFar = allResults.filter { it.isUsable }
            if (stableSoFar.isNotEmpty()) {
                currentBestResult = rankResults(stableSoFar, goal).first()
                currentBest = currentBestResult.config
            }
        }

        // --- Second pass (THOROUGH only): re-sweep driver dimension with locked-in best ---
        if (mode == SweepMode.THOROUGH) {
            checkCancelled()
            val pass2Trials = SweepPlan.buildDimensionTrials(
                TunerDimension.GRAPHICS_DRIVER,
                currentBest,
                passIndex = 1,
            )
            for (trial in pass2Trials) {
                checkCancelled()
                val result = runTrial(
                    trialDef = trial,
                    trialIndex = globalTrialIndex,
                    total = totalEstimated,
                    emit = emit,
                )
                allResults.add(result)
                globalTrialIndex++
                emit(TunerProgress.TrialComplete(result, currentBestResult))
            }
        }

        // --- Final ranking ---
        val ranked = rankResults(allResults.filter { it.isUsable }, goal)
        val winner = ranked.firstOrNull() ?: allResults.minByOrNull { it.avgFps }
        val scoredResults = allResults.map { r ->
            val pos = ranked.indexOf(r)
            val score = if (pos >= 0) (ranked.size - pos).toFloat() else 0f
            r.copy(goalScore = score)
        }

        val outcome = TunerOutcome(
            goal = goal,
            winner = winner,
            allResults = scoredResults,
            rankedResults = ranked,
            totalTrials = totalEstimated,
            completedTrials = globalTrialIndex,
        )
        outcomeResult = outcome

        // Clean up any lingering override
        IntentLaunchManager.clearTemporaryOverride(appId)

        emit(TunerProgress.SweepComplete(outcome))
    }

    /**
     * Runs a single trial end-to-end:
     *   PREPARING → config applied (auto override) → READY_TO_LAUNCH emitted
     *   → wait for window → WARMUP → MEASURING → teardown → COOLDOWN
     */
    private suspend fun runTrial(
        trialDef: TrialDefinition,
        trialIndex: Int,
        total: Int,
        emit: suspend (TunerProgress) -> Unit,
    ): TunerResult {
        try {
            emit(TunerProgress.Preparing(trialIndex, total, trialDef.description))

            // 1. Read GPU temp at trial start
            val gpuTempStart = GpuTemp.readTempC()

            // Thermal gate: if GPU is hot, insert a cooldown before starting
            if (gpuTempStart >= THERMAL_GATE_TEMP_C) {
                Timber.tag(TAG).w("GPU temp ${gpuTempStart}C >= ${THERMAL_GATE_TEMP_C}C — thermal cooldown before trial $trialIndex")
                for (s in THERMAL_COOLDOWN_SEC downTo 1) {
                    checkCancelled()
                    emit(TunerProgress.Cooldown(trialIndex, total, s, GpuTemp.readTempC()))
                    delay(1_000)
                }
            }

            // 2. Apply config override (in-memory / silent, no disk write)
            withContext(Dispatchers.IO) {
                IntentLaunchManager.applyAutoConfigOverride(context, appId, trialDef.config)
            }

            // Delete any stale fps_session.json from a previous trial
            withContext(Dispatchers.IO) { FpsSessionReader.delete(context) }

            // Clear stale channel state before this trial
            windowMappedChannel.tryReceive()
            guestTerminatedChannel.tryReceive()
            exitCompleteChannel.tryReceive()
            manualStopChannel.tryReceive()
            var frameRating: FrameRating? = frameRatingChannel.tryReceive().getOrNull()

            // 3. Signal UI to launch — UI must call applyAutoConfigOverride + navigate to XServerScreen
            emit(TunerProgress.ReadyToLaunch(trialIndex, total, trialDef.description, trialDef.config))

            // 4. Wait for game window (or crash/timeout)
            val windowAppeared = withTimeoutOrNull(WINDOW_WAIT_TIMEOUT_SEC * 1_000) {
                // Race: window mapped vs crash vs cancellation
                var windowSeen = false
                while (!windowSeen && !cancelled) {
                    when {
                        windowMappedChannel.tryReceive().isSuccess -> windowSeen = true
                        guestTerminatedChannel.tryReceive().isSuccess -> return@withTimeoutOrNull false
                        else -> delay(200)
                    }
                }
                windowSeen
            } ?: false

            if (!windowAppeared || cancelled) {
                // Crashed or hung before window appeared
                val alreadyCrashed = guestTerminatedChannel.tryReceive().isSuccess
                val status = if (alreadyCrashed) {
                    TunerResult.TrialStatus.CRASHED
                } else {
                    TunerResult.TrialStatus.HUNG
                }
                // If the game is hung (no GuestProgramTerminated), request a programmatic exit
                // so Wine doesn't remain as a zombie. If it already crashed, teardown handles it.
                if (!alreadyCrashed && !cancelled) {
                    Timber.tag(TAG).w("Trial $trialIndex hung/timed-out — requesting programmatic trial exit")
                    triggerTrialExit()
                }
                emit(TunerProgress.TrialAborted(trialIndex, status))
                teardown(emit, trialIndex, total, gpuTempStart)
                return TunerResult(
                    config = trialDef.config,
                    description = trialDef.description,
                    status = status,
                    gpuTempStartC = gpuTempStart,
                    gpuTempEndC = GpuTemp.readTempC(),
                    trialIndex = trialIndex,
                )
            }

            // Try to get FrameRating if not already provided
            frameRating = frameRatingChannel.tryReceive().getOrNull() ?: frameRating

            // 5. WARMUP — discard first 15 s of FPS readings
            for (s in WARMUP_SEC downTo 1) {
                checkCancelled()
                // Early-abort: crash within LAUNCH_HANG_THRESHOLD_SEC of window appearing
                if (guestTerminatedChannel.tryReceive().isSuccess) {
                    emit(TunerProgress.TrialAborted(trialIndex, TunerResult.TrialStatus.CRASHED))
                    teardown(emit, trialIndex, total, gpuTempStart)
                    return TunerResult(
                        config = trialDef.config,
                        description = trialDef.description,
                        status = TunerResult.TrialStatus.CRASHED,
                        gpuTempStartC = gpuTempStart,
                        gpuTempEndC = GpuTemp.readTempC(),
                        trialIndex = trialIndex,
                    )
                }
                emit(TunerProgress.Warmup(trialIndex, total, s))
                delay(1_000)
            }

            // Reset FrameRating after warmup so we only measure the measurement window
            withContext(Dispatchers.Main.immediate) {
                frameRating?.reset()
            }

            // 6. MEASUREMENT WINDOW
            var crashed = false
            if (measurementMode == MeasurementMode.AUTO) {
                for (s in AUTO_MEASURE_SEC downTo 1) {
                    checkCancelled()
                    if (guestTerminatedChannel.tryReceive().isSuccess) {
                        crashed = true
                        break
                    }
                    val currentFps = frameRating?.getCurrentFPS() ?: 0f
                    emit(TunerProgress.Measuring(trialIndex, total, s, currentFps))
                    delay(1_000)
                }
            } else {
                // MANUAL: wait until user taps stop or game crashes
                var measuring = true
                var measureSec = 0
                while (measuring && !cancelled) {
                    when {
                        manualStopChannel.tryReceive().isSuccess -> measuring = false
                        guestTerminatedChannel.tryReceive().isSuccess -> {
                            crashed = true; measuring = false
                        }
                        else -> {
                            val currentFps = frameRating?.getCurrentFPS() ?: 0f
                            emit(TunerProgress.Measuring(trialIndex, total, 0, currentFps))
                            delay(1_000)
                            measureSec++
                        }
                    }
                }
            }

            // 7a. AUTO-CLOSE the trial game — fire the same exit path the overlay bar uses.
            //     Only needed when the game is still running (i.e. measurement window expired
            //     or MANUAL stop — not when the game already crashed on its own).
            if (!crashed) {
                Timber.tag(TAG).i("Trial $trialIndex measurement done — requesting programmatic trial exit")
                triggerTrialExit()
            }

            // 7. Write FPS summary (AUTO mode: call writeSessionSummary; read the JSON)
            withContext(Dispatchers.Main.immediate) {
                frameRating?.writeSessionSummary()
            }
            delay(500) // brief wait for async file write

            // 8. Read FPS data
            val fpsData: FpsSessionData? = withContext(Dispatchers.IO) {
                FpsSessionReader.read(context)
            }

            // Fallback to in-memory FrameRating values if JSON not available
            val avgFps = fpsData?.avgFps ?: frameRating?.getAvgFPS() ?: 0f
            val maxFps = fpsData?.maxFps ?: frameRating?.getAvgFPS() ?: 0f
            val minFps = fpsData?.minFps ?: 0f
            val stdDev = frameRating?.getFpsStdDev() ?: 0f

            val gpuTempEnd = GpuTemp.readTempC()
            val throttleSuspect = gpuTempStart >= 0 && gpuTempEnd >= 0 &&
                (gpuTempEnd - gpuTempStart) >= TunerResult.THROTTLE_DELTA_C

            val status = when {
                crashed -> TunerResult.TrialStatus.CRASHED
                avgFps < TunerResult.PLAYABLE_FPS_THRESHOLD -> TunerResult.TrialStatus.UNSTABLE
                else -> TunerResult.TrialStatus.STABLE
            }

            Timber.tag(TAG).i(
                "Trial $trialIndex complete: status=$status avgFps=$avgFps " +
                    "stdDev=$stdDev throttle=$throttleSuspect desc=${trialDef.description}",
            )

            teardown(emit, trialIndex, total, gpuTempStart)

            return TunerResult(
                config = trialDef.config,
                description = trialDef.description,
                status = status,
                avgFps = avgFps.coerceIn(0f, 9999f),
                minFps = minFps.coerceIn(0f, 9999f),
                maxFps = maxFps.coerceIn(0f, 9999f),
                fpsStdDev = stdDev.coerceIn(0f, 9999f),
                gpuTempStartC = gpuTempStart,
                gpuTempEndC = gpuTempEnd,
                throttleSuspect = throttleSuspect,
                trialIndex = trialIndex,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Trial $trialIndex threw exception — marking as CRASHED")
            try { teardown(emit, trialIndex, total, -1) } catch (_: Exception) {}
            return TunerResult(
                config = trialDef.config,
                description = trialDef.description,
                status = TunerResult.TrialStatus.CRASHED,
                trialIndex = trialIndex,
            )
        }
    }

    /**
     * Post-trial teardown: signal UI to exit, kill stale Wine processes, cooldown.
     *
     * The UI must call trialController.onExitComplete() after it has navigated away
     * from XServerScreen and ProcessHelper.hardKillStaleWineProcesses() is safe to call.
     */
    private suspend fun teardown(
        emit: suspend (TunerProgress) -> Unit,
        trialIndex: Int,
        total: Int,
        gpuTempStart: Int,
    ) {
        // Clear the override so the next launch doesn't inherit it
        IntentLaunchManager.clearTemporaryOverride(appId)

        // Wait for the UI to signal exit complete (navigated away from XServerScreen)
        val exited = withTimeoutOrNull(TEARDOWN_WAIT_SEC * 1_000) {
            while (exitCompleteChannel.tryReceive().isFailure && !cancelled) {
                delay(200)
            }
            true
        }
        if (exited == null) {
            Timber.tag(TAG).w("Trial $trialIndex: UI did not signal onExitComplete within ${TEARDOWN_WAIT_SEC}s")
        }

        // Hard-kill any lingering Wine processes
        withContext(Dispatchers.IO) {
            try {
                ProcessHelper.hardKillStaleWineProcesses()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "hardKillStaleWineProcesses threw — continuing")
            }
            // Additional wait for process list to drain
            val deadline = System.currentTimeMillis() + TEARDOWN_WAIT_SEC * 1_000
            while (ProcessHelper.listRunningWineProcesses().isNotEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(200)
            }
        }

        // Inter-trial cooldown
        val gpuTemp = GpuTemp.readTempC()
        for (s in BETWEEN_TRIAL_COOLDOWN_SEC downTo 1) {
            if (cancelled) break
            emit(TunerProgress.Cooldown(trialIndex, total, s, gpuTemp))
            delay(1_000)
        }
    }

    // -------------------------------------------------------------------------
    // Goal-specific ranking
    // -------------------------------------------------------------------------

    /**
     * Ranks a list of usable (STABLE) results according to [goal].
     *
     * LOW_END: pre-filter to results above the playable threshold that use "System"
     *          driver or the lowest VRAM; fall back to all stable if none qualify.
     * HIGH_END: pre-filter to Wrapper/Turnip results; fall back to all stable if none.
     * MAX_FPS / MAX_STABILITY: sort all stable results directly.
     */
    internal fun rankResults(stable: List<TunerResult>, goal: TunerGoal): List<TunerResult> {
        if (stable.isEmpty()) return emptyList()
        return when (goal) {
            TunerGoal.LOW_END -> {
                // Prefer System driver AND above playable threshold
                val lightAndPlayable = stable.filter {
                    it.avgFps >= TunerResult.PLAYABLE_FPS_THRESHOLD &&
                        it.config.graphicsDriver.equals("System", ignoreCase = true)
                }
                val candidates = lightAndPlayable.ifEmpty {
                    // Fall back: anything above threshold
                    stable.filter { it.avgFps >= TunerResult.PLAYABLE_FPS_THRESHOLD }
                }.ifEmpty { stable }
                candidates.sortedWith(goal.comparator)
            }

            TunerGoal.HIGH_END -> {
                // Prefer Wrapper/Turnip results
                val wrapperStable = stable.filter {
                    it.config.graphicsDriver.equals("Wrapper", ignoreCase = true)
                }
                wrapperStable.ifEmpty { stable }.sortedWith(goal.comparator)
            }

            else -> stable.sortedWith(goal.comparator)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("AutoTunerEngine cancelled")
    }
}
