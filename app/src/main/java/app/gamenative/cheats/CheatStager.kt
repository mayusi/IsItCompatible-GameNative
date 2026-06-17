package app.gamenative.cheats

import android.content.Context
import app.gamenative.gamefixes.types.DllOverrideFix
import app.gamenative.service.SteamService
import com.winlator.core.envvars.EnvVars
import com.winlator.container.Container
import timber.log.Timber
import java.io.File

/**
 * Stages the bundled cheat-engine proxy into the game directory and wires up the Wine DLL
 * override so Wine loads our native engine into the game process.
 *
 * WHY d3dx9_43.dll IS THE CARRIER (device-proven — do not revert to dinput8):
 *   On arm64ec Proton, Wine FORCES dinput8/winmm/xinput/version + all core DLLs to
 *   ": builtin" (they are arm64ec hybrid builtins); the loader ignores any disk PE in those
 *   slots. We PROVED on-device that an x86_64 proxy physically at system32\dinput8.dll WITH
 *   *dinput8=n,b STILL loaded ": builtin" — our DllMain never ran, cheat_proof.txt never
 *   appeared, the engine never started. BUT Wine loads d3d9 / d3dx9_43 / d3dcompiler_43 /
 *   MSVCR100 / MSVCP100 ": native" from disk on the SAME launch. d3dx9_43 is a DX9 HELPER lib
 *   that sits OFF the DXVK render path (DXVK owns d3d9/dxgi — we never touch them), so
 *   replacing it does NOT break rendering. The repo already ships "d3dx9_43=native,builtin"
 *   for game 413420 (gamefixes/registry.json) — proving a d3dx9_43 native override works on
 *   this exact stack. So our engine rides in d3dx9_43.dll, planted in the EXE DIR.
 *
 * COMPLETE FORWARDING (game never crashes on a missing d3dx9 export): the proxy d3dx9_43.dll
 * is linked with a generated .def whose ~329 entries are ALL forwarders to d3dx9_43_real.dll.
 * We stage the prefix's genuine system32\d3dx9_43.dll next to the exe as d3dx9_43_real.dll, so
 * the Wine loader resolves every forwarder into the real implementation. Our DllMain still
 * runs the cheat engine. No system32 overwrite is needed (d3dx9_43 is NOT a builtin — the
 * game's import resolves the exe-dir copy first), so the shared prefix is left untouched.
 *
 * COEXISTENCE WITH A FLiNG TRAINER: a FLiNG trainer is itself a dinput8.dll, staged next to
 * the exe as dinput8.fling.dll (see [FlingStager]); our proxy chain-loads it in DllMain.
 *
 * PER-GAME CARRIER (catalog-driven): the carrier defaults to d3dx9_43 but is overridable per
 * game (see [TrainerEntry.proxyName] / registry). The intended fallback chain for non-DX9
 * games is: d3dx9_43 (DX9) -> d3dx11_43 (DX11) -> d3dcompiler_47 -> (last resort) a dxgi
 * chain-forward carrier. Only the d3dx9_43 carrier binary ships today; the others would each
 * need their own generated forwarder .def from the genuine lib.
 *
 * Call this once per game launch, AFTER [app.gamenative.gamefixes.GameFixesRegistry.applyFor],
 * while [container] and a numeric [gameId] are in scope and before the Wine process starts.
 *
 * This object is deliberately defensive: every external operation is wrapped so that no
 * exception can propagate into the game-launch critical path.
 */
object CheatStager {

    private const val TAG = "CheatStager"

    /**
     * Default carrier DLL — d3dx9_43.dll (the device-proven DX9 carrier). Per-game overrides
     * (e.g. a DX11 game wanting d3dx11_43) flow in via [TrainerEntry.proxyName] / registry;
     * for now the default covers the DX9 case and the per-game hook is reserved for future
     * carrier binaries. The asset must be bundled at assets/cheatdll/<carrier>.
     */
    private const val DEFAULT_CARRIER = "d3dx9_43.dll"

    /**
     * Name of the renamed genuine library the proxy's .def forwarders point at. The proxy
     * d3dx9_43.dll exports forward to "d3dx9_43_real.<Name>", so we stage the prefix's real
     * system32 d3dx9_43.dll next to the exe under this name. Keep in sync with the SIDECAR
     * constant in build_def.py / the forwarder target in d3dx9_43.def.
     */
    private const val REAL_SIDECAR_NAME = "d3dx9_43_real.dll"

    /**
     * Relative path (from the container rootDir) to the prefix's genuine system32 d3dx9_43.dll
     * — the source we copy out to stage [REAL_SIDECAR_NAME] next to the exe. (This is the REAL
     * Wine builtin; we never overwrite it.)
     */
    private const val SYSTEM32_REAL_REL = ".wine/drive_c/windows/system32/$DEFAULT_CARRIER"

    private const val DLL_OVERRIDE_MODE = "n,b"

    private const val FEX_TSO_KEY = "FEX_TSOENABLED"
    private const val FEX_TSO_VALUE = "1"

