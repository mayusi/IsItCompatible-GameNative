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
}
