package app.gamenative.autotuner

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.gamefixes.types.DllOverrideFix
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.SteamDrmPreflight
import app.gamenative.utils.SteamUtils
import app.gamenative.utils.VideoFileAutoFixer
import app.gamenative.utils.WineLogClassifier
import app.gamenative.service.SteamService
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File

/**
 * FixLadder — ordered rungs of automated fixes the auto-tuner tries when a trial
 * fails with CRASHED or BLACK_SCREEN.
 *
 * Design constraints:
 * - DLL-override fixes are applied by merging into the envVars field of the trial's
 *   ContainerData (returned as a new ContainerData copy, NOT written to the container
 *   on disk). The engine passes this patched ContainerData into the next trial via
 *   [IntentLaunchManager.applyAutoConfigOverride], so the user's persistent container
 *   is never mutated during fix-retry.
 * - WMV renames touch the filesystem but are idempotent / reversible (.bak suffix).
 * - DXVK_ASYNC is applied as an env-var override in the same manner as DLL overrides.
 *
 * [FailureClass] mirrors the patterns already in [CrashClassifier] but is defined
 * independently so FixLadder can be used from the engine without importing the
 * snackbar-oriented CrashClassifier.
 */
object FixLadder {
    private const val TAG = "FixLadder"

    // -------------------------------------------------------------------------
    // Failure classification
    // -------------------------------------------------------------------------

    enum class FailureClass {
        WMV_CODEC,
        D3D_COMPILER,
        D3D12_UNSUPPORTED,
        D3D9_RENDER, // old DirectX 9 game (e.g. DMC3) failing under dxvk/D3D9 — needs wined3d / d3dx9
        BOX64_DYNAREC, // Box64 JIT/dynarec fault (old-game CPU-translation crash)
        STEAM_INIT_FAILED,
        STEAM_OVERLAY,
        EOS_CRASH,
        MSVC_MISSING,
        SEH_ANTICHEAT,
        BLACK_SCREEN_NOFIX,
        WINE_LAUNCH_FAILED, // Android linker rejected Wine/wineserver — required .so not found (stale LD_PRELOAD path)
        UNKNOWN_CRASH,
    }

    /**
     * Classify [logLines] into a [FailureClass] by pattern-matching Wine debug output.
     * Delegates to [WineLogClassifier] — the single source of truth for pattern→class mapping
     * shared with [app.gamenative.utils.CrashClassifier]. Patterns cannot diverge.
     */
    fun classifyFailure(logLines: List<String>): FailureClass = WineLogClassifier.classify(logLines)

    // -------------------------------------------------------------------------
    // Fix rung application
    // -------------------------------------------------------------------------

    /**
     * A single fix candidate in the ladder.
     *
     * @param id          Stable string id (used to track tried rungs across retries).
     * @param description Human-readable description for progress events.
     * @param appliesToClasses Which [FailureClass] values this rung can address.
     * @param condition   Optional predicate; if false the rung is skipped.
     * @param apply       Applies the fix. Returns an [AppliedFix] on success, null if not applicable.
     *                    The engine passes the current [ContainerData] + context + appId.
     *                    DLL overrides are returned as a modified ContainerData via [FixResult].
     */
    data class Rung(
        val id: String,
        val description: String,
        val appliesToClasses: Set<FailureClass>,
        val condition: (context: Context, appId: String, baseConfig: ContainerData, failureClass: FailureClass) -> Boolean = { _, _, _, _ -> true },
        val apply: (context: Context, appId: String, baseConfig: ContainerData, installPath: String, failureClass: FailureClass) -> FixResult?,
    )

    /**
     * Result of applying a fix rung.
     * [appliedFix] describes what was done.
     * [patchedConfig] is the new ContainerData to use for the retry (may be same as input if only filesystem changes were made).
     */
    data class FixResult(
        val appliedFix: AppliedFix,
        val patchedConfig: ContainerData,
    )

