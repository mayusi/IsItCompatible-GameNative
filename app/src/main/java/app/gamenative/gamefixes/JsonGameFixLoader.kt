package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.types.ConditionalExeFix
import app.gamenative.gamefixes.types.KeyedDllOverrideFix
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
                // SEC-4: whitelist the registry key to safe namespaces.
                // Certain keys enable code execution if written to from an OTA fix
                // (AppInit_DLLs, Image File Execution Options, Session Manager).
                // Only allow writes under Software\... (per-game app settings).
                if (!isRegistryKeySafe(registryKey)) {
                    Timber.tag(TAG).w("registry_key for $source/$gameId rejected — key '$registryKey' is not in an allowed namespace")
                    return null
                }
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
                // SEC-8: reject at parse-time to prevent the entry from reaching
                // LaunchArgFix.apply() at all.
                if (LaunchArgFix.containsDangerousMetacharacters(launchArgs)) {
                    Timber.tag(TAG).w("launch_arg for $source/$gameId contains dangerous metacharacter — skipping: '$launchArgs'")
                    return null
                }
                KeyedLaunchArgFix(
                    gameSource = source,
                    gameId = gameId,
                    launchArgs = launchArgs,
                )
            }
            "dll_override" -> {
                // Merges new DLL overrides into WINEDLLOVERRIDES without clobbering existing entries.
                // JSON format: { "overrides": { "dllname": "mode", ... } }
                val overridesObj = entry.optJSONObject("overrides") ?: return null
                val overrides = mutableMapOf<String, String>()
                for (k in overridesObj.keys()) {
                    overrides[k] = overridesObj.getString(k)
                }
                if (overrides.isEmpty()) return null
                KeyedDllOverrideFix(
                    gameSource = source,
                    gameId = gameId,
                    overrides = overrides,
                )
            }
            "conditional_exe" -> {
                // Applies an inner fix only when container.executablePath contains exePattern.
                // JSON format: { "exePattern": "dmc1.exe", "inner": { ...nested fix object... } }
                val exePattern = entry.optString("exePattern", "").trim()
                if (exePattern.isEmpty()) {
                    Timber.tag(TAG).w("conditional_exe missing exePattern — skipping $source/$gameId")
                    return null
                }
                val innerObj = entry.optJSONObject("inner") ?: run {
                    Timber.tag(TAG).w("conditional_exe missing inner object — skipping $source/$gameId")
                    return null
                }
                // Synthesise a full entry JSON for the inner fix by inheriting source/gameId.
                if (!innerObj.has("source")) innerObj.put("source", source.name)
                if (!innerObj.has("gameId")) innerObj.put("gameId", gameId)
                val innerKeyed = parseEntry(innerObj) ?: run {
                    Timber.tag(TAG).w("conditional_exe inner fix failed to parse — skipping $source/$gameId")
                    return null
                }
                // Wrap in a KeyedGameFix adapter so the registry can store it.
                object : app.gamenative.gamefixes.KeyedGameFix,
                    app.gamenative.gamefixes.GameFix by ConditionalExeFix(exePattern, innerKeyed) {
                    override val gameSource = source
                    override val gameId = gameId
                }
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
     * SEC-4: Whitelists registry keys to safe per-application namespaces.
     *
     * Blocked dangerous key paths (enable code execution if OTA-written):
     *   - ...\Windows NT\CurrentVersion\Windows   (AppInit_DLLs value lives here)
     *   - ...\Image File Execution Options         (debugger hijacking)
     *   - ...\Session Manager                      (AppCertDlls, KnownDLLs)
     *   - ...\Winlogon                             (shell/userinit hooks)
     *
     * Allowed: any key starting with "Software\" (case-insensitive) EXCEPT
     * those matching the blocked subpath list above.  Fully-qualified variants
     * (HKEY_CURRENT_USER\Software\, HKLM\Software\, etc.) are also accepted.
     */
    private fun isRegistryKeySafe(registryKey: String): Boolean {
        val key = registryKey.trim()
        val keyLower = key.lowercase()

        // Block-list: dangerous key sub-paths that enable code execution.
        // These are matched as substrings so partial paths are also caught.
        val blockedSubpaths = listOf(
            "\\windows nt\\currentversion\\windows",   // AppInit_DLLs container key
            "\\image file execution options",           // debugger hijack
            "\\session manager",                        // AppCertDlls / KnownDLLs
            "\\winlogon",                               // shell/userinit hooks
            "\\appcertdlls",                            // direct sub-key reference
            "\\knowndlls",                              // direct sub-key reference
        )
        for (blocked in blockedSubpaths) {
            if (keyLower.contains(blocked)) {
                return false
            }
        }

        // Allow-list: must begin with a recognised Software hive prefix.
        val allowedPrefixes = listOf(
            "software\\",
            "hkey_current_user\\software\\",
            "hkey_local_machine\\software\\",
            "hkcu\\software\\",
            "hklm\\software\\",
        )
        return allowedPrefixes.any { keyLower.startsWith(it) }
    }

    /**
     * Guards against path-traversal in any path-bearing string coming from JSON.
     * Rejects:
     *   - ".." segments (classic traversal)
     *   - absolute POSIX paths (start with "/")
     *   - absolute Windows/UNC paths (start with "\\", or match "[A-Za-z]:\\")
     *   - backslash separators (not valid in relative Linux paths; also a traversal vector)
     *
     * Only clearly-relative forward-slash paths are accepted, matching how installPath
     * sub-paths are used throughout the codebase (e.g. "config/settings.ini").
     *
     * Mirrors the runtime canonical-path guard in [IniFileFix] and [DeleteGameFilesFix].
     */
    private fun requireNoPathTraversal(path: String, context: String) {
        require(!path.contains("..")) {
            "Path traversal detected (.. segment) in $context: '$path'"
        }
        require(!path.startsWith("/")) {
            "Absolute path rejected in $context: '$path'"
        }
        require(!path.startsWith("\\")) {
            "Absolute/UNC path rejected in $context: '$path'"
        }
        // Reject Windows drive-letter absolute paths: "C:\..." or "c:/"
        require(!path.matches(Regex("^[A-Za-z]:[/\\\\].*"))) {
            "Windows absolute path rejected in $context: '$path'"
        }
        // Reject any backslash (not valid in relative Linux paths and is a traversal vector)
        require(!path.contains("\\")) {
            "Backslash separator rejected in $context: '$path'"
        }
    }
}
