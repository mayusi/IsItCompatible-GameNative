package app.gamenative.gamefixes

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.ContainerUtils
import app.gamenative.service.gog.GOGConstants
import app.gamenative.service.gog.GOGService
import app.gamenative.service.SteamService
import app.gamenative.service.epic.EpicService
import com.winlator.container.Container
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object GameFixesRegistry {
    private const val GAME_DRIVE_LETTER = "A"

    private val fixes: Map<Pair<GameSource, String>, GameFix> = listOf(
        GOG_Fix_1129934535,
        GOG_Fix_1141086411,
        GOG_Fix_1177610018,
        GOG_Fix_1453375253,
        GOG_Fix_1454315831,
        GOG_Fix_1454587428,
        GOG_Fix_1458058109,
        GOG_Fix_1589319779,
        GOG_Fix_1635627436,
        GOG_Fix_1787707874,
        GOG_Fix_1808582759,
        GOG_Fix_2147483047,
        STEAM_Fix_105600,
        STEAM_Fix_208650,
        STEAM_Fix_268910,
        STEAM_Fix_271590,
        STEAM_Fix_292030,
        STEAM_Fix_374320,
        STEAM_Fix_400,
        STEAM_Fix_22300,
        STEAM_Fix_22330,
        STEAM_Fix_22370,
        STEAM_Fix_22380,
        STEAM_Fix_22490,
        STEAM_Fix_413150,
        STEAM_Fix_413420,
        STEAM_Fix_489830,
        STEAM_Fix_524220,
        STEAM_Fix_582010,
        STEAM_Fix_601150,
        STEAM_Fix_631510,
        STEAM_Fix_752580,
        STEAM_Fix_1091500,
        STEAM_Fix_1145360,
        STEAM_Fix_1174180,
        STEAM_Fix_1245620,
        STEAM_Fix_1593500,
        STEAM_Fix_1637320,
        STEAM_Fix_2868840,
        STEAM_Fix_3373660,
        STEAM_Fix_367520,
        STEAM_Fix_990080,
        EPIC_Fix_b1b4e0b67a044575820cb5e63028dcae,
        EPIC_Fix_dabb52e328834da7bbe99691e374cb84,
        EPIC_Fix_e345fdb9186645a48d30c3f85a8951dc,
        EPIC_Fix_59a0c86d02da42e8ba6444cb171e61bf,
        EPIC_Fix_864c7bc2c2394f7dbd1b534aa068ff56,
    ).associateBy { it.gameSource to it.gameId }

    /**
     * Merged map of compiled-in + JSON-loaded fixes.
     * Compiled-in fixes take priority: the JSON set only fills gaps for game IDs not
     * already covered by the compiled set, so there is zero regression for existing fixes.
     */
    private val allFixes: Map<Pair<GameSource, String>, GameFix>
        get() {
            val jsonFixes = JsonGameFixLoader.loadedFixes
            // Compiled-in wins: put compiled-in after JSON so it overwrites on collision.
            return jsonFixes + fixes
        }

    private var fixesProvider: () -> Map<Pair<GameSource, String>, GameFix> = { allFixes }

    /**
     * Initialises the JSON fix loader. Call once from Application.onCreate()
     * (or lazily on first launch). Safe to call multiple times.
     */
    fun init(context: Context) {
        JsonGameFixLoader.init(context)
        GameFixesSyncWorker.scheduleIfNeeded(context)
    }

    /**
     * Returns true if a fix is registered for the given (gameSource, gameId) pair.
     * The gameId must be the canonical ID used as the registry key
     * (for Epic this is the catalogId, not the numeric appId).
     */
    fun hasFixFor(gameSource: GameSource, gameId: String): Boolean {
        return fixesProvider()[gameSource to gameId] != null
    }

    /**
     * Applies the fix for the given appId immediately.
     * This is the same logic as applyFor() but returns the Boolean result
     * so callers (e.g. a Quick Menu "re-apply" action) can check success.
     *
     * Returns null if no fix is registered for this game.
     * Returns true if the fix was applied successfully, false on partial failure.
     */
    fun applyForNow(context: Context, appId: String, container: Container): Boolean? {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return null
        val catalogId = when (source) {
            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId.toInt()) ?: return null
                game.catalogId
            }
            else -> gameId
        }
        val fix = fixesProvider()[source to catalogId] ?: return null
        val (installPath, installPathWindows) = resolvePaths(context, source, gameId) ?: return null
        return fix.apply(context, catalogId, installPath, installPathWindows, container)
    }

    fun applyFor(context: Context, appId: String, container: Container) {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return
        val catalogId = when (source) {
            // EPIC auto-generates the id. so we need the catalog id instead.
            GameSource.EPIC -> {
                val game = EpicService.getEpicGameOf(gameId.toInt()) ?: return
                game.catalogId
            }
            else -> gameId
        }
        Timber.i("GameFixesRegistry: Applying fixes for game: $source $catalogId if available")
        val fix = fixesProvider()[source to catalogId] ?: return
        val (installPath, installPathWindows) = resolvePaths(context, source, gameId) ?: return
        val succeeded = fix.apply(context, catalogId, installPath, installPathWindows, container)
        if (succeeded) {
            val gameName = resolveGameDisplayName(source, gameId, catalogId)
            Timber.i("GameFixesRegistry: Fix applied successfully for $gameName ($source $catalogId)")
            SnackbarManager.show("Applied known fix for $gameName")
        } else {
            Timber.w("GameFixesRegistry: Fix for $source $catalogId returned false (partial failure)")
        }
    }

    private fun resolveGameDisplayName(source: GameSource, gameId: String, catalogId: String): String {
        return when (source) {
            GameSource.STEAM -> SteamService.getAppInfoOf(gameId.toIntOrNull() ?: 0)?.name ?: catalogId
            GameSource.GOG -> runBlocking(Dispatchers.IO) {
                GOGService.getGOGGameOf(gameId)?.title
            } ?: catalogId
            GameSource.EPIC -> EpicService.getEpicGameOf(gameId.toIntOrNull() ?: 0)?.title ?: catalogId
            else -> catalogId
        }
    }

    private fun resolvePaths(context: Context, source: GameSource, gameId: String): Pair<String, String>? {
        return when (source) {
            GameSource.GOG -> {
                val game = runBlocking(Dispatchers.IO) { GOGService.getGOGGameOf(gameId) } ?: return null
                if (!game.isInstalled) return null
                val path = game.installPath.ifEmpty { GOGConstants.getGameInstallPath(game.title) }
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.STEAM -> {
                val path = SteamService.getAppDirPath(gameId.toInt())
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            GameSource.EPIC -> {
                val path = EpicService.getInstallPath(gameId.toInt()) ?: return null
                if (path.isEmpty() || !File(path).exists()) return null
                path to "$GAME_DRIVE_LETTER:\\"
            }
            else -> null
        }
    }

    /**
     * Resolves the host-filesystem install path for [appId] without requiring a full
     * GameFix lookup.  Used by the auto-tuner's fix ladder to obtain the install path
     * for VideoFileAutoFixer without going through the full applyForNow flow.
     *
     * Returns null if the game is not installed, the path is empty, or the source
     * is not supported.
     */
    fun resolveInstallPathFor(context: Context, appId: String): String? {
        val source = ContainerUtils.extractGameSourceFromContainerId(appId)
        val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return null
        return try {
            resolvePaths(context, source, gameId)?.first
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Test-only hook to override the game-fixes provider.
     * Not intended for production code paths.
     *
     * @param provider Fixes provider for tests; pass null to restore the default provider.
     */
    internal fun setFixesProviderForTests(provider: (() -> Map<Pair<GameSource, String>, GameFix>)?) {
        fixesProvider = provider ?: { allFixes }
    }
}
