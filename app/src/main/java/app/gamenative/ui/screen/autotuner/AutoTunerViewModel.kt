package app.gamenative.ui.screen.autotuner

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PluviaApp
import app.gamenative.autotuner.AutoTunerEngine
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.SweepMode
import app.gamenative.autotuner.TunerGoal
import app.gamenative.autotuner.TunerOutcome
import app.gamenative.autotuner.TunerProgress
import app.gamenative.autotuner.TunerResult
import app.gamenative.events.AndroidEvent
import com.winlator.widget.FrameRating
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

// ============================================================
// UI state model
// ============================================================

enum class AutoTunerPhase {
    IDLE,
    PREPARING,
    WAITING_LAUNCH,   // ReadyToLaunch emitted — waiting for XServerScreen to start
    WARMUP,
    MEASURING,
    COOLDOWN,
    TRIAL_ABORTED,
    BETWEEN_TRIALS,   // post-teardown gap before next PREPARING
    COMPLETE,
    ERROR,
    CANCELLED,
}

data class AutoTunerUiState(
    val phase: AutoTunerPhase = AutoTunerPhase.IDLE,
    val trialIndex: Int = 0,
    val totalTrials: Int = 0,
    val currentDescription: String = "",
    val remainingSeconds: Int = 0,
    val currentFps: Float = 0f,
    val gpuTempC: Int = -1,
    val bestSoFar: TunerResult? = null,
    val allResults: List<TunerResult> = emptyList(),
    val outcome: TunerOutcome? = null,
    val errorMessage: String? = null,
    /** True when ReadyToLaunch has been emitted and the XServerScreen trial is in progress. */
    val trialIsRunning: Boolean = false,
    val goal: TunerGoal = TunerGoal.MAX_FPS,
    val mode: SweepMode = SweepMode.STANDARD,
    val measurementMode: MeasurementMode = MeasurementMode.AUTO,
    val appId: String = "",
    val estimatedMinutes: Int = 0,
)

/**
 * AutoTunerViewModel
 *
 * Owns the AutoTunerEngine lifetime and bridges the engine's progressFlow to
 * AutoTunerUiState for the UI.
 *
 * Trial launch loop (the core tricky part):
 * ─────────────────────────────────────────
 * 1. The engine emits ReadyToLaunch(trialIndex, total, description, trialConfig).
 * 2. The ViewModel flips trialIsRunning=true and exposes the pendingLaunchRequest via
 *    a StateFlow<ReadyToLaunch?> that the AutoTunerProgressScreen observes.
 * 3. The screen launches XServerScreen in the host (PluviaMain) by calling
 *    onLaunchTrial(appId) — which is wired in PluviaMain to call preLaunchApp +
 *    viewModel.launchApp exactly as a normal launch would. This reuses the full
 *    normal launch path and does NOT regress it.
 * 4. XServerScreen callbacks (onWindowMapped, onGuestTerminated, onExitComplete,
 *    provideFrameRating, onManualStop) flow from the screen → ViewModel → engine.trialController.
 * 5. When the engine emits teardown/Cooldown the ViewModel flips trialIsRunning=false
 *    and the screen pops the XServerScreen (calls navigateBack).
 *
 * Normal (non-tuner) launch is completely unaffected because it goes through
 * MainViewModel.launchApp; this ViewModel is only alive when the user explicitly
 * starts an auto-tune session.
 */
class AutoTunerViewModel : ViewModel() {

    private val TAG = "AutoTunerVM"

    private val _uiState = MutableStateFlow(AutoTunerUiState())
    val uiState: StateFlow<AutoTunerUiState> = _uiState.asStateFlow()

    /**
     * Set when the engine emits ReadyToLaunch; the Progress screen observes this and
     * triggers the game launch via the callback supplied in startTuning().
     */
    private val _pendingLaunch = MutableStateFlow<TunerProgress.ReadyToLaunch?>(null)
    val pendingLaunch: StateFlow<TunerProgress.ReadyToLaunch?> = _pendingLaunch.asStateFlow()

    private var engine: AutoTunerEngine? = null
    private var sweepJob: Job? = null

    // ──────────────────────────────────────────────────────────────────
    // Public API — called by the UI
    // ──────────────────────────────────────────────────────────────────

    /**
     * Kick off the sweep.
     *
     * @param context         Application context.
     * @param appId           Game app id.
     * @param goal            Tuning objective.
     * @param mode            Sweep depth.
     * @param measurementMode Auto or manual measurement.
     */
    fun startTuning(
        context: Context,
        appId: String,
        goal: TunerGoal,
        mode: SweepMode,
        measurementMode: MeasurementMode,
    ) {
        if (sweepJob?.isActive == true) {
            Timber.tag(TAG).w("startTuning called while sweep already running — ignoring")
            return
        }

        val eng = AutoTunerEngine(
            context = context.applicationContext,
            appId = appId,
            goal = goal,
            mode = mode,
            measurementMode = measurementMode,
        )
        // Wire the auto-exit callback so the engine can close the trial game programmatically.
        eng.onRequestTrialExit = { requestTrialExit() }
        engine = eng

        _uiState.value = AutoTunerUiState(
            phase = AutoTunerPhase.PREPARING,
            goal = goal,
            mode = mode,
            measurementMode = measurementMode,
            appId = appId,
        )

        sweepJob = viewModelScope.launch {
            eng.progressFlow.collect { progress ->
                handleProgress(progress)
            }
        }
    }

    /** Cancel the in-progress sweep. */
    fun cancel() {
        engine?.cancel()
        _pendingLaunch.value = null
        _uiState.value = _uiState.value.copy(
            phase = AutoTunerPhase.CANCELLED,
            trialIsRunning = false,
        )
        sweepJob?.cancel()
    }

