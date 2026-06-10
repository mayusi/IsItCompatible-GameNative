package app.gamenative.autotuner

import com.winlator.container.ContainerData

/**
 * Per-trial outcome produced by AutoTunerEngine.
 *
 * [config]            : the ContainerData that was tested.
 * [description]       : human-readable description of what was swept (e.g. "Turnip 26.2.0 + dxvk 2.4.1").
 * [status]            : STABLE = completed + hit FPS threshold; UNSTABLE = completed but low fps;
 *                       HUNG / CRASHED / SKIPPED = did not complete normally.
 * [avgFps]            : mean FPS across the measurement window (0 if not measured).
 *                       For multi-run configs, this is the AVERAGE across all completed runs.
 * [minFps]            : minimum FPS recorded (> 1; 0 if not measured).
 *                       For multi-run configs, this is the average of each run's min.
 * [maxFps]            : maximum FPS recorded (0 if not measured).
 *                       For multi-run configs, this is the average of each run's max.
 * [fpsStdDev]         : population standard deviation of per-second readings (0 if < 2 readings).
 *                       For multi-run configs, this is the maximum stdDev across all runs
 *                       (conservative: take the worst-case stability reading).
 * [gpuTempStartC]     : GPU temperature at trial start (degrees C; -1 = unreadable).
 * [gpuTempEndC]       : GPU temperature just before teardown (-1 = unreadable).
 * [throttleSuspect]   : true if gpuTempEndC exceeded gpuTempStartC by >= THROTTLE_DELTA_C.
 *                       For multi-run configs, true if ANY run was flagged.
 * [goalScore]         : goal-specific numeric score (higher = better); set by AutoTunerEngine
 *                       during ranking so the UI can display a relative bar chart.
 * [trialIndex]        : 0-based position in the sweep plan.
 * [runsCompleted]     : number of individual measurement runs that were averaged into this result.
 *                       1 for QUICK mode (single-run); 2 for STANDARD/THOROUGH.
 * [outlierFlagged]    : true if the multi-run FPS readings differed by > 40%, suggesting
 *                       one run was a thermal/cache artifact. The average is still used but
 *                       this flag lets the UI surface a warning.
 */
data class TunerResult(
    val config: ContainerData,
    val description: String,
    val status: TrialStatus,
    val avgFps: Float = 0f,
    val minFps: Float = 0f,
    val maxFps: Float = 0f,
    val fpsStdDev: Float = 0f,
    val gpuTempStartC: Int = -1,
    val gpuTempEndC: Int = -1,
    val throttleSuspect: Boolean = false,
    val goalScore: Float = 0f,
    val trialIndex: Int = 0,
    /** Number of individual measurement runs averaged into this result. */
    val runsCompleted: Int = 1,
    /** True if multi-run FPS readings differed by > 40% — one run may be a thermal artifact. */
    val outlierFlagged: Boolean = false,
) {
    enum class TrialStatus {
        /** Completed measurement; avgFps >= playable threshold */
        STABLE,
        /** Completed measurement; avgFps < playable threshold */
        UNSTABLE,
        /** Process did not produce a game window in time */
        HUNG,
        /** GuestProgramTerminated fired during or before measurement */
        CRASHED,
        /** Skipped (e.g. identical to previously tested config, or sweep cancelled) */
        SKIPPED,
    }

    /** True if this result counts as a successful run for ranking purposes. */
    val isUsable: Boolean get() = status == TrialStatus.STABLE

    companion object {
        /** Temperature delta (degrees C) above which we flag a throttle suspicion. */
        const val THROTTLE_DELTA_C = 8

        /** FPS floor below which a completed run is UNSTABLE rather than STABLE. */
        const val PLAYABLE_FPS_THRESHOLD = 25f
    }

    /**
     * Computes a "lightness" score for LOW_END goal ranking.
     * Lower = lighter (preferred by LOW_END comparator).
     *
     *  driverScore: "System" = 0, everything else = 1.
     *  vramRank: approximate rank from common values ("1024" < "2048" < "3072" < "4096").
     */
    fun lightnessScore(): Int {
        val driverScore = if (config.graphicsDriver.equals("System", ignoreCase = true)) 0 else 1
        val vramRank = when (config.videoMemorySize) {
            "1024" -> 0
            "2048" -> 1
            "3072" -> 2
            "4096" -> 3
            else -> 4
        }
        return driverScore * 100 + vramRank
    }
}
