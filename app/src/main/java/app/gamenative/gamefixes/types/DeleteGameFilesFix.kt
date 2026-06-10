package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File
import timber.log.Timber

/**
 * Deletes or renames files relative to the game's install path on every launch.
 *
 * This fix resolves issues where specific game files (e.g., corrupted or incompatible
 * video files) cause crashes. Files matching the provided glob patterns are renamed
 * to .bak (reversible and idempotent).
 *
 * Used for games installed via Steam where files are on the game install path,
 * not in drive_c/.
 */
class DeleteGameFilesFix(
    private val installPathRelativeGlobs: List<String>,
) : GameFix {
    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        if (installPath.isEmpty()) {
            Timber.tag("GameFixes").w("Install path is empty for game $gameId; skipping DeleteGameFilesFix")
            return false
        }

        val baseDir = File(installPath)
        if (!baseDir.exists()) {
            Timber.tag("GameFixes").w("Install path does not exist: $installPath for game $gameId")
            return false
        }

        var allSucceeded = true
        for (globPattern in installPathRelativeGlobs) {
            if (globPattern.isBlank()) {
                Timber.tag("GameFixes").w("Blank glob pattern for game $gameId; skipping")
                continue
            }

            try {
                val matchedFiles = globPattern(baseDir, globPattern)
                for (file in matchedFiles) {
                    // Guard: ensure the file is inside installPath (no traversal attacks)
                    if (!file.absolutePath.startsWith(baseDir.absolutePath)) {
                        Timber.tag("GameFixes").w("File traversal detected: ${file.absolutePath} is outside $installPath; skipping")
                        continue
                    }

                    try {
                        // Rename to .bak instead of deleting (reversible and idempotent).
                        // If .bak already exists (e.g. user restored the original), delete the
                        // live original — the .bak copy is the safe backup, so the original can
                        // be removed without data loss.
                        val bakFile = File(file.absolutePath + ".bak")
                        if (bakFile.exists()) {
                            // .bak exists — original was restored; delete the re-appeared original
                            if (file.delete()) {
                                Timber.tag("GameFixes").i("Deleted restored '${file.absolutePath}' (backup ${bakFile.name} already present) for game $gameId")
                            } else {
                                Timber.tag("GameFixes").w("Failed to delete restored '${file.absolutePath}' for game $gameId")
                                allSucceeded = false
                            }
                            continue
                        }

                        if (file.renameTo(bakFile)) {
                            Timber.tag("GameFixes").i("Renamed '${file.absolutePath}' to '${bakFile.absolutePath}' for game $gameId")
                        } else {
                            Timber.tag("GameFixes").w("Failed to rename '${file.absolutePath}' to .bak for game $gameId")
                            allSucceeded = false
                        }
                    } catch (e: Exception) {
                        Timber.tag("GameFixes").e(e, "Error processing file '${file.absolutePath}' for game $gameId")
                        allSucceeded = false
                    }
                }
            } catch (e: Exception) {
                Timber.tag("GameFixes").e(e, "Error globbing pattern '$globPattern' under '$installPath' for game $gameId")
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    /**
     * Simple glob pattern matcher that supports *.ext wildcards.
     * Returns all files matching the pattern under baseDir.
     */
    private fun globPattern(baseDir: File, pattern: String): List<File> {
        val result = mutableListOf<File>()
        val parts = pattern.split('/')
        globRecursive(baseDir, parts, 0, result)
        return result
    }

    private fun globRecursive(currentDir: File, parts: List<String>, partIndex: Int, result: MutableList<File>) {
        if (partIndex >= parts.size) return

        val currentPart = parts[partIndex]
        val isLastPart = partIndex == parts.size - 1

        // Handle wildcard patterns
        if (currentPart.contains('*')) {
            val regex = currentPart.replace(".", "\\.").replace("*", ".*").toRegex()
            val files = currentDir.listFiles() ?: return

            for (file in files) {
                val name = file.name
                if (regex.matches(name)) {
                    if (isLastPart) {
                        result.add(file)
                    } else if (file.isDirectory) {
                        globRecursive(file, parts, partIndex + 1, result)
                    }
                }
            }
        } else {
            // No wildcard; traverse to the next directory
            val nextDir = File(currentDir, currentPart)
            if (nextDir.exists() && nextDir.isDirectory) {
                if (isLastPart) {
                    // This is a directory we were asked to match; not a file
                    return
                }
                globRecursive(nextDir, parts, partIndex + 1, result)
            }
        }
    }
}

class KeyedDeleteGameFilesFix(
    override val gameSource: GameSource,
    override val gameId: String,
    installPathRelativeGlobs: List<String>,
) : KeyedGameFix, GameFix by DeleteGameFilesFix(installPathRelativeGlobs)