    /**
     * Persist the winning config. Call after user confirms on the results screen.
     * Must be called from a coroutine (e.g. viewModelScope.launch).
     */
    fun applyWinner(context: Context) {
        viewModelScope.launch {
            try {
                engine?.applyWinner(context.applicationContext)
                Timber.tag(TAG).i("Winner applied successfully")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "applyWinner failed")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // XServerScreen lifecycle callbacks — forwarded to engine.trialController
    // ──────────────────────────────────────────────────────────────────

    /** Call when the XServerScreen window appears (WindowMapped event). */
    fun onWindowMapped() {
        Timber.tag(TAG).d("onWindowMapped -> trialController")
        engine?.trialController?.onWindowMapped()
    }

    /** Call when GuestProgramTerminated fires. */
    fun onGuestTerminated() {
        Timber.tag(TAG).d("onGuestTerminated -> trialController")
        engine?.trialController?.onGuestTerminated()
    }

    /**
     * Call when XServerScreen has finished tearing down (navigated away).
     * Flips trialIsRunning=false here so the Progress screen stops showing the
     * "game running" state before the cooldown counter appears.
     */
    fun onExitComplete() {
        Timber.tag(TAG).d("onExitComplete -> trialController")
        _uiState.value = _uiState.value.copy(trialIsRunning = false)
        engine?.trialController?.onExitComplete()
    }

    /** Provide the FrameRating instance once available from XServerScreen. */
    fun provideFrameRating(fr: FrameRating) {
        Timber.tag(TAG).d("provideFrameRating -> trialController")
        engine?.trialController?.provideFrameRating(fr)
    }

    /** MANUAL mode: user tapped Stop Recording. */
    fun onManualStopRecording() {
        Timber.tag(TAG).d("onManualStopRecording -> trialController")
        engine?.trialController?.onManualStopRecording()
    }

    /**
     * Called by the engine (via [AutoTunerEngine.onRequestTrialExit]) when the measurement
     * window ends, a crash/hang is detected, or the user taps "Stop Recording" in MANUAL mode.
     *
     * Emits [AndroidEvent.RequestTrialExit] on the event bus.  XServerScreen listens for this
     * event and invokes its internal [exit()] function — the exact same path that the in-game
     * overlay bar's "Exit Game" button uses — so Wine teardown is clean and identical to a
     * manual close.  Only fires when [trialIsRunning] is true to ensure it never closes a
     * normal (non-tuner) game session.
     */
    fun requestTrialExit() {
        if (!_uiState.value.trialIsRunning) {
            Timber.tag(TAG).d("requestTrialExit ignored — no trial currently running")
            return
        }
        Timber.tag(TAG).i("requestTrialExit — emitting AndroidEvent.RequestTrialExit")
        PluviaApp.events.emit(AndroidEvent.RequestTrialExit)
    }

    /** Called by the Progress screen after it has triggered the XServerScreen launch. */
    fun onTrialLaunched() {
        _pendingLaunch.value = null
    }

    // ──────────────────────────────────────────────────────────────────
    // Internal progress handling
    // ──────────────────────────────────────────────────────────────────

    private fun handleProgress(progress: TunerProgress) {
        Timber.tag(TAG).d("progress: $progress")
        when (progress) {
            is TunerProgress.Started -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.PREPARING,
                    totalTrials = progress.total,
                    estimatedMinutes = progress.estimatedMinutes,
                )
            }

            is TunerProgress.Preparing -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.PREPARING,
                    trialIndex = progress.trialIndex,
                    totalTrials = progress.total,
                    currentDescription = progress.description,
                    trialIsRunning = false,
                )
            }

            is TunerProgress.ReadyToLaunch -> {
                // Signal UI to launch the game trial.
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.WAITING_LAUNCH,
                    trialIndex = progress.trialIndex,
                    totalTrials = progress.total,
                    currentDescription = progress.description,
                    trialIsRunning = true,
                )
                _pendingLaunch.value = progress
            }

            is TunerProgress.Warmup -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.WARMUP,
                    trialIndex = progress.trialIndex,
                    totalTrials = progress.total,
                    remainingSeconds = progress.remainingSeconds,
                )
            }

            is TunerProgress.Measuring -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.MEASURING,
                    trialIndex = progress.trialIndex,
                    totalTrials = progress.total,
                    remainingSeconds = progress.remainingSeconds,
                    currentFps = progress.currentFps,
                )
            }

            is TunerProgress.TrialAborted -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.TRIAL_ABORTED,
                    trialIndex = progress.trialIndex,
                )
            }

            is TunerProgress.Cooldown -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.COOLDOWN,
                    trialIndex = progress.trialIndex,
                    totalTrials = progress.total,
                    remainingSeconds = progress.remainingSeconds,
                    gpuTempC = progress.gpuTempC,
                    trialIsRunning = false,
                )
            }

            is TunerProgress.TrialComplete -> {
                val updated = _uiState.value.allResults.toMutableList()
                    .also { it.add(progress.result) }
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.BETWEEN_TRIALS,
                    allResults = updated,
                    bestSoFar = progress.bestSoFar,
                    trialIsRunning = false,
                )
            }

            is TunerProgress.SweepComplete -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.COMPLETE,
                    outcome = progress.outcome,
                    trialIsRunning = false,
                )
                _pendingLaunch.value = null
            }

            is TunerProgress.Error -> {
                _uiState.value = _uiState.value.copy(
                    phase = AutoTunerPhase.ERROR,
                    errorMessage = progress.message,
                    trialIsRunning = false,
                )
                _pendingLaunch.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine?.cancel()
    }
}
