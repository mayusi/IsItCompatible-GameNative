package app.gamenative.cheats

import android.content.Context
import app.gamenative.gamefixes.types.DllOverrideFix
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.core.envvars.EnvVars
import timber.log.Timber
import java.io.File

/**
 * Stages the bundled cheat DLL into the game directory and wires up the Wine DLL override
 * so that Wine loads our dinput8.dll (native) before the built-in.
 *
 * Call this once per game launch, AFTER [app.gamenative.gamefixes.GameFixesRegistry.applyFor],
 * while [container] and a numeric [gameId] are in scope and before the Wine process starts.
 *
 * This object is deliberately defensive: every external operation is wrapped so that no
 * exception can propagate into the game-launch critical path.
 */
object CheatStager {

    private const val TAG = "CheatStager"
    private const val ASSET_PATH = "cheatdll/dinput8.dll"
    private const val DLL_NAME = "dinput8.dll"
    private const val DLL_OVERRIDE_MODE = "n,b"
    private const val FEX_TSO_KEY = "FEX_TSOENABLED"
    private const val FEX_TSO_VALUE = "1"

    /**
     * Drops the cheat DLL into the game folder and sets WINEDLLOVERRIDES so Wine loads it.
     *
     * @param context  Android context (used for asset access).
     * @param container  The Wine/container configuration for this game.
     * @param appId  Compound app-id string (e.g. "STEAM_271590") used for registry lookup.
     * @param gameId  Numeric Steam/GOG/Epic app-id used to resolve the install directory.
     * @return true if the DLL was staged and the override was applied; false if this game
     *         has no cheat table, the install dir is missing, or any step fails.
     */
    fun stage(context: Context, container: Container, appId: String, gameId: Int): Boolean {
        return try {
            stageInternal(context, container, appId, gameId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unexpected error staging cheat DLL for appId=$appId — suppressed")
            false
        }
    }

    // -------------------------------------------------------------------------
    // Internal implementation — all individual steps still catch their own
    // exceptions so we can log exactly what went wrong.
    // -------------------------------------------------------------------------

    private fun stageInternal(context: Context, container: Container, appId: String, gameId: Int): Boolean {
        // Fresh launch = fresh DLL slot table (the old DLL died with the previous
        // session). Clear any stale "active" UI flags so toggles don't show ON for
        // cheats that aren't actually frozen in this new process.
        CheatUiState.clearGame(appId)

        // Step 1: bail out immediately if there is no cheat table for this game.
        if (!CheatTableRegistry.hasTableFor(appId)) {
            Timber.tag(TAG).d("No cheat table for $appId — skipping staging")
            return false
        }

        // Step 2: resolve the game install directory.
        val gameDirPath = SteamService.getAppDirPath(gameId)
        val gameDir = File(gameDirPath)
        if (!gameDir.exists() || !gameDir.isDirectory) {
            Timber.tag(TAG).w("Game dir does not exist for gameId=$gameId (path=$gameDirPath) — skipping cheat staging")
            return false
        }

        // Step 3: copy the bundled DLL asset into the game directory.
        val dllDeployed = deployDll(context, gameDir)
        if (!dllDeployed) {
            // deployDll already logged the failure.
            return false
        }

        // Step 4: set WINEDLLOVERRIDES so Wine loads our native dinput8 first.
        val overrideApplied = DllOverrideFix(mapOf(DLL_NAME.removeSuffix(".dll") to DLL_OVERRIDE_MODE))
            .apply(
                context = context,
                gameId = gameId.toString(),
                installPath = gameDirPath,
                installPathWindows = "",   // Not required by DllOverrideFix; it only mutates envVars
                container = container,
            )
        if (!overrideApplied) {
            Timber.tag(TAG).w("DllOverrideFix.apply() returned false for gameId=$gameId")
            // Not fatal — the DLL is already in place; Wine might still pick it up if
            // a WINEDLLOVERRIDES entry was set by another fix.
        }

        // Step 5: optionally enable FEX TSO mode for better memory-write visibility.
        applyFexTso(container)

        Timber.tag(TAG).i("Cheat DLL staged for appId=$appId gameId=$gameId (override applied=$overrideApplied)")
        return true
    }

    /**
     * Copies the DLL from assets to [gameDir]/dinput8.dll, overwriting any existing file.
     * Returns true on success, false (with logging) on any failure.
     */
    private fun deployDll(context: Context, gameDir: File): Boolean {
        return try {
            val dest = File(gameDir, DLL_NAME)
            context.assets.open(ASSET_PATH).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Timber.tag(TAG).i("Deployed $DLL_NAME to ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to deploy $DLL_NAME from assets/$ASSET_PATH")
            false
        }
    }

    /**
     * Adds FEX_TSOENABLED=1 to [container.envVars] if the key is not already present.
     * Persists the change via [Container.saveData]. Any failure is silently logged.
     */
    private fun applyFexTso(container: Container) {
        try {
            val envVars = EnvVars(container.envVars)
            if (!envVars.has(FEX_TSO_KEY)) {
                envVars.put(FEX_TSO_KEY, FEX_TSO_VALUE)
                container.envVars = envVars.toString()
                container.saveData()
                Timber.tag(TAG).d("Set $FEX_TSO_KEY=$FEX_TSO_VALUE in container env")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not set $FEX_TSO_KEY — non-fatal")
        }
    }
}
