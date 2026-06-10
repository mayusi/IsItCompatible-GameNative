package app.gamenative.autotuner

/**
 * Per-goal weighting vector for the composite scorer.
 *
 * Each weight is in [0..1] and should sum to roughly 1.0, but the scorer
 * normalises via the weight sum, so arbitrary magnitudes are fine.
 *
 * [fps]         : weight for normalised average-FPS score (0=don't care, 1=maximise)
 * [stability]   : weight for normalised stability score  (higher = smoother preferred)
 * [battery]     : weight for normalised battery score    (higher = lower watts preferred)
 * [temperature] : weight for normalised temperature score (higher = cooler preferred)
 *
 * The [noSignalBattery] vector is used when [battery] > 0 but the device is charging
 * (battery drain signal unavailable), so the goal gracefully degrades.
 */
data class GoalWeights(
    val fps: Float,
    val stability: Float,
    val battery: Float,
    val temperature: Float,
) {
    /** Total weight (for normalised composite). */
    val total: Float get() = fps + stability + battery + temperature

    /**
     * Returns a version of this weight set with the battery weight redistributed
     * into fps+stability proportionally, used when battery signal is unavailable.
     */
    fun withoutBatterySignal(): GoalWeights {
        if (battery == 0f) return this
        val totalNoBatt = fps + stability + temperature
        return if (totalNoBatt > 0f) {
            val scale = (total - battery) / totalNoBatt
            copy(
                fps = fps * scale / (total - battery) * (fps + stability + temperature),
                stability = stability * scale / (total - battery) * (fps + stability + temperature),
                battery = 0f,
                temperature = temperature * scale / (total - battery) * (fps + stability + temperature),
            )
        } else {
            GoalWeights(fps = 0.75f, stability = 0.25f, battery = 0f, temperature = 0f)
        }
    }

    companion object {
        /** Convenient canonical no-battery fallback for FPS_BATTERY goal. */
        val FPS_BATTERY_FALLBACK = GoalWeights(fps = 0.75f, stability = 0.25f, battery = 0f, temperature = 0f)
    }
}

/**
 * The optimisation objective for an auto-tuner sweep.
 *
 * Seven intents covering compatibility probe, FPS-axis variants, and custom weights.
 *
 * Each goal exposes:
 *  - [label]               : short human-readable name shown in UI
 *  - [description]         : one-line description shown in UI
 *  - [weights]             : GoalWeights for composite post-sweep scoring
 *  - [isFastProbe]         : true only for COMPAT_PROBE (flat trial list, no coord-descent)
 *  - [requiresBatterySignal]: true if battery drain is a primary metric (FPS_BATTERY)
 *  - [comparator]          : simple comparator for STABLE results; used by simple goals and
 *                            as tie-breaker; composite goals use rankResults() override.
 *
 * COMPAT_PROBE uses SweepMode.PROBE automatically — the engine detects isFastProbe and
 * runs a flat loop over the probe archetype list instead of coordinate descent.
 *
 * LOW_END retains the existing lightnessScore comparator path (no composite needed).
 *
 * CUSTOM carries user-supplied weights via [customWeights]; pass them via
 * AutoTunerEngine.run()/startTuning() as the optional customWeights parameter.
 *
 * OLD ENUM NAMES (for migration reference):
 *   MAX_STABILITY → FPS_STABLE
 *   HIGH_END      → (removed; FPS_STABLE covers it with a stability weight)
 * UI `when` statements that reference MAX_STABILITY or HIGH_END must be updated by the UI agent.
 */
