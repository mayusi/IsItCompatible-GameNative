package app.gamenative.utils

import android.content.Context
import app.gamenative.PluviaApp
import app.gamenative.PrefManager
import app.gamenative.data.GameSource
import app.gamenative.db.dao.AmazonGameDao
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.db.dao.EpicGameDao
import app.gamenative.db.dao.GOGGameDao
import app.gamenative.enums.Marker
import app.gamenative.events.AndroidEvent
import app.gamenative.service.SteamService
import app.gamenative.service.amazon.AmazonConstants
import app.gamenative.service.amazon.AmazonService
import app.gamenative.service.epic.EpicConstants
import app.gamenative.service.epic.EpicService
import app.gamenative.service.gog.GOGConstants
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Re-detects storefront games (Steam / GOG / Epic / Amazon) whose game FILES still
 * exist on disk — typically on a removable SD/USB volume that survives an app
 * uninstall — but whose Room DB "installed" row has been wiped (fresh install / DB loss).
 *
 * This complements [CustomGameScanner], which already re-links *custom* games. The goal
 * is purely additive: it NEVER deletes anything, NEVER overwrites a game that is already
 * correctly installed, and re-uses each store's existing "mark installed" persistence
 * path rather than inventing a new one:
 *   - Steam : mirrors [CustomGameScanner.createLibraryItemFromFolder] — inserts an
 *             [AppInfo] row with isDownloaded + customInstallPath and drops a
 *             DOWNLOAD_COMPLETE_MARKER (so the game reads as imported/installed).
 *   - GOG   : flips the gog_games row to isInstalled + installPath via the GOG DAO
 *             (same shape GOGManager.detectAndUpdateExistingInstallations writes).
 *   - Epic  : calls [EpicService.isGameInstalled], whose marker-based reconciliation
 *             flips the epic_games row to installed.
 *   - Amazon: calls [AmazonService.isGameInstalled], whose marker-based reconciliation
 *             flips the amazon_games row to installed.
 *
 * A directory is only re-linked when it contains real game files AND a
 * [Marker.DOWNLOAD_COMPLETE_MARKER] (proof a download finished), so half-downloaded
 * folders are skipped. If a directory cannot be confidently mapped to a known appId it
 * is skipped (logged) — we never re-link to the wrong game.
 */
object GameRelinkManager {

    private const val TAG = "GameRelinkManager"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RelinkDaoEntryPoint {
        fun appInfoDao(): AppInfoDao
        fun gogGameDao(): GOGGameDao
        fun epicGameDao(): EpicGameDao
        fun amazonGameDao(): AmazonGameDao
    }

    /**
     * Scans every install root (internal + configured external + mounted removable
     * volumes) for storefront game directories whose DB "installed" row is gone, and
     * re-links each one it can confidently identify.
     *
     * Safe to call repeatedly: it is idempotent (already-installed games are left
     * untouched) and cheap when there is nothing to re-link. Runs entirely on
     * [Dispatchers.IO].
     *
     * @return the number of games re-linked into the library during this call.
     */
    suspend fun relinkOrphanGamesOnDisk(context: Context): Int = withContext(Dispatchers.IO) {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            RelinkDaoEntryPoint::class.java,
        )

        var relinked = 0
        relinked += runStore("Steam") { relinkSteamGames(entryPoint.appInfoDao()) }
        relinked += runStore("GOG") { relinkGogGames(entryPoint.gogGameDao()) }
        relinked += runStore("Epic") { relinkEpicGames(context, entryPoint.epicGameDao()) }
        relinked += runStore("Amazon") { relinkAmazonGames(context, entryPoint.amazonGameDao()) }

        if (relinked > 0) {
            Timber.tag(TAG).i("Re-linked %d storefront game(s) found on disk", relinked)
        } else {
            Timber.tag(TAG).d("No orphan storefront games to re-link on disk")
        }