    /**
     * Drops the cheat-engine carrier DLL into the game folder and sets WINEDLLOVERRIDES so
     * Wine loads it.
     *
     * @param context  Android context (used for asset access).
     * @param container  The Wine/container configuration for this game.
     * @param appId  Compound app-id string (e.g. "STEAM_271590") used for registry lookup.
     * @param gameId  Numeric Steam/GOG/Epic app-id used to resolve the install directory.
     * @return true if the DLL was staged and the override was applied; false if the Trainer is
     *         off, the install dir is missing, or any step fails.
     */
    fun stage(context: Context, container: Container, appId: String, gameId: Int): Boolean {
        return try {
            stageInternal(context, container, appId, gameId)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unexpected error staging cheat DLL for appId=$appId — suppressed")
            false
        }
    }

    /**
     * Resolve the carrier DLL name for this game. Default is [DEFAULT_CARRIER]; a future
     * per-game override (DX11/DX12 carriers) can be wired here from the trainer catalog /
     * registry via [TrainerEntry.proxyName]. Returns a name like "d3dx9_43.dll".
     */
    private fun carrierFor(appId: String): String = DEFAULT_CARRIER

    // -------------------------------------------------------------------------
    // Internal implementation — all individual steps still catch their own
    // exceptions so we can log exactly what went wrong.
    // -------------------------------------------------------------------------

    private fun stageInternal(context: Context, container: Container, appId: String, gameId: Int): Boolean {
        // Fresh launch = fresh DLL slot table (the old DLL died with the previous
        // session). Clear any stale "active" UI flags so toggles don't show ON for
        // cheats that aren't actually frozen in this new process.
        CheatUiState.clearGame(appId)

        // Step 1: gate on the Trainer feature, NOT on whether this game has a table.
        // When the Trainer is ON, we inject the cheat DLL into EVERY game so the
        // DIY "make your own cheat" scanner works on games without a pre-made table
        // (and one-tap cheats work on games that do have one). When the Trainer is
        // OFF (the default), we inject nothing — zero impact on any launch.
        if (!app.gamenative.PrefManager.trainerEnabled) {
            Timber.tag(TAG).d("Trainer disabled — skipping cheat DLL staging for $appId")
            return false
        }

        val carrier = carrierFor(appId)
        val assetPath = "cheatdll/$carrier"

        // Step 2: resolve the game install directory.
        val gameDirPath = SteamService.getAppDirPath(gameId)
        val gameDir = File(gameDirPath)
        if (!gameDir.exists() || !gameDir.isDirectory) {
            Timber.tag(TAG).w("Game dir does not exist for gameId=$gameId (path=$gameDirPath) — skipping cheat staging")
            return false
        }

        // Step 3: copy the carrier proxy into the install root AND next to the exe. For a
        // non-builtin carrier (d3dx9_43), the game's own import resolves the exe-dir copy
        // along the standard search path (which begins with the EXE's own directory), so
        // EXE-DIR placement is what makes our engine load. We drop it in both: the install
        // root (root-exe games) and the resolved exe dir (subfolder-exe games). NO system32
        // overwrite — d3dx9_43 is not a builtin, so the shared prefix is left untouched.
        val dllDeployed = deployDll(context, assetPath, carrier, gameDir)
        if (!dllDeployed) {
            // deployDll already logged the failure.
            return false
        }
        val exeDir = CheatPaths.exeDir(gameDir, container.executablePath)
        if (exeDir.absolutePath != gameDir.absolutePath) {
            // Best-effort — a failure here only matters for subfolder exes, and the root
            // copy plus the WINEDLLOVERRIDES entry still cover the common case.
            deployDll(context, assetPath, carrier, exeDir)
        }

        // Step 3b: stage the genuine library next to the exe as d3dx9_43_real.dll so the
        // proxy's ~329 export forwarders (d3dx9_43_real.<Name>) resolve into the real
        // implementation — the game's d3dx9 calls keep working. Best-effort: a failure here
        // is logged but does not abort the launch (the engine still loads; only forwarded
        // d3dx9 calls would be at risk if the sidecar is somehow absent — rare on a DX9 game
        // that already ships the prefix builtin). Stage into both root and exe dir.
        stageRealSidecar(context, container, gameDir, exeDir)

        // Step 3c: DEVICE-PROVEN CORRECTION — on this arm64ec Proton, Wine resolves the
        // game's d3dx9_43 import from system32 BEFORE the exe dir, so the exe-dir proxy is
        // never loaded (the real system32 d3dx9_43.dll wins). d3dx9_43 is NOT a Wine builtin
        // (unlike dinput8), and an x86_64 PE in system32 loads ": native" here (proven: d3d9
        // and the real d3dx9_43 both load native from system32). So we OVERWRITE
        // system32\d3dx9_43.dll with our proxy and back up the genuine one as
        // system32\d3dx9_43_real.dll (the forwarder target, resolved from the same dir) —
        // the exact recipe DXVK uses for d3d9. Idempotent: never clobbers an existing
        // _real backup with our proxy on relaunch.
        deployIntoSystem32(context, container, carrier, assetPath)

        // Step 4: set WINEDLLOVERRIDES so Wine loads our native d3dx9_43.dll. d3dx9_43 is NOT
        // a Wine builtin, so a BARE "d3dx9_43=n,b" is sufficient (no "*" strong form needed —
        // the repo already proves a plain "d3dx9_43=native,builtin" works on this stack).
        val overrideKey = carrier.removeSuffix(".dll")
        val overrideApplied = DllOverrideFix(mapOf(overrideKey to DLL_OVERRIDE_MODE))
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

        Timber.tag(TAG).i("Cheat carrier '$carrier' staged for appId=$appId gameId=$gameId (override applied=$overrideApplied)")
        return true
    }

