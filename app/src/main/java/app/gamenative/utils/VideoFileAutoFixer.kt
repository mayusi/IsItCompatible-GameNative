package app.gamenative.utils

import timber.log.Timber
import java.io.File

/**
 * Renames intro/cutscene WMV files that crash Wine's media-format handler to .bak
 * (reversible, idempotent).  Used by [CrashClassifier] when a WMV3/MFMEDIA crash is
 * detected in Wine debug output.
 *
 * Path-safety: all scanning is restricted to subdirectories immediately under
 * [installPath]; no symlink-following or ".." traversal is attempted.
 */
object VideoFileAutoFixer {

    private val VIDEO_SUBDIRS = listOf(
        "Video", "video", "Movie", "movie", "movies", "Movies", "cutscenes", "Cutscenes",
    )

    private val VIDEO_EXTENSIONS = setOf("wmv", "wm2", "wmv2")

    /**
     * Scans common video subdirs under [installPath] for WMV-family files and renames
     * each to "<name>.bak".  Already-renamed files (.bak) are ignored.
     *
     * @return the number of files successfully renamed (0 if none found or nothing to do).
     */
    fun renameIntroVideos(installPath: String): Int {
        if (installPath.isEmpty()) {
            Timber.tag("VideoFileAutoFixer").w("installPath is empty — skipping")
            return 0
        }
        val baseDir = File(installPath)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            Timber.tag("VideoFileAutoFixer").w("installPath does not exist or is not a directory: $installPath")
            return 0
        }

        var renamed = 0
        for (subdirName in VIDEO_SUBDIRS) {
            val subdir = File(baseDir, subdirName)
            if (!subdir.isDirectory) continue

            // Guard: the subdir must be a direct child of installPath (no traversal).
            if (!subdir.canonicalPath.startsWith(baseDir.canonicalPath)) {
                Timber.tag("VideoFileAutoFixer").w("Path traversal guard triggered for $subdir — skipping")
                continue
            }

            val files = subdir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val ext = file.extension.lowercase()
                if (ext !in VIDEO_EXTENSIONS) continue
                // Already renamed.
                if (file.name.endsWith(".bak", ignoreCase = true)) continue

                val bakFile = File(file.absolutePath + ".bak")
                if (bakFile.exists()) {
                    // .bak already exists; skip — the live file will be dealt with on next launch
                    // by DeleteGameFilesFix if one is registered, or just skip here.
                    Timber.tag("VideoFileAutoFixer").d("Backup already exists for ${file.name} — skipping rename")
                    continue
                }

                try {
                    if (file.renameTo(bakFile)) {
                        Timber.tag("VideoFileAutoFixer").i("Renamed ${file.absolutePath} -> ${bakFile.name}")
                        renamed++
                    } else {
                        Timber.tag("VideoFileAutoFixer").w("Failed to rename ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Timber.tag("VideoFileAutoFixer").e(e, "Error renaming ${file.absolutePath}")
                }
            }
        }

        Timber.tag("VideoFileAutoFixer").i("Renamed $renamed WMV file(s) under $installPath")
        return renamed
    }
}
