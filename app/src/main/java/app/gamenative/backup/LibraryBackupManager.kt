package app.gamenative.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.db.dao.SteamAppDao
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.MarkerUtils
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Library backup / export + restore.
 *
 * The installed-game library lives in the Room database in internal storage, which is wiped
 * on uninstall with no recoverable manifest. This manager writes a flat, versioned JSON
 * manifest of the installed library to public Downloads (via MediaStore) so it survives a
 * full uninstall, and can later re-link those games — but ONLY for games whose files are
 * still physically present on disk. It never downloads and never deletes.
 *
 * Re-link reuses the exact persistence paths the app itself uses to mark a game installed:
 *  - Steam:   insert an [AppInfo] with `customInstallPath` + write DOWNLOAD_COMPLETE_MARKER
 *             (identical to the imported-game path in [CustomGameScanner.createLibraryItemFromFolder]).
 *  - GOG:     update the existing row with isInstalled=true + installPath + DOWNLOAD_COMPLETE_MARKER.
 *  - Epic:    update the existing row with isInstalled=true + installPath + DOWNLOAD_COMPLETE_MARKER.
 *  - Amazon:  AmazonGameDao.markAsInstalled(productId, path, size, versionId) + DOWNLOAD_COMPLETE_MARKER.
 *  - Custom:  re-add the folder to PrefManager.customGameManualFolders (the source of truth).
 */
object LibraryBackupManager {

    /** Bump when the schema changes in a non-additive way. New fields with defaults stay v1. */
    const val SCHEMA_VERSION = 1

    private const val TAG = "LibraryBackupManager"
    private const val FILE_PREFIX = "gamenative-iic-library-"
    private const val MIME_JSON = "application/json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BackupDaoEntryPoint {
        fun steamAppDao(): SteamAppDao
        fun appInfoDao(): AppInfoDao
        fun gogGameDao(): GOGGameDao
        fun epicGameDao(): EpicGameDao
        fun amazonGameDao(): AmazonGameDao
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    @Serializable
    data class LibraryBackup(
        val version: Int = SCHEMA_VERSION,
        val exportedAt: Long = 0L,
        val appVersion: String = "",
        val games: List<BackupGame> = emptyList(),
    )

    @Serializable
    data class BackupGame(
        /** Source-specific identity key, kept as a string for forward-compat:
         *  Steam = appId, GOG = id, Epic = catalogId, Amazon = productId, Custom = folder name. */
        val sourceId: String = "",
        /** One of [GameSource] names: STEAM / GOG / EPIC / AMAZON / CUSTOM_GAME. */
        val store: String = "",
        val title: String = "",
        /** Absolute on-disk install path recorded at export time (re-resolved if missing on import). */
        val installPath: String = "",
        /** Internal numeric/string id used for events & versionId where applicable (optional). */
        val internalId: String = "",
        /** Amazon-only: version id needed by markAsInstalled (optional). */
        val versionId: String = "",
        val installSizeBytes: Long = 0L,
    )

    data class RestoreResult(
        val restored: Int = 0,
        val skippedMissingFiles: Int = 0,
        val failed: Int = 0,
        val errorMessage: String? = null,
    ) {
        val total: Int get() = restored + skippedMissingFiles + failed
    }

    /** Where the export landed, surfaced to the UI for the result message. */
    data class ExportResult(
        val uri: Uri,
        val displayName: String,
        val gameCount: Int,
    )

