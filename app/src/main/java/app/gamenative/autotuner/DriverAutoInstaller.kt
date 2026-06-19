package app.gamenative.autotuner

import android.content.Context
import android.net.Uri
import app.gamenative.service.SteamService
import com.winlator.contents.AdrenotoolsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Automatically downloads and installs the best available Turnip GPU driver when none is
 * present on the device.
 *
 * This is wired into AutoTunerEngine.runProbeSweep so that "Make It Work" auto-installs
 * a driver before running any probe trials — closing the DMC3 / RP6 zero-frames blocker
 * without requiring any manual user action.
 *
 * All methods are fully try/catch-safe — they never throw.
 */
object DriverAutoInstaller {

    private const val TAG = "DriverAutoInstaller"
    private const val GRAPHICS_DRIVER_MANIFEST = "graphics_driver_download.json"

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    sealed class DriverInstallResult {
        /** A custom Turnip driver was already installed — nothing to do. */
        object AlreadyPresent : DriverInstallResult()

        /** A driver was downloaded and installed successfully. */
        data class Installed(val driverName: String) : DriverInstallResult()

        /** Installation could not be completed (network error, bad archive, etc.). */
        data class Failed(val reason: String) : DriverInstallResult()
    }

    // -------------------------------------------------------------------------
    // Manifest model — mirrors GraphicsDriverDownloader's model exactly so we
    // can reuse the same asset file without introducing a new dependency.
    // -------------------------------------------------------------------------

    @Serializable
    private data class DriverManifest(
        val version: Int,
        val updatedAt: String,
        val components: List<DriverComponent>,
    )

    @Serializable
    private data class DriverComponent(
        val id: String,
        val name: String,
        val url: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Ensures a custom Turnip GPU driver is installed on this device.
     *
     * Fast path: if [AdrenotoolsManager.enumarateInstalledDrivers] is non-empty,
     * returns [DriverInstallResult.AlreadyPresent] immediately (no network call).
     *
     * Slow path: reads [GRAPHICS_DRIVER_MANIFEST] from assets, picks the newest
     * "turnip-*" entry (last in ascending order), downloads it via
     * [SteamService.fetchFileWithFallback], installs via [AdrenotoolsManager.installDriver],
     * then cleans up the temp file.
     *
     * @param context    Application context.
     * @param onProgress Optional progress callback. Receives human-readable status strings
     *                   (e.g. "Downloading GPU driver 42%") suitable for surfacing in the
     *                   tuner progress UI.
     * @return A [DriverInstallResult] — never throws.
     */
    suspend fun ensureGpuDriverInstalled(
        context: Context,
        onProgress: ((String) -> Unit)? = null,
    ): DriverInstallResult = withContext(Dispatchers.IO) {
        try {
            // ---------------------------------------------------------------
            // 1. Fast path — already has a custom driver.
            // ---------------------------------------------------------------
            val already = try {
                AdrenotoolsManager(context).enumarateInstalledDrivers()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "enumarateInstalledDrivers threw — treating as empty")
                emptyList<String>()
            }

            if (already.isNotEmpty()) {
                Timber.tag(TAG).i("Driver already present: $already — skipping auto-install")
                return@withContext DriverInstallResult.AlreadyPresent
            }

            // ---------------------------------------------------------------
            // 2. Pick best driver from manifest.
            // ---------------------------------------------------------------
            val component = pickBestTurnipDriver(context)
                ?: return@withContext DriverInstallResult.Failed(
                    "No Turnip driver entry found in $GRAPHICS_DRIVER_MANIFEST",
                )

            Timber.tag(TAG).i("Auto-install: selected driver '${component.id}' (${component.name})")
            onProgress?.invoke("Installing GPU driver: ${component.name}")

            // ---------------------------------------------------------------
            // 3. Download to cache dir.
            // ---------------------------------------------------------------
            val destFile = File(context.cacheDir, component.name)
            try {
                onProgress?.invoke("Downloading GPU driver (0%)")
                SteamService.fetchFileWithFallback(
                    fileName = "drivers/${component.name}",
                    dest = destFile,
                    context = context,
                ) { progress ->
                    val pct = (progress * 100).toInt().coerceIn(0, 100)
                    onProgress?.invoke("Downloading GPU driver ($pct%)")
                }
                Timber.tag(TAG).i("Download complete: ${destFile.absolutePath} (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download failed for '${component.name}'")
                destFile.delete()
                return@withContext DriverInstallResult.Failed(
                    "Download failed: ${e.message ?: e.javaClass.simpleName}",
                )
            }

            // ---------------------------------------------------------------
            // 4. Install the driver from the temp file.
            // ---------------------------------------------------------------
            onProgress?.invoke("Installing GPU driver…")
            val installedName = try {
                AdrenotoolsManager(context).installDriver(Uri.fromFile(destFile))
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "installDriver threw for '${component.name}'")
                ""
            } finally {
                // Always clean up the temp file, regardless of install outcome.
                destFile.delete()
            }

            if (installedName.isNullOrEmpty()) {
                Timber.tag(TAG).w("installDriver returned empty name for '${component.name}' — install failed")
                return@withContext DriverInstallResult.Failed(
                    "Driver archive was downloaded but installation failed (invalid archive or driver already present).",
                )
            }

            Timber.tag(TAG).i("Auto-install succeeded: driver '$installedName' is now installed")
            onProgress?.invoke("GPU driver installed: $installedName")
            DriverInstallResult.Installed(installedName)

        } catch (e: Exception) {
            // Catch-all — the tuner must never crash due to driver auto-install logic.
            Timber.tag(TAG).e(e, "Unexpected error in ensureGpuDriverInstalled")
            DriverInstallResult.Failed("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the asset manifest and returns the best Turnip driver component.
     *
     * "Best" = the last entry whose id starts with "turnip-" (the manifest lists
     * turnip-24.1.0 … turnip-25.3.0 in ascending order, so last = newest).
     * Deliberately excludes zink, virgl, vortek, adrenotools-*, wrapper-*, etc.
     */
    private fun pickBestTurnipDriver(context: Context): DriverComponent? {
        return try {
            val raw = context.assets.open(GRAPHICS_DRIVER_MANIFEST).bufferedReader().use { it.readText() }
            val manifest = json.decodeFromString<DriverManifest>(raw)
            // Last "turnip-*" entry = newest version (list is ascending)
            manifest.components.lastOrNull { it.id.startsWith("turnip-") }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to read/parse $GRAPHICS_DRIVER_MANIFEST")
            null
        }
    }
}
