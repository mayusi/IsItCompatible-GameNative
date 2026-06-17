package app.gamenative.utils

import app.gamenative.PrefManager
import app.gamenative.data.AppInfo
import app.gamenative.db.dao.AppInfoDao
import app.gamenative.enums.Marker
import app.gamenative.service.SteamService
import app.gamenative.service.SteamService.Companion.INVALID_APP_ID
import app.gamenative.service.SteamService.Companion.getMainAppDepots
import timber.log.Timber

private const val TAG = "SteamInstallUtils"

/**
 * Shared helper that marks a Steam game as installed in the Room database, matching
 * the known-good logic in [CustomGameScanner.createLibraryItemFromFolder].
 *
 * Callers that previously inlined this logic (GameRelinkManager, LibraryBackupManager)
 * were leaving [AppInfo.downloadedDepots] empty, causing SteamService verify/repair
 * (~line 1768) to re-download ALL content on the next launch. This helper ensures
 * every "mark installed" path populates depot IDs consistently.
 *
 * @param appId        The Steam numeric app ID.
 * @param installPath  The absolute on-disk install path.
 * @param appInfoDao   The Room DAO used to persist the row.
 */
suspend fun markSteamGameInstalled(
    appId: Int,
    installPath: String,
    appInfoDao: AppInfoDao,
) {
    val preferredLanguage = PrefManager.containerLanguage
    val rawDepots = getMainAppDepots(appId, preferredLanguage)
    val mainAppDepotIds = rawDepots
        .filter { (_, depot) -> depot.dlcAppId == INVALID_APP_ID }
        .keys.sorted()

    if (mainAppDepotIds.isEmpty()) {
        // Steam not yet fully connected or depot manifest not cached — proceed with an
        // empty list rather than blocking. SteamService can re-populate depots on next
        // launch; the important thing is that the game reads as installed.
        Timber.tag(TAG).w(
            "No main depots found for appId=%d (Steam may not be fully connected); " +
                "marking installed with empty depot list",
            appId,
        )
    }

    appInfoDao.insert(
        AppInfo(
            id = appId,
            isDownloaded = true,
            downloadedDepots = mainAppDepotIds,
            dlcDepots = emptyList(),
            branch = "public",
            customInstallPath = installPath,
        ),
    )

    MarkerUtils.addMarker(installPath, Marker.DOWNLOAD_COMPLETE_MARKER)
}