    private fun entryPoint(context: Context): BackupDaoEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, BackupDaoEntryPoint::class.java)

    // ── Export ──────────────────────────────────────────────────────────────

    /**
     * Gather all installed games (Steam/GOG/Epic/Amazon + custom), serialize to pretty JSON, and
     * write to public Downloads via MediaStore. Returns the location of the written file.
     */
    suspend fun exportLibrary(context: Context): Result<ExportResult> = withContext(Dispatchers.IO) {
        try {
            val backup = buildBackup(context)
            if (backup.games.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No installed games found to back up"),
                )
            }

            val payload = json.encodeToString(LibraryBackup.serializer(), backup)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(backup.exportedAt))
            val fileName = "$FILE_PREFIX$stamp.json"

            val uri = writeToDownloads(context, fileName, payload)
                ?: return@withContext Result.failure(
                    IllegalStateException("Failed to write backup to Downloads"),
                )

            Timber.tag(TAG).i("Exported %d games to %s", backup.games.size, uri)
            Result.success(ExportResult(uri = uri, displayName = fileName, gameCount = backup.games.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Library export failed")
            Result.failure(e)
        }
    }

    private suspend fun buildBackup(context: Context): LibraryBackup {
        val ep = entryPoint(context)
        val games = mutableListOf<BackupGame>()

        // Steam — installed = joined against app_info.is_downloaded.
        runCatching {
            val appInfos = ep.appInfoDao().getAll().associateBy { it.id }
            ep.steamAppDao().getInstalledGames().forEach { app ->
                val info = appInfos[app.id]
                val path = resolveSteamInstallPath(app.id, info)
                games += BackupGame(
                    sourceId = app.id.toString(),
                    store = GameSource.STEAM.name,
                    title = app.name,
                    installPath = path.orEmpty(),
                    internalId = app.id.toString(),
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to collect Steam games for backup") }

        // GOG
        runCatching {
            ep.gogGameDao().getInstalledGames().forEach { g ->
                games += BackupGame(
                    sourceId = g.id,
                    store = GameSource.GOG.name,
                    title = g.title,
                    installPath = g.installPath,
                    internalId = g.id,
                    installSizeBytes = g.installSize,
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to collect GOG games for backup") }

        // Epic
        runCatching {
            ep.epicGameDao().getInstalledGames().forEach { g ->
                games += BackupGame(
                    sourceId = g.catalogId,
                    store = GameSource.EPIC.name,
                    title = g.title.ifBlank { g.appName },
                    installPath = g.installPath,
                    internalId = g.id.toString(),
                    installSizeBytes = g.installSize,
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to collect Epic games for backup") }

        // Amazon
        runCatching {
            ep.amazonGameDao().getInstalledGames().forEach { g ->
                games += BackupGame(
                    sourceId = g.productId,
                    store = GameSource.AMAZON.name,
                    title = g.title,
                    installPath = g.installPath,
                    internalId = g.appId.toString(),
                    versionId = g.versionId,
                    installSizeBytes = g.installSize,
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to collect Amazon games for backup") }

        // Custom games — the manual-folder list is the source of truth.
        runCatching {
            PrefManager.customGameManualFolders.forEach { folderPath ->
                val folder = File(folderPath)
                games += BackupGame(
                    sourceId = folder.name,
                    store = GameSource.CUSTOM_GAME.name,
                    title = folder.name,
                    installPath = folderPath,
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Failed to collect custom games for backup") }

        return LibraryBackup(
            version = SCHEMA_VERSION,
            exportedAt = System.currentTimeMillis(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }.getOrDefault(""),
            games = games,
        )
    }

    /** Best-effort install-path resolution for a Steam app (imported path wins, else scan disk). */
    private fun resolveSteamInstallPath(appId: Int, info: AppInfo?): String? {
        if (info != null && info.isImported) return info.customInstallPath
        return runCatching { app.gamenative.service.SteamService.getAppDirPath(appId) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && File(it).exists() }
    }

    private fun writeToDownloads(context: Context, fileName: String, content: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, MIME_JSON)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                    ?: return null
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } else {
            // Pre-Q: write straight into the public Downloads dir.
            @Suppress("DEPRECATION")
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val outFile = File(downloads, fileName)
            outFile.writeText(content, Charsets.UTF_8)
            Uri.fromFile(outFile)
        }
    }

    // ── Import / Restore ──────────────────────────────────────────────────────

    /**
     * Parse the manifest at [uri] and re-link every game whose files still exist on disk.
     * Defensive: malformed/missing fields are skipped, never crashes. Never downloads, never deletes.
     */
    suspend fun importLibrary(context: Context, uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read backup file")
            null
        } ?: return@withContext RestoreResult(errorMessage = "Could not read the selected file")

        val backup = try {
            json.decodeFromString(LibraryBackup.serializer(), text)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to parse backup JSON")
            return@withContext RestoreResult(errorMessage = "The selected file is not a valid library backup")
        }

        if (backup.games.isEmpty()) {
            return@withContext RestoreResult(errorMessage = "Backup contains no games")
        }

        val ep = entryPoint(context)
        var restored = 0
        var skipped = 0
        var failed = 0

        for (game in backup.games) {
            val source = runCatching { GameSource.valueOf(game.store) }.getOrNull()
            if (source == null) {
                Timber.tag(TAG).w("Skipping game with unknown store: %s", game.store)
                failed++
                continue
            }

            // Resolve the on-disk folder: recorded path first, then a source-aware re-resolution.
            val installDir = resolveExistingInstallDir(context, source, game)
            if (installDir == null) {
                skipped++
                continue
            }

            val ok = try {
                relink(context, ep, source, game, installDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to re-link %s (%s)", game.title, game.store)
                false
            }

            when {
                ok -> restored++
                else -> failed++
            }
        }

        Timber.tag(TAG).i("Restore complete: restored=%d skipped=%d failed=%d", restored, skipped, failed)
        RestoreResult(restored = restored, skippedMissingFiles = skipped, failed = failed)
    }

    /** Returns the install folder iff it physically exists (recorded path, else re-resolved). */
    private fun resolveExistingInstallDir(context: Context, source: GameSource, game: BackupGame): File? {
        val recorded = game.installPath.takeIf { it.isNotBlank() }?.let(::File)
        if (recorded != null && recorded.exists() && recorded.isDirectory) return recorded

        // Re-resolution fallback for Steam — the recorded absolute path may differ across installs,
        // but the app's own resolver scans the standard install roots by folder name.
        if (source == GameSource.STEAM) {
            val appId = game.internalId.toIntOrNull() ?: game.sourceId.toIntOrNull()
            if (appId != null) {
                val path = runCatching { app.gamenative.service.SteamService.getAppDirPath(appId) }.getOrNull()
                if (!path.isNullOrBlank()) {
                    val dir = File(path)
                    if (dir.exists() && dir.isDirectory) return dir
                }
            }
        }
        return null
    }

    /** Persist the install state for one game, mirroring the app's own install-complete flow. */
    private suspend fun relink(
        context: Context,
        ep: BackupDaoEntryPoint,
        source: GameSource,
        game: BackupGame,
        installDir: File,
    ): Boolean {
        val path = installDir.absolutePath
        when (source) {
            GameSource.STEAM -> {
                val appId = game.internalId.toIntOrNull() ?: game.sourceId.toIntOrNull() ?: return false
                app.gamenative.utils.markSteamGameInstalled(appId, path, ep.appInfoDao())
                emitInstalledChanged(appId, GameSource.STEAM)
            }

            GameSource.GOG -> {
                val existing = ep.gogGameDao().getById(game.sourceId) ?: return false
                ep.gogGameDao().update(
                    existing.copy(
                        isInstalled = true,
                        installPath = path,
                        installSize = game.installSizeBytes.takeIf { it > 0 } ?: existing.installSize,
                    ),
                )
                MarkerUtils.addMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                game.sourceId.toIntOrNull()?.let { emitInstalledChanged(it, GameSource.GOG) }
            }

            GameSource.EPIC -> {
                val existing = ep.epicGameDao().getByCatalogId(game.sourceId)
                    ?: game.internalId.toIntOrNull()?.let { ep.epicGameDao().getById(it) }
                    ?: return false
                ep.epicGameDao().update(
                    existing.copy(
                        isInstalled = true,
                        installPath = path,
                        installSize = game.installSizeBytes.takeIf { it > 0 } ?: existing.installSize,
                    ),
                )
                MarkerUtils.addMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                emitInstalledChanged(existing.id, GameSource.EPIC)
            }

            GameSource.AMAZON -> {
                val existing = ep.amazonGameDao().getByProductId(game.sourceId) ?: return false
                ep.amazonGameDao().markAsInstalled(
                    productId = game.sourceId,
                    path = path,
                    size = game.installSizeBytes.takeIf { it > 0 } ?: existing.installSize,
                    versionId = game.versionId.ifBlank { existing.versionId },
                )
                MarkerUtils.addMarker(path, Marker.DOWNLOAD_COMPLETE_MARKER)
                emitInstalledChanged(existing.appId, GameSource.AMAZON)
            }

            GameSource.CUSTOM_GAME -> {
                // The manual-folder set is the source of truth; re-add and invalidate the cache.
                val current = PrefManager.customGameManualFolders
                if (!current.contains(path)) {
                    PrefManager.customGameManualFolders = current + path
                }
                CustomGameScanner.invalidateCache()
            }
        }
        return true
    }

    private fun emitInstalledChanged(gameId: Int, source: GameSource) {
        runCatching {
            app.gamenative.PluviaApp.events.emitJava(
                AndroidEvent.LibraryInstallStatusChanged(gameId, source),
            )
        }
    }
}
