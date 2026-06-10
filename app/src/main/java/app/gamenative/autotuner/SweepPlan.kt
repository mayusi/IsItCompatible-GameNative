package app.gamenative.autotuner

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.container.ContainerData
import timber.log.Timber

/**
 * Sweep mode controls how many dimensions, trials, and runs-per-config are used.
 *
 * [QUICK]    ~8 config slots  × 1 run = ~8 launches  — ~10–15 min
 *            Dimensions A + B + C (driver, dxwrapper, box64preset).
 *            Single-run to keep it fast; no averaging.
 *
 * [STANDARD] ~11 config slots × 2 runs = ~22 launches — ~35–45 min
 *            Adds FEXCore preset sweep.
 *            2 runs per config with interleaved ordering (each config gets one
 *            "cooler" sample and one "warmer" sample within each dimension), averaged.
 *
 * [THOROUGH] ~16 config slots × 2 runs = ~32 launches — ~50–65 min
 *            All 5 dimensions + second-pass driver re-sweep.
 *            2 runs per config, interleaved. Most reliable winner.
 *
 * Time budget notes: each "launch" is ~warmup(15 s) + measure(45 s) + teardown/cooldown(~60 s)
 * = ~2 min per launch. Estimates above use 2 min/launch with thermal-gate overhead.
 */
enum class SweepMode(val label: String, val description: String, val runsPerConfig: Int) {
    QUICK(
        "Quick (~8 runs, ~10–15 min)",
        "Tests driver, DX wrapper, and Box64 preset. Single-run, fastest.",
        runsPerConfig = 1,
    ),
    STANDARD(
        "Standard (~22 runs, ~35–45 min)",
        "Adds FEXCore preset. 2 runs per config, interleaved for thermal fairness.",
        runsPerConfig = 2,
    ),
    THOROUGH(
        "Thorough (~32 runs, ~50–65 min)",
        "All dimensions + second-pass driver re-sweep. 2 runs per config, most reliable.",
        runsPerConfig = 2,
    ),
}

/**
 * A single trial definition produced by the sweep plan.
 *
 * [config]      : ContainerData to apply for this trial (full override of the baseline).
 * [description] : human-readable string, e.g. "Pass 1 · Driver: System (stock Vulkan)".
 * [dimId]       : which dimension is being varied (null for the baseline trial).
 * [valueLabel]  : which value within that dimension.
 * [passIndex]   : 0 for the first coordinate-descent pass; 1 for the optional second pass.
 */
data class TrialDefinition(
    val config: ContainerData,
    val description: String,
    val dimId: String?,
    val valueLabel: String,
    val passIndex: Int = 0,
)

/**
 * Builds the ordered list of [TrialDefinition]s for a coordinate-descent sweep.
 *
 * COORDINATE DESCENT LOGIC
 * ========================
 * Starting from a device-specific baseline (obtained from [ContainerUtils.getDefaultContainerData]
 * which already reflects [DeviceProfileDetector]'s one-time tuning), for each dimension:
 *   1. Emit one trial per candidate value in that dimension.
 *   2. After all trials in the dimension run, the engine picks the best and updates its
 *      internal "current best" config.  The next dimension's trials are built from THAT
 *      best config.
 *
 * Because the engine drives the sweep (it holds state), [SweepPlan] emits only the
 * *initial* trial list — all candidate configs rooted in the same static baseline.
 * The engine calls [rebuildFromDimension] after each dimension is complete to produce
 * updated configs for subsequent dimensions.
 *
 * Designed to be rebuilt cheaply; it is a pure data structure with no side effects.
 */
