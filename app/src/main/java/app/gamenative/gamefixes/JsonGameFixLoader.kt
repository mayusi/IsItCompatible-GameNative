package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Loads JSON-driven game fixes from:
 *   1. Bundled asset: assets/gamefixes/registry.json  (always present)
 *   2. Cached download: filesDir/gamefixes/registry.json  (updated by GameFixesSyncWorker)
 *
 * The cached version takes precedence over the bundled asset if it passes validation,
 * allowing OTA updates without a code release.
 *
 * Compiled-in [KeyedGameFix] entries in [GameFixesRegistry] ALWAYS win over JSON entries
 * for the same (source, gameId) pair — the merge is done in GameFixesRegistry.allFixes.
 *
 * JSON Schema:
 * {
 *   "version": 1,
 *   "fixes": [
 *     {
 *       "source": "STEAM" | "GOG" | "EPIC" | "AMAZON" | "CUSTOM_GAME",
 *       "gameId": "<string>",
 *       "type": "delete_files" | "registry_key" | "ini_fix" | "wine_env" | "launch_arg",
 *       // type-specific fields — see each branch below
 *     },
 *     ...
 *   ]
 * }
 *
 * Security: path-bearing fields are validated against path-traversal attacks.
 * Globs and relative paths must not contain ".." segments.
 */
object JsonGameFixLoader {

    private const val TAG = "JsonGameFixLoader"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256 KB

    /**
     * Cached map of parsed fixes. Populated by [init]. Key = (GameSource, gameId).
     */
    @Volatile
    var loadedFixes: Map<Pair<GameSource, String>, GameFix> = emptyMap()
        private set

    fun init(context: Context) {
        loadedFixes = load(context)
        Timber.tag(TAG).d("Loaded ${loadedFixes.size} JSON-driven fixes")
    }

    private fun load(context: Context): Map<Pair<GameSource, String>, GameFix> {
        // Prefer cached (downloaded) registry if it is valid.
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
            context.assets.open("gamefixes/registry.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load bundled registry.json")
            null
        }
    }

    /**
     * Public entry-point for [GameFixesSyncWorker]: validate and store a freshly downloaded
     * JSON string to the cache. If validation fails, the existing cache is left untouched.
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
            loadedFixes = parsed
            Timber.tag(TAG).i("Updated cached registry (${parsed.size} fixes)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write registry cache")
            false
        }
    }

    fun getCacheFile(context: Context): File {
        return File(context.filesDir, "gamefixes/registry.json")
    }

    // -------------------------------------------------------------------------
    // JSON parser
    // -------------------------------------------------------------------------

    internal fun parse(json: String): Map<Pair<GameSource, String>, GameFix> {
        val root = JSONObject(json)
        val fixesArray: JSONArray = root.optJSONArray("fixes") ?: return emptyMap()
        val result = mutableMapOf<Pair<GameSource, String>, GameFix>()

        for (i in 0 until fixesArray.length()) {
            val entry = fixesArray.optJSONObject(i) ?: continue
            try {
                val fix = parseEntry(entry) ?: continue
                val key = fix.gameSource to fix.gameId
                if (result.containsKey(key)) {
                    Timber.tag(TAG).w("Duplicate JSON fix for $key — keeping first")
                } else {
                    result[key] = fix
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skipping malformed JSON fix entry at index $i")
            }
        }
        return result
    }

    private fun parseEntry(entry: JSONObject): KeyedGameFix? {
        val sourceStr = entry.optString("source", "").uppercase()
        val gameId = entry.optString("gameId", "").trim()
        val type = entry.optString("type", "").lowercase()

        if (sourceStr.isEmpty() || gameId.isEmpty() || type.isEmpty()) return null

        val source = try {
            GameSource.valueOf(sourceStr)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Unknown source '$sourceStr' — skipping")
            return null
        }

        return when (type) {
            "delete_files" -> {
                val globs = parseStringArray(entry.optJSONArray("globs")) ?: return null
                // Security: reject globs that attempt path traversal
                globs.forEach { glob ->
                    requireNoPathTraversal(glob, "glob in delete_files for $source/$gameId")
                }
                KeyedDeleteGameFilesFix(
                    gameSource = source,
                    gameId = gameId,
                    installPathRelativeGlobs = globs,
                )
            }
            "registry_key" -> {
                val registryKey = entry.optString("registryKey", "")
                if (registryKey.isEmpty()) return null
                val valuesObj = entry.optJSONObject("values") ?: return null
                val values = mutableMapOf<String, String>()
                for (k in valuesObj.keys()) {
                    values[k] = valuesObj.getString(k)
                }
                KeyedRegistryKeyFix(
                    gameSource = source,
                    gameId = gameId,
                    registryKey = registryKey,
                    defaultValues = values,
                )
            }
            "ini_fix" -> {
                val relativePath = entry.optString("relativePath", "")
                if (relativePath.isEmpty()) return null
                // Security: ini relative path must not traverse
                requireNoPathTraversal(relativePath, "ini_fix relativePath for $source/$gameId")
                val defaultValuesObj = entry.optJSONObject("defaultValues") ?: return null
                val defaultValues = mutableMapOf<String, String>()
                for (k in defaultValuesObj.keys()) {
                    defaultValues[k] = defaultValuesObj.getString(k)
                }
                KeyedIniFileFix(
                    gameSource = source,
                    gameId = gameId,
                    relativePath = relativePath,
                    defaultValues = defaultValues,
                )
            }
            "wine_env" -> {
                val envVarsObj = entry.optJSONObject("envVars") ?: return null
                val envVars = mutableMapOf<String, String>()
                for (k in envVarsObj.keys()) {
                    envVars[k] = envVarsObj.getString(k)
                }
                KeyedWineEnvVarFix(
                    gameSource = source,
                    gameId = gameId,
                    envVarsToSet = envVars,
                )
            }
            "launch_arg" -> {
                val launchArgs = entry.optString("launchArgs", "")
                if (launchArgs.isEmpty()) return null
                KeyedLaunchArgFix(
                    gameSource = source,
                    gameId = gameId,
                    launchArgs = launchArgs,
                )
            }
            else -> {
                Timber.tag(TAG).w("Unknown fix type '$type' — skipping $source/$gameId")
                null
            }
        }
    }

    private fun parseStringArray(array: JSONArray?): List<String>? {
        if (array == null || array.length() == 0) return null
        return (0 until array.length()).map { array.getString(it) }
    }

    /**
     * Guards against path-traversal in any path-bearing string coming from JSON.
     * Mirrors the runtime guard in [DeleteGameFilesFix] (the installed-path startsWith check)
     * but rejects at parse-time so traversal entries never reach the filesystem at all.
     */
    private fun requireNoPathTraversal(path: String, context: String) {
        require(!path.contains("..")) {
            "Path traversal detected in $context: '$path'"
        }
    }
}
