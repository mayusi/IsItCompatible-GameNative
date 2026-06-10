package app.gamenative.gamefixes

import android.content.Context
import org.json.JSONObject
import timber.log.Timber

/**
 * A single playable game within a multi-game collection.
 *
 * @param name        Display name shown in the "Games in this collection" UX.
 * @param exePath     Relative Windows path to the game's exe (e.g. "dmc1.exe").
 *                    Matches the format used by [container.executablePath].
 * @param execArgs    Optional extra arguments passed to the exe on launch.
 * @param fixHints    Reserved for future per-sub-game fix metadata; unused today.
 */
data class CollectionSubGame(
    val name: String,
    val exePath: String,
    val execArgs: String = "",
    val fixHints: List<String> = emptyList(),
)

/**
 * Describes a multi-game collection identified by a single Steam/GOG/etc. appId.
 *
 * @param appId          The store appId that maps to a container (e.g. "631510").
 * @param collectionName Display name for the collection header.
 * @param subGames       Ordered list of playable titles inside the collection.
 * @param excludedExes   Filenames (case-insensitive) that should be hidden / de-prioritised
 *                       in the executable-path dropdown — typically the crashing launcher.
 */
data class GameCollection(
    val appId: String,
    val collectionName: String,
    val subGames: List<CollectionSubGame>,
    val excludedExes: List<String> = emptyList(),
)

/**
 * Curated registry of multi-game collections.
 *
 * Bundled entries are compiled in below.  An OTA-updateable JSON file at
 * assets/gamefixes/collections.json (and its cached counterpart) is loaded on
 * [init] and merged in, with OTA entries only filling gaps not covered by the
 * compiled-in set.
 *
 * Usage:
 *   CollectionRegistry.init(context)           // once, from Application.onCreate
 *   CollectionRegistry.getCollection("631510") // returns GameCollection? for DMC HD
 */
object CollectionRegistry {

    private const val TAG = "CollectionRegistry"
    private const val MAX_FILE_SIZE = 128 * 1024 // 128 KB

    // ---------------------------------------------------------------------------
    // Compiled-in entries
    // ---------------------------------------------------------------------------

    /**
     * Devil May Cry HD Collection (Steam 631510).
     *
     * Three separate games share one Wine prefix.  Each has its own root-level exe.
     * Directory layout confirmed on device:
     *   dmc1.exe  (DMC1)
     *   dmc2.exe  (DMC2)
     *   dmc3.exe  (DMC3: Dante's Awakening)
     *   dmcLauncher.exe  -- crashes when used to pick a game; excluded below.
     */
    private val DMC_HD_COLLECTION = GameCollection(
        appId = "631510",
        collectionName = "Devil May Cry HD Collection",
        subGames = listOf(
            CollectionSubGame(
                name = "Devil May Cry 1",
                exePath = "dmc1.exe",
            ),
            CollectionSubGame(
                name = "Devil May Cry 2",
                exePath = "dmc2.exe",
            ),
            CollectionSubGame(
                name = "Devil May Cry 3: Dante's Awakening",
                exePath = "dmc3.exe",
            ),
        ),
        excludedExes = listOf("dmcLauncher.exe", "dmclauncher.exe"),
    )

    private val BUILTIN: Map<String, GameCollection> = listOf(
        DMC_HD_COLLECTION,
    ).associateBy { it.appId }

    // ---------------------------------------------------------------------------
    // OTA-loaded entries (JSON)
    // ---------------------------------------------------------------------------

    @Volatile
    private var jsonCollections: Map<String, GameCollection> = emptyMap()

    /**
     * Merged view: compiled-in wins over OTA for the same appId.
     */
    private val allCollections: Map<String, GameCollection>
        get() = jsonCollections + BUILTIN // BUILTIN overwrites on collision

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Returns the [GameCollection] for [steamAppId], or null if not a known collection. */
    fun getCollection(steamAppId: String): GameCollection? = allCollections[steamAppId]

    /** Returns true if [steamAppId] maps to a known multi-game collection. */
    fun isKnownCollection(steamAppId: String): Boolean = allCollections.containsKey(steamAppId)

    // ---------------------------------------------------------------------------
    // Initialisation (loads OTA JSON if available)
    // ---------------------------------------------------------------------------