enum class TunerGoal(
    val label: String,
    val description: String,
    val weights: GoalWeights,
    /** True if this goal runs COMPAT_PROBE mode (flat archetype list, early-abort-on-boot). */
    val isFastProbe: Boolean = false,
    /** True if battery drain is a primary metric; the engine warns if not discharging. */
    val requiresBatterySignal: Boolean = false,
    /** Returns negative if [a] ranks better than [b] (used in sortedWith for simple goals). */
    val comparator: Comparator<TunerResult>,
) {
    /**
     * COMPAT_PROBE — "Does it even run?"
     *
     * Fastest mode. Runs ~5-6 curated archetype configs with a PROBE sweep (1 run, 8 s warmup,
     * 20 s measure). Scoring is BOOT-only: did the game window appear without crashing?
     * Winner = first boot-passing config; partial passes ranked by crash-time (later = better).
     * Expect ~5-8 min total.
     */
    COMPAT_PROBE(
        label = "Compatibility Probe",
        description = "Fast check: does the game boot at all? Tests ~6 archetypes in ~5-8 min.",
        weights = GoalWeights(fps = 0f, stability = 0f, battery = 0f, temperature = 0f),
        isFastProbe = true,
        requiresBatterySignal = false,
        comparator = compareByDescending<TunerResult> { it.bootSucceeded }
            .thenByDescending { it.avgFps },  // avgFps used as proxy for survival time in probe
    ),

    /**
     * MAX_FPS — Maximise raw average FPS among STABLE results.
     * Tie-break: lower stdDev preferred (smoother wins on equal avg fps).
     */
    MAX_FPS(
        label = "Max FPS",
        description = "Find the config that delivers the highest average frame rate.",
        weights = GoalWeights(fps = 1.0f, stability = 0.05f, battery = 0f, temperature = 0f),
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.fpsStdDev },
    ),

    /**
     * FPS_STABLE — FPS + stability composite. Best balance for competitive/online play.
     * Formerly MAX_STABILITY (renamed; the UI agent must update any `when` on MAX_STABILITY).
     */
    FPS_STABLE(
        label = "FPS + Stability",
        description = "Best balance of frame rate and smoothness — ideal for competitive/online play.",
        weights = GoalWeights(fps = 0.6f, stability = 0.4f, battery = 0f, temperature = 0f),
        comparator = compareBy<TunerResult> { it.fpsStdDev }
            .thenByDescending { it.avgFps },
    ),

    /**
     * FPS_BATTERY — FPS per watt. Requires device to be discharging during sweep.
     * Falls back to (0.75, 0.25, 0, 0) weights if battery signal is unavailable.
     * Requires battery drain sysfs path to be readable (see BatteryReader).
     */
    FPS_BATTERY(
        label = "FPS + Battery",
        description = "Best FPS per watt — extends playtime. Device must be unplugged during sweep.",
        weights = GoalWeights(fps = 0.6f, stability = 0.1f, battery = 0.3f, temperature = 0f),
        requiresBatterySignal = true,
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.fpsStdDev },
    ),

    /**
     * FPS_COOL — FPS with a thermal penalty. Prefers configs that keep the GPU coolest
     * at a given FPS level. Useful for long sessions or warm environments.
     */
    FPS_COOL(
        label = "FPS + Cool",
        description = "Best FPS while keeping thermals low — good for long play sessions.",
        weights = GoalWeights(fps = 0.65f, stability = 0.1f, battery = 0f, temperature = 0.25f),
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.gpuTempEndC },
    ),

    /**
     * LOW_END — Lightest STABLE config above the playable threshold.
     * Prefers System driver (no Wrapper overhead), lower VRAM.
     * Falls back to best-avg among all STABLE if nothing hits the threshold.
     * Uses the lightnessScore comparator, not composite scoring.
     */
    LOW_END(
        label = "Low-End Friendly",
        description = "Lightest stable config — for modest hardware or battery conservation.",
        weights = GoalWeights(fps = 0.3f, stability = 0.3f, battery = 0.2f, temperature = 0.2f),
        comparator = compareBy<TunerResult> { it.lightnessScore() }
            .thenByDescending { it.avgFps },
    ),

    /**
     * CUSTOM — User-defined weighted composite via GoalWeights sliders.
     * Weights are passed to the engine via the optional customWeights parameter in
     * AutoTunerEngine constructor / startTuning(). The engine substitutes them for
     * the default CUSTOM weights at ranking time.
     */
    CUSTOM(
        label = "Custom Weights",
        description = "Set your own FPS/stability/battery/temperature weights via sliders.",
        weights = GoalWeights(fps = 0.5f, stability = 0.3f, battery = 0.1f, temperature = 0.1f),
        comparator = compareByDescending<TunerResult> { it.avgFps }
            .thenBy { it.fpsStdDev },
    ),
}
