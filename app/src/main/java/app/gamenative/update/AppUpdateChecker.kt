package app.gamenative.update

import android.util.Log
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.utils.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-update checker for the GameNative IIC fork.
 *
 * Hits https://api.github.com/repos/mayusi/IsItCompatible-GameNative/releases/latest,
 * compares the release tag against [BuildConfig.VERSION_NAME], and (when an
 * update is available) writes the pending-update prefs so the UI can surface
 * a banner + install flow.
 *
 * Ported from io.github.mayusi.isitcompatible.getit.AppUpdateChecker.
 * Deltas from IIC source:
 *  - OWNER_REPO = "mayusi/IsItCompatible-GameNative"
 *  - HTTP client = [Net.http] (fork's singleton OkHttp with DoH) instead of private client.
 *  - Prefs wired to [PrefManager] (object singleton) instead of UserPreferences DataStore flow.
 *  - BuildConfig = app.gamenative.BuildConfig.
 *
 * Key design decisions (inherited from IIC):
 *  - DEBUG build → always returns [UpdateCheckResult.UpToDate] immediately.
 *  - 404 from GitHub means the repo has ZERO published releases — silent no-op.
 *  - In-memory ETag cache so repeat checks within a session don't burn
 *    GitHub's 60-req/hr unauthenticated rate limit.
 */
@Singleton
class AppUpdateChecker @Inject constructor() {

    private val http = Net.http
    private val json = Json { ignoreUnknownKeys = true }

    // In-memory ETag cache (resets on process restart, which is fine).
    // Stored as an atomic pair so concurrent coroutines always see a consistent
    // etag+body snapshot (prevents "304 but no cached body" race).
    private val etagCache = AtomicReference<Pair<String, String>?>(null)

    companion object {
        private const val TAG = "AppUpdateChecker"
        private const val OWNER_REPO = "mayusi/IsItCompatible-GameNative"
        private const val RELEASES_LATEST_URL =
            "https://api.github.com/repos/$OWNER_REPO/releases/latest"
        private const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12 hours
    }

    /**
     * Performs one check against GitHub. Returns a [UpdateCheckResult] describing
     * what was found. Never throws; errors are wrapped in [UpdateCheckResult.Failed].
     */
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        // Self-update is meaningless on the debug build (different package id,
        // different signing key). Skip silently so the checker never triggers
        // the update flow on a dev device.
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "DEBUG build — skipping self-update check")
            return@withContext UpdateCheckResult.UpToDate
        }

        val cached = etagCache.get()
        val reqBuilder = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "GameNative-IIC/${BuildConfig.VERSION_NAME}")
        cached?.let { reqBuilder.header("If-None-Match", it.first) }

        try {
            http.newCall(reqBuilder.build()).execute().use { response ->
                when (response.code) {
                    304 -> {
                        // Cache hit — parse the body we saved last time.
                        val body = etagCache.get()?.second
                            ?: return@withContext UpdateCheckResult.Failed("304 but no cached body")
                        parseRelease(body)
                    }
                    404 -> {
                        // Repo exists but has no releases yet.
                        Log.d(TAG, "No releases found for $OWNER_REPO (404)")
                        UpdateCheckResult.NoReleasesYet
                    }
                    200 -> {
                        val body = response.body?.string()
                            ?: return@withContext UpdateCheckResult.Failed("Empty response body")
                        response.header("ETag")?.let { etag ->
                            etagCache.set(etag to body)
                        }
                        parseRelease(body)
                    }
                    else -> UpdateCheckResult.Failed("GitHub returned HTTP ${response.code}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed", t)
            UpdateCheckResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Debounced entry point called from Application.onCreate.
     *
     * Reads [PrefManager] to decide whether a check is due (>12h since last check
     * and auto-check is enabled), runs [check], stamps the timestamp, and writes
     * pendingUpdate* prefs if an update was found.
     */
    suspend fun checkIfDue(context: android.content.Context) {
        if (!PrefManager.updateAutoCheckEnabled) return
        val now = System.currentTimeMillis()
        val lastCheck = PrefManager.lastUpdateCheckMs
        if (lastCheck > 0 && (now - lastCheck) < CHECK_INTERVAL_MS) return

        Log.d(TAG, "Scheduled update check running…")
        val result = check()
        PrefManager.lastUpdateCheckMs = now

        if (result is UpdateCheckResult.UpdateAvailable) {
            PrefManager.setPendingUpdate(
                version = result.version,
                url = result.apkUrl,
                filename = result.apkFilename,
                notes = result.patchNotes,
                sizeBytes = result.apkSizeBytes,
                sha256 = result.sha256,
            )
            Log.i(TAG, "Update available: ${result.version} sha256=${result.sha256 ?: "none"}")
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private fun parseRelease(body: String): UpdateCheckResult {
        val release = runCatching { json.decodeFromString<GhRelease>(body) }.getOrElse {
            return UpdateCheckResult.Failed("JSON parse error: ${it.message}")
        }

        if (release.draft || release.prerelease) {
            Log.d(TAG, "Latest release is draft/prerelease — treating as up-to-date")
            return UpdateCheckResult.UpToDate
        }

        val tag = release.tagName ?: return UpdateCheckResult.UpToDate
        val releaseVersion = normalizeVersion(tag)
        val installedVersion = normalizeVersion(BuildConfig.VERSION_NAME)

        if (!isNewer(releaseVersion, installedVersion)) {
            return UpdateCheckResult.UpToDate
        }

        // Pick APK asset: prefer arm64-v8a / arm64 in the name, fall back to first .apk.
        val apkAssets = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apkAssets.isEmpty()) {
            return UpdateCheckResult.Failed("Release $tag has no .apk assets")
        }
        val ARM64_HINTS = listOf("arm64-v8a", "arm64v8a", "aarch64", "arm64")
        val picked = apkAssets.firstOrNull { asset ->
            ARM64_HINTS.any { it in asset.name.lowercase() }
        } ?: apkAssets.first()

        // Extract SHA256: look for a line containing "SHA256: <64-hex>" in release body.
        val sha256 = parseSha256FromBody(release.body)

        if (sha256 == null) {
            Log.w(TAG, "No SHA256 found for release $tag — will verify signature only")
        } else {
            Log.d(TAG, "SHA256 for ${picked.name}: $sha256")
        }

        return UpdateCheckResult.UpdateAvailable(
            version = tag,
            releaseTitle = release.name ?: tag,
            patchNotes = release.body ?: "",
            apkUrl = picked.browserDownloadUrl,
            apkFilename = picked.name,
            apkSizeBytes = picked.size,
            sha256 = sha256,
        )
    }

    /**
     * Parses a SHA256 hex string from the release body.
     * Looks for a line matching "SHA256: <64-hex>" (case-insensitive, leading whitespace ok).
     */
    private fun parseSha256FromBody(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val regex = Regex("""(?i)SHA256:\s*([0-9a-fA-F]{64})""")
        return regex.find(body)?.groupValues?.get(1)?.lowercase()
    }

    /**
     * Strips a leading v/V prefix and returns a list of integer components.
     * "v1.2.3" → [1, 2, 3].  Non-numeric parts (like "-rc1" or "-IIC") are ignored.
     */
    private fun normalizeVersion(raw: String): List<Int> {
        return raw.trimStart('v', 'V')
            .split('.')
            .mapNotNull { part -> part.filter { it.isDigit() }.toIntOrNull() }
    }

    /**
     * Returns true if [candidate] is strictly newer than [current] using
     * lexicographic integer-tuple comparison (1.2.3 > 1.2.0, etc.).
     */
    private fun isNewer(candidate: List<Int>, current: List<Int>): Boolean {
        val maxLen = maxOf(candidate.size, current.size)
        for (i in 0 until maxLen) {
            val c = candidate.getOrElse(i) { 0 }
            val r = current.getOrElse(i) { 0 }
            if (c > r) return true
            if (c < r) return false
        }
        return false // equal
    }
}

/** Result type returned by [AppUpdateChecker.check]. */
sealed interface UpdateCheckResult {
    /** Installed version is current. */
    data object UpToDate : UpdateCheckResult

    /** An update is available. */
    data class UpdateAvailable(
        val version: String,
        val releaseTitle: String,
        val patchNotes: String,
        val apkUrl: String,
        val apkFilename: String,
        val apkSizeBytes: Long,
        /** SHA-256 hex of the APK, parsed from the release body. Null if not published. */
        val sha256: String? = null,
    ) : UpdateCheckResult

    /** The GitHub repo has zero published releases (expected early-lifecycle state). */
    data object NoReleasesYet : UpdateCheckResult

    /** Network error, JSON parse error, or unexpected HTTP status. */
    data class Failed(val reason: String) : UpdateCheckResult
}
