package app.gamenative.autotuner

import android.content.Context
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * CrashFixCatalog — crowd-sourced OTA catalog of known crash-fix solutions.
 *
 * Loaded from:
 *   1. Bundled asset: assets/crashfixes/registry.json  (always present — seed data)
 *   2. Cached download: filesDir/crashfixes/registry.json  (updated by CrashFixSyncWorker)
 *
 * Mirrors [app.gamenative.cheats.CheatTableLoader] EXACTLY — same preference order
 * (cached download wins over bundled if valid + under 256 KB), same size cap,
 * same defensive parse (a bad entry is skipped, the rest load normally), same
 * saveAndReload entry-point for the sync worker.
 *
 * Schema (assets/crashfixes/registry.json):
 * {
 *   "version": 1,
 *   "fixes": [
 *     {
 *       "failureClass": "D3D12_UNSUPPORTED",   // FixLadder.FailureClass.name()
 *       "rungId": "dx12_force_dx11",            // FixLadder.Rung.id
 *       "games": ["*"],                         // list of appIds OR ["*"] for all
 *       "_comment": "optional human note"
 *     },
 *     ...
 *   ]
 * }
 *
 * Thread safety: [loadedEntries] is @Volatile; [saveAndReload] replaces it atomically
 * (same pattern as CheatTableLoader). The list is read-only after assignment.
 *
 * FUTURE — contribution / upload side: once enough devices accumulate per-device wins in
 * TunerMemory, a batch uploader can aggregate them into the GitHub registry.json.
 * That is OUT OF SCOPE for this task (download + seed + wire only).
 */
object CrashFixCatalog {

    private const val TAG = "CrashFixCatalog"
    private const val MAX_FILE_SIZE = 256 * 1024 // 256 KB — matches cheattables / gamefixes cap

    // -------------------------------------------------------------------------
    // Parsed entry type
    // -------------------------------------------------------------------------

    /**
     * One row from the "fixes" array: a single (failureClass, rungId, games) triplet.
     */
    private data class CatalogEntry(
        val failureClass: FixLadder.FailureClass,
        val rungId: String,
        /** Matched appIds, or empty-set indicating wildcard (["*"]). */
        val appIds: Set<String>,
        val isWildcard: Boolean,
    )

    // -------------------------------------------------------------------------
    // Volatile in-memory state
    // -------------------------------------------------------------------------

    @Volatile
    private var loadedEntries: List<CatalogEntry> = emptyList()

    // -------------------------------------------------------------------------
    // Init — mirrors CheatTableLoader.init(context)
    // -------------------------------------------------------------------------

    /**
     * Loads the catalog on startup. Prefers a valid cached download; falls back to the bundled
     * seed asset. Call once from Application.onCreate() on a background coroutine, exactly like
     * CheatTableLoader.init. Safe to call multiple times (idempotent load, last write wins).
     */
    fun init(context: Context) {
        loadedEntries = load(context)
        Timber.tag(TAG).d("Loaded ${loadedEntries.size} crash-fix catalog entries")
    }

    // -------------------------------------------------------------------------
    // Public lookup API
    // -------------------------------------------------------------------------

