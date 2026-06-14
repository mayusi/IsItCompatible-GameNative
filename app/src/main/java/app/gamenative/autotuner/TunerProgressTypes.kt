package app.gamenative.autotuner

import com.winlator.container.ContainerData
import com.winlator.widget.FrameRating

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

    /** A fix is being applied and the trial will be retried. */
    data class FixRetrying(
        val trialIndex: Int,
        val retryNum: Int,
        val maxRetries: Int,
        val fixDescription: String,
        val baseFailure: TunerResult.TrialStatus,
    ) : TunerProgress()
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