    /**
     * The ordered ladder of fix rungs. The engine walks this in order, picking the first
     * un-tried rung that applies to the current [FailureClass].
     */
    val LADDER: List<Rung> = listOf(

        // Rung 0 — Repair Wine runtime native libraries (WINE_LAUNCH_FAILED).
        //
        // When the Android linker rejects Wine/wineserver because libevshim.so (or another
        // preloaded native lib) is not found at its baked-in LD_PRELOAD path, the game
        // exits in ~7ms before any Wine log is written.  This rung re-copies libevshim.so
        // from the CURRENT (valid) nativeLibraryDir into the stable imagefs usr/lib dir —
        // the same repair BionicProgramLauncherComponent.java does on every normal launch.
        // After this rung runs, the next launch attempt will pick up the stable path and
        // succeed.  Null-safe: any failure is swallowed so the rung degrades gracefully.
        Rung(
            id = "repair_wine_runtime",
            description = "Repair Wine runtime — re-copy native preload library to stable path",
            appliesToClasses = setOf(FailureClass.WINE_LAUNCH_FAILED),
            apply = { context, appId, baseConfig, _, _ ->
                try {
                    val imageFs = com.winlator.xenvironment.ImageFs.find(context)
                    val stableLibDir = imageFs?.getLibDir()
                    val nativeLibDir = context.applicationInfo?.nativeLibraryDir
                    if (stableLibDir != null && nativeLibDir != null) {
                        val src  = File(nativeLibDir, "libevshim.so")
                        val dest = File(stableLibDir, "libevshim.so")
                        if (src.exists()) {
                            val needsCopy = !dest.exists()
                                || dest.length() != src.length()
                                || dest.lastModified() < src.lastModified()
                            if (needsCopy) {
                                stableLibDir.mkdirs()
                                src.inputStream().use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output) }
                                }
                                Timber.tag(TAG).i("repair_wine_runtime: re-copied libevshim.so for $appId")
                            }
                            FixResult(
                                appliedFix = AppliedFix.WineRuntimeRepaired,
                                patchedConfig = baseConfig,
                            )
                        } else null
                    } else null
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung repair_wine_runtime failed for $appId")
                    null
                }
            },
        ),

        // Rung 1 — Per-game registry fix (compiled-in or JSON)
        Rung(
            id = "game_registry_fix",
            description = "Apply game-specific fix",
            appliesToClasses = FailureClass.entries.toSet(), // applies to all
            condition = { context, appId, _, _ ->
                val source = ContainerUtils.extractGameSourceFromContainerId(appId)
                val gameId = ContainerUtils.extractGameIdFromContainerId(appId)?.toString() ?: return@Rung false
                GameFixesRegistry.hasFixFor(source, gameId)
            },
            apply = { context, appId, baseConfig, _, _ ->
                try {
                    val container = ContainerUtils.getContainer(context, appId)
                    val result = GameFixesRegistry.applyForNow(context, appId, container)
                    if (result == true) {
                        FixResult(
                            appliedFix = AppliedFix.GameRegistryFix(appId),
                            patchedConfig = baseConfig,
                        )
                    } else null
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung game_registry_fix failed for $appId")
                    null
                }
            },
        ),

        // Rung 2 — WMV rename for WMV_CODEC and BLACK_SCREEN (intro video may be causing black screen)
        Rung(
            id = "wmv_rename",
            description = "Skip intro videos (rename WMV)",
            appliesToClasses = setOf(FailureClass.WMV_CODEC, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            condition = { context, appId, _, _ ->
                val installPath = GameFixesRegistry.resolveInstallPathFor(context, appId)
                !installPath.isNullOrEmpty()
            },
            apply = { context, appId, baseConfig, installPath, _ ->
                try {
                    val path = installPath.ifEmpty {
                        GameFixesRegistry.resolveInstallPathFor(context, appId) ?: return@Rung null
                    }
                    val renamed = VideoFileAutoFixer.renameIntroVideos(path)
                    if (renamed >= 0) { // 0 is ok — no files to rename is fine
                        FixResult(
                            appliedFix = AppliedFix.WmvRename(renamed),
                            patchedConfig = baseConfig,
                        )
                    } else null
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung wmv_rename failed for $appId")
                    null
                }
            },
        ),

        // Rung 3 — Disable Steam overlay
        Rung(
            id = "steam_overlay_disable",
            description = "Disable Steam overlay",
            appliesToClasses = setOf(FailureClass.STEAM_OVERLAY, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = mergeWineDllOverride(baseConfig, "GameOverlayRenderer64", "n,b")
                    FixResult(
                        appliedFix = AppliedFix.SteamOverlayDisabled,
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung steam_overlay_disable failed for $appId")
                    null
                }
            },
        ),

        // Rung 4 — d3dcompiler_47 override
        Rung(
            id = "d3dcompiler_override",
            description = "Add d3dcompiler_47 override",
            appliesToClasses = setOf(FailureClass.D3D_COMPILER, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = mergeWineDllOverride(baseConfig, "d3dcompiler_47", "n,b")
                    FixResult(
                        appliedFix = AppliedFix.DllOverride("d3dcompiler_47", "n,b"),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung d3dcompiler_override failed for $appId")
                    null
                }
            },
        ),

        // Rung 5 — Force the game's DX11 render path when the device's Vulkan driver
        // cannot satisfy DX12 / D3D_FEATURE_LEVEL_12 (e.g. "DX12 isn't supported on
        // your device — try without DX12" on UE4 games like Lies of P).
        //
        // CORRECT FIX (verified): do NOT switch the wrapper to wined3d — that routes
        // DX11 through OpenGL and is unplayable / crashes for modern UE4 AAA titles.
        // Instead append the game's "-dx11" launch arg, which forces UE4 onto its
        // DX11 renderer while KEEPING the fast, well-supported dxvk (DX11→Vulkan)
        // wrapper. This is exactly the Winlator/ProtonDB community fix. We only
        // append -dx11 when execArgs is empty (don't clobber a user's args) and the
        // container is on a DX12-capable wrapper config.
        Rung(
            id = "dx12_force_dx11",
            description = "Force DX11 render path (-dx11), keep dxvk",
            appliesToClasses = setOf(FailureClass.D3D12_UNSUPPORTED, FailureClass.BLACK_SCREEN_NOFIX),
            condition = { _, _, baseConfig, _ ->
                baseConfig.execArgs.isBlank() && (
                    baseConfig.dxwrapper.contains("vkd3d", ignoreCase = true) ||
                        baseConfig.dxwrapper.equals("dxvk", ignoreCase = true) ||
                        baseConfig.dxwrapperConfig.contains("vkd3d", ignoreCase = true) ||
                        baseConfig.dxwrapperConfig.contains("12_", ignoreCase = true)
                )
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val arg = "-dx11"
                    val patched = baseConfig.copy(execArgs = arg)
                    FixResult(
                        appliedFix = AppliedFix.LaunchArg(arg),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung dx12_force_dx11 failed for $appId")
                    null
                }
            },
        ),

        // Rung 5b — VKD3D_CONFIG workaround for DX12 titles still failing AFTER dx12_force_dx11.
        // Positioned after dx12_force_dx11 so the cleaner -dx11 render-path switch is always
        // tried first (best for UE titles). Only relevant when the container actually runs a
        // vkd3d (DX12→Vulkan) wrapper. no_upload_hvv avoids host-visible-VRAM upload paths that
        // crash on some Adreno drivers; nodxr disables DXR (no ray-tracing support) which is a
        // common black-screen / D3D12-unsupported trigger.
        Rung(
            id = "vkd3d_config_workaround",
            description = "Apply VKD3D_CONFIG workaround (no_upload_hvv, nodxr)",
            appliesToClasses = setOf(FailureClass.D3D12_UNSUPPORTED, FailureClass.BLACK_SCREEN_NOFIX),
            condition = { _, _, baseConfig, _ ->
                baseConfig.dxwrapper.contains("vkd3d", ignoreCase = true)
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val value = "no_upload_hvv,nodxr"
                    val patched = mergeEnvVar(baseConfig, "VKD3D_CONFIG", value)
                    FixResult(
                        appliedFix = AppliedFix.WineEnvVar("VKD3D_CONFIG", value),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung vkd3d_config_workaround failed for $appId")
                    null
                }
            },
        ),

        // Rung 5c — d3dx9 helper DLL overrides for old DirectX 9 games (DMC3-class).
        // Many 2000s D3D9 games bundle or expect the d3dx9 helper libraries. Forcing Wine's
        // built-in d3dx9_43 / d3dx9_36 (the two most common versions) resolves missing-symbol
        // and bad-bundled-DLL crashes. native,builtin lets a present game-bundled copy win but
        // falls back to Wine's. Cheap and safe — only adds overrides, never removes the game's.
        Rung(
            id = "d3dx9_override",
            description = "Add d3dx9 helper DLL overrides (old D3D9 games)",
            appliesToClasses = setOf(FailureClass.D3D9_RENDER, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    var patched = mergeWineDllOverride(baseConfig, "d3dx9_43", "n,b")
                    patched = mergeWineDllOverride(patched, "d3dx9_36", "n,b")
                    FixResult(
                        appliedFix = AppliedFix.DllOverride("d3dx9_43,d3dx9_36", "n,b"),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung d3dx9_override failed for $appId")
                    null
                }
            },
        ),

        // Rung 5d — Switch a D3D9 game off dxvk onto wined3d.
        // dxvk's D3D9 path is strong for most titles but some old fixed-function / SM2-3 games
        // (DMC3, many 2006-era games) render black or crash under it. wined3d (D3D9→OpenGL) is
        // the community fallback for exactly these. We ONLY do this for D3D9_RENDER failures (or
        // a black screen on a dxvk container) and only when currently on dxvk — never touch a
        // DX11/DX12 title's wrapper (that path is handled by dx12_force_dx11 / vkd3d rungs).
        Rung(
            id = "d3d9_wined3d_fallback",
            description = "Use wined3d for this old DirectX 9 game",
            appliesToClasses = setOf(FailureClass.D3D9_RENDER, FailureClass.BLACK_SCREEN_NOFIX),
            condition = { _, _, baseConfig, _ ->
                baseConfig.dxwrapper.equals("dxvk", ignoreCase = true)
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = baseConfig.copy(dxwrapper = "wined3d")
                    FixResult(
                        appliedFix = AppliedFix.DxWrapperSwitch("wined3d"),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung d3d9_wined3d_fallback failed for $appId")
                    null
                }
            },
        ),

        // Rung 5e-pre — BUG 5 FIX: install D3D/vcrun Windows components for old DirectX 9 games.
        //
        // On the RP6 the container was created with ALL wincomponents=0 (direct3d=0, directsound=0,
        // etc.). DMC-era (D3D9, 2006) games and many other old titles require the Wine D3D runtime
        // components to be installed. Without them the game may black-screen or crash immediately.
        //
        // This rung patches the ContainerData.wincomponents string to enable the D3D9-relevant set:
        //   direct3d=1 — core Wine D3D runtime (absolutely required for any D3D game)
        //   directshow=1 — media playback used for cutscenes / intro videos
        //   directsound=1 — audio (many old games crash without this)
        //   vcrun2010=1 — Visual C++ runtime (bundled DLLs often expect this stub)
        //
        // Condition: only fires when AT LEAST ONE of these components is currently disabled (=0).
        // Applies to D3D9_RENDER, BLACK_SCREEN_NOFIX, UNKNOWN_CRASH.
        Rung(
            id = "install_d3d_components",
            description = "Enable D3D/vcrun Windows components for old D3D9 game",
            appliesToClasses = setOf(FailureClass.D3D9_RENDER, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            condition = { _, _, baseConfig, _ ->
                // Only apply when at least one of the target components is off.
                val kv = KeyValueSet(baseConfig.wincomponents.ifEmpty { Container.DEFAULT_WINCOMPONENTS })
                kv.get("direct3d") != "1" ||
                    kv.get("directshow") != "1" ||
                    kv.get("directsound") != "1" ||
                    kv.get("vcrun2010") != "1"
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val kv = KeyValueSet(baseConfig.wincomponents.ifEmpty { Container.DEFAULT_WINCOMPONENTS })
                    val changed = mutableListOf<String>()
                    if (kv.get("direct3d") != "1") { kv.put("direct3d", "1"); changed.add("direct3d") }
                    if (kv.get("directshow") != "1") { kv.put("directshow", "1"); changed.add("directshow") }
                    if (kv.get("directsound") != "1") { kv.put("directsound", "1"); changed.add("directsound") }
                    if (kv.get("vcrun2010") != "1") { kv.put("vcrun2010", "1"); changed.add("vcrun2010") }
                    if (changed.isEmpty()) {
                        // condition guard should prevent this, but be safe
                        return@Rung null
                    }
                    val patched = baseConfig.copy(wincomponents = kv.toString())
                    val changedStr = changed.joinToString(",")
                    Timber.tag(TAG).i("Rung install_d3d_components: enabled [$changedStr] for $appId")
                    FixResult(
                        appliedFix = AppliedFix.WinComponents(changedStr),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung install_d3d_components failed for $appId")
                    null
                }
            },
        ),

        // Rung 5e — Drop Box64 to the COMPATIBILITY preset on a CPU-translation fault or an
        // otherwise-unexplained crash. Old games hit dynarec edge cases the PERFORMANCE/
        // INTERMEDIATE presets miss; COMPATIBILITY trades speed for correctness and is the
        // single most reliable "make it at least boot" lever for stubborn old titles. Only
        // applied when not already on COMPATIBILITY (no point retrying the same preset).
        Rung(
            id = "box64_compat_preset",
            description = "Use Box64 COMPATIBILITY preset",
            appliesToClasses = setOf(FailureClass.BOX64_DYNAREC, FailureClass.UNKNOWN_CRASH, FailureClass.BLACK_SCREEN_NOFIX),
            condition = { _, _, baseConfig, _ ->
                !baseConfig.box64Preset.equals("COMPATIBILITY", ignoreCase = true)
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = baseConfig.copy(box64Preset = "COMPATIBILITY")
                    FixResult(
                        appliedFix = AppliedFix.Box64PresetSwitch("COMPATIBILITY"),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung box64_compat_preset failed for $appId")
                    null
                }
            },
        ),

        // Rung 6 — Disable EOS (was Rung 5)
        Rung(
            id = "eos_disable",
            description = "Disable Epic Online Services",
            appliesToClasses = setOf(FailureClass.EOS_CRASH, FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = mergeWineDllOverride(baseConfig, "EOSSDK-Win64-Shipping", "n,b")
                    FixResult(
                        appliedFix = AppliedFix.EosDisabled,
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung eos_disable failed for $appId")
                    null
                }
            },
        ),

        // Rung 7 — DXVK_ASYNC=1 for BLACK_SCREEN / UNKNOWN when dxwrapper=dxvk (was Rung 6)
        Rung(
            id = "dxvk_async",
            description = "Enable DXVK async shader compilation",
            appliesToClasses = setOf(FailureClass.BLACK_SCREEN_NOFIX, FailureClass.UNKNOWN_CRASH),
            condition = { _, _, baseConfig, _ ->
                baseConfig.dxwrapper.equals("dxvk", ignoreCase = true)
            },
            apply = { _, appId, baseConfig, _, _ ->
                try {
                    val patched = mergeEnvVar(baseConfig, "DXVK_ASYNC", "1")
                    FixResult(
                        appliedFix = AppliedFix.WineEnvVar("DXVK_ASYNC", "1"),
                        patchedConfig = patched,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung dxvk_async failed for $appId")
                    null
                }
            },
        ),

        // Rung 8 — Deploy Goldberg-style Steam API emulation for STEAM_INIT_FAILED crashes.
        //
        // LEGIT BOUNDARY: This rung deploys GameNative's own bundled steam_api replacement
        // (assets/steampipe/steam_api[64].dll) + steam_settings/ + steam_appid.txt. It does
        // NOT strip DRM, does NOT patch executables, does NOT bundle or invoke Steamless.
        //
        // HOW REAL-FOLDER RESOLUTION WORKS:
        //
        //   STEAM source  — extractGameIdFromContainerId returns the real Steam appid (Int).
        //                   SteamService.getAppDirPath(appid) returns the true install directory.
        //                   SteamUtils.replaceSteamApi(context, appId) already uses that same
        //                   path internally, so we can delegate to it directly. ✓
        //
        //   CUSTOM_GAME   — extractGameIdFromContainerId returns a synthetic folder-hash ID, NOT
        //                   a Steam appid. SteamService.getAppDirPath would resolve a wrong Steam-
        //                   library path. The REAL game folder is obtained via
        //                   CustomGameScanner.getFolderPathFromAppId(appId) — this is what the
        //                   container's A: drive is mapped to.
        //                   However, for a custom game we have NO way to infer the correct Steam
        //                   appid — writing the folder-hash as steam_appid.txt would be wrong and
        //                   would break emulation rather than fix it. Therefore, for CUSTOM_GAME
        //                   containers this rung is a SAFE NO-OP: condition() returns false so the
        //                   rung is never attempted. We do not fabricate appids.
        //
        // SUSPEND HANDLING:
        //   The rung apply lambda is NOT suspend. SteamUtils.replaceSteamApi IS suspend.
        //   We call it via runBlocking(Dispatchers.IO) — the same pattern used throughout
        //   ContainerUtils.createNewContainer for other suspend calls inside non-suspend contexts.
        Rung(
            id = "steam_emulation_enable",
            description = "Enable Steam API emulation",
            appliesToClasses = setOf(FailureClass.STEAM_INIT_FAILED, FailureClass.BLACK_SCREEN_NOFIX),
            condition = { context, appId, baseConfig, _ ->
                // Only attempt when not already in real-Steam mode — emulation is incompatible
                // with launchRealSteam / launchBionicSteam (those have their own Steam session).
                if (baseConfig.launchRealSteam || baseConfig.launchBionicSteam) return@Rung false

                // CUSTOM_GAME containers: we cannot reliably determine the real Steam appid.
                // Attempting emulation without the correct appid would produce a broken
                // steam_appid.txt (folder-hash, not an actual Steam appid). Safe no-op.
                val source = ContainerUtils.extractGameSourceFromContainerId(appId)
                if (source != GameSource.STEAM) return@Rung false

                // Resolve the REAL game install folder for the preflight scan.
                val gameId = runCatching { ContainerUtils.extractGameIdFromContainerId(appId) }.getOrNull()
                    ?: return@Rung false
                val realInstallDir = runCatching { SteamService.getAppDirPath(gameId) }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@Rung false

                // Run the preflight detector. We ONLY help games that use the Steam API but
                // are NOT DRM-wrapped (start_protected_game.exe would require real Steam).
                val prediction = SteamDrmPreflight.analyze(File(realInstallDir))
                prediction.needsSteamSession && !prediction.drmWrapped
            },
            apply = { context, appId, baseConfig, _, _ ->
                // Double-check source; condition already guards this, but be explicit.
                val source = ContainerUtils.extractGameSourceFromContainerId(appId)
                if (source != GameSource.STEAM) {
                    // CUSTOM_GAME / other: no real Steam appid known — honest no-op.
                    Timber.tag(TAG).i("Rung steam_emulation_enable: skipping non-STEAM source $source for $appId")
                    return@Rung null
                }

                try {
                    // SteamUtils.replaceSteamApi is suspend; call it on IO dispatcher.
                    runBlocking(Dispatchers.IO) {
                        SteamUtils.replaceSteamApi(context, appId, isOffline = false)
                    }
                    FixResult(
                        appliedFix = AppliedFix.SteamEmulationEnabled,
                        patchedConfig = baseConfig, // no ContainerData fields need changing
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Rung steam_emulation_enable failed for $appId")
                    null
                }
            },
        ),
    )

    // -------------------------------------------------------------------------
    // Helper: pick next un-tried applicable rung
    // -------------------------------------------------------------------------

    /**
     * Returns the first rung from [LADDER] that:
     * 1. Applies to [failureClass].
     * 2. Has not been tried yet (id not in [triedRungIds]).
     * 3. Passes its [Rung.condition] check.
     *
     * [preferredRungIds] is an optional learned-prior ordering (e.g. rung ids that
     * historically fixed this [failureClass] on this device, best-first). When non-empty,
     * the first preferred rung that is applicable + untried + passes its condition is
     * returned BEFORE falling back to [LADDER] order. Additive and default-empty, so all
     * existing callers keep the original LADDER-order behaviour unchanged.
     */
    fun nextRung(
        context: Context,
        appId: String,
        baseConfig: ContainerData,
        failureClass: FailureClass,
        triedRungIds: Set<String>,
        preferredRungIds: List<String> = emptyList(),
    ): Rung? {
        // A rung is eligible if it is untried, applies to this failure class, and passes
        // its own condition predicate. Used for both the preferred-order and LADDER-order
        // passes so the two cannot diverge.
        fun eligible(rung: Rung): Boolean =
            rung.id !in triedRungIds &&
                failureClass in rung.appliesToClasses &&
                rung.condition(context, appId, baseConfig, failureClass)

        // Pass 1 — honour the learned prior: try preferred rung ids first, in their given
        // order, returning the first one that is currently eligible.
        if (preferredRungIds.isNotEmpty()) {
            for (preferredId in preferredRungIds) {
                val rung = LADDER.firstOrNull { it.id == preferredId } ?: continue
                if (eligible(rung)) return rung
            }
        }

        // Pass 2 — fall back to LADDER order (unchanged original behaviour).
        return LADDER.firstOrNull { eligible(it) }
    }

    // -------------------------------------------------------------------------
    // Private helpers — env-var patching on ContainerData (no disk I/O)
    // -------------------------------------------------------------------------

    /**
     * Returns a copy of [base] with [dll]=[mode] merged into WINEDLLOVERRIDES.
     * If the dll is already present the original is kept (user wins).
     */
    private fun mergeWineDllOverride(base: ContainerData, dll: String, mode: String): ContainerData {
        val existing = DllOverrideFix.parseOverrides(extractEnvVar(base.envVars, "WINEDLLOVERRIDES"))
        if (existing.containsKey(dll)) return base // already set
        val merged = (existing + mapOf(dll to mode)).entries.joinToString(";") { "${it.key}=${it.value}" }
        val newEnvVars = putEnvVar(base.envVars, "WINEDLLOVERRIDES", merged)
        return base.copy(envVars = newEnvVars)
    }

    /**
     * Returns a copy of [base] with [key]=[value] set in the env vars string.
     * If the key already exists it is overwritten.
     */
    private fun mergeEnvVar(base: ContainerData, key: String, value: String): ContainerData {
        val newEnvVars = putEnvVar(base.envVars, key, value)
        return base.copy(envVars = newEnvVars)
    }

    /** Extracts the value of [key] from a Wine-style env vars string (key=value\nkey2=value2). */
    private fun extractEnvVar(envVarsString: String, key: String): String {
        return try {
            val envVars = EnvVars(envVarsString)
            envVars.get(key)
        } catch (_: Exception) {
            ""
        }
    }

    /** Returns [envVarsString] with [key] set to [value], using EnvVars to parse/serialize. */
    private fun putEnvVar(envVarsString: String, key: String, value: String): String {
        return try {
            val envVars = EnvVars(envVarsString)
            envVars.put(key, value)
            envVars.toString()
        } catch (_: Exception) {
            envVarsString // fallback: unchanged
        }
    }
}