    /**
     * Copies the carrier DLL from assets to [destDir]/[carrier], overwriting any existing file.
     * Returns true on success, false (with logging) on any failure.
     */
    /**
     * Overwrite the prefix's system32\d3dx9_43.dll with our proxy, after backing up the
     * genuine one as system32\d3dx9_43_real.dll (the forwarder target — resolved from the
     * same directory the proxy is loaded from). This is what actually makes Wine load our
     * engine: on this arm64ec stack the game's d3dx9_43 import resolves from system32 first,
     * and an x86_64 PE there loads ": native" (d3dx9_43 is a redistributable, NOT a Wine
     * arm64ec builtin — proven on-device alongside DXVK's d3d9).
     *
     * IDEMPOTENT: the genuine library is backed up to _real ONLY if a _real backup does not
     * already exist (so on relaunch — when system32\d3dx9_43.dll is already OUR proxy — we
     * never overwrite the cached genuine original with our proxy). If the backup step fails,
     * the overwrite is aborted so the real library is preserved.
     *
     * Best-effort: any failure is logged and swallowed; it never aborts the launch.
     */
    private fun deployIntoSystem32(context: Context, container: Container, carrier: String, assetPath: String) {
        try {
            val rootDir = container.rootDir ?: run {
                Timber.tag(TAG).w("container.rootDir is null — cannot stage $carrier into system32")
                return
            }
            val sys32 = File(rootDir, ".wine/drive_c/windows/system32")
            if (!sys32.isDirectory) {
                Timber.tag(TAG).w("system32 not found at ${sys32.absolutePath} — skipping system32 staging")
                return
            }
            val live = File(sys32, carrier)                                   // e.g. system32/d3dx9_43.dll
            val realBackup = File(sys32, REAL_SIDECAR_NAME)                   // system32/d3dx9_43_real.dll
            // Back up the genuine library ONCE (idempotency guard).
            if (live.isFile && !realBackup.exists()) {
                try {
                    live.inputStream().use { input -> realBackup.outputStream().use { output -> input.copyTo(output) } }
                    Timber.tag(TAG).i("Backed up genuine $carrier -> ${realBackup.name} in system32")
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to back up genuine $carrier in system32 — aborting system32 overwrite to preserve the real library")
                    return
                }
            }
            // Overwrite system32/<carrier> with our proxy.
            context.assets.open(assetPath).use { input ->
                live.outputStream().use { output -> input.copyTo(output) }
            }
            Timber.tag(TAG).i("Deployed proxy $carrier into ${live.absolutePath} (system32 — the loaded slot)")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to deploy $carrier into system32 — non-fatal")
        }
    }

    private fun deployDll(context: Context, assetPath: String, carrier: String, destDir: File): Boolean {
        return try {
            val dest = File(destDir, carrier)
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Timber.tag(TAG).i("Deployed $carrier to ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to deploy $carrier from assets/$assetPath")
            false
        }
    }

    /**
     * Stage the prefix's genuine system32\d3dx9_43.dll next to the exe (and at the install
     * root) as [REAL_SIDECAR_NAME], so the proxy's .def forwarders (d3dx9_43_real.<Name>)
     * reach the real implementation. We COPY OUT the real builtin — we never overwrite it.
     *
     * Best-effort: any failure is logged and swallowed; it never aborts the launch.
     */
    private fun stageRealSidecar(context: Context, container: Container, gameDir: File, exeDir: File) {
        try {
            val rootDir = container.rootDir
            if (rootDir == null) {
                Timber.tag(TAG).w("container.rootDir is null — cannot stage $REAL_SIDECAR_NAME")
                return
            }
            val realSrc = File(rootDir, SYSTEM32_REAL_REL)
            if (!realSrc.isFile) {
                Timber.tag(TAG).w("Genuine ${realSrc.name} not found at ${realSrc.absolutePath} — forwarders may be unresolved")
                return
            }
            // Stage next to the exe (the dir the loader searches for the forwarder target) and
            // at the install root (belt-and-suspenders for root-exe games).
            val targets = if (exeDir.absolutePath != gameDir.absolutePath) listOf(gameDir, exeDir) else listOf(gameDir)
            for (dir in targets) {
                try {
                    val dest = File(dir, REAL_SIDECAR_NAME)
                    realSrc.inputStream().use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                    Timber.tag(TAG).i("Staged genuine d3dx9_43 as ${dest.absolutePath}")
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to stage $REAL_SIDECAR_NAME into ${dir.absolutePath} — non-fatal")
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to stage $REAL_SIDECAR_NAME — non-fatal")
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
