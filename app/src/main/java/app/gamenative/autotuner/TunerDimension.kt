package app.gamenative.autotuner

import com.winlator.box86_64.Box86_64Preset
import com.winlator.container.ContainerData
import com.winlator.fexcore.FEXCorePreset

/**
 * Models a single dimension that the coordinate-descent sweep explores.
 *
 * Each dimension holds:
 *  [id]          : stable identifier for logging / de-dup.
 *  [label]       : human-readable name for UI progress display.
 *  [values]      : ordered list of (ContainerData -> ContainerData) mutators, each
 *                  representing one candidate value for this dimension.
 *  [valueLabels] : parallel human-readable labels for each mutator in [values].
 *
 * The sweep applies each mutator to a clone of the current "best so far" config,
 * runs the trial, and keeps the best-performing variant as the new baseline for
 * the next dimension.
 *
 * Value sets are grounded in real ContainerData fields, Box86_64Preset,
 * FEXCorePreset, and DeviceProfileDetector observed defaults — no invented values.
 */
data class TunerDimension(
    val id: String,
    val label: String,
    val values: List<(ContainerData) -> ContainerData>,
    val valueLabels: List<String>,
) {
    init {
        require(values.size == valueLabels.size) {
            "TunerDimension '$id': values.size (${values.size}) != valueLabels.size (${valueLabels.size})"
        }
        require(values.isNotEmpty()) { "TunerDimension '$id': must have at least one value" }
    }

    companion object {

        // -----------------------------------------------------------------------
        // Dimension A: Graphics Driver + Driver Version
        // Grounded in DeviceProfileDetector and ContainerUtils.setContainerDefaults
        // -----------------------------------------------------------------------
        val GRAPHICS_DRIVER: TunerDimension = TunerDimension(
            id = "graphics_driver",
            label = "Graphics Driver",
            values = listOf(
                // System (mesa / stock Vulkan — safe for all GPUs)
                { base: ContainerData ->
                    base.copy(
                        graphicsDriver = "System",
                        graphicsDriverVersion = "",
                    )
                },
                // Wrapper — Turnip Gen8 V25 (baseline for Adreno 830/750/740)
                { base: ContainerData ->
                    base.copy(
                        graphicsDriver = "Wrapper",
                        graphicsDriverVersion = "Turnip_Gen8_V25",
                    )
                },
                // Wrapper — Turnip 26.2.0 R4 (latest for Adreno 8xx, from setContainerDefaults)
                { base: ContainerData ->
                    base.copy(
                        graphicsDriver = "Wrapper",
                        graphicsDriverVersion = "Turnip_v26.2.0_R4",
                    )
                },
                // Wrapper — Turnip Gen8 V30 (Adreno 8 Elite Gen5, from setContainerDefaults)
                { base: ContainerData ->
                    base.copy(
                        graphicsDriver = "Wrapper",
                        graphicsDriverVersion = "Turnip_Gen8_V30",
                    )
                },
                // Wrapper — Adreno Turnip T26 @Mr_Purple_666 (8 Elite 8gen5 alt)
                { base: ContainerData ->
                    base.copy(
                        graphicsDriver = "Wrapper",
                        graphicsDriverVersion = "Turnip Adreno Driver T26 (@Mr_Purple_666)",
                    )
                },
            ),
            valueLabels = listOf(
                "System (stock Vulkan)",
                "Wrapper: Turnip Gen8 V25",
                "Wrapper: Turnip v26.2.0 R4",
                "Wrapper: Turnip Gen8 V30",
                "Wrapper: Turnip T26 @Purple",
            ),
        )

        // -----------------------------------------------------------------------
        // Dimension B: DX Wrapper
        // Grounded in Container.DEFAULT_DXWRAPPER = "dxvk", ContainerUtils
        // -----------------------------------------------------------------------
        val DX_WRAPPER: TunerDimension = TunerDimension(
            id = "dx_wrapper",
            label = "DX Wrapper",
            values = listOf(
                // dxvk — default for all Adreno profiles (DeviceProfileDetector always sets dxvk)
                { base: ContainerData -> base.copy(dxwrapper = "dxvk") },
                // vkd3d — DX12 path
                { base: ContainerData -> base.copy(dxwrapper = "vkd3d") },
                // wined3d — software path, DX < 9 games
                { base: ContainerData -> base.copy(dxwrapper = "wined3d") },
            ),
            valueLabels = listOf(
                "dxvk",
                "vkd3d",
                "wined3d",
            ),
        )

        // -----------------------------------------------------------------------
        // Dimension C: Box64 Preset
        // Grounded in Box86_64Preset constants (Box86_64Preset.java)
        // -----------------------------------------------------------------------
        val BOX64_PRESET: TunerDimension = TunerDimension(
            id = "box64_preset",
            label = "Box64 Preset",
            values = listOf(
                { base: ContainerData -> base.copy(box64Preset = Box86_64Preset.COMPATIBILITY) },
                { base: ContainerData -> base.copy(box64Preset = Box86_64Preset.INTERMEDIATE) },
                { base: ContainerData -> base.copy(box64Preset = Box86_64Preset.PERFORMANCE) },
                { base: ContainerData -> base.copy(box64Preset = Box86_64Preset.STABILITY) },
            ),
            valueLabels = listOf(
                "Box64: COMPATIBILITY",
                "Box64: INTERMEDIATE",
                "Box64: PERFORMANCE",
                "Box64: STABILITY",
            ),
        )

        // -----------------------------------------------------------------------
        // Dimension D: FEXCore Preset (arm64ec path; optional for Standard/Thorough)
        // Grounded in FEXCorePreset constants (FEXCorePreset.java)
        // -----------------------------------------------------------------------
        val FEXCORE_PRESET: TunerDimension = TunerDimension(
            id = "fexcore_preset",
            label = "FEXCore Preset",
            values = listOf(
                { base: ContainerData -> base.copy(fexcorePreset = FEXCorePreset.COMPATIBILITY) },
                { base: ContainerData -> base.copy(fexcorePreset = FEXCorePreset.INTERMEDIATE) },
                { base: ContainerData -> base.copy(fexcorePreset = FEXCorePreset.PERFORMANCE) },
                { base: ContainerData -> base.copy(fexcorePreset = FEXCorePreset.EXTREME) },
            ),
            valueLabels = listOf(
                "FEXCore: COMPATIBILITY",
                "FEXCore: INTERMEDIATE",
                "FEXCore: PERFORMANCE",
                "FEXCore: EXTREME",
            ),
        )

        // -----------------------------------------------------------------------
        // Dimension E: Video Memory Size (optional for Thorough)
        // Grounded in DeviceProfileDetector defaults + common values
        // -----------------------------------------------------------------------
        val VIDEO_MEMORY: TunerDimension = TunerDimension(
            id = "video_memory",
            label = "Video Memory",
            values = listOf(
                { base: ContainerData -> base.copy(videoMemorySize = "1024") },
                { base: ContainerData -> base.copy(videoMemorySize = "2048") },
                { base: ContainerData -> base.copy(videoMemorySize = "3072") },
                { base: ContainerData -> base.copy(videoMemorySize = "4096") },
            ),
            valueLabels = listOf(
                "VRAM: 1024 MB",
                "VRAM: 2048 MB",
                "VRAM: 3072 MB",
                "VRAM: 4096 MB",
            ),
        )

        // -----------------------------------------------------------------------
        // Helper: all available dimensions in canonical order
        // -----------------------------------------------------------------------
        val ALL: List<TunerDimension> = listOf(
            GRAPHICS_DRIVER,
            DX_WRAPPER,
            BOX64_PRESET,
            FEXCORE_PRESET,
            VIDEO_MEMORY,
        )
    }
}