    /**
     * Returns the catalog's recommended rung ids for [failureClass] and [appId], ordered:
     *   1. Game-specific entries first (exact appId match).
     *   2. Wildcard entries (games=["*"]) next.
     *
     * Only returns rung ids that exist in [FixLadder.LADDER], so stale catalog entries
     * for rungs that no longer exist are silently dropped. Returns an empty list when no
     * catalog entries match — the caller falls back to LADDER order unchanged.
     *
     * Null-safe: any exception returns an empty list so a broken catalog entry can never
     * break the fix loop.
     */
    fun preferredRungsFor(failureClass: FixLadder.FailureClass, appId: String): List<String> {
        return try {
            val entries = loadedEntries
            val knownRungIds = FixLadder.LADDER.map { it.id }.toSet()

            val specific = entries
                .filter { it.failureClass == failureClass && !it.isWildcard && appId in it.appIds }
                .map { it.rungId }
                .filter { it in knownRungIds }

            val wildcard = entries
                .filter { it.failureClass == failureClass && it.isWildcard }
                .map { it.rungId }
                .filter { it in knownRungIds }

            // Deduplicate: specific first, then wildcard, no duplicates
            (specific + wildcard).distinct()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "preferredRungsFor failed — returning empty (catalog error, non-fatal)")
            emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // saveAndReload — entry-point for CrashFixSyncWorker (mirrors CheatTableLoader.saveAndReload)
    // -------------------------------------------------------------------------

    /**
     * Validates and stores a freshly-downloaded JSON string to the local cache, then
     * reloads the in-memory entries from it. If validation fails, the existing cache
     * and in-memory entries are left untouched.
     *
     * Returns true on success, false if the JSON is invalid, too large, or write fails.
     * Mirrors [app.gamenative.cheats.CheatTableLoader.saveAndReload] exactly.
     */
    fun saveAndReload(context: Context, rawJson: String): Boolean {
        if (rawJson.length > MAX_FILE_SIZE) {
            Timber.tag(TAG).w("Downloaded crash-fix registry exceeds size cap — rejecting")
            return false
        }
        val parsed = try {
            parse(rawJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Downloaded crash-fix registry failed to parse — rejecting")
            return false
        }
        return try {
            val cacheFile = getCacheFile(context)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(rawJson, Charsets.UTF_8)
            loadedEntries = parsed
            Timber.tag(TAG).i("Updated crash-fix catalog cache (${parsed.size} entries)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to write crash-fix registry cache")
            false
        }
    }

    fun getCacheFile(context: Context): File =
        File(context.filesDir, "crashfixes/registry.json")

    // -------------------------------------------------------------------------
    // Internal — load (mirrors CheatTableLoader.load)
    // -------------------------------------------------------------------------

    private fun load(context: Context): List<CatalogEntry> {
        val cacheFile = getCacheFile(context)
        val jsonString: String? = when {
            cacheFile.exists() && cacheFile.length() <= MAX_FILE_SIZE -> {
                try {
                    cacheFile.readText(Charsets.UTF_8).also {
                        Timber.tag(TAG).d("Using cached crash-fix registry from ${cacheFile.path}")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to read cached crash-fix registry — falling back to bundled")
                    null
                }
            }
            cacheFile.exists() -> {
                Timber.tag(TAG).w("Cached crash-fix registry exceeds size cap (${cacheFile.length()} bytes) — ignoring")
                null
            }
            else -> null
        }

        val resolvedJson = jsonString ?: loadBundled(context) ?: return emptyList()
        return parse(resolvedJson)
    }

    private fun loadBundled(context: Context): String? {
        return try {
            context.assets.open("crashfixes/registry.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load bundled crashfixes/registry.json")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Internal — JSON parser (defensive: bad entry is skipped, rest load normally)
    // -------------------------------------------------------------------------

    private fun parse(json: String): List<CatalogEntry> {
        val root = JSONObject(json)
        val fixesArray = root.optJSONArray("fixes") ?: return emptyList()
        val result = mutableListOf<CatalogEntry>()

        for (i in 0 until fixesArray.length()) {
            val entry = fixesArray.optJSONObject(i) ?: continue
            try {
                val parsed = parseEntry(entry) ?: continue
                result.add(parsed)
            } catch (e: Exception) {
                // A bad entry is skipped; the rest continue loading — defensive.
                Timber.tag(TAG).w(e, "Skipping malformed crash-fix catalog entry at index $i")
            }
        }
        return result
    }

    private fun parseEntry(entry: JSONObject): CatalogEntry? {
        val failureClassStr = entry.optString("failureClass", "").trim()
        val rungId = entry.optString("rungId", "").trim()

        if (failureClassStr.isEmpty() || rungId.isEmpty()) {
            Timber.tag(TAG).w("Crash-fix catalog entry missing failureClass or rungId — skipping")
            return null
        }

        val failureClass = try {
            FixLadder.FailureClass.valueOf(failureClassStr)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w("Unknown FailureClass '$failureClassStr' — skipping entry")
            return null
        }

        val gamesArray = entry.optJSONArray("games")
        val isWildcard: Boolean
        val appIds: Set<String>

        if (gamesArray == null || gamesArray.length() == 0) {
            // No games array: treat as wildcard
            isWildcard = true
            appIds = emptySet()
        } else {
            val ids = mutableSetOf<String>()
            for (j in 0 until gamesArray.length()) {
                val id = gamesArray.optString(j, "").trim()
                if (id == "*") {
                    // Wildcard present in array: entire entry is wildcard
                    return CatalogEntry(
                        failureClass = failureClass,
                        rungId = rungId,
                        appIds = emptySet(),
                        isWildcard = true,
                    )
                }
                if (id.isNotEmpty()) ids.add(id)
            }
            isWildcard = false
            appIds = ids
        }

        return CatalogEntry(
            failureClass = failureClass,
            rungId = rungId,
            appIds = appIds,
            isWildcard = isWildcard,
        )
    }
}
