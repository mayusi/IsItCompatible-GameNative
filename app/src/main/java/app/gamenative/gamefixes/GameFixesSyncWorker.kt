package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.utils.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodically fetches an updated gamefixes registry.json from the CDN and caches it.
 * Uses a coroutine-based approach (no WorkManager dependency required).
 *
 * The fetch runs once per app session on a background thread. It is best-effort:
 * any error is silently logged and the bundled registry continues to be used.
 *
 * To activate live OTA updates, host a valid registry.json at RAW_REGISTRY_URL.
 */
object GameFixesSyncWorker {

    /**
     * Placeholder URL — the lead can host the file here and the update system
     * will start delivering fixes over-the-air automatically.
     */
    private const val RAW_REGISTRY_URL =
        "https://raw.githubusercontent.com/mayusi/IsItCompatible-GameNative/main/gamefixes/registry.json"

    private const val TAG = "GameFixesSyncWorker"
    private const val MAX_BODY_SIZE = 256 * 1024L // 256 KB
    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 30L

    // Minimum interval between syncs (24 hours) to avoid hammering the CDN.
    private const val MIN_SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Schedule a background sync if enough time has elapsed since the last successful sync.
     * Safe to call from any thread; actual work runs in a background coroutine.
     */
    fun scheduleIfNeeded(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (!shouldSync(appContext)) {
                Timber.tag(TAG).d("Sync not needed yet — skipping")
                return@launch
            }
            sync(appContext)
        }
    }

    private fun shouldSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences("gamefixes_sync", Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("last_sync_ms", 0L)
        return System.currentTimeMillis() - lastSync >= MIN_SYNC_INTERVAL_MS
    }

    private fun markSynced(context: Context) {
        context.getSharedPreferences("gamefixes_sync", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_sync_ms", System.currentTimeMillis())
            .apply()
    }

    private suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        Timber.tag(TAG).d("Starting gamefixes registry sync from $RAW_REGISTRY_URL")
        try {
            val request = Request.Builder()
                .url(RAW_REGISTRY_URL)
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Sync failed — HTTP ${response.code}")
                    return@withContext
                }

                val body = response.body ?: run {
                    Timber.tag(TAG).w("Sync failed — empty response body")
                    return@withContext
                }

                // Guard: reject oversized responses before buffering in memory.
                val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                if (contentLength > MAX_BODY_SIZE) {
                    Timber.tag(TAG).w("Sync rejected — Content-Length $contentLength exceeds $MAX_BODY_SIZE bytes")
                    return@withContext
                }

                val rawJson = body.string()
                if (rawJson.length > MAX_BODY_SIZE) {
                    Timber.tag(TAG).w("Sync rejected — body ${rawJson.length} bytes exceeds size cap")
                    return@withContext
                }

                val saved = JsonGameFixLoader.saveAndReload(context, rawJson)
                if (saved) {
                    markSynced(context)
                    Timber.tag(TAG).i("Gamefixes registry synced successfully")
                } else {
                    Timber.tag(TAG).w("Sync discarded — validation failed")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Gamefixes registry sync error — will retry next session")
        }
    }
}
