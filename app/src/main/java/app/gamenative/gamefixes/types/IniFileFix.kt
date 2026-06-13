package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import java.nio.charset.StandardCharsets
import timber.log.Timber

private fun updateIniValue(content: String, key: String, value: String): String {
    val regex = Regex("(?im)^(${Regex.escape(key)}\\s*=\\s*).*$")
    return if (regex.containsMatchIn(content)) {
        content.replace(regex, "$1$value")
    } else {
        val suffix = if (content.endsWith("\n") || content.isEmpty()) "" else System.lineSeparator()
        content + suffix + "$key=$value" + System.lineSeparator()
    }
}

class IniFileFix(
    private val relativePath: String,
    private val defaultValues: Map<String, String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val iniFile = File(installPath, relativePath)

        // Runtime canonical-path guard: ensure the resolved path stays within installPath.
        // This catches any edge-cases that the parse-time requireNoPathTraversal missed,
        // e.g. symlink-based escapes or platform-specific path weirdness.
        val baseDirCanonical = File(installPath).canonicalPath
        val iniFileCanonical = iniFile.canonicalPath
        if (!iniFileCanonical.startsWith(baseDirCanonical + File.separator) &&
            iniFileCanonical != baseDirCanonical
        ) {
            Timber.tag("GameFixes").w(
                "IniFileFix: canonical path '$iniFileCanonical' escapes installPath '$baseDirCanonical' — aborting fix for game $gameId"
            )
            return false
        }

        if (!iniFile.isFile) {
            return false
        }

        return runCatching {
            val original = iniFile.readText(StandardCharsets.UTF_8)
            var updated = original
            for ((key, value) in defaultValues) {
                updated = updateIniValue(updated, key, value)
            }

            val fileChanged = updated != original

            if (fileChanged) {
                iniFile.writeText(updated, StandardCharsets.UTF_8)
            }

            if (fileChanged) {
                Timber.tag("GameFixes").i("Updated $relativePath for game $gameId")
            }

            fileChanged
        }.getOrElse { error ->
            Timber.tag("GameFixes").w(error, "Failed to update $relativePath for game $gameId")
            false
        }
    }
}

class KeyedIniFileFix(
    override val gameSource: GameSource,
    override val gameId: String,
    relativePath: String,
    defaultValues: Map<String, String>,
) : KeyedGameFix, GameFix by IniFileFix(relativePath, defaultValues)
