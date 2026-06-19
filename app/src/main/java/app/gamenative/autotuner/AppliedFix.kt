package app.gamenative.autotuner

/**
 * Describes a fix that was applied during a fix-retry attempt.
 * Each variant represents a distinct category of automated fix.
 * Used to populate [TunerResult.appliedFixes] so the UI and per-game persistence can
 * record what the tuner tried and what ultimately worked.
 */
sealed class AppliedFix {
    abstract fun label(): String

    /** WMV intro video files were renamed to .bak to skip on next launch. */
    data class WmvRename(val filesRenamed: Int) : AppliedFix() {
        override fun label(): String = "Skip intro videos ($filesRenamed renamed)"
    }

    /** A DLL override entry was added to WINEDLLOVERRIDES. */
    data class DllOverride(val dll: String, val mode: String) : AppliedFix() {
        override fun label(): String = "$dll=$mode"
    }

    /** A Wine environment variable was set via env-var override. */
    data class WineEnvVar(val key: String, val value: String) : AppliedFix() {
        override fun label(): String = "$key=$value"
    }

    /** A compiled-in / JSON game registry fix was applied. */
    data class GameRegistryFix(val name: String) : AppliedFix() {
        override fun label(): String = "Game fix: $name"
    }

    /** Steam overlay DLL was disabled. */
    object SteamOverlayDisabled : AppliedFix() {
        override fun label(): String = "Steam overlay disabled"
    }

    /** Epic Online Services DLL was disabled. */
    object EosDisabled : AppliedFix() {
        override fun label(): String = "EOS disabled"
    }

    /** A launch argument was appended to force a render path (e.g. "-dx11" to disable DX12). */
    data class LaunchArg(val arg: String) : AppliedFix() {
        override fun label(): String = "Launch arg: $arg"
    }

    /** The DirectX wrapper was switched (e.g. dxvk -> wined3d for an old D3D9 game). */
    data class DxWrapperSwitch(val wrapper: String) : AppliedFix() {
        override fun label(): String = "DirectX wrapper: $wrapper"
    }

    /** The Box64 preset was switched (e.g. -> COMPATIBILITY for a dynarec fault). */
    data class Box64PresetSwitch(val preset: String) : AppliedFix() {
        override fun label(): String = "Box64 preset: $preset"
    }

    /**
     * Goldberg-style Steam API emulation was deployed against the game's install folder.
     * Covers: steam_api[64].dll replacement + steam_settings/ + steam_appid.txt.
     * Only applied for Steam-source games whose DRM check confirmed they are NOT DRM-wrapped.
     */
    object SteamEmulationEnabled : AppliedFix() {
        override fun label(): String = "Steam API emulation enabled"
    }

    /**
     * BUG 5 FIX: Windows components (direct3d, directsound, etc.) were enabled on the container.
     * Applied for old D3D9 games (DMC-era) that need these components to render correctly.
     * The [components] string lists the component keys that were turned on.
     */
    data class WinComponents(val components: String) : AppliedFix() {
        override fun label(): String = "Win components enabled: $components"
    }

    /**
     * Wine runtime native preload library (libevshim.so) was re-copied from the app's current
     * nativeLibraryDir into the stable imagefs usr/lib directory.  Applied when the Android
     * linker rejected Wine/wineserver at startup because the baked-in LD_PRELOAD path pointed
     * at a deleted hash directory from a previous APK install.
     */
    object WineRuntimeRepaired : AppliedFix() {
        override fun label(): String = "Wine runtime repaired (libevshim re-copied)"
    }
}