    /**
     * Loads OTA collections.json from cache (if present) or falls back to the
     * bundled asset.  Safe to call multiple times; later calls replace earlier.
     */
    fun init(context: Context) {
        jsonCollections = load(context)
        Timber.tag(TAG).d("Loaded ${jsonCollections.size} OTA collection entries")
    }

    private fun load(context: Context): Map<String, GameCollection> {
        val cacheFile = getCacheFile(context)
        val jsonString: String? = when {
            cacheFile.exists() && cacheFile.length() <= MAX_FILE_SIZE -> {
                try {
                    cacheFile.readText(Charsets.UTF_8).also {
                        Timber.tag(TAG).d("Using cached collections from ${cacheFile.path}")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read cached collections — falling back to bundled")
                    null
                }
            }
            cacheFile.exists() -> {
                Timber.tag(TAG).w("Cached collections.json exceeds size cap — ignoring")
                null
            }
            else -> null
        }
        val resolved = jsonString ?: loadBundled(context) ?: return emptyMap()
        return try {
            parse(resolved)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse collections JSON")
            emptyMap()
        }
    }

    private fun loadBundled(context: Context): String? {
        return try {
            context.assets.open("gamefixes/collections.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Timber.tag(TAG).d("No bundled collections.json found (${e.message})")
            null
        }
    }

    /**
     * Validates and persists a freshly-downloaded collections JSON.
     * Returns true on success, false if validation fails.
     */
    fun saveAndReload(context: Context, rawJson: String): Boolean {
        if (rawJson.length > MAX_FILE_SIZE) {
            Timber.tag(TAG).w("Downloaded collections.json exceeds size cap — rejecting")
            return false
        }
        val parsed = try {
            parse(rawJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Downloaded collections.json failed to parse — rejecting")
            return false
        }
        return try {
            val cacheFile = getCacheFile(context)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(rawJson, Charsets.UTF_8)
            jsonCollections = parsed
            Timber.tag(TAG).i("Updated cached collections (${parsed.size} entries)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write collections cache")
            false
        }
    }

    fun getCacheFile(context: Context) =
        java.io.File(context.filesDir, "gamefixes/collections.json")

    // ---------------------------------------------------------------------------
    // JSON parser
    // ---------------------------------------------------------------------------

    /**
     * Parses a collections.json string into a map of appId -> [GameCollection].
     *
     * Schema:
     * {
     *   "version": 1,
     *   "collections": [
     *     {
     *       "appId": "631510",
     *       "collectionName": "Devil May Cry HD Collection",
     *       "subGames": [
     *         { "name": "Devil May Cry 1", "exePath": "dmc1.exe", "execArgs": "", "fixHints": [] }
     *       ],
     *       "excludedExes": ["dmcLauncher.exe"]
     *     }
     *   ]
     * }
     */
    internal fun parse(json: String): Map<String, GameCollection> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("collections") ?: return emptyMap()
        val result = mutableMapOf<String, GameCollection>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            try {
                val appId = obj.optString("appId", "").trim()
                val collectionName = obj.optString("collectionName", "").trim()
                if (appId.isEmpty() || collectionName.isEmpty()) continue

                val subGamesArr = obj.optJSONArray("subGames") ?: continue
                val subGames = mutableListOf<CollectionSubGame>()
                for (j in 0 until subGamesArr.length()) {
                    val sg = subGamesArr.optJSONObject(j) ?: continue
                    val name = sg.optString("name", "").trim()
                    val exePath = sg.optString("exePath", "").trim()
                    if (name.isEmpty() || exePath.isEmpty()) continue
                    val execArgs = sg.optString("execArgs", "")
                    val fixHintsArr = sg.optJSONArray("fixHints")
                    val fixHints = if (fixHintsArr != null) {
                        (0 until fixHintsArr.length()).map { fixHintsArr.getString(it) }
                    } else emptyList()
                    subGames.add(CollectionSubGame(name, exePath, execArgs, fixHints))
                }
                if (subGames.isEmpty()) continue

                val excludedArr = obj.optJSONArray("excludedExes")
                val excludedExes = if (excludedArr != null) {
                    (0 until excludedArr.length()).map { excludedArr.getString(it) }
                } else emptyList()

                result[appId] = GameCollection(appId, collectionName, subGames, excludedExes)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skipping malformed collection entry at index $i")
            }
        }
        return result
    }
}
