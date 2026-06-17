package app.gamenative.cheats

import android.content.Context
import app.gamenative.data.GameSource
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * One downloadable FLiNG-style trainer in the OTA catalog.
 *
 * A trainer IS a proxy `dinput8.dll` (see [FlingStager]). This entry describes where to
 * fetch it ([url]), how to verify it ([sha256] + [sizeBytes]), and what it does ([options]).
 * Identity is (source, gameId) — the same keying scheme as the cheat-table catalog.
 */
data class TrainerEntry(
    val source: GameSource,
    val gameId: String,
    val title: String,
    val gameVersion: String,
    val options: List<String>,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    val proxyName: String,
    val notes: String,
)

/**
 * Loads the JSON-driven FLiNG trainer catalog from:
 *   1. Bundled asset:   assets/flingtrainers/registry.json   (always present)
 *   2. Cached download: filesDir/flingtrainers/registry.json (updated by FlingCatalogSyncWorker)
 *
 * Mirrors [CheatTableLoader] exactly — same preference order (a valid cache wins over the
 * bundled seed), same 256 KB cap, same defensive parse (a single bad entry is skipped, the
 * rest load normally). There is NO user-store overlay: a manually-imported trainer is a
 * per-game DLL handled directly by [FlingStager] (filesDir/fling_trainers/<appId>/dinput8.dll),
 * not a catalog entry — the catalog only describes trainers available to DOWNLOAD.
 *
 * Thread safety: [loadedTrainers] is @Volatile; [saveAndReload] replaces it atomically under
 * the caller's own coroutine context (same pattern as CheatTableLoader).
 */
object FlingCatalogLoader {

    private const val TAG = "FlingCatalogLoader"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256 KB — matches cheattables cap
    private const val BUNDLED_ASSET = "flingtrainers/registry.json"

    /**
     * Cached list of parsed trainers, in catalog order. Populated by [init].
     * Multiple entries may share a (source, gameId) key (e.g. one per game version);
     * [trainersFor] returns all of them.
     */
    @Volatile
    var loadedTrainers: List<TrainerEntry> = emptyList()
        private set

    fun init(context: Context) {
        loadedTrainers = load(context)
        Timber.tag(TAG).d("Loaded ${loadedTrainers.size} FLiNG catalog trainers")
    }

    /** All trainers in the catalog (bundled or OTA cache, cache wins when valid). */
    fun allTrainers(): List<TrainerEntry> = loadedTrainers

    /** Catalog trainers available for the given (source, gameId), in catalog order. */
    fun trainersFor(source: GameSource, gameId: String): List<TrainerEntry> =
        loadedTrainers.filter { it.source == source && it.gameId == gameId }

    private fun load(context: Context): List<TrainerEntry> {
        val cacheFile = getCacheFile(context)
        val jsonString: String? = when {
            cacheFile.exists() && cacheFile.length() <= MAX_FILE_SIZE -> {
                try {
                    cacheFile.readText(Charsets.UTF_8).also {
                        Timber.tag(TAG).d("Using cached catalog from ${cacheFile.path}")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read cached catalog — falling back to bundled")
                    null
                }
            }
            cacheFile.exists() -> {
                Timber.tag(TAG).w("Cached catalog exceeds size cap (${cacheFile.length()} bytes) — ignoring")
                null
            }
            else -> null
        }

        // Catalog = OTA cache if valid, else bundled. (cache wins over bundled.)
        val resolvedJson = jsonString ?: loadBundled(context)
        return if (resolvedJson != null) parse(resolvedJson) else emptyList()
    }

    private fun loadBundled(context: Context): String? {
        return try {
            context.assets.open(BUNDLED_ASSET)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load bundled $BUNDLED_ASSET")
            null
        }
    }

    /**
     * Public entry-point for [FlingCatalogSyncWorker]: validate and store a freshly
     * downloaded JSON string to the cache. If validation fails the existing cache is
     * left untouched. Returns true on success, false if the JSON is invalid or too large.
     */
    fun saveAndReload(context: Context, rawJson: String): Boolean {
        if (rawJson.length > MAX_FILE_SIZE) {
            Timber.tag(TAG).w("Downloaded catalog exceeds size cap — rejecting")
            return false
        }
        val parsed = try {
            parse(rawJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Downloaded catalog failed to parse — rejecting")
            return false
        }
        return try {
            val cacheFile = getCacheFile(context)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(rawJson, Charsets.UTF_8)
            loadedTrainers = parsed
            Timber.tag(TAG).i("Updated cached FLiNG catalog (${parsed.size} trainers)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write catalog cache")
            false
        }
    }

    fun getCacheFile(context: Context): File {
        return File(context.filesDir, "flingtrainers/registry.json")
    }

    // -------------------------------------------------------------------------
    // JSON parser
    // -------------------------------------------------------------------------

    internal fun parse(json: String): List<TrainerEntry> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("trainers") ?: return emptyList()
        val result = mutableListOf<TrainerEntry>()

        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            try {
                val parsed = parseEntry(entry) ?: continue
                result.add(parsed)
            } catch (e: Exception) {
                // A bad entry is skipped; the rest continue loading — defensive.
                Timber.tag(TAG).w(e, "Skipping malformed trainer entry at index $i")
            }
        }
        return result
    }

    private fun parseEntry(entry: JSONObject): TrainerEntry? {
        val sourceStr = entry.optString("source", "").uppercase().trim()
        val gameId    = entry.optString("gameId", "").trim()
        val url       = entry.optString("url", "").trim()
        val sha256    = entry.optString("sha256", "").trim().lowercase()

        if (sourceStr.isEmpty() || gameId.isEmpty()) return null

        val source = try {
            GameSource.valueOf(sourceStr)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Unknown source '$sourceStr' — skipping trainer")
            return null
        }

        // Security gate at parse time: an entry the downloader could never safely act on is
        // dropped now so it never reaches the UI. HTTPS only, sha256 must be 64 hex chars.
        if (!url.startsWith("https://", ignoreCase = true)) {
            Timber.tag(TAG).w("Trainer for $source/$gameId has non-HTTPS url — skipping")
            return null
        }
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) {
            Timber.tag(TAG).w("Trainer for $source/$gameId has invalid sha256 — skipping")
            return null
        }

        val title       = entry.optString("title", "").trim()
        val gameVersion = entry.optString("gameVersion", "").trim().ifEmpty { "latest" }
        val proxyName   = entry.optString("proxyName", "dinput8").trim().ifEmpty { "dinput8" }
        val notes       = entry.optString("notes", "").trim()
        val sizeBytes   = entry.optLong("sizeBytes", 0L)

        val optionsArray = entry.optJSONArray("options")
        val options = buildList {
            if (optionsArray != null) {
                for (j in 0 until optionsArray.length()) {
                    val o = optionsArray.optString(j, "").trim()
                    if (o.isNotEmpty()) add(o)
                }
            }
        }

        return TrainerEntry(
            source      = source,
            gameId      = gameId,
            title       = title,
            gameVersion = gameVersion,
            options     = options,
            url         = url,
            sha256      = sha256,
            sizeBytes   = sizeBytes,
            proxyName   = proxyName,
            notes       = notes,
        )
    }
}
