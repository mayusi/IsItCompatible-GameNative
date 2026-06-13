package app.gamenative.autotuner

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.gamefixes.GameFixesRegistry
import app.gamenative.gamefixes.types.DllOverrideFix
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.VideoFileAutoFixer
import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars
import timber.log.Timber

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
        STEAM_OVERLAY,
        EOS_CRASH,
        MSVC_MISSING,
        SEH_ANTICHEAT,
        BLACK_SCREEN_NOFIX,
        UNKNOWN_CRASH,
    }

    /**
     * Classify [logLines] into a [FailureClass] by pattern-matching Wine debug output.
     * Mirrors the logic in CrashClassifier without the snackbar/action coupling.
     */
    fun classifyFailure(logLines: List<String>): FailureClass {
        if (logLines.isEmpty()) return FailureClass.UNKNOWN_CRASH
        val joined = logLines.joinToString("\n")
        return when {
            joined.contains("Unrecognised format WMV3") ||
                (joined.contains("WMV3") && joined.contains("err:mfmediatype")) ||
                joined.contains("err:winegstreamer:wg_parser_connect") ||
                (joined.contains("err:quartz:") && joined.contains(".wmv")) ||
                joined.contains("audio/x-wma") ||
                (joined.contains("videoconv") && joined.contains("MEDIACONV")) ->
                FailureClass.WMV_CODEC

            joined.contains(Regex("err:module:import_dll Library d3dcompiler")) ->
                FailureClass.D3D_COMPILER

            joined.contains("GameOverlayRenderer64") ||
                joined.contains("GameOverlayRenderer.dll") ->
                FailureClass.STEAM_OVERLAY

            joined.contains("EOS_Platform_Create") ||
                joined.contains("eossdk-win64-shipping") ||
                joined.contains("EOSSDK-Win64-Shipping") ->
                FailureClass.EOS_CRASH

            joined.contains(Regex("err:module:import_dll Library.*(MSVC|vcruntime|VCRUNTIME|msvcp|MSVCP)")) ->
                FailureClass.MSVC_MISSING

            joined.contains("err:seh:setup_exception") ->
                FailureClass.SEH_ANTICHEAT

            else -> FailureClass.UNKNOWN_CRASH
        }
    }

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

        // Rung 1 — Per-game registry fix (compiled-in or JSON)
        Rung(
            id = "game_registry_fix",
            description = "Apply game-specific fix",
            appliesToClasses = FailureClass.values().toSet(), // applies to all
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

        // Rung 5 — Disable EOS
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

        // Rung 6 — DXVK_ASYNC=1 for BLACK_SCREEN / UNKNOWN when dxwrapper=dxvk
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
    )

    // -------------------------------------------------------------------------
    // Helper: pick next un-tried applicable rung
    // -------------------------------------------------------------------------

    /**
     * Returns the first rung from [LADDER] that:
     * 1. Applies to [failureClass].
     * 2. Has not been tried yet (id not in [triedRungIds]).
     * 3. Passes its [Rung.condition] check.
     */
    fun nextRung(
        context: Context,
        appId: String,
        baseConfig: ContainerData,
        failureClass: FailureClass,
        triedRungIds: Set<String>,
    ): Rung? {
        return LADDER.firstOrNull { rung ->
            rung.id !in triedRungIds &&
                failureClass in rung.appliesToClasses &&
                rung.condition(context, appId, baseConfig, failureClass)
        }
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
