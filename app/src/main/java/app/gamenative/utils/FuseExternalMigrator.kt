package app.gamenative.utils

import android.content.Context
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * IIC: One-time migration that moves Steam game directories from the FUSE-backed
 * primary-external path to internal storage so games can actually run.
 *
 * Background:
 * A prior fork change routed new game installs to
 *   /storage/emulated/0/Android/data/<pkg>/files/Steam/steamapps/common/
 * on modern Android (the FUSE primary-external app-scoped dir).  This path is
 * backed by Android's FUSE FuseDaemon (MediaProvider).  When Wine/Box64 opens
 * game files at high concurrency, FuseDaemon crashes (assertion failure in
 * pf_opendir: "active_nodes_.find(node) != active_nodes_.end()") and vold tears
 * down the FUSE volume, sending SIGKILL to every process with open file descriptors
 * on that volume — i.e. the game (dmc3.exe etc.).  The game renders one frame
 * then dies.
 *
 * Games on internal storage (/data/data/<pkg>) use a real ext4/f2fs filesystem
 * with no FUSE intermediary and never hit this bug.
 *
 * This migrator runs at startup (from PluviaApp) and:
 *   1. Skips if a real removable SD card / USB is present (those volumes are safe
 *      to use and intentionally supported for external storage installs).
 *   2. Scans the FUSE primary-external common dir for game subdirectories.
 *   3. Moves each found game dir into internal common dir using an atomic rename
 *      (same filesystem is unlikely — falls back to copy+delete via StorageUtils).
 *   4. Marks migration complete in prefs so it never runs again.
 *
 * The migration is non-destructive: if the target dir already exists (a prior
 * partial run or duplicate) it is skipped.
 */
object FuseExternalMigrator {

    private const val TAG = "FuseExternalMigrator"

    /** Pref key — set to 1.0 once migration has been attempted. */
    private const val PREF_MIGRATED = "iic_fuse_external_migrated"

    suspend fun migrateIfNeeded(context: Context) = withContext(Dispatchers.IO) {
        // Only relevant on modern Android flavor
        if (!BuildConfig.MODERN_ANDROID) return@withContext

        // Already ran
        if (PrefManager.getFloat(PREF_MIGRATED, 0f) >= 1f) return@withContext

        // If a real removable SD card is present, the user may have legitimately installed
        // to external storage.  We don't auto-migrate in that case — leave their setup alone.
        if (DownloadService.externalVolumePaths.isNotEmpty()) {
            Timber.tag(TAG).i("Real SD card / USB detected — skipping FUSE external migration")
            PrefManager.setFloat(PREF_MIGRATED, 1f)
            return@withContext
        }

        val fuseDirBase = DownloadService.baseExternalAppDirPath
        if (fuseDirBase.isBlank()) {
            Timber.tag(TAG).w("baseExternalAppDirPath is blank — nothing to migrate")
            PrefManager.setFloat(PREF_MIGRATED, 1f)
            return@withContext
        }

        // The FUSE path where old installs landed
        val fuseCommonPath = Paths.get(fuseDirBase, "files", "Steam", "steamapps", "common").toString()
        val fuseCommonDir = File(fuseCommonPath)

        if (!fuseCommonDir.isDirectory) {
            Timber.tag(TAG).i("No FUSE external game directory found at $fuseCommonPath — nothing to migrate")
            PrefManager.setFloat(PREF_MIGRATED, 1f)
            return@withContext
        }

        val gameDirs = fuseCommonDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (gameDirs.isEmpty()) {
            Timber.tag(TAG).i("FUSE external common dir is empty — nothing to migrate")
            PrefManager.setFloat(PREF_MIGRATED, 1f)
            return@withContext
        }

        val internalCommonPath = SteamService.internalAppInstallPath
        val internalCommonDir = File(internalCommonPath)
        if (!internalCommonDir.exists()) internalCommonDir.mkdirs()

        Timber.tag(TAG).i(
            "FUSE external migration: found %d game(s) at %s — moving to %s",
            gameDirs.size,
            fuseCommonPath,
            internalCommonPath,
        )

        var migratedCount = 0
        var skippedCount = 0
        var errorCount = 0

        for (gameDir in gameDirs) {
            val targetDir = File(internalCommonPath, gameDir.name)
            if (targetDir.exists()) {
                Timber.tag(TAG).w("Skipping %s — target already exists at %s", gameDir.name, targetDir.absolutePath)
                skippedCount++
                continue
            }

            // Try atomic rename first (succeeds only if both dirs are on the same filesystem,
            // which is unlikely here — but try anyway as it's instant).
            val renamed = gameDir.renameTo(targetDir)
            if (renamed) {
                Timber.tag(TAG).i("Moved (renamed) %s to internal storage", gameDir.name)
                migratedCount++
            } else {
                // Cross-filesystem: copy then delete
                Timber.tag(TAG).i("Rename failed for %s — falling back to copy+delete", gameDir.name)
                val result = StorageUtils.moveDirectory(
                    sourceDir = gameDir.absolutePath,
                    targetDir = targetDir.absolutePath,
                    onProgressUpdate = { file, _, moved, total ->
                        if (moved % 500 == 0 || moved == total) {
                            Timber.tag(TAG).d("Migrating %s: %d/%d files — %s", gameDir.name, moved, total, file)
                        }
                    },
                )
                if (result.isSuccess) {
                    Timber.tag(TAG).i("Moved (copy+delete) %s to internal storage successfully", gameDir.name)
                    migratedCount++
                } else {
                    Timber.tag(TAG).e(
                        result.exceptionOrNull(),
                        "Failed to migrate %s: %s",
                        gameDir.name,
                        result.exceptionOrNull()?.message,
                    )
                    errorCount++
                }
            }
        }

        Timber.tag(TAG).i(
            "FUSE external migration complete: %d moved, %d skipped (already at target), %d errors",
            migratedCount,
            skippedCount,
            errorCount,
        )

        // If all succeeded (or nothing was there), clear the stale FUSE-path externalStoragePath
        // pref so the app no longer points at the FUSE dir, and mark the migration done.
        // If ANY game failed to move, do NOT mark migrated — otherwise a partially-moved
        // game (split between FUSE and internal) would be stuck forever: getAppDirPath would
        // resolve to the broken FUSE copy and the launch would FUSE-teardown crash, with no
        // chance to retry. Leaving the flag unset lets the migration retry on next launch.
        if (errorCount == 0) {
            if (PrefManager.externalStoragePath.startsWith(fuseDirBase)) {
                Timber.tag(TAG).i("Clearing stale externalStoragePath pref (was pointing at FUSE dir)")
                PrefManager.externalStoragePath = ""
                PrefManager.useExternalStorage = false
            }
            PrefManager.setFloat(PREF_MIGRATED, 1f)
        } else {
            Timber.tag(TAG).w(
                "FUSE migration had %d error(s) — NOT marking done so it retries next launch",
                errorCount,
            )
        }
    }
}