class SweepPlan private constructor(
    val mode: SweepMode,
    val baseline: ContainerData,
    val trials: List<TrialDefinition>,
) {
    companion object {
        private const val TAG = "SweepPlan"

        /**
         * Build the initial sweep plan for [mode] starting from [baseline].
         *
         * The returned plan contains all trials for the FIRST dimension only (pass 1 dimension A).
         * The engine calls [buildDimensionTrials] iteratively once it knows the winner of each dim.
         *
         * @param mode        sweep depth
         * @param baseline    starting ContainerData (should be the device's default from ContainerUtils)
         * @return            a SweepPlan with trials for the first dimension
         */
        fun build(mode: SweepMode, baseline: ContainerData): SweepPlan {
            val dimensions = dimensionsForMode(mode)
            if (dimensions.isEmpty()) {
                return SweepPlan(mode, baseline, emptyList())
            }
            // Build the first dimension's trials from baseline
            val firstDimTrials = buildDimensionTrials(dimensions.first(), baseline, passIndex = 0)
            Timber.tag(TAG).d(
                "SweepPlan built: mode=$mode, dims=${dimensions.size}, " +
                    "firstDim=${dimensions.first().id} (${firstDimTrials.size} trials)",
            )
            return SweepPlan(mode, baseline, firstDimTrials)
        }

        /**
         * Build trial definitions for one dimension, given the current-best [currentBase].
         * Call this iteratively as each dimension resolves.
         *
         * @param dim          the dimension to sweep
         * @param currentBase  ContainerData that is the best config found so far
         * @param passIndex    0 = first pass; 1 = second (Thorough) pass
         */
        fun buildDimensionTrials(
            dim: TunerDimension,
            currentBase: ContainerData,
            passIndex: Int = 0,
        ): List<TrialDefinition> {
            val passLabel = if (passIndex == 0) "Pass 1" else "Pass 2"
            return dim.values.mapIndexed { idx, mutator ->
                val candidate = mutator(currentBase)
                TrialDefinition(
                    config = candidate,
                    description = "$passLabel · ${dim.label}: ${dim.valueLabels[idx]}",
                    dimId = dim.id,
                    valueLabel = dim.valueLabels[idx],
                    passIndex = passIndex,
                )
            }
        }

        /**
         * Returns the ordered list of [TunerDimension]s to sweep for a given [SweepMode].
         */
        fun dimensionsForMode(mode: SweepMode): List<TunerDimension> = when (mode) {
            SweepMode.QUICK -> listOf(
                TunerDimension.GRAPHICS_DRIVER,
                TunerDimension.DX_WRAPPER,
                TunerDimension.BOX64_PRESET,
            )
            SweepMode.STANDARD -> listOf(
                TunerDimension.GRAPHICS_DRIVER,
                TunerDimension.DX_WRAPPER,
                TunerDimension.BOX64_PRESET,
                TunerDimension.FEXCORE_PRESET,
            )
            SweepMode.THOROUGH -> listOf(
                TunerDimension.GRAPHICS_DRIVER,
                TunerDimension.DX_WRAPPER,
                TunerDimension.BOX64_PRESET,
                TunerDimension.FEXCORE_PRESET,
                TunerDimension.VIDEO_MEMORY,
            )
            // Note: THOROUGH also gets a second-pass for GRAPHICS_DRIVER in the engine
        }

        /**
         * Counts the total number of individual game launches for a given mode (for progress
         * display).  THOROUGH adds one extra dimension-A pass.  Multiplied by runsPerConfig
         * so the progress bar reflects the true number of launches.
         */
        fun estimatedTrialCount(mode: SweepMode): Int {
            val dims = dimensionsForMode(mode)
            val configSlots = dims.sumOf { it.values.size } +
                if (mode == SweepMode.THOROUGH) TunerDimension.GRAPHICS_DRIVER.values.size else 0
            return configSlots * mode.runsPerConfig
        }

        /**
         * Estimated total minutes for [mode], rounded up, based on ~2 min per launch
         * (15 s warmup + 45 s measure + ~60 s teardown/cooldown).
         */
        fun estimatedMinutes(mode: SweepMode): Int {
            return estimatedTrialCount(mode) * 2
        }
    }
}
