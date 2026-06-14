package app.gamenative.cheats

import android.content.Context
import app.gamenative.data.GameSource
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Loads JSON-driven cheat tables from:
 *   1. Bundled asset: assets/cheattables/registry.json  (always present)
 *   2. Cached download: filesDir/cheattables/registry.json  (updated by CheatTableSyncWorker)
 *
 * Mirrors [app.gamenative.gamefixes.JsonGameFixLoader] exactly — same preference
 * order (cache wins over bundled if valid), same size cap, same defensive parse
 * (a bad individual table is skipped, the rest load normally).
 *
 * Thread safety: [loadedTables] is @Volatile; [saveAndReload] replaces it
 * atomically under the caller's own coroutine context (same pattern as JsonGameFixLoader).
 */
object CheatTableLoader {

    private const val TAG = "CheatTableLoader"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256 KB — matches gamefixes cap

    /**
     * Cached map of parsed tables. Populated by [init].
     * Key = (GameSource, gameId).
     */
    @Volatile
    var loadedTables: Map<Pair<GameSource, String>, CheatTable> = emptyMap()
        private set

    fun init(context: Context) {
        loadedTables = load(context)
        Timber.tag(TAG).d("Loaded ${loadedTables.size} cheat tables")
    }

    private fun load(context: Context): Map<Pair<GameSource, String>, CheatTable> {
        val cacheFile = getCacheFile(context)
        val jsonString: String? = when {
            cacheFile.exists() && cacheFile.length() <= MAX_FILE_SIZE -> {
                try {
                    cacheFile.readText(Charsets.UTF_8).also {
                        Timber.tag(TAG).d("Using cached registry from ${cacheFile.path}")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read cached registry — falling back to bundled")
                    null
                }
            }
            cacheFile.exists() -> {
                Timber.tag(TAG).w("Cached registry exceeds size cap (${cacheFile.length()} bytes) — ignoring")
                null
            }
            else -> null
        }

        val resolvedJson = jsonString ?: loadBundled(context) ?: return emptyMap()
        return parse(resolvedJson)
    }

    private fun loadBundled(context: Context): String? {
        return try {
            context.assets.open("cheattables/registry.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load bundled cheattables/registry.json")
            null
        }
    }

    /**
     * Public entry-point for [CheatTableSyncWorker]: validate and store a freshly
     * downloaded JSON string to the cache.  If validation fails the existing cache
     * is left untouched.
     *
     * Returns true on success, false if the JSON is invalid or too large.
     */
    fun saveAndReload(context: Context, rawJson: String): Boolean {
        if (rawJson.length > MAX_FILE_SIZE) {
            Timber.tag(TAG).w("Downloaded registry exceeds size cap — rejecting")
            return false
        }
        val parsed = try {
            parse(rawJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Downloaded registry failed to parse — rejecting")
            return false
        }
        return try {
            val cacheFile = getCacheFile(context)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(rawJson, Charsets.UTF_8)
            loadedTables = parsed
            Timber.tag(TAG).i("Updated cached registry (${parsed.size} tables)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write registry cache")
            false
        }
    }

    fun getCacheFile(context: Context): File {
        return File(context.filesDir, "cheattables/registry.json")
    }

    // -------------------------------------------------------------------------
    // JSON parser
    // -------------------------------------------------------------------------

    internal fun parse(json: String): Map<Pair<GameSource, String>, CheatTable> {
        val root = JSONObject(json)
        val tablesArray = root.optJSONArray("tables") ?: return emptyMap()
        val result = mutableMapOf<Pair<GameSource, String>, CheatTable>()

        for (i in 0 until tablesArray.length()) {
            val entry = tablesArray.optJSONObject(i) ?: continue
            try {
                val table = parseTable(entry) ?: continue
                val key = table.source to table.gameId
                if (result.containsKey(key)) {
                    Timber.tag(TAG).w("Duplicate cheat table for $key — keeping first")
                } else {
                    result[key] = table
                }
            } catch (e: Exception) {
                // A bad table is skipped; the rest continue loading — defensive.
                Timber.tag(TAG).w(e, "Skipping malformed cheat table at index $i")
            }
        }
        return result
    }

    private fun parseTable(entry: JSONObject): CheatTable? {
        val sourceStr = entry.optString("source", "").uppercase()
        val gameId    = entry.optString("gameId", "").trim()
        val title     = entry.optString("title", "").trim()

        if (sourceStr.isEmpty() || gameId.isEmpty()) return null

        val source = try {
            GameSource.valueOf(sourceStr)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Unknown source '$sourceStr' — skipping table")
            return null
        }

        val cheatsArray = entry.optJSONArray("cheats") ?: return null
        val cheats = mutableListOf<Cheat>()
        for (j in 0 until cheatsArray.length()) {
            val cheatObj = cheatsArray.optJSONObject(j) ?: continue
            try {
                val cheat = parseCheat(cheatObj) ?: continue
                cheats.add(cheat)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skipping malformed cheat at index $j in table $source/$gameId")
            }
        }

        // A table with no valid cheats is still stored — the OTA author may add cheats later.
        return CheatTable(
            source  = source,
            gameId  = gameId,
            title   = title,
            cheats  = cheats,
        )
    }

    private fun parseCheat(obj: JSONObject): Cheat? {
        val id       = obj.optString("id", "").trim()
        val name     = obj.optString("name", "").trim()
        val category = obj.optString("category", "").trim()
        val vtype    = obj.optString("vtype", "").trim()

        if (id.isEmpty() || vtype.isEmpty()) return null

        val recipeObj = obj.optJSONObject("recipe") ?: return null
        val recipe = CheatRecipe(
            kind          = recipeObj.optString("kind", "guided_known"),
            prompt        = recipeObj.optString("prompt", ""),
            narrowPrompt  = recipeObj.optString("narrowPrompt", "").takeIf { it.isNotEmpty() },
            freezeValue   = if (recipeObj.has("freezeValue")) recipeObj.getLong("freezeValue") else null,
        )

        val aob: AobPattern? = if (obj.isNull("aob") || !obj.has("aob")) {
            null
        } else {
            val aobObj = obj.optJSONObject("aob")
            if (aobObj == null) null else AobPattern(
                pattern = aobObj.optString("pattern", ""),
                offset  = aobObj.optInt("offset", 0),
                vtype   = aobObj.optString("vtype", vtype),
            )
        }

        return Cheat(
            id       = id,
            name     = name,
            category = category,
            vtype    = vtype,
            recipe   = recipe,
            aob      = aob,
        )
    }
}
