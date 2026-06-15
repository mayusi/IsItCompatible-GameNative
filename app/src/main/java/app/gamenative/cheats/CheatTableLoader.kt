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
            // freezeValue may be absent OR explicitly JSON null (aob_patch cheats NOP code
            // and carry no freeze value). Guard isNull() too — getLong() throws on JSON null.
            freezeValue   = if (recipeObj.has("freezeValue") && !recipeObj.isNull("freezeValue")) {
                recipeObj.getLong("freezeValue")
            } else {
                null
            },
        )

        val aob: AobPattern? = if (!obj.has("aob") || obj.isNull("aob")) {
            // Key absent or explicitly null — no AOB for this cheat. Current behaviour preserved.
            null
        } else {
            val aobObj = obj.optJSONObject("aob")
            if (aobObj == null) {
                // "aob" key is present but is not a JSON object (e.g. a scalar) — malformed.
                Timber.tag(TAG).w("Cheat '$id': 'aob' is not a JSON object — ignoring aob block")
                null
            } else {
                val aobPattern = aobObj.optString("pattern", "").trim()
                if (aobPattern.isEmpty()) {
                    Timber.tag(TAG).w("Cheat '$id': aob missing required 'pattern' field — skipping cheat")
                    return null
                }
                // 'offset' must be a JSON number; optInt returns 0 on missing OR on type mismatch,
                // so we distinguish "present but non-numeric" via has() + type check.
                if (aobObj.has("offset") && aobObj.opt("offset") !is Number) {
                    Timber.tag(TAG).w("Cheat '$id': aob 'offset' is not numeric — skipping cheat")
                    return null
                }
                val aobOffset = aobObj.optInt("offset", 0)
                // vtype falls back to the parent cheat's vtype when absent in the aob block.
                val aobVtype = aobObj.optString("vtype", "").trim().let {
                    if (it.isEmpty()) vtype else it
                }
                // patchBytes is optional; null when absent or explicitly JSON null.
                val aobPatchBytes = if (!aobObj.has("patchBytes") || aobObj.isNull("patchBytes")) {
                    null
                } else {
                    aobObj.optString("patchBytes", "").trim().takeIf { it.isNotEmpty() }
                }
                AobPattern(
                    pattern    = aobPattern,
                    offset     = aobOffset,
                    vtype      = aobVtype,
                    patchBytes = aobPatchBytes,
                )
            }
        }

        // -----------------------------------------------------------------
        // Parse optional "chain" block (pointer_chain / static cheats).
        // Parsed for ALL cheats when present — set to null if absent/null/malformed
        // (same pattern as "aob" above).
        // -----------------------------------------------------------------
        val chain: ChainSpec? = if (!obj.has("chain") || obj.isNull("chain")) {
            null
        } else {
            val chainObj = obj.optJSONObject("chain")
            if (chainObj == null) {
                Timber.tag(TAG).w("Cheat '$id': 'chain' is not a JSON object — ignoring chain block")
                null
            } else {
                val chainModule = chainObj.optString("module", "").trim()

                val baseStr = chainObj.optString("base", "").trim()
                val chainBase = parseHexLong(baseStr)
                if (chainBase == null) {
                    Timber.tag(TAG).w("Cheat '$id': chain 'base' is missing or unparseable ('$baseStr') — ignoring chain block")
                    null
                } else {
                    val valueOffsetStr = chainObj.optString("valueOffset", "0x0").trim()
                    val chainValueOffset = parseHexLong(valueOffsetStr) ?: run {
                        Timber.tag(TAG).w("Cheat '$id': chain 'valueOffset' is unparseable ('$valueOffsetStr') — ignoring chain block")
                        null
                    }

                    if (chainValueOffset == null) {
                        null
                    } else {
                        val offsetsArray = chainObj.optJSONArray("offsets")
                        val chainOffsets: List<Long> = if (offsetsArray == null) {
                            emptyList()
                        } else {
                            buildList {
                                for (k in 0 until offsetsArray.length()) {
                                    val hexStr = offsetsArray.optString(k, "").trim()
                                    val parsed = parseHexLong(hexStr)
                                    if (parsed != null) add(parsed)
                                    else Timber.tag(TAG).w("Cheat '$id': chain offsets[$k] is unparseable ('$hexStr') — skipping entry")
                                }
                            }
                        }

                        val chainWriteMax = chainObj.optBoolean("writeMax", false)
                        val maxOffsetStr = chainObj.optString("maxOffset", "0x0").trim()
                        val chainMaxOffset = parseHexLong(maxOffsetStr) ?: 0L

                        ChainSpec(
                            module      = chainModule,
                            base        = chainBase,
                            offsets     = chainOffsets,
                            valueOffset = chainValueOffset,
                            writeMax    = chainWriteMax,
                            maxOffset   = chainMaxOffset,
                        )
                    }
                }
            }
        }

        // Validation: recipe kinds that depend on AOB must have a valid aob block.
        // A broken "aob_freeze" or "aob_patch" cheat is skipped rather than loaded half-formed.
        if (recipe.kind == "aob_freeze" || recipe.kind == "aob_patch") {
            if (aob == null) {
                Timber.tag(TAG).w(
                    "Cheat '$id': kind='${recipe.kind}' requires a non-null 'aob' block — skipping cheat"
                )
                return null
            }
        }

        // Validation: recipe kinds that depend on a chain block must have a valid chain.
        // Mirrors the aob guard above exactly.
        if (recipe.kind == "pointer_chain" || recipe.kind == "static") {
            if (chain == null) {
                Timber.tag(TAG).w(
                    "Cheat '$id': kind='${recipe.kind}' requires a non-null 'chain' block with valid 'base' and 'valueOffset' — skipping cheat"
                )
                return null
            }
        }

        return Cheat(
            id       = id,
            name     = name,
            category = category,
            vtype    = vtype,
            recipe   = recipe,
            aob      = aob,
            chain    = chain,
        )
    }

    /**
     * Parses a hex string (with or without a leading "0x"/"0X" prefix) to a [Long].
     * Accepts both upper- and lower-case hex digits.
     * Returns null if the string is blank or contains non-hex characters.
     */
    private fun parseHexLong(s: String): Long? {
        val stripped = s.trim().removePrefix("0x").removePrefix("0X")
        if (stripped.isEmpty()) return null
        return stripped.toLongOrNull(16)
    }
}
