package app.gamenative.cheats

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.epic.EpicService
import app.gamenative.utils.ContainerUtils
import timber.log.Timber

/**
 * Singleton registry for cheat tables.
 *
 * Mirrors [app.gamenative.gamefixes.GameFixesRegistry] exactly:
 *   - [init] loads the bundled + cached JSON via [CheatTableLoader] and schedules OTA sync.
 *   - An in-memory [cachedTables] map is rebuilt lazily after each OTA reload.
 *   - [tableForAppId] parses the compound appId via [ContainerUtils] and handles the
 *     Epic catalogId special-case, exactly as GameFixesRegistry.applyFor does.
 *   - [invalidateCache] nulls the map so the next access rebuilds from [CheatTableLoader.loadedTables].
 *
 * There are no compiled-in tables (unlike GameFixesRegistry which has Kotlin object fixes) -
 * all data comes from JSON, so [buildAllTables] is simply the loaded map with no merge step.
 * The shape is kept identical to the gamefixes pattern for future extensibility.
 */
object CheatTableRegistry {

    private const val TAG = "CheatTableRegistry"

    /**
     * Cached map built from the loaded JSON tables.
     * Nulled by [invalidateCache]; rebuilt on the next access.
     */
    @Volatile
    private var cachedTables: Map<Pair<GameSource, String>, CheatTable>? = null

    private fun buildAllTables(): Map<Pair<GameSource, String>, CheatTable> {
        cachedTables?.let { return it }
        val built = CheatTableLoader.loadedTables
        cachedTables = built
        return built
    }

    // Provider hook: test-overridable, same pattern as GameFixesRegistry.fixesProvider.
    private var tablesProvider: () -> Map<Pair<GameSource, String>, CheatTable> = { buildAllTables() }

    /**
     * Initialises [CheatTableLoader] and schedules the 24h OTA sync.
     * Call once from Application.onCreate() on a background coroutine.
     * Safe to call multiple times (idempotent).
     */
    fun init(context: Context) {
        CheatTableLoader.init(context)
        // Invalidate so the next lookup picks up the freshly loaded tables.
        cachedTables = null
        CheatTableSyncWorker.scheduleIfNeeded(context)
    }

    /**
     * Invalidates the cached table map.
     * Must be called after [CheatTableLoader.loadedTables] is updated (i.e. after a
     * successful OTA reload via [CheatTableLoader.saveAndReload]) so the next lookup
     * rebuilds from the new data.
     */
    fun invalidateCache() {
        cachedTables = null
    }

    // -------------------------------------------------------------------------
    // Lookup API
    // -------------------------------------------------------------------------

    /**
     * Returns the [CheatTable] for the given (source, gameId) pair, or null if none exists.
     * The gameId must be the canonical registry key (for Epic: the catalogId string).
     */
    fun tableFor(source: GameSource, gameId: String): CheatTable? {
        return tablesProvider()[source to gameId]
    }

    /**
     * Parses a compound appId (e.g. "STEAM_271590", "EPIC_12345") using [ContainerUtils]
     * and returns the matching [CheatTable], or null.
     *
     * Mirrors GameFixesRegistry.applyFor Epic catalogId handling:
     * Epic auto-generated numeric appId is replaced with catalogId before lookup,
     * exactly as GameFixesRegistry.applyFor and applyForNow do.
     */
    fun tableForAppId(appId: String): CheatTable? {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = try {
            ContainerUtils.extractGameIdFromContainerId(appId).toString()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Could not extract gameId from appId '$appId' - skipping cheat table lookup")
            return null
        }

        val lookupKey = when (source) {
            // EPIC auto-generates a numeric id; use catalogId for registry lookup,
            // matching the GameFixesRegistry.applyFor / applyForNow pattern exactly.
            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId.toInt()) ?: return null
                game.catalogId
            }
            else -> gameId
        }

        return tablesProvider()[source to lookupKey]
    }

    /**
     * Returns true if a cheat table is registered for the given compound appId.
     * Equivalent to tableForAppId(appId) != null but reads more clearly at call sites.
     */
    fun hasTableFor(appId: String): Boolean = tableForAppId(appId) != null

    // -------------------------------------------------------------------------
    // Test hook - not for production use
    // -------------------------------------------------------------------------

    internal fun setTablesProviderForTests(provider: (() -> Map<Pair<GameSource, String>, CheatTable>)?) {
        tablesProvider = provider ?: { buildAllTables() }
    }
}