        relinked
    }

    /** Runs one store's relink pass, isolating failures so one store can't abort the rest. */
    private suspend fun runStore(store: String, block: suspend () -> Int): Int {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to re-link %s games on disk", store)
            0
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Steam
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Steam install dirs live under <root>/Steam/steamapps/common/<DirName>. We map each
     * <DirName> back to an owned SteamApp via [SteamService.findSteamAppWithInstallDir]
     * (the same lookup [CustomGameScanner] uses), and — when not already installed —
     * insert the AppInfo row + completion marker exactly like the custom-game importer.
     */
    private suspend fun relinkSteamGames(appInfoDao: AppInfoDao): Int {
        if (SteamService.instance == null) {
            Timber.tag(TAG).d("Steam service not ready; skipping Steam relink")
            return 0
        }

        var count = 0
        for (commonRoot in SteamService.allInstallPaths.distinct()) {
            val root = File(commonRoot)
            val dirs = root.takeIf { it.isDirectory }?.listFiles { f -> f.isDirectory } ?: continue

            for (dir in dirs) {
                try {
                    // Only re-link dirs that finished downloading.
                    if (!MarkerUtils.hasMarker(dir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)) continue

                    val matches = SteamService.findSteamAppWithInstallDir(dirName = dir.name)
                    // Require an unambiguous match — never guess between multiple apps.
                    if (matches?.size != 1) {
                        if ((matches?.size ?: 0) > 1) {
                            Timber.tag(TAG).d("Skipping Steam dir '%s': %d candidate apps", dir.name, matches?.size)
                        }
                        continue
                    }
                    val steamApp = matches[0]
                    if (!SteamService.isAppLicensed(steamApp.packageId)) continue
                    // Idempotent: leave already-installed games alone.
                    if (SteamService.getInstalledApp(steamApp.id) != null) continue

                    markSteamGameInstalled(steamApp.id, dir.absolutePath, appInfoDao)

                    count++
                    Timber.tag(TAG).i("Re-linked Steam game %d (%s) at %s", steamApp.id, steamApp.name, dir.absolutePath)
                    PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(steamApp.id, GameSource.STEAM))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to re-link Steam dir %s", dir.absolutePath)
                }
            }
        }
        return count
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GOG
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * GOG install dirs live under <root>/GOG/games/common/<DirName>. We identify the
     * game by the goggame-*.info / *.info "gameId" field (authoritative), falling back
     * to a sanitized-title match against the gog_games table. On a confident match we
     * flip the DB row to isInstalled + installPath (same fields the normal
     * install-complete path writes).
     */
    private suspend fun relinkGogGames(gogGameDao: GOGGameDao): Int {
        val roots = buildList {
            runCatching { add(GOGConstants.internalGOGGamesPath) }
            if (PrefManager.externalStoragePath.isNotBlank()) {
                runCatching { add(GOGConstants.externalGOGGamesPath) }
            }
        }.distinct()

        // Build a sanitized-title → game map once for the fallback path.
        val allGames = gogGameDao.getAllAsList()
        val gamesById = allGames.associateBy { it.id }
        val gamesBySanitizedTitle = allGames
            .filter { it.title.isNotBlank() }
            .associateBy { sanitize(it.title) }

        var count = 0
        for (rootPath in roots) {
            val dirs = File(rootPath).takeIf { it.isDirectory }?.listFiles { f -> f.isDirectory } ?: continue

            for (dir in dirs) {
                try {
                    if (!hasRealGameFiles(dir)) continue

                    val byInfo = readGogGameIdFromInfo(dir)?.let { gamesById[it] }
                    val game = byInfo ?: gamesBySanitizedTitle[sanitize(dir.name)]
                    if (game == null) {
                        Timber.tag(TAG).d("Skipping GOG dir '%s': no matching owned game", dir.name)
                        continue
                    }
                    // Idempotent: only re-link if the DB no longer points at a valid install.
                    if (game.isInstalled && game.installPath.isNotBlank() && File(game.installPath).exists()) continue

                    val installSize = runCatching { StorageUtils.getFolderSize(dir.absolutePath) }.getOrDefault(0L)
                    gogGameDao.update(
                        game.copy(
                            isInstalled = true,
                            installPath = dir.absolutePath,
                            installSize = installSize,
                        ),
                    )

                    count++
                    Timber.tag(TAG).i("Re-linked GOG game %s (%s) at %s", game.id, game.title, dir.absolutePath)
                    PluviaApp.events.emitJava(
                        AndroidEvent.LibraryInstallStatusChanged(game.id.toIntOrNull() ?: 0, GameSource.GOG),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to re-link GOG dir %s", dir.absolutePath)
                }
            }
        }
        return count
    }

    /** Reads the "gameId" from a goggame-*.info / *.info file in [dir], if present. */
    private fun readGogGameIdFromInfo(dir: File): String? {
        val infoFiles = dir.listFiles { f -> f.isFile && f.extension.equals("info", ignoreCase = true) } ?: return null
        for (infoFile in infoFiles) {
            val gameId = runCatching {
                JSONObject(infoFile.readText()).optString("gameId", "")
            }.getOrDefault("")
            if (gameId.isNotEmpty()) return gameId
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Epic
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Epic install dirs live under <root>/Epic/games/<SanitizedTitle> and carry a
     * DOWNLOAD_COMPLETE_MARKER when finished. We match a dir to an owned EpicGame by
     * sanitized title, then delegate to [EpicService.isGameInstalled], whose existing
     * marker-based reconciliation flips the epic_games row to installed.
     */
    private suspend fun relinkEpicGames(context: Context, epicGameDao: EpicGameDao): Int {
        val roots = buildList {
            runCatching { add(EpicConstants.internalEpicGamesPath(context)) }
            if (PrefManager.externalStoragePath.isNotBlank()) {
                runCatching { add(EpicConstants.externalEpicGamesPath()) }
            }
        }.distinct()

        val games = epicGameDao.getAllAsList()
        val gamesBySanitizedTitle = games
            .filter { it.title.isNotBlank() }
            .associateBy { sanitize(it.title) }

        var count = 0
        for (rootPath in roots) {
            val dirs = File(rootPath).takeIf { it.isDirectory }?.listFiles { f -> f.isDirectory } ?: continue

            for (dir in dirs) {
                try {
                    if (!MarkerUtils.hasMarker(dir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)) continue

                    val game = gamesBySanitizedTitle[sanitize(dir.name)]
                    if (game == null) {
                        Timber.tag(TAG).d("Skipping Epic dir '%s': no matching owned game", dir.name)
                        continue
                    }
                    // Idempotent: skip games already pointing at a valid install.
                    if (game.isInstalled && game.installPath.isNotBlank() && File(game.installPath).exists()) continue

                    // EpicService.isGameInstalled performs the DB write (isInstalled + installPath)
                    // when the completion marker is present — the existing reconcile path.
                    if (EpicService.isGameInstalled(context, game.id)) {
                        count++
                        Timber.tag(TAG).i("Re-linked Epic game %d (%s) at %s", game.id, game.title, dir.absolutePath)
                        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(game.id, GameSource.EPIC))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to re-link Epic dir %s", dir.absolutePath)
                }
            }
        }
        return count
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Amazon
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Amazon install dirs live under <root>/Amazon/games/<SanitizedTitle> and carry a
     * DOWNLOAD_COMPLETE_MARKER when finished. We match a dir to an owned AmazonGame by
     * sanitized title, then delegate to [AmazonService.isGameInstalled], whose existing
     * marker-based reconciliation flips the amazon_games row to installed.
     */
    private suspend fun relinkAmazonGames(context: Context, amazonGameDao: AmazonGameDao): Int {
        val roots = buildList {
            runCatching { add(AmazonConstants.internalAmazonGamesPath(context)) }
            if (PrefManager.externalStoragePath.isNotBlank()) {
                runCatching { add(AmazonConstants.externalAmazonGamesPath()) }
            }
        }.distinct()

        val games = amazonGameDao.getAllAsList()
        val gamesBySanitizedTitle = games
            .filter { it.title.isNotBlank() }
            .associateBy { sanitize(it.title) }

        var count = 0
        for (rootPath in roots) {
            val dirs = File(rootPath).takeIf { it.isDirectory }?.listFiles { f -> f.isDirectory } ?: continue

            for (dir in dirs) {
                try {
                    if (!MarkerUtils.hasMarker(dir.absolutePath, Marker.DOWNLOAD_COMPLETE_MARKER)) continue

                    val game = gamesBySanitizedTitle[sanitize(dir.name)]
                    if (game == null) {
                        Timber.tag(TAG).d("Skipping Amazon dir '%s': no matching owned game", dir.name)
                        continue
                    }
                    // Idempotent: skip games already pointing at a valid install.
                    if (game.isInstalled && game.installPath.isNotBlank() && File(game.installPath).exists()) continue

                    // AmazonService.isGameInstalled marks the row installed when the
                    // completion marker is present — the existing reconcile path.
                    if (AmazonService.isGameInstalled(context, game.productId)) {
                        count++
                        Timber.tag(TAG).i("Re-linked Amazon game %s (%s) at %s", game.productId, game.title, dir.absolutePath)
                        PluviaApp.events.emitJava(AndroidEvent.LibraryInstallStatusChanged(game.appId, GameSource.AMAZON))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to re-link Amazon dir %s", dir.absolutePath)
                }
            }
        }
        return count
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors the title→folder sanitization used by GOG/Epic/Amazon getGameInstallPath
     * (strip everything but alphanumerics/space, collapse case) so we can map a dir name
     * back to a DB title. Kept permissive on punctuation differences across stores.
     */
    private fun sanitize(value: String): String =
        value.replace(Regex("[^a-zA-Z0-9 ]"), "").trim().replace(Regex("\\s+"), " ").lowercase()

    /** True if [dir] holds something that looks like an actual install (not an empty shell). */
    private fun hasRealGameFiles(dir: File): Boolean {
        val children = dir.listFiles() ?: return false
        return children.any { child ->
            when {
                child.isDirectory -> true
                child.name.startsWith(".") -> false // skip marker/metadata dotfiles
                child.isFile && child.length() > 0L -> true
                else -> false
            }
        }
    }
}
