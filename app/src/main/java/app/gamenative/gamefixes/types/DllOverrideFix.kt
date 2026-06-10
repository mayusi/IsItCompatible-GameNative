package app.gamenative.gamefixes.types

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFix
import app.gamenative.gamefixes.KeyedGameFix
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

/**
 * A [GameFix] that MERGES new DLL override entries into WINEDLLOVERRIDES rather than
 * skipping when the env var already exists.
 *
 * This fixes a latent bug in [WineEnvVarFix]: it skips any env var that the user has
 * already configured, so game-fix DLL overrides are silently ignored when the user has
 * set ANY custom WINEDLLOVERRIDES value.  This fix reads the existing value, parses it,
 * and only adds entries that are not already present — preserving user customisation.
 *
 * Format: Each override is "dll=mode" (e.g. "d3dcompiler_47=n,b"), entries separated
 * by ";".  Matches the Wine WINEDLLOVERRIDES syntax.
 *
 * @param overrides Map of dll-name -> mode string (e.g. mapOf("eossdk-win64-shipping" to "n,b")).
 */
class DllOverrideFix(
    private val overrides: Map<String, String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        return try {
            val envVars = EnvVars(container.envVars)

            // Parse existing WINEDLLOVERRIDES into a mutable map keyed by dll name.
            val existingRaw = envVars.get("WINEDLLOVERRIDES")
            val existing = parseOverrides(existingRaw).toMutableMap()

            var added = 0
            for ((dll, mode) in overrides) {
                if (existing.containsKey(dll)) {
                    // Already present — respect user / prior-fix configuration.
                    Timber.tag("DllOverrideFix").d("DLL override for '$dll' already present — skipping for game $gameId")
                    continue
                }
                existing[dll] = mode
                added++
            }

            if (added == 0) {
                // Nothing to do.
                return true
            }

            val merged = existing.entries.joinToString(";") { (dll, mode) -> "$dll=$mode" }
            envVars.put("WINEDLLOVERRIDES", merged)
            container.envVars = envVars.toString()
            container.saveData()
            Timber.tag("DllOverrideFix").i("Merged $added DLL override(s) into WINEDLLOVERRIDES for game $gameId: $overrides")
            true
        } catch (e: Exception) {
            Timber.tag("DllOverrideFix").e(e, "Failed to merge DLL overrides for game $gameId")
            false
        }
    }

    companion object {
        /**
         * Parse a WINEDLLOVERRIDES string into a map of dll-name -> mode.
         * Supports ";" as entry separator and "=" as key/value separator.
         * Handles both "dll=mode" and bare "dll" (treated as mode "").
         * Handles legacy space-separated format used by older WineEnvVarFix entries.
         */
        fun parseOverrides(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            // Support both ";" and " " as separators for broader compatibility.
            val separator = if (raw.contains(';')) ";" else " "
            for (entry in raw.split(separator)) {
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) continue
                val eqIdx = trimmed.indexOf('=')
                if (eqIdx < 0) {
                    map[trimmed] = ""
                } else {
                    val dll = trimmed.substring(0, eqIdx).trim()
                    val mode = trimmed.substring(eqIdx + 1).trim()
                    if (dll.isNotEmpty()) map[dll] = mode
                }
            }
            return map
        }
    }
}

class KeyedDllOverrideFix(
    override val gameSource: GameSource,
    override val gameId: String,
    overrides: Map<String, String>,
) : KeyedGameFix, GameFix by DllOverrideFix(overrides)
