package app.gamenative.autotuner

import android.content.Context
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.iic.AutoTunerResultBroadcaster
import app.gamenative.utils.CrashClassifier
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.IntentLaunchManager
import com.winlator.container.ContainerData
import com.winlator.core.ProcessHelper
import com.winlator.widget.FrameRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber

// =============================================================================
//  TYPES — MeasurementMode, TunerProgress, TunerOutcome, TunerTrialController
//  are defined in TunerProgressTypes.kt (same package).
//
//  SENSORS — GpuTemp, GpuBusy, BatteryReader, FpsSessionReader, FpsSessionData
//  are defined in TunerSensors.kt (same package).
// =============================================================================

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
 * @param executablePath  Optional sub-game executable path (e.g. for DMC1/DMC2/DMC3 in a
 *                        collection). When set, each trial config is stamped with this path
 *                        and the winner is saved under a per-exe extraData key.
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
    /**
     * Optional sub-game executable path for collection games (e.g. DMC1/DMC2/DMC3).
     * When set, each trial config is stamped with this executablePath so the correct
     * sub-game is launched, and the winner is saved under a per-exe extraData key.
     */
    val executablePath: String? = null,
) {
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
    //
    // BUG-1 fix: replace CONFLATED with capacity=1 / DROP_OLDEST so that a
    // previous trial's late callback (e.g. onWindowMapped arriving after the
    // next trial has already started) can NEVER bleed into the new trial.
    // We drain each channel fully with a while-loop before every trial launch
    // (see runTrial below) so that any residual signal from the prior trial is
    // discarded before we start waiting on the next one.
    //
    // CONFLATED channels silently discard signals when the buffer is full, which
    // makes them appear empty even if a stale value is sitting there — the drain
    // tryReceive() call then returns isFailure, fooling the engine into thinking
    // no stale signal exists.  capacity=1/DROP_OLDEST has the same single-slot
    // behaviour but gives a honest empty-after-drain guarantee.
    private val windowMappedChannel = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val guestTerminatedChannel = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val exitCompleteChannel = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val frameRatingChannel = Channel<FrameRating>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val manualStopChannel = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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
     * the result summary in the container's extraData.
     *
     * Storage key:
     *   - If [executablePath] is set: "autotuner_result_v1:<exe-basename-lowercase>"
     *     so collections (DMC1/DMC2/DMC3) each get an independent result.
     *   - Otherwise: "autotuner_result_v1" (single-game behaviour, unchanged).
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

                // Determine the extraData key: per-exe if executablePath is set
                val extraDataKey = buildExtraDataKey(executablePath)

                // Persist summary
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
                    // v1.12.0 additions
                    if (winner.appliedFixes.isNotEmpty()) {
                        put("appliedFixes", org.json.JSONArray(winner.appliedFixes))
                    }
                    executablePath?.let { put("executablePath", it) }
                }
                container.putExtra(extraDataKey, summary.toString())
                container.saveData()
                Timber.tag(TAG).i("Winner applied and stored in extraData[$extraDataKey] for $appId")

                // Broadcast the winner to the IIC app so it can store a Tier-1 verified
                // guide for this game+device. Best-effort + crash-safe: any failure here
                // must NEVER propagate — the container save above already succeeded.
                try {
                    val numericId = ContainerUtils.extractGameIdFromContainerId(appId)
                    val gameSource = try {
                        ContainerUtils.extractGameSourceFromContainerId(appId).name
                    } catch (_: Exception) {
                        "STEAM"
                    }
                    AutoTunerResultBroadcaster.send(
                        context = context,
                        appId = numericId,
                        gameSource = gameSource,
                        outcome = outcome,
                        winnerConfig = winner.config,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "AutoTuner result broadcast failed (non-fatal) for $appId")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to apply winner for $appId")
                throw e
            }
        }
    }

    companion object {
        private const val TAG = "AutoTunerEngine"
        const val EXTRA_DATA_KEY_BASE = "autotuner_result_v1"

        /** Returns the extraData key to use for saving/loading auto-tune results. */
        fun buildExtraDataKey(executablePath: String?): String {
            if (executablePath.isNullOrBlank()) return EXTRA_DATA_KEY_BASE
            val exeBasename = java.io.File(executablePath).name.lowercase()
            return "$EXTRA_DATA_KEY_BASE:$exeBasename"
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

            // Stamp executablePath into the probe trial config if set
            val stampedTrial = if (executablePath != null) {
                trial.copy(config = trial.config.copy(executablePath = executablePath))
            } else trial

            val result = runTrialWithFixRetry(
                trialDef = stampedTrial,
                trialIndex = idx,
                total = total,
                runIndex = 1,
                runsPerConfig = 1,
                emit = emit,
                batteryAvailable = batteryAvailable,
                maxRetries = 2, // PROBE: up to 2 fix retries
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
                val stampedTrial = if (executablePath != null) {
                    trial.copy(config = trial.config.copy(executablePath = executablePath))
                } else trial
                val result = runTrialWithFixRetry(
                    trialDef = stampedTrial,
                    trialIndex = globalTrialIndex + i,
                    total = total,
                    runIndex = 1,
                    runsPerConfig = 1,
                    emit = emit,
                    batteryAvailable = batteryAvailable,
                    maxRetries = 1, // non-PROBE: 1 fix retry
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
                val stampedTrial = if (executablePath != null) {
                    trial.copy(config = trial.config.copy(executablePath = executablePath))
                } else trial
                val rawResult = runTrialWithFixRetry(
                    trialDef = stampedTrial,
                    trialIndex = launchIndex,
                    total = total,
                    runIndex = runPass,
                    runsPerConfig = runsPerConfig,
                    emit = emit,
                    batteryAvailable = batteryAvailable,
                    maxRetries = 1, // non-PROBE: 1 fix retry
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
            TunerResult.TrialStatus.BLACK_SCREEN,
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

        // bootSucceeded only if at least one run genuinely succeeded (no black-screen)
        val bootSucceeded = runs.any { it.bootSucceeded && !it.blackScreenDetected }
        val blackScreenDetected = runs.any { it.blackScreenDetected }

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
            blackScreenDetected = blackScreenDetected,
            windowMapped = runs.any { it.windowMapped },
            appliedFixes = runs.flatMap { it.appliedFixes }.distinct(),
        )
    }

    /**
     * Runs a single trial end-to-end:
     *   PREPARING → config applied (auto override) → READY_TO_LAUNCH emitted
     *   → wait for window → WARMUP → MEASURING → teardown → COOLDOWN
     *
     * v1.11.0: during MEASURING, samples GpuTemp + BatteryReader every tick.
     * v1.12.0: black-screen detection via FPS + GpuBusy sustain counter;
     *          bootSucceeded redefined to require sustained render, not just window-map.
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

            // Drain all lifecycle channels before this trial so that any stale
            // signal from the previous trial (e.g. a late onWindowMapped) cannot
            // bleed through and produce a false STABLE/HUNG result.
            // The while-loop is necessary for correctness: a single tryReceive()
            // only removes one element and would silently leave a second one behind
            // if, for example, both onWindowMapped and onGuestTerminated fired late.
            while (windowMappedChannel.tryReceive().isSuccess) { /* drain */ }
            while (guestTerminatedChannel.tryReceive().isSuccess) { /* drain */ }
            while (exitCompleteChannel.tryReceive().isSuccess) { /* drain */ }
            while (manualStopChannel.tryReceive().isSuccess) { /* drain */ }
            var frameRating: FrameRating? = null
            while (true) {
                val r = frameRatingChannel.tryReceive()
                if (r.isSuccess) frameRating = r.getOrNull() else break
            }

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
                    windowMapped = false,
                )
            }

            // Window appeared — track it.
            val windowMapped = true

            // Try to get FrameRating if not already provided
            frameRating = frameRatingChannel.tryReceive().getOrNull() ?: frameRating

            // 5. WARMUP — also run black-screen detection during warmup
            var blackScreenSustainCounter = 0
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
                        bootSucceeded = false,
                        windowMapped = windowMapped,
                    )
                }
                val currentFps = frameRating?.getCurrentFPS() ?: 0f
                val gpuBusy = GpuBusy.readPercent()
                val isBlackScreenTick = currentFps <= TunerResult.BLACK_SCREEN_FPS_THRESHOLD &&
                    (gpuBusy < 0 || gpuBusy <= TunerResult.BLACK_SCREEN_GPU_BUSY_THRESHOLD)
                if (isBlackScreenTick) blackScreenSustainCounter++ else blackScreenSustainCounter = 0

                if (blackScreenSustainCounter >= TunerResult.BLACK_SCREEN_SUSTAIN_SEC) {
                    Timber.tag(TAG).w(
                        "Trial $trialIndex BLACK_SCREEN detected during WARMUP: " +
                            "fps=$currentFps gpuBusy=$gpuBusy sustain=$blackScreenSustainCounter",
                    )
                    triggerTrialExit()
                    emit(TunerProgress.TrialAborted(trialIndex, TunerResult.TrialStatus.BLACK_SCREEN, runIndex))
                    teardown(emit, trialIndex, total, gpuTempStart)
                    return TunerResult(
                        config = trialDef.config,
                        description = trialDef.description,
                        shortLabel = TunerResult.buildShortLabel(trialDef.description),
                        status = TunerResult.TrialStatus.BLACK_SCREEN,
                        gpuTempStartC = gpuTempStart,
                        gpuTempEndC = GpuTemp.readTempC(),
                        trialIndex = trialIndex,
                        runsCompleted = 0,
                        bootSucceeded = false,
                        blackScreenDetected = true,
                        windowMapped = windowMapped,
                    )
                }

                emit(TunerProgress.Warmup(trialIndex, total, s, runIndex, runsPerConfig))
                delay(1_000)
            }

            // Reset FrameRating after warmup so we only measure the measurement window
            withContext(Dispatchers.Main.immediate) {
                frameRating?.reset()
            }
            blackScreenSustainCounter = 0

            // 6. MEASUREMENT WINDOW — sample GPU temp + battery + black-screen detection every tick
            var crashed = false
            var blackScreen = false
            val powerSamples = mutableListOf<Float>()
            // Track sustained FPS for genuine bootSucceeded check
            var sustainedRenderSec = 0

            if (measurementMode == MeasurementMode.AUTO) {
                for (s in AUTO_MEASURE_SEC downTo 1) {
                    checkCancelled()
                    if (guestTerminatedChannel.tryReceive().isSuccess) {
                        crashed = true
                        break
                    }
                    val currentFps = frameRating?.getCurrentFPS() ?: 0f
                    val gpuBusy = GpuBusy.readPercent()
                    val currentTempC = GpuTemp.readTempC()
                    val currentPowerW = if (batteryAvailable) BatteryReader.readPowerW() else null
                    currentPowerW?.let { powerSamples.add(it) }

                    // Black-screen detection
                    val isBlackScreenTick = currentFps <= TunerResult.BLACK_SCREEN_FPS_THRESHOLD &&
                        (gpuBusy < 0 || gpuBusy <= TunerResult.BLACK_SCREEN_GPU_BUSY_THRESHOLD)
                    if (isBlackScreenTick) blackScreenSustainCounter++ else blackScreenSustainCounter = 0

                    // Sustained render tracking
                    if (currentFps >= TunerResult.SUSTAINED_RENDER_FPS_THRESHOLD) sustainedRenderSec++

                    if (blackScreenSustainCounter >= TunerResult.BLACK_SCREEN_SUSTAIN_SEC) {
                        Timber.tag(TAG).w(
                            "Trial $trialIndex BLACK_SCREEN detected during MEASURE: " +
                                "fps=$currentFps gpuBusy=$gpuBusy sustain=$blackScreenSustainCounter",
                        )
                        blackScreen = true
                        break
                    }

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
                while (measuring && !cancelled) {
                    when {
                        manualStopChannel.tryReceive().isSuccess -> measuring = false
                        guestTerminatedChannel.tryReceive().isSuccess -> {
                            crashed = true
                            measuring = false
                        }
                        else -> {
                            val currentFps = frameRating?.getCurrentFPS() ?: 0f
                            val gpuBusy = GpuBusy.readPercent()
                            val currentTempC = GpuTemp.readTempC()
                            val currentPowerW = if (batteryAvailable) BatteryReader.readPowerW() else null
                            currentPowerW?.let { powerSamples.add(it) }

                            // Black-screen detection in MANUAL mode too
                            val isBlackScreenTick = currentFps <= TunerResult.BLACK_SCREEN_FPS_THRESHOLD &&
                                (gpuBusy < 0 || gpuBusy <= TunerResult.BLACK_SCREEN_GPU_BUSY_THRESHOLD)
                            if (isBlackScreenTick) blackScreenSustainCounter++ else blackScreenSustainCounter = 0
                            if (currentFps >= TunerResult.SUSTAINED_RENDER_FPS_THRESHOLD) sustainedRenderSec++
                            if (blackScreenSustainCounter >= TunerResult.BLACK_SCREEN_SUSTAIN_SEC) {
                                blackScreen = true
                                measuring = false
                            }

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
                chargeCounterStart - chargeCounterEnd
            } else null

            val throttleSuspect = gpuTempStart >= 0 && gpuTempEnd >= 0 &&
                (gpuTempEnd - gpuTempStart) >= TunerResult.THROTTLE_DELTA_C

            val avgPowerW = if (powerSamples.isNotEmpty()) powerSamples.average().toFloat() else null

            // bootSucceeded: window mapped AND not black-screen AND not crashed AND
            // sustained render (>= SUSTAINED_RENDER_FPS_THRESHOLD) for ~5 seconds.
            val bootSucceeded = windowMapped && !blackScreen && !crashed &&
                sustainedRenderSec >= 5

            val status = when {
                blackScreen -> TunerResult.TrialStatus.BLACK_SCREEN
                crashed -> TunerResult.TrialStatus.CRASHED
                avgFps < TunerResult.PLAYABLE_FPS_THRESHOLD -> TunerResult.TrialStatus.UNSTABLE
                else -> TunerResult.TrialStatus.STABLE
            }

            Timber.tag(TAG).i(
                "Trial $trialIndex complete: status=$status avgFps=$avgFps " +
                    "stdDev=$stdDev throttle=$throttleSuspect bootSucceeded=$bootSucceeded " +
                    "blackScreen=$blackScreen sustainedRenderSec=$sustainedRenderSec " +
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
                blackScreenDetected = blackScreen,
                windowMapped = windowMapped,
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
                windowMapped = false,
            )
        }
    }

    /**
     * Wraps [runTrial] with fix-retry logic.
     *
     * After a base trial that results in CRASHED or BLACK_SCREEN:
     * 1. Snapshot the Wine log ring buffer via [CrashClassifier.snapshot].
     * 2. Classify the failure via [FixLadder.classifyFailure].
     * 3. Walk the fix ladder, picking the first un-tried applicable rung.
     * 4. Apply the fix (DLL overrides → patched ContainerData; WMV rename → filesystem).
     * 5. Emit [TunerProgress.FixRetrying] and re-run the trial with the patched config.
     * 6. If the retry succeeds (STABLE or bootSucceeded), return it with appliedFixes recorded.
     * 7. Cap total retries at [maxRetries].
     *
     * The user's persistent container is NEVER mutated here — DLL overrides are applied
     * only to the ContainerData copy passed to [IntentLaunchManager.applyAutoConfigOverride].
     * WMV renames touch the filesystem but are idempotent and reversible (.bak suffix).
     */
    private suspend fun runTrialWithFixRetry(
        trialDef: TrialDefinition,
        trialIndex: Int,
        total: Int,
        runIndex: Int = 1,
        runsPerConfig: Int = 1,
        emit: suspend (TunerProgress) -> Unit,
        batteryAvailable: Boolean,
        maxRetries: Int,
    ): TunerResult {
        // Run the base trial
        CrashClassifier.reset()
        var result = runTrial(
            trialDef = trialDef,
            trialIndex = trialIndex,
            total = total,
            runIndex = runIndex,
            runsPerConfig = runsPerConfig,
            emit = emit,
            batteryAvailable = batteryAvailable,
        )

        val accumulatedFixes = mutableListOf<String>()
        val triedRungIds = mutableSetOf<String>()
        var retryNum = 0

        while (retryNum < maxRetries &&
            (result.status == TunerResult.TrialStatus.CRASHED ||
                result.status == TunerResult.TrialStatus.BLACK_SCREEN)
        ) {
            // Classify the failure
            val logLines = CrashClassifier.snapshot()
            val failureClass = if (result.status == TunerResult.TrialStatus.BLACK_SCREEN) {
                // If it's a pure black-screen with no log pattern match, use BLACK_SCREEN_NOFIX
                val classified = FixLadder.classifyFailure(logLines)
                if (classified == FixLadder.FailureClass.UNKNOWN_CRASH) {
                    FixLadder.FailureClass.BLACK_SCREEN_NOFIX
                } else classified
            } else {
                FixLadder.classifyFailure(logLines)
            }

            // Find the next applicable rung
            val installPath = try {
                withContext(Dispatchers.IO) {
                    GameFixesRegistry.resolveInstallPathFor(context, appId) ?: ""
                }
            } catch (_: Exception) { "" }

            val rung = FixLadder.nextRung(
                context = context,
                appId = appId,
                baseConfig = result.config,
                failureClass = failureClass,
                triedRungIds = triedRungIds,
            ) ?: break // no more applicable rungs

            triedRungIds.add(rung.id)

            // Apply the fix
            val fixResult = try {
                withContext(Dispatchers.IO) {
                    rung.apply(context, appId, result.config, installPath, failureClass)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Fix rung ${rung.id} threw during apply — skipping")
                null
            }

            if (fixResult == null) {
                Timber.tag(TAG).d("Fix rung ${rung.id} returned null — skipping to next rung")
                continue
            }

            retryNum++
            accumulatedFixes.add(fixResult.appliedFix.label())
            Timber.tag(TAG).i(
                "Trial $trialIndex fix-retry $retryNum/$maxRetries: " +
                    "failureClass=$failureClass rung=${rung.id} fix=${fixResult.appliedFix.label()}",
            )

            emit(
                TunerProgress.FixRetrying(
                    trialIndex = trialIndex,
                    retryNum = retryNum,
                    maxRetries = maxRetries,
                    fixDescription = fixResult.appliedFix.label(),
                    baseFailure = result.status,
                ),
            )

            // Build the patched trial definition with the fixed config
            val patchedTrialDef = trialDef.copy(
                config = fixResult.patchedConfig,
                description = "${trialDef.description} [fix: ${fixResult.appliedFix.label()}]",
            )

            // Reset crash classifier for the retry
            CrashClassifier.reset()

            val retryResult = runTrial(
                trialDef = patchedTrialDef,
                trialIndex = trialIndex,
                total = total,
                runIndex = runIndex,
                runsPerConfig = runsPerConfig,
                emit = emit,
                batteryAvailable = batteryAvailable,
            )

            result = retryResult.copy(
                appliedFixes = (accumulatedFixes + retryResult.appliedFixes).distinct(),
                fixRetryOf = trialIndex,
            )

            // If retry succeeded, stop trying more fixes
            if (result.status == TunerResult.TrialStatus.STABLE ||
                (result.status != TunerResult.TrialStatus.CRASHED && result.bootSucceeded)
            ) {
                Timber.tag(TAG).i(
                    "Trial $trialIndex fix-retry $retryNum SUCCEEDED: status=${result.status} " +
                        "bootSucceeded=${result.bootSucceeded} fixes=${accumulatedFixes}",
                )
                break
            }
        }

        // Stamp accumulated fixes even if all retries failed
        return if (accumulatedFixes.isNotEmpty() && result.appliedFixes.isEmpty()) {
            result.copy(appliedFixes = accumulatedFixes)
        } else result
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
