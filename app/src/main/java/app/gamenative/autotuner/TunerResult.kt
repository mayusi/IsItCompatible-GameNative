package app.gamenative.autotuner

import com.winlator.container.ContainerData

/**
 * Per-trial outcome produced by AutoTunerEngine.
 *
 * [config]            : the ContainerData that was tested.
 * [description]       : human-readable description of what was swept (e.g. "Turnip 26.2.0 + dxvk 2.4.1").
 * [shortLabel]        : compact description with the "Pass N · DimName:" prefix stripped,
 *                       e.g. "Turnip V30 + dxvk + Box64 Perf". Populated by aggregateRuns/runTrial.
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
 *
 * --- v1.11.0 IIC additions (all have defaults — existing code compiles unchanged) ---
 *
 * [bootSucceeded]          : true if the game window appeared and did NOT crash within the
 *                            PROBE measure window. Used only by COMPAT_PROBE ranking.
 * [avgPowerW]              : mean power draw in watts during the trial (discharging samples only).
 *                            Null if device was charging or sysfs path unreadable.
 * [chargeCounterDeltaUAh]  : battery charge-counter delta (µAh) from trial start to end.
 *                            Null if unreadable or device was not discharging.
 * [fpsNormScore]           : normalised FPS score [0..1] computed post-sweep (avgFps / maxAvgFps).
 * [stabNormScore]          : normalised stability score [0..1] (1 - fpsStdDev / maxStdDev).
 * [battNormScore]          : normalised battery score [0..1] (1 - avgPowerW / maxPowerW).
 *                            Null if no battery signal was available.
 * [tempNormScore]          : normalised temperature score [0..1] (1 - gpuTempEndC / 80).coerceIn(0,1).
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

    // --- v1.11.0 IIC additions -----------------------------------------------
    /** Short label with "Pass N · DimName:" prefix stripped. e.g. "Turnip V30 + dxvk + Box64 Perf". */
    val shortLabel: String = "",
    /** COMPAT_PROBE: true if game window appeared and did not crash within the probe measure window. */
    val bootSucceeded: Boolean = false,
    /** Mean power draw in watts averaged over discharging-only samples; null if unavailable. */
    val avgPowerW: Float? = null,
    /** Battery charge-counter delta (µAh) over the trial; null if unreadable. */
    val chargeCounterDeltaUAh: Int? = null,
    /** Normalised FPS score in [0..1] (computed post-sweep by composite ranker). */
    val fpsNormScore: Float = 0f,
    /** Normalised stability score in [0..1] (computed post-sweep by composite ranker). */
    val stabNormScore: Float = 0f,
    /** Normalised battery score in [0..1]; null if battery signal not available. */
    val battNormScore: Float? = null,
    /** Normalised temperature score in [0..1] (computed post-sweep by composite ranker). */
    val tempNormScore: Float = 0f,

    // --- v1.12.0 IIC additions -----------------------------------------------
    /** True if a game window mapped but sustained FPS + GPU were near zero (black screen). */
    val blackScreenDetected: Boolean = false,
    /** True if the game window appeared at any point during the trial. */
    val windowMapped: Boolean = false,
    /** Fixes applied during fix-retry attempts that produced this result. */
    val appliedFixes: List<String> = emptyList(),
    /** trialIndex of the base (non-retry) trial this result was a retry of; null if not a retry. */
    val fixRetryOf: Int? = null,
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
        /** Game window appeared but FPS and GPU activity stayed near zero — black screen */
        BLACK_SCREEN,
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

        /** FPS at or below which a frame is considered black-screen / stalled. */
        const val BLACK_SCREEN_FPS_THRESHOLD = 5.0f

        /** GPU busy percentage at or below which the GPU is considered idle (black screen). */
        const val BLACK_SCREEN_GPU_BUSY_THRESHOLD = 8

        /** Consecutive seconds at black-screen FPS+GPU levels before we declare BLACK_SCREEN. */
        const val BLACK_SCREEN_SUSTAIN_SEC = 10

        /** Minimum sustained avgFps across ~5s required for a genuine boot success. */
        const val SUSTAINED_RENDER_FPS_THRESHOLD = 12.0f

        /**
         * Strips the "Pass N · DimName:" prefix from a trial description to produce a
         * compact short label.
         *
         * e.g. "Pass 1 · Driver: Wrapper: Turnip Gen8 V30" → "Turnip Gen8 V30"
         *      "Pass 1 · DX Wrapper: dxvk"                 → "dxvk"
         *      "Turnip-latest + dxvk (probe)"               → "Turnip-latest + dxvk (probe)"
         */
        fun buildShortLabel(description: String): String {
            // Strip "Pass N · <DimLabel>: " prefix if present
            val colonIdx = description.lastIndexOf(": ")
            return if (colonIdx >= 0 && description.contains("·")) {
                description.substring(colonIdx + 2).trim()
            } else {
                description.trim()
            }
        }
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
