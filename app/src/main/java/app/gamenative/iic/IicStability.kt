package app.gamenative.iic

/**
 * Shared stability-label logic for all IIC broadcast sites.
 *
 * Uses FPS standard deviation (frame-pacing steadiness) instead of raw average FPS so that
 * a config that hits 45 fps but stutters wildly is correctly labelled GLITCHY rather than
 * PLAYABLE.  Falls back to a conservative avg-FPS bucket when fewer than 3 readings have
 * been collected (stdDev is mathematically unreliable at n=1..2).
 *
 * Call sites:
 *  - XServerScreen.exit() — end-of-session metadata from FrameRating
 *  - AutoTunerResultBroadcaster.send() — per-trial TunerResult from the sweep engine
 */
object IicStability {

    /**
     * Returns a stability label for the given FPS statistics.
     *
     * @param stdDev  Population standard deviation of per-second FPS readings.
     *                0 when fewer than 2 readings were taken (Welford sentinel).
     * @param avgFps  Mean FPS over the measurement window.  0 means nothing was measured.
     * @param samples Number of per-second FPS readings collected.
     *
     * @return One of "PERFECT", "PLAYABLE", "GLITCHY", or "" (empty = nothing measured).
     */
    fun label(stdDev: Float, avgFps: Float, samples: Int): String = when {
        avgFps <= 0f -> ""                                   // nothing measured
        samples in 1..2 || stdDev <= 0f ->                   // too few readings for real variance
            if (avgFps >= 30f) "PLAYABLE" else "GLITCHY"    // fall back to conservative fps bucket
        stdDev <= 3f -> "PERFECT"                            // rock-steady pacing
        stdDev <= 8f -> "PLAYABLE"                           // minor fluctuation
        else -> "GLITCHY"                                    // visible stutter/jank
    }
}
