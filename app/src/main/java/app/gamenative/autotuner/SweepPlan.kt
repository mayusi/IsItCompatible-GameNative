package app.gamenative.autotuner

import android.content.Context
import app.gamenative.utils.ContainerUtils
import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.ContainerData
import timber.log.Timber

/**
 * Sweep mode controls how many dimensions, trials, and runs-per-config are used.
 *
 * [PROBE]    ~8 config slots × 1 run = ~8 launches — ~7-12 min
 *            Flat archetype list (no coordinate descent).
 *            Scoring: boot-render only (bootSucceeded flag). Used by COMPAT_PROBE goal.
 *            warmupSec=8, measureSec=20, early-abort-on-boot.
 *            Includes 2 D3D9-path archetypes (Turnip+wined3d, System+wined3d+COMPAT).
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
 * Time budget notes:
 *   PROBE: warmup 8 s + measure 20 s + teardown ~60 s ≈ 1.5 min/launch → ~6–8 min total.
 *   QUICK/STANDARD/THOROUGH: warmup 15 s + measure 45 s + teardown ~60 s ≈ 2 min/launch.
 */
enum class SweepMode(val label: String, val description: String, val runsPerConfig: Int) {
    PROBE(
        "Probe (~8 runs, ~7–12 min)",
        "Fast boot check: 8 curated archetypes (incl. D3D9 paths), boot-render scoring only. No coord-descent.",
        runsPerConfig = 1,
    ),
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
 * [dimId]       : which dimension is being varied (null for the baseline trial or probe trials).
 * [valueLabel]  : which value within that dimension.
 * [passIndex]   : 0 for the first coordinate-descent pass; 1 for the optional second pass.
 * [isProbe]     : true for COMPAT_PROBE archetype trials (flat list, no coord-descent).
 */
data class TrialDefinition(
    val config: ContainerData,
    val description: String,
    val dimId: String?,
    val valueLabel: String,
    val passIndex: Int = 0,
    val isProbe: Boolean = false,
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
 * PROBE MODE
 * ==========
 * [buildProbeTrials] returns a flat list of ~6 curated archetype configs that cover the
 * diversity of real-device combinations seen on the Odin / Adreno 8-series.  These are
 * NOT coordinate-descent variants — each is a fully-specified config.  The engine runs
 * them in order and early-aborts on the first boot-passing config (bootSucceeded=true).
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
         * Timing constants for PROBE mode (different from the normal 15/45 s warmup/measure).
         * The engine checks these when mode == PROBE to override its defaults.
         */
        const val PROBE_WARMUP_SEC = 8
        const val PROBE_MEASURE_SEC = 20

        /**
         * Build the initial sweep plan for [mode] starting from [baseline].
         *
         * For PROBE mode use [buildProbeTrials] directly instead.
         *
         * The returned plan contains all trials for the FIRST dimension only (pass 1 dimension A).
         * The engine calls [buildDimensionTrials] iteratively once it knows the winner of each dim.
         *
         * @param mode        sweep depth
         * @param baseline    starting ContainerData (should be the device's default from ContainerUtils)
         * @return            a SweepPlan with trials for the first dimension
         */
        fun build(mode: SweepMode, baseline: ContainerData): SweepPlan {
            if (mode == SweepMode.PROBE) {
                val probeTrials = buildProbeTrials(baseline)
                return SweepPlan(mode, baseline, probeTrials)
            }
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
                    isProbe = false,
                )
            }
        }

        /**
         * Builds the flat COMPAT_PROBE archetype trial list.
         *
         * Returns ~6 curated configs covering the primary diversity axes seen on Adreno 8-series
         * devices (Odin 2 Pro, etc.):
         *   1. System + dxvk               — safe baseline (works on all Vulkan-capable GPUs)
         *   2. System + wined3d            — software DX path (for games that don't like dxvk)
         *   3. Turnip v26.2.0 R4 + dxvk   — latest upstream Turnip, dxvk (most common DX path)
         *   4. Turnip v26.2.0 R4 + vkd3d  — latest Turnip, DX12 path
         *   5. Turnip Gen8V30 + dxvk       — Gen8 V30 (Adreno 8 Elite Gen5)
         *   6. Turnip Gen8V25 + dxvk       — Gen8 V25 baseline (Adreno 830/750/740)
         *        with Box64 INTERMEDIATE to give a more forgiving compatibility posture
         *
         * These are NOT coordinate-descent variants — each is a fully-specified config
         * derived from [baseline] so it inherits device defaults (VRAM, Wine version, etc.)
         * for any field not explicitly overridden here.
         *
         * Config values are grounded in real TunerDimension / ContainerData field names.
         *
         * @param baseline  device default ContainerData to derive from
         * @return          ordered list of TrialDefinitions with isProbe=true
         */
        fun buildProbeTrials(baseline: ContainerData): List<TrialDefinition> {
            data class Archetype(
                val label: String,
                val driver: String,
                val driverVersion: String,
                val dxwrapper: String,
                val box64Preset: String = Box86_64Preset.INTERMEDIATE,
            )

            val archetypes = listOf(
                Archetype(
                    label = "System + dxvk",
                    driver = "System",
                    driverVersion = "",
                    dxwrapper = "dxvk",
                ),
                Archetype(
                    label = "System + wined3d",
                    driver = "System",
                    driverVersion = "",
                    dxwrapper = "wined3d",
                ),
                Archetype(
                    label = "Turnip v26.2.0 R4 + dxvk",
                    driver = "Wrapper",
                    driverVersion = "Turnip_v26.2.0_R4",
                    dxwrapper = "dxvk",
                ),
                Archetype(
                    label = "Turnip v26.2.0 R4 + vkd3d",
                    driver = "Wrapper",
                    driverVersion = "Turnip_v26.2.0_R4",
                    dxwrapper = "vkd3d",
                ),
                Archetype(
                    label = "Turnip Gen8V30 + dxvk",
                    driver = "Wrapper",
                    driverVersion = "Turnip_Gen8_V30",
                    dxwrapper = "dxvk",
                    box64Preset = Box86_64Preset.INTERMEDIATE,
                ),
                Archetype(
                    label = "Turnip Gen8V25 + dxvk",
                    driver = "Wrapper",
                    driverVersion = "Turnip_Gen8_V25",
                    dxwrapper = "dxvk",
                    box64Preset = Box86_64Preset.INTERMEDIATE,
                ),
                // Archetype 7: Turnip + wined3d (older D3D9 games — DMC1-class)
                // wined3d avoids Vulkan translation entirely; useful for games that
                // break with dxvk/vkd3d but work under the D3D9 software path.
                Archetype(
                    label = "Turnip v26.2.0 R4 + wined3d",
                    driver = "Wrapper",
                    driverVersion = "Turnip_v26.2.0_R4",
                    dxwrapper = "wined3d",
                    box64Preset = Box86_64Preset.INTERMEDIATE,
                ),
                // Archetype 8: System + wined3d + COMPATIBILITY box64 preset
                // Maximum compatibility posture for D3D9 games: no Turnip overhead,
                // software DX path, and the most conservative Box64 JIT settings.
                Archetype(
                    label = "System + wined3d + Box64 COMPAT",
                    driver = "System",
                    driverVersion = "",
                    dxwrapper = "wined3d",
                    box64Preset = Box86_64Preset.COMPATIBILITY,
                ),
            )

            return archetypes.mapIndexed { idx, arch ->
                val cfg = baseline.copy(
                    graphicsDriver = arch.driver,
                    graphicsDriverVersion = arch.driverVersion,
                    dxwrapper = arch.dxwrapper,
                    box64Preset = arch.box64Preset,
                )
                TrialDefinition(
                    config = cfg,
                    description = "Probe ${idx + 1}/${archetypes.size}: ${arch.label}",
                    dimId = null,
                    valueLabel = arch.label,
                    passIndex = 0,
                    isProbe = true,
                )
            }
        }

        /**
         * Returns the ordered list of [TunerDimension]s to sweep for a given [SweepMode].
         * Returns empty list for PROBE (not a coordinate-descent sweep).
         */
        fun dimensionsForMode(mode: SweepMode): List<TunerDimension> = when (mode) {
            SweepMode.PROBE -> emptyList()
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
         * display). THOROUGH adds one extra dimension-A pass. Multiplied by runsPerConfig
         * so the progress bar reflects the true number of launches.
         *
         * PROBE mode returns the fixed probe archetype count (8 launches).
         */
        fun estimatedTrialCount(mode: SweepMode): Int {
            if (mode == SweepMode.PROBE) return 8  // 6 original + 2 D3D9 archetypes
            val dims = dimensionsForMode(mode)
            val configSlots = dims.sumOf { it.values.size } +
                if (mode == SweepMode.THOROUGH) TunerDimension.GRAPHICS_DRIVER.values.size else 0
            return configSlots * mode.runsPerConfig
        }

        /**
         * Estimated total minutes for [mode], rounded up.
         *
         * PROBE: ~1.5 min/launch (8 s warmup + 20 s measure + ~60 s teardown).
         * Others: ~2 min/launch (15 s warmup + 45 s measure + ~60 s teardown).
         */
        fun estimatedMinutes(mode: SweepMode): Int {
            return if (mode == SweepMode.PROBE) {
                (estimatedTrialCount(mode) * 1.5).toInt().coerceAtLeast(5)
            } else {
                estimatedTrialCount(mode) * 2
            }
        }
    }
}
