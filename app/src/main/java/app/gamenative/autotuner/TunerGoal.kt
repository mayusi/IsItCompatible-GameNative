package app.gamenative.autotuner

/**
 * The optimisation objective for an auto-tuner sweep.
 *
 * Each goal exposes:
 *  - [label]       : short human-readable name shown in UI
 *  - [description] : one-line description shown in UI
 *  - [comparator]  : ranks two STABLE results; returns negative when [a] is *better* than [b]
 *                    (i.e. "natural order" with lower index = better).
 *
 * Goals that prefer higher FPS put avgFps descending first.
 * Goals that prefer stability put fpsStdDev ascending first, then avgFps.
 *
 * LOW_END and HIGH_END add soft pre-filtering logic that is applied by
 * AutoTunerEngine.rankResults() before the comparator is called; the comparator
 * is the final tie-breaker inside the filtered set.
 */
enum class TunerGoal(
    val label: String,
    val description: String,
    /** Returns negative if [a] ranks better than [b] (used in sortedWith). */
    val comparator: Comparator<TunerResult>,
) {
    /**
     * Maximise raw average FPS among STABLE results.
     * Tie-break: lower stdDev preferred (smoother wins on equal avg fps).
     */
    MAX_FPS(
        label = "Max FPS",
        description = "Find the config that delivers the highest average frame rate.",
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.fpsStdDev },
    ),

    /**
     * Minimise frame-time variance first (smoothest experience), then maximise FPS.
     * Only STABLE results are included in ranking.
     */
    MAX_STABILITY(
        label = "Max Stability",
        description = "Find the smoothest, crash-free config — best for online or competitive play.",
        comparator = compareBy<TunerResult> { it.fpsStdDev }
            .thenByDescending { it.avgFps },
    ),

    /**
     * For low-end devices: lightest STABLE config above a playable threshold (~25 FPS).
     * Prefers System driver (avoids Turnip/Wrapper overhead), lower VRAM.
     * If nothing hits the threshold, falls back to best-avg among all STABLE.
     *
     * "Lightness" is scored as:
     *   driverScore (System=0, Wrapper=1) * 1000
     *   + vram rank ascending
     * Then avgFps descending as tie-breaker.
     */
    LOW_END(
        label = "Low-End Friendly",
        description = "Find the lightest config that stays playable on modest hardware.",
        comparator = compareBy<TunerResult> { it.lightnessScore() }
            .thenByDescending { it.avgFps },
    ),

    /**
     * For high-end devices: maximise FPS among Wrapper/Turnip-capable STABLE results.
     * Falls back to all STABLE if no Wrapper results are stable.
     */
    HIGH_END(
        label = "High-End Max",
        description = "Squeeze every frame out of flagship-class hardware with Turnip/Wrapper.",
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.fpsStdDev },
    ),
}
