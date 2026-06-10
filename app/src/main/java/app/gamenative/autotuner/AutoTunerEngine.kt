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
//      // Optional: pass customWeights for TunerGoal.CUSTOM
//      val engine = AutoTunerEngine(context, appId, TunerGoal.CUSTOM, mode, measurementMode,
//                                   customWeights = GoalWeights(0.5f, 0.3f, 0.1f, 0.1f))
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
    data class Started(
        val total: Int,
        val estimatedMinutes: Int,
        /** True if the device is discharging at sweep start (battery signal available). */
        val batteryAvailable: Boolean = false,
    ) : TunerProgress()

    /** Engine is preparing configuration for trial [trialIndex]. */
    data class Preparing(
        val trialIndex: Int,
        val total: Int,
        val description: String,
        /** Which run of this config (1-based). 1 when runsPerConfig == 1. */
        val runIndex: Int = 1,
        /** How many runs this config will be measured. 1 for QUICK. */
        val runsPerConfig: Int = 1,
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
        /** Which run of this config (1-based). 1 when runsPerConfig == 1. */
        val runIndex: Int = 1,
        /** How many runs this config will be measured. 1 for QUICK. */
        val runsPerConfig: Int = 1,
    ) : TunerProgress()

    /** Warmup period (discard initial FPS readings). */
    data class Warmup(
        val trialIndex: Int,
        val total: Int,
        val remainingSeconds: Int,
        /** Which run of this config (1-based). 1 when runsPerConfig == 1. */
        val runIndex: Int = 1,
        /** How many runs this config will be measured. 1 for QUICK. */
        val runsPerConfig: Int = 1,
    ) : TunerProgress()

    /** Active measurement window. */
    data class Measuring(
        val trialIndex: Int,
        val total: Int,
        val remainingSeconds: Int,
        val currentFps: Float,
        /** Which run of this config (1-based). 1 when runsPerConfig == 1. */
        val runIndex: Int = 1,
        /** How many runs this config will be measured. 1 for QUICK. */
        val runsPerConfig: Int = 1,
        /** Current GPU temperature in °C; -1 if unreadable. */
        val currentGpuTempC: Int = -1,
        /** Current power draw in watts; null if unavailable or charging. */
        val currentPowerW: Float? = null,
    ) : TunerProgress()

    /** Trial aborted early (crash/hang detected). */
    data class TrialAborted(
        val trialIndex: Int,
        val reason: TunerResult.TrialStatus,
        /** Which run of this config (1-based). 1 when runsPerConfig == 1. */
        val runIndex: Int = 1,
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
        /** Ranked results so far (all usable results, composite-scored). */
        val rankedSoFar: List<TunerResult> = emptyList(),
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
    /** True if battery drain signal was available during the sweep (device was discharging). */
    val batteryAvailable: Boolean = false,
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
//  BATTERY / POWER SENSOR HELPER
// =============================================================================

/**
 * Battery and power-draw sensor reader.
 *
 * All reads are wrapped in try/catch — these sysfs paths are world-readable on
 * standard Android kernels (no root required) but may not exist on all devices.
 * All methods return null / false on any failure.
 *
 * Confirmed readable on the Odin 2 Pro (MediaTek / Adreno 8-series) via adb;
 * standard Linux power-supply class paths.  Needs device verification on other
 * boards — see the DEVICE-VERIFICATION section in the engine notes below.
 *
 * Path reference (Linux kernel power_supply class):
 *   /sys/class/power_supply/battery/status       — "Discharging" | "Charging" | "Full" | …
 *   /sys/class/power_supply/battery/power_now    — instantaneous power in µW
 *   /sys/class/power_supply/battery/charge_counter — battery charge in µAh
 */
object BatteryReader {
    private const val BATTERY_PATH = "/sys/class/power_supply/battery"
    private const val STATUS_PATH = "$BATTERY_PATH/status"
    private const val POWER_NOW_PATH = "$BATTERY_PATH/power_now"
    private const val CHARGE_COUNTER_PATH = "$BATTERY_PATH/charge_counter"

    /** Returns true if the battery status file reads "Discharging". */
    fun isDischarging(): Boolean {
        return try {
            File(STATUS_PATH).readText().trim().equals("Discharging", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads instantaneous power draw in watts.
     *
     * Returns null if:
     *  - device is NOT "Discharging" (while charging, power_now reflects charger draw — unreliable)
     *  - the sysfs path does not exist or is unreadable
     *  - the value is outside the plausible range (0..100 W)
     *
     * NOTE: Must only be called/used when [isDischarging] is true; the Discharging guard
     * is also applied inline here for safety.
     */
    fun readPowerW(): Float? {
        return try {
            if (!isDischarging()) return null
            val rawUW = File(POWER_NOW_PATH).readText().trim().toLongOrNull() ?: return null
            val watts = rawUW / 1_000_000f
            if (watts in 0f..100f) watts else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the battery charge counter in µAh.
     * Returns null if the path is unreadable.
     * Use start/end delta to compute energy consumed over a trial.
     */
    fun readChargeCounterUAh(): Int? {
        return try {
            File(CHARGE_COUNTER_PATH).readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
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
 * @param goal            Optimisation objective (7 intents; see TunerGoal).
 * @param mode            Sweep depth (Probe / Quick / Standard / Thorough).
 * @param measurementMode AUTO (hands-off FPS read) or MANUAL (user plays + stops).
 * @param customWeights   Optional GoalWeights for TunerGoal.CUSTOM; ignored for other goals.
 *
 * DEVICE VERIFICATION NEEDED:
 * ─────────────────────────────────────────────────────────────────────────────
 * The following features have NOT been exercised on a physical device and need
 * a real sweep to confirm correctness:
 *
 *   1. BatteryReader.readPowerW() / isDischarging()
 *      Path: /sys/class/power_supply/battery/power_now (µW)
 *      Confirmed readable on Odin 2 Pro via adb; unverified on other boards.
 *      Must verify: value is µW (divide by 1e6 gives watts in 1-10 W range for mobile),
 *      path exists, world-readable without root, and only returns valid data while
 *      discharging (not while charging from a PD charger).
 *
 *   2. BatteryReader.readChargeCounterUAh()
 *      Path: /sys/class/power_supply/battery/charge_counter
 *      Delta (start-end) used as fallback energy proxy. Needs verification that the
 *      counter decrements (not increments) as charge is consumed.
 *
 *   3. COMPAT_PROBE early-abort-on-boot
 *      The engine sets bootSucceeded=true when the game window appears in the probe
 *      measure window. Verify: (a) windowMappedChannel fires reliably within measureSec
 *      for booting games; (b) early-abort actually skips remaining probe trials correctly.
 *
 *   4. Composite normalisation (rankResultsComposite)
 *      maxAvgFps / maxStdDev / maxPowerW / maxTempEndC are computed across all usable
 *      results. If only 1 result is usable, all norms = 1.0 → composite = weight total.
 *      Verify this edge case is handled gracefully.
 *
 *   5. FPS_BATTERY no-battery fallback
 *      When FPS_BATTERY is chosen but the device is charging, battNormScore stays null
 *      and weights fall back to GoalWeights.FPS_BATTERY_FALLBACK.
 *      Verify: UI warning appears; fallback weights produce sensible ranking.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class AutoTunerEngine(
    private val context: Context,
    private val appId: String,
    private val goal: TunerGoal,
    private val mode: SweepMode,
    private val measurementMode: MeasurementMode = MeasurementMode.AUTO,
    /** Optional user-supplied weights for TunerGoal.CUSTOM. Ignored for other goals. */
    private val customWeights: GoalWeights? = null,
) {
    private val TAG = "AutoTunerEngine"

    // -------------------------------------------------------------------------
    // Timing constants (all in seconds)
    // -------------------------------------------------------------------------
    private val WARMUP_SEC = if (mode == SweepMode.PROBE) SweepPlan.PROBE_WARMUP_SEC else 15
    private val AUTO_MEASURE_SEC = if (mode == SweepMode.PROBE) SweepPlan.PROBE_MEASURE_SEC else 45
    private val WINDOW_WAIT_TIMEOUT_SEC = 90L   // max seconds to wait for game window
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
                    // v1.11.0 additions
                    winner.avgPowerW?.let { put("avgPowerW", it.toDouble()) }
                    put("fpsNormScore", winner.fpsNormScore.toDouble())
                    put("stabNormScore", winner.stabNormScore.toDouble())
                    winner.battNormScore?.let { put("battNormScore", it.toDouble()) }
                    put("tempNormScore", winner.tempNormScore.toDouble())
                    put("batteryAvailable", outcome.batteryAvailable)
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
        val estimatedMinutes = SweepPlan.estimatedMinutes(mode)

        // Check battery availability at sweep start
        val batteryAvailable = BatteryReader.isDischarging()
        Timber.tag(TAG).i("Sweep start: batteryAvailable=$batteryAvailable goal=${goal.name} mode=${mode.name}")

        if (goal.requiresBatterySignal && !batteryAvailable) {
            Timber.tag(TAG).w(
                "Goal ${goal.name} requires battery signal but device is not discharging. " +
                    "Battery norms will be null; goal falls back to no-battery weights.",
            )
        }

        emit(TunerProgress.Started(totalEstimated, estimatedMinutes, batteryAvailable))

        if (goal.isFastProbe || mode == SweepMode.PROBE) {
            runProbeSweep(emit, batteryAvailable)
            return
        }

        // --- Normal coordinate-descent sweep ---
        val baseline = ContainerUtils.getDefaultContainerData()
        var currentBest: ContainerData = baseline
        var currentBestResult: TunerResult? = null

        val dimensions = SweepPlan.dimensionsForMode(mode)
        var globalTrialIndex = 0

        // --- First pass: iterate each dimension with multi-run averaging ---
        for (dim in dimensions) {
            checkCancelled()
            val dimTrials = SweepPlan.buildDimensionTrials(dim, currentBest, passIndex = 0)

            val aggregated = runDimensionWithAveraging(
                dimTrials = dimTrials,
                globalTrialIndex = globalTrialIndex,
                total = totalEstimated,
                emit = emit,
                currentBestResult = currentBestResult,
                batteryAvailable = batteryAvailable,
            )
            globalTrialIndex += dimTrials.size * mode.runsPerConfig
            allResults.addAll(aggregated)

            // After dimension: pick best from all stable aggregated results so far
            val stableSoFar = allResults.filter { it.isUsable }
            if (stableSoFar.isNotEmpty()) {
                currentBestResult = rankResults(stableSoFar, goal, batteryAvailable).first()
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
            val pass2Aggregated = runDimensionWithAveraging(
                dimTrials = pass2Trials,
                globalTrialIndex = globalTrialIndex,
                total = totalEstimated,
                emit = emit,
                currentBestResult = currentBestResult,
                batteryAvailable = batteryAvailable,
            )
            globalTrialIndex += pass2Trials.size * mode.runsPerConfig
            allResults.addAll(pass2Aggregated)
        }

        // --- Final composite ranking ---
        val ranked = rankResults(allResults.filter { it.isUsable }, goal, batteryAvailable)
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
            batteryAvailable = batteryAvailable,
        )
        outcomeResult = outcome

        // Clean up any lingering override
        IntentLaunchManager.clearTemporaryOverride(appId)

        emit(TunerProgress.SweepComplete(outcome))
    }

    // -------------------------------------------------------------------------
    // PROBE SWEEP — flat archetype list, early-abort-on-boot
    // -------------------------------------------------------------------------

    /**
     * Runs the COMPAT_PROBE flat archetype sweep.
     *
     * Each probe trial uses short warmup (8 s) + short measure (20 s).
     * Scoring is boot-render only: bootSucceeded = window appeared AND no crash within
     * the measure window.
     *
     * Early-abort strategy: once a config produces bootSucceeded=true, skip remaining trials
     * and declare it the winner. This keeps COMPAT_PROBE fast (typically 1-2 launches).
     *
     * All probe results are still collected for the full-results display (the UI shows
     * "tried 3/6, first boot success at trial 2" etc.).
     */
    private suspend fun runProbeSweep(
        emit: suspend (TunerProgress) -> Unit,
        batteryAvailable: Boolean,
    ) {
        val baseline = ContainerUtils.getDefaultContainerData()
        val probeTrials = SweepPlan.buildProbeTrials(baseline)
        val total = probeTrials.size

        for ((idx, trial) in probeTrials.withIndex()) {
            checkCancelled()

            val result = runTrial(
                trialDef = trial,
                trialIndex = idx,
                total = total,
                runIndex = 1,
                runsPerConfig = 1,
                emit = emit,
                batteryAvailable = batteryAvailable,
            )
            allResults.add(result)

            val ranked = rankProbeResults(allResults)
            val bestSoFar = ranked.firstOrNull()

            emit(TunerProgress.TrialComplete(result, bestSoFar, ranked))

            // Early-abort: first boot-success ends the probe
            if (result.bootSucceeded) {
                Timber.tag(TAG).i(
                    "PROBE early-abort: bootSucceeded on trial $idx '${trial.description}' — skipping remaining trials",
                )
                break
            }
        }

        // Rank probe results: boot-success first, then by crash time (avgFps proxy)
        val ranked = rankProbeResults(allResults)
        val winner = ranked.firstOrNull()

        val scoredResults = allResults.map { r ->
            val pos = ranked.indexOf(r)
            r.copy(goalScore = if (pos >= 0) (ranked.size - pos).toFloat() else 0f)
        }

        val outcome = TunerOutcome(
            goal = goal,
            winner = winner,
            allResults = scoredResults,
            rankedResults = ranked,
            totalTrials = total,
            completedTrials = allResults.size,
            batteryAvailable = batteryAvailable,
        )
        outcomeResult = outcome

        IntentLaunchManager.clearTemporaryOverride(appId)
        emit(TunerProgress.SweepComplete(outcome))
    }

    /** Ranks probe results: bootSucceeded=true first, then by avgFps desc (survival-time proxy). */
    private fun rankProbeResults(results: List<TunerResult>): List<TunerResult> {
        return results.sortedWith(
            compareByDescending<TunerResult> { if (it.bootSucceeded) 1 else 0 }
                .thenByDescending { it.avgFps },
        )
    }

    // -------------------------------------------------------------------------
    // Multi-run interleaved sweep of one dimension's candidate set
    // -------------------------------------------------------------------------

    /**
     * Runs all candidate configs in [dimTrials] with interleaved multi-run averaging.
     *
     * INTERLEAVE STRATEGY (STANDARD/THOROUGH, runsPerConfig >= 2):
     *   Pass 1: run each candidate once in order  → [A1, B1, C1, ...]
     *   Pass 2: run each candidate again in order → [A2, B2, C2, ...]
     *   Aggregate: A = avg(A1, A2), B = avg(B1, B2), etc.
     *
     * QUICK mode (runsPerConfig == 1): simple single-run loop.
     *
     * Returns one aggregated [TunerResult] per candidate (not per raw run).
     */
    private suspend fun runDimensionWithAveraging(
        dimTrials: List<TrialDefinition>,
        globalTrialIndex: Int,
        total: Int,
        emit: suspend (TunerProgress) -> Unit,
        currentBestResult: TunerResult?,
        batteryAvailable: Boolean,
    ): List<TunerResult> {
        val runsPerConfig = mode.runsPerConfig

        if (runsPerConfig == 1) {
            // Fast path: single-run, no averaging needed
            val results = mutableListOf<TunerResult>()
            var localBest = currentBestResult
            dimTrials.forEachIndexed { i, trial ->
                checkCancelled()
                val result = runTrial(
                    trialDef = trial,
                    trialIndex = globalTrialIndex + i,
                    total = total,
                    runIndex = 1,
                    runsPerConfig = 1,
                    emit = emit,
                    batteryAvailable = batteryAvailable,
                )
                results.add(result)
                if (result.isUsable) {
                    val ranked = rankResults(results.filter { it.isUsable }, goal, batteryAvailable)
                    if (ranked.isNotEmpty()) localBest = ranked.first()
                }
                val rankedSoFar = rankResults(
                    (allResults + results).filter { it.isUsable },
                    goal,
                    batteryAvailable,
                )
                emit(TunerProgress.TrialComplete(result, localBest, rankedSoFar))
            }
            return results
        }

        // Multi-run path: interleaved passes
        val rawRuns: Array<MutableList<TunerResult>> = Array(dimTrials.size) { mutableListOf() }
        var launchIndex = globalTrialIndex

        for (runPass in 1..runsPerConfig) {
            dimTrials.forEachIndexed { configIdx, trial ->
                checkCancelled()
                val rawResult = runTrial(
                    trialDef = trial,
                    trialIndex = launchIndex,
                    total = total,
                    runIndex = runPass,
                    runsPerConfig = runsPerConfig,
                    emit = emit,
                    batteryAvailable = batteryAvailable,
                )
                rawRuns[configIdx].add(rawResult)
                launchIndex++
            }
        }

        // Aggregate: one TunerResult per config
        val aggregated = mutableListOf<TunerResult>()
        var localBest = currentBestResult
        dimTrials.forEachIndexed { configIdx, trial ->
            val runs = rawRuns[configIdx]
            val agg = aggregateRuns(
                runs = runs,
                trialDef = trial,
                trialIndex = globalTrialIndex + configIdx,
            )
            aggregated.add(agg)
            if (agg.isUsable) {
                val ranked = rankResults(aggregated.filter { it.isUsable }, goal, batteryAvailable)
                if (ranked.isNotEmpty()) localBest = ranked.first()
            }
            val rankedSoFar = rankResults(
                (allResults + aggregated).filter { it.isUsable },
                goal,
                batteryAvailable,
            )
            emit(TunerProgress.TrialComplete(agg, localBest, rankedSoFar))
        }
        return aggregated
    }

    // -------------------------------------------------------------------------
    // Run aggregation: N raw TunerResults → one averaged TunerResult
    // -------------------------------------------------------------------------

    /**
     * Combines [runs] (all raw results for the same config) into a single [TunerResult].
     *
     * Aggregation rules (unchanged from v1.10.x except for new fields):
     *  - avgFps     : arithmetic mean (successful runs only)
     *  - fpsStdDev  : maximum (conservative worst-case stability)
     *  - status     : worst-case (CRASHED > HUNG > UNSTABLE > STABLE)
     *  - avgPowerW  : mean of non-null readings across runs
     *  - chargeCounterDeltaUAh : sum across runs
     *  - shortLabel  : built from trialDef.description
     */
    private fun aggregateRuns(
        runs: List<TunerResult>,
        trialDef: TrialDefinition,
        trialIndex: Int,
    ): TunerResult {
        if (runs.isEmpty()) {
            return TunerResult(
                config = trialDef.config,
                description = trialDef.description,
                shortLabel = TunerResult.buildShortLabel(trialDef.description),
                status = TunerResult.TrialStatus.CRASHED,
                trialIndex = trialIndex,
            )
        }

        val statusPriority = listOf(
            TunerResult.TrialStatus.CRASHED,
            TunerResult.TrialStatus.HUNG,
            TunerResult.TrialStatus.UNSTABLE,
            TunerResult.TrialStatus.STABLE,
            TunerResult.TrialStatus.SKIPPED,
        )
        val worstStatus = runs
            .map { it.status }
            .minByOrNull { statusPriority.indexOf(it).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
            ?: TunerResult.TrialStatus.CRASHED

        val measuredRuns = runs.filter {
            it.status == TunerResult.TrialStatus.STABLE || it.status == TunerResult.TrialStatus.UNSTABLE
        }

        val runsCompleted = measuredRuns.size

        val avgFps = if (measuredRuns.isEmpty()) 0f else measuredRuns.map { it.avgFps }.average().toFloat()
        val minFps = if (measuredRuns.isEmpty()) 0f else measuredRuns.map { it.minFps }.average().toFloat()
        val maxFps = if (measuredRuns.isEmpty()) 0f else measuredRuns.map { it.maxFps }.average().toFloat()
        val fpsStdDev = if (measuredRuns.isEmpty()) 0f else measuredRuns.maxOf { it.fpsStdDev }

        val throttleSuspect = runs.any { it.throttleSuspect }
        val gpuTempStart = runs.first().gpuTempStartC
        val gpuTempEnd = runs.last().gpuTempEndC

        val outlierFlagged = if (measuredRuns.size >= 2) {
            val fpsMean = avgFps
            val maxDeviation = measuredRuns.maxOf { kotlin.math.abs(it.avgFps - fpsMean) }
            fpsMean > 0f && (maxDeviation / fpsMean) > 0.40f
        } else {
            false
        }

        // Aggregate battery fields
        val powerReadings = measuredRuns.mapNotNull { it.avgPowerW }
        val aggAvgPowerW = if (powerReadings.isEmpty()) null else powerReadings.average().toFloat()
        val chargeDeltas = measuredRuns.mapNotNull { it.chargeCounterDeltaUAh }
        val aggChargeCounterDeltaUAh = if (chargeDeltas.isEmpty()) null else chargeDeltas.sum()

        val bootSucceeded = runs.any { it.bootSucceeded }

        val outlierSuffix = if (outlierFlagged) " [outlier flagged]" else ""

        Timber.tag(TAG).i(
            "Aggregated ${runs.size} run(s) for '${trialDef.description}': " +
                "avgFps=$avgFps minFps=$minFps maxFps=$maxFps stdDev=$fpsStdDev " +
                "status=$worstStatus throttle=$throttleSuspect outlier=$outlierFlagged " +
                "avgPowerW=$aggAvgPowerW",
        )

        return TunerResult(
            config = trialDef.config,
            description = trialDef.description + outlierSuffix,
            shortLabel = TunerResult.buildShortLabel(trialDef.description),
            status = worstStatus,
            avgFps = avgFps.coerceIn(0f, 9999f),
            minFps = minFps.coerceIn(0f, 9999f),
            maxFps = maxFps.coerceIn(0f, 9999f),
            fpsStdDev = fpsStdDev.coerceIn(0f, 9999f),
            gpuTempStartC = gpuTempStart,
            gpuTempEndC = gpuTempEnd,
            throttleSuspect = throttleSuspect,
            trialIndex = trialIndex,
            runsCompleted = runsCompleted.coerceAtLeast(1),
            outlierFlagged = outlierFlagged,
            bootSucceeded = bootSucceeded,
            avgPowerW = aggAvgPowerW,
            chargeCounterDeltaUAh = aggChargeCounterDeltaUAh,
        )
    }

    /**
     * Runs a single trial end-to-end:
     *   PREPARING → config applied (auto override) → READY_TO_LAUNCH emitted
     *   → wait for window → WARMUP → MEASURING → teardown → COOLDOWN
     *
     * New in v1.11.0: during MEASURING, samples GpuTemp + BatteryReader every tick.
     * Sets bootSucceeded in the returned TunerResult for PROBE mode.
     */
    private suspend fun runTrial(
        trialDef: TrialDefinition,
        trialIndex: Int,
        total: Int,
        runIndex: Int = 1,
        runsPerConfig: Int = 1,
        emit: suspend (TunerProgress) -> Unit,
        batteryAvailable: Boolean,
    ): TunerResult {
        try {
            emit(TunerProgress.Preparing(trialIndex, total, trialDef.description, runIndex, runsPerConfig))

            // 1. Read GPU temp at trial start
            val gpuTempStart = GpuTemp.readTempC()
            val chargeCounterStart = if (batteryAvailable) BatteryReader.readChargeCounterUAh() else null

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

            // 3. Signal UI to launch
            emit(TunerProgress.ReadyToLaunch(trialIndex, total, trialDef.description, trialDef.config, runIndex, runsPerConfig))

            // 4. Wait for game window (or crash/timeout)
            val windowAppeared = withTimeoutOrNull(WINDOW_WAIT_TIMEOUT_SEC * 1_000) {
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
                val alreadyCrashed = guestTerminatedChannel.tryReceive().isSuccess
                val status = if (alreadyCrashed) {
                    TunerResult.TrialStatus.CRASHED
                } else {
                    TunerResult.TrialStatus.HUNG
                }
                if (!alreadyCrashed && !cancelled) {
                    Timber.tag(TAG).w("Trial $trialIndex hung/timed-out — requesting programmatic trial exit")
                    triggerTrialExit()
                }
                emit(TunerProgress.TrialAborted(trialIndex, status, runIndex))
                teardown(emit, trialIndex, total, gpuTempStart)
                return TunerResult(
                    config = trialDef.config,
                    description = trialDef.description,
                    shortLabel = TunerResult.buildShortLabel(trialDef.description),
                    status = status,
                    gpuTempStartC = gpuTempStart,
                    gpuTempEndC = GpuTemp.readTempC(),
                    trialIndex = trialIndex,
                    runsCompleted = 0,
                    bootSucceeded = false,
                )
            }

            // For PROBE mode: window appearing IS the success signal — set bootSucceeded=true
            // and track it; we then continue measuring briefly to check for immediate crash.
            val windowAppearedTime = System.currentTimeMillis()

            // Try to get FrameRating if not already provided
            frameRating = frameRatingChannel.tryReceive().getOrNull() ?: frameRating

            // 5. WARMUP
            for (s in WARMUP_SEC downTo 1) {
                checkCancelled()
                if (guestTerminatedChannel.tryReceive().isSuccess) {
                    emit(TunerProgress.TrialAborted(trialIndex, TunerResult.TrialStatus.CRASHED, runIndex))
                    teardown(emit, trialIndex, total, gpuTempStart)
                    return TunerResult(
                        config = trialDef.config,
                        description = trialDef.description,
                        shortLabel = TunerResult.buildShortLabel(trialDef.description),
                        status = TunerResult.TrialStatus.CRASHED,
                        gpuTempStartC = gpuTempStart,
                        gpuTempEndC = GpuTemp.readTempC(),
                        trialIndex = trialIndex,
                        runsCompleted = 0,
                        bootSucceeded = false, // crashed during warmup = boot failure in probe
                    )
                }
                emit(TunerProgress.Warmup(trialIndex, total, s, runIndex, runsPerConfig))
                delay(1_000)
            }

            // Reset FrameRating after warmup so we only measure the measurement window
            withContext(Dispatchers.Main.immediate) {
                frameRating?.reset()
            }

            // 6. MEASUREMENT WINDOW — sample GPU temp + battery every tick
            var crashed = false
            val powerSamples = mutableListOf<Float>()  // discharging-only samples in watts
            var bootSucceeded = true  // window appeared; will flip to false if crashes during measure

            if (measurementMode == MeasurementMode.AUTO) {
                for (s in AUTO_MEASURE_SEC downTo 1) {
                    checkCancelled()
                    if (guestTerminatedChannel.tryReceive().isSuccess) {
                        crashed = true
                        // In PROBE mode a crash during the measure window invalidates boot success
                        bootSucceeded = false
                        break
                    }
                    val currentFps = frameRating?.getCurrentFPS() ?: 0f
                    val currentTempC = GpuTemp.readTempC()
                    val currentPowerW = if (batteryAvailable) BatteryReader.readPowerW() else null
                    currentPowerW?.let { powerSamples.add(it) }

                    // For PROBE mode: early success on stable fps reading
                    // (bootSucceeded already true; no early-abort here — engine handles it post-trial)

                    emit(
                        TunerProgress.Measuring(
                            trialIndex = trialIndex,
                            total = total,
                            remainingSeconds = s,
                            currentFps = currentFps,
                            runIndex = runIndex,
                            runsPerConfig = runsPerConfig,
                            currentGpuTempC = currentTempC,
                            currentPowerW = currentPowerW,
                        ),
                    )
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
                            crashed = true
                            bootSucceeded = false
                            measuring = false
                        }
                        else -> {
                            val currentFps = frameRating?.getCurrentFPS() ?: 0f
                            val currentTempC = GpuTemp.readTempC()
                            val currentPowerW = if (batteryAvailable) BatteryReader.readPowerW() else null
                            currentPowerW?.let { powerSamples.add(it) }
                            emit(
                                TunerProgress.Measuring(
                                    trialIndex = trialIndex,
                                    total = total,
                                    remainingSeconds = 0,
                                    currentFps = currentFps,
                                    runIndex = runIndex,
                                    runsPerConfig = runsPerConfig,
                                    currentGpuTempC = currentTempC,
                                    currentPowerW = currentPowerW,
                                ),
                            )
                            delay(1_000)
                            measureSec++
                        }
                    }
                }
            }

            // 7a. AUTO-CLOSE the trial game
            if (!crashed) {
                Timber.tag(TAG).i("Trial $trialIndex measurement done — requesting programmatic trial exit")
                triggerTrialExit()
            }

            // 7b. Write FPS summary
            withContext(Dispatchers.Main.immediate) {
                frameRating?.writeSessionSummary()
            }
            delay(500) // brief wait for async file write

            // 8. Read FPS data
            val fpsData: FpsSessionData? = withContext(Dispatchers.IO) {
                FpsSessionReader.read(context)
            }

            val avgFps = fpsData?.avgFps ?: frameRating?.getAvgFPS() ?: 0f
            val maxFps = fpsData?.maxFps ?: frameRating?.getAvgFPS() ?: 0f
            val minFps = fpsData?.minFps ?: 0f
            val stdDev = frameRating?.getFpsStdDev() ?: 0f

            val gpuTempEnd = GpuTemp.readTempC()
            val chargeCounterEnd = if (batteryAvailable) BatteryReader.readChargeCounterUAh() else null
            val chargeCounterDelta = if (chargeCounterStart != null && chargeCounterEnd != null) {
                // charge counter decreases as charge is consumed
                chargeCounterStart - chargeCounterEnd
            } else null

            val throttleSuspect = gpuTempStart >= 0 && gpuTempEnd >= 0 &&
                (gpuTempEnd - gpuTempStart) >= TunerResult.THROTTLE_DELTA_C

            // Average power from discharging samples
            val avgPowerW = if (powerSamples.isNotEmpty()) powerSamples.average().toFloat() else null

            val status = when {
                crashed -> TunerResult.TrialStatus.CRASHED
                avgFps < TunerResult.PLAYABLE_FPS_THRESHOLD -> TunerResult.TrialStatus.UNSTABLE
                else -> TunerResult.TrialStatus.STABLE
            }

            Timber.tag(TAG).i(
                "Trial $trialIndex complete: status=$status avgFps=$avgFps " +
                    "stdDev=$stdDev throttle=$throttleSuspect bootSucceeded=$bootSucceeded " +
                    "avgPowerW=$avgPowerW chargeCounterDelta=$chargeCounterDelta " +
                    "desc=${trialDef.description}",
            )

            teardown(emit, trialIndex, total, gpuTempStart)

            return TunerResult(
                config = trialDef.config,
                description = trialDef.description,
                shortLabel = TunerResult.buildShortLabel(trialDef.description),
                status = status,
                avgFps = avgFps.coerceIn(0f, 9999f),
                minFps = minFps.coerceIn(0f, 9999f),
                maxFps = maxFps.coerceIn(0f, 9999f),
                fpsStdDev = stdDev.coerceIn(0f, 9999f),
                gpuTempStartC = gpuTempStart,
                gpuTempEndC = gpuTempEnd,
                throttleSuspect = throttleSuspect,
                trialIndex = trialIndex,
                runsCompleted = 1,
                bootSucceeded = bootSucceeded,
                avgPowerW = avgPowerW,
                chargeCounterDeltaUAh = chargeCounterDelta,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Trial $trialIndex threw exception — marking as CRASHED")
            try { teardown(emit, trialIndex, total, -1) } catch (_: Exception) {}
            return TunerResult(
                config = trialDef.config,
                description = trialDef.description,
                shortLabel = TunerResult.buildShortLabel(trialDef.description),
                status = TunerResult.TrialStatus.CRASHED,
                trialIndex = trialIndex,
                runsCompleted = 0,
            )
        }
    }

    /**
     * Post-trial teardown: signal UI to exit, kill stale Wine processes, cooldown.
     */
    private suspend fun teardown(
        emit: suspend (TunerProgress) -> Unit,
        trialIndex: Int,
        total: Int,
        gpuTempStart: Int,
    ) {
        IntentLaunchManager.clearTemporaryOverride(appId)

        val exited = withTimeoutOrNull(TEARDOWN_WAIT_SEC * 1_000) {
            while (exitCompleteChannel.tryReceive().isFailure && !cancelled) {
                delay(200)
            }
            true
        }
        if (exited == null) {
            Timber.tag(TAG).w("Trial $trialIndex: UI did not signal onExitComplete within ${TEARDOWN_WAIT_SEC}s")
        }

        withContext(Dispatchers.IO) {
            try {
                ProcessHelper.hardKillStaleWineProcesses()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "hardKillStaleWineProcesses threw — continuing")
            }
            val deadline = System.currentTimeMillis() + TEARDOWN_WAIT_SEC * 1_000
            while (ProcessHelper.listRunningWineProcesses().isNotEmpty() &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(200)
            }
        }

        val gpuTemp = GpuTemp.readTempC()
        for (s in BETWEEN_TRIAL_COOLDOWN_SEC downTo 1) {
            if (cancelled) break
            emit(TunerProgress.Cooldown(trialIndex, total, s, gpuTemp))
            delay(1_000)
        }
    }

    // -------------------------------------------------------------------------
    // Goal-specific ranking  (composite post-sweep normalisation)
    // -------------------------------------------------------------------------

    /**
     * Ranks a list of usable (STABLE) results according to [goal] and [batteryAvailable].
     *
     * LOW_END: pre-filters + lightnessScore comparator (unchanged).
     * COMPAT_PROBE: should not reach here (probe uses rankProbeResults); delegates to
     *               goal.comparator as safe fallback.
     * All other goals: post-sweep composite normalisation (see rankResultsComposite).
     *
     * After ranking, normalised scores are stamped back into each TunerResult so the
     * UI can display relative bars without re-computing.
     */
    internal fun rankResults(
        stable: List<TunerResult>,
        goal: TunerGoal,
        batteryAvailable: Boolean = false,
    ): List<TunerResult> {
        if (stable.isEmpty()) return emptyList()

        return when (goal) {
            TunerGoal.LOW_END -> {
                // Preserve existing lightnessScore path — no composite needed
                val lightAndPlayable = stable.filter {
                    it.avgFps >= TunerResult.PLAYABLE_FPS_THRESHOLD &&
                        it.config.graphicsDriver.equals("System", ignoreCase = true)
                }
                val candidates = lightAndPlayable.ifEmpty {
                    stable.filter { it.avgFps >= TunerResult.PLAYABLE_FPS_THRESHOLD }
                }.ifEmpty { stable }
                candidates.sortedWith(goal.comparator)
            }

            TunerGoal.COMPAT_PROBE -> {
                // Should not reach here in normal operation; safe fallback
                stable.sortedWith(goal.comparator)
            }

            else -> rankResultsComposite(stable, goal, batteryAvailable)
        }
    }

    /**
     * Composite post-sweep normalisation ranking.
     *
     * 1. Compute max values across all results:
     *      maxAvgFps, maxStdDev, maxPowerW, maxTempEndC
     * 2. Per result compute normalised scores [0..1]:
     *      fpsNorm  = avgFps / maxAvgFps
     *      stabNorm = 1 - (fpsStdDev / maxStdDev)
     *      battNorm = 1 - (avgPowerW / maxPowerW)   [null if no battery signal]
     *      tempNorm = (1 - gpuTempEndC / 80.0).coerceIn(0, 1)
     * 3. Select effective weights:
     *      CUSTOM goal → customWeights ?: goal.weights
     *      FPS_BATTERY + !batteryAvailable → goal.weights.withoutBatterySignal()
     *      others → goal.weights
     * 4. composite = weighted sum / weight total
     * 5. Sort by composite desc; stamp normalised scores back into TunerResult.
     */
    private fun rankResultsComposite(
        stable: List<TunerResult>,
        goal: TunerGoal,
        batteryAvailable: Boolean,
    ): List<TunerResult> {
        // --- 1. Compute maxima ---
        val maxAvgFps = stable.maxOf { it.avgFps }.coerceAtLeast(1f)
        val maxStdDev = stable.maxOf { it.fpsStdDev }.coerceAtLeast(1f)
        val powerReadings = stable.mapNotNull { it.avgPowerW }
        val maxPowerW = if (powerReadings.isNotEmpty()) powerReadings.max().coerceAtLeast(0.001f) else null

        // --- 2. Select effective weights ---
        val effectiveWeights: GoalWeights = when {
            goal == TunerGoal.CUSTOM -> customWeights ?: goal.weights
            goal == TunerGoal.FPS_BATTERY && !batteryAvailable -> GoalWeights.FPS_BATTERY_FALLBACK
            else -> goal.weights
        }

        val weightTotal = effectiveWeights.total.coerceAtLeast(0.001f)

        // --- 3. Normalise and score ---
        val scored = stable.map { r ->
            val fpsNorm = (r.avgFps / maxAvgFps).coerceIn(0f, 1f)
            val stabNorm = (1f - r.fpsStdDev / maxStdDev).coerceIn(0f, 1f)
            val battNorm: Float? = if (batteryAvailable && maxPowerW != null && r.avgPowerW != null) {
                (1f - r.avgPowerW / maxPowerW).coerceIn(0f, 1f)
            } else null
            val tempNorm = if (r.gpuTempEndC >= 0) {
                (1f - r.gpuTempEndC / 80f).coerceIn(0f, 1f)
            } else {
                0.5f // unknown temp: neutral
            }

            val composite = (
                effectiveWeights.fps * fpsNorm +
                    effectiveWeights.stability * stabNorm +
                    (effectiveWeights.battery * (battNorm ?: 0f)) +
                    effectiveWeights.temperature * tempNorm
                ) / weightTotal

            r.copy(
                fpsNormScore = fpsNorm,
                stabNormScore = stabNorm,
                battNormScore = battNorm,
                tempNormScore = tempNorm,
                goalScore = composite,
            )
        }

        // --- 4. Sort by composite desc ---
        return scored.sortedByDescending { it.goalScore }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("AutoTunerEngine cancelled")
    }
}
