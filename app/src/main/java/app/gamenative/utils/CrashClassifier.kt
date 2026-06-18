package app.gamenative.utils

import android.content.Context
import app.gamenative.autotuner.FixLadder
import app.gamenative.gamefixes.types.DllOverrideFix
import com.winlator.container.Container
import timber.log.Timber

/**
 * Classifies Wine crash output into a human-readable suggestion with an optional one-tap
 * fix action.  Called after a game terminates with a non-zero exit status.
 *
 * All pattern matching is done against a ring-buffer of the last [RING_BUFFER_LINES] lines
 * of Wine debug output captured during the session.
 *
 * All classification is best-effort and wrapped in try/catch — a failure here must never
 * propagate to crash the host app.
 */
object CrashClassifier {

    /**
     * Maximum number of Wine/Box64 debug lines retained in memory per session.
     * 1000 (was 300): old/verbose games (D3D9 shader init, Box64 dynarec dumps) push the actual
     * error well past 300 lines of init spam, so a too-small buffer dropped the signal before the
     * classifier could see it. 1000 keeps the relevant crash context in view.
     */
    const val RING_BUFFER_LINES = 1000

    /**
     * Thread-safe, in-memory ring buffer for Wine debug output.
     * Reset at the start of each game launch (call [reset]).
     */
    private val ringBuffer = ArrayDeque<String>(RING_BUFFER_LINES + 1)
    private val lock = Any()

    /** Append a line to the ring buffer, dropping the oldest entry when full. */
    fun appendLine(line: String) {
        synchronized(lock) {
            if (ringBuffer.size >= RING_BUFFER_LINES) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(line)
        }
    }

    /** Drain a snapshot of the current ring buffer contents (oldest first). */
    fun snapshot(): List<String> {
        synchronized(lock) {
            return ringBuffer.toList()
        }
    }

    /** Clear the ring buffer — call before each new game launch. */
    fun reset() {
        synchronized(lock) { ringBuffer.clear() }
    }

    // -------------------------------------------------------------------------

    /**
     * A fix suggestion returned by [classify].
     *
     * @param message     The user-visible message shown in the snackbar.
     * @param actionLabel Label for the snackbar action button, or null for informational only.
     * @param action      Action to run when the button is tapped, or null.
     * @param diagnosis   Full plain-English explanation of what went wrong and what the
     *                    auto-fixer does about it.  Shown in a "Why?" dialog when the user
     *                    wants more detail.  Empty string means no diagnosis available.
     */
    data class CrashSuggestion(
        val message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
        val diagnosis: String = "",
    )

    /**
     * Classify [logLines] and return a [CrashSuggestion] or null if no pattern matches.
     *
     * @param logLines    Lines of Wine debug output to scan.
     * @param context     Android context (needed to save container data on action).
     * @param appId       The full container/app ID (e.g. "STEAM_730").
     * @param container   The game's container (for DLL-override fixes).
     * @param installPath Host filesystem path to the game's install directory (for video rename).
     */
    fun classify(
        logLines: List<String>,
        context: Context,
        appId: String,
        container: Container?,
        installPath: String,
    ): CrashSuggestion? {
        return try {
            classifyInternal(logLines, context, appId, container, installPath)
        } catch (e: Exception) {
            Timber.tag("CrashClassifier").e(e, "classify() threw unexpectedly — suppressed")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Internal classification (allowed to throw; outer classify() wraps it)
    // -------------------------------------------------------------------------

    /**
     * Delegates pattern detection to [WineLogClassifier] (single source of truth shared with
     * [app.gamenative.autotuner.FixLadder]), then maps the result to a [CrashSuggestion] with
     * the appropriate UI message and optional one-tap fix action.
     *
     * Pattern 7 (wrong/missing executable) is CrashClassifier-only — it has no corresponding
     * fix rung in FixLadder, so it remains here and is checked after the shared classification.
     */
    private fun classifyInternal(
        logLines: List<String>,
        context: Context,
        appId: String,
        container: Container?,
        installPath: String,
    ): CrashSuggestion? {
        if (logLines.isEmpty()) return null

        val failureClass = WineLogClassifier.classify(logLines)

        val suggestion = when (failureClass) {
            FixLadder.FailureClass.MSVC_MISSING -> CrashSuggestion(
                message = "This game needs Visual C++ runtime — re-launching may install it automatically",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: a required Microsoft Visual C++ runtime DLL is missing " +
                    "from the Wine prefix (e.g. vcruntime140.dll or msvcp140.dll). Most Windows " +
                    "games depend on these to start.\n\n" +
                    "What the auto-fixer does: installs the Visual C++ runtime via winetricks on " +
                    "the next launch attempt so the missing DLL is present when the game loads.",
            )

            // WMV_CODEC: audio/x-wma is now also caught via WineLogClassifier (was missing here before)
            FixLadder.FailureClass.WMV_CODEC -> {
                val action: (() -> Unit)? = if (installPath.isNotEmpty()) {
                    {
                        val count = VideoFileAutoFixer.renameIntroVideos(installPath)
                        Timber.tag("CrashClassifier").i("Renamed $count intro WMV file(s) for $appId")
                    }
                } else null
                CrashSuggestion(
                    message = "Intro videos are crashing the game — tap to rename them and skip on next launch",
                    actionLabel = if (action != null) "Rename" else null,
                    action = action,
                    diagnosis = "What happened: the game's intro or cutscene videos use a Windows " +
                        "media codec (WMV3 / WMA) that Wine's GStreamer bridge cannot play on " +
                        "Android. The video decoder hangs or crashes before the game's main " +
                        "screen appears.\n\n" +
                        "What the fix does: renames the offending .wmv files to .wmv.bak so Wine " +
                        "can't find them. The game skips its intro and goes straight to the main " +
                        "menu on the next launch. No game data is deleted — the rename is " +
                        "reversible.",
                )
            }

            FixLadder.FailureClass.D3D_COMPILER -> CrashSuggestion(
                message = "Missing D3D shader compiler — tap to add a DLL override",
                actionLabel = if (container != null) "Fix" else null,
                action = if (container != null) {
                    {
                        try {
                            DllOverrideFix(mapOf("d3dcompiler_47" to "n,b"))
                                .apply(context, appId, installPath, "", container)
                        } catch (e: Exception) {
                            Timber.tag("CrashClassifier").e(e, "d3dcompiler DllOverrideFix action failed")
                        }
                    }
                } else null,
                diagnosis = "What happened: the game tried to load d3dcompiler_47.dll (a shader " +
                    "compiler that DirectX 11 games use to compile HLSL shaders at runtime), but " +
                    "Wine couldn't find it in the prefix or in the system paths.\n\n" +
                    "What the fix does: adds a DLL override entry (WINEDLLOVERRIDES) that tells " +
                    "Wine to load the native d3dcompiler_47 bundled with GameNative instead of " +
                    "looking for a Windows copy. This is applied immediately — re-launch the game " +
                    "after tapping Fix.",
            )

            FixLadder.FailureClass.D3D12_UNSUPPORTED -> CrashSuggestion(
                message = "DirectX 12 not supported on this device — tap to switch to DX11 mode",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: the game tried to create a DirectX 12 device, but " +
                    "your GPU's Vulkan driver doesn't meet the required feature level " +
                    "(D3D_FEATURE_LEVEL_12). This is common on many Android GPUs when running " +
                    "newer games via the vkd3d translation layer.\n\n" +
                    "What the auto-fixer does: adds -dx11 to the game's launch arguments, which " +
                    "forces most Unreal Engine 4 / 5 games to use their built-in DirectX 11 " +
                    "renderer. DX11 is then handled by DXVK (DX11 → Vulkan), which is fast and " +
                    "well-supported. The game runs — often at better performance than DX12 on " +
                    "mobile GPUs.",
            )

            FixLadder.FailureClass.STEAM_INIT_FAILED -> CrashSuggestion(
                message = "Steam session not found — the game couldn't initialise Steam",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: the game called SteamAPI_Init() and it failed, which " +
                    "means it couldn't find a running or emulated Steam session. Games that " +
                    "depend on the Steam API to start (even offline features like achievements " +
                    "or cloud saves) will crash at this point.\n\n" +
                    "What the auto-fixer does: deploys GameNative's bundled Steam API emulation " +
                    "(a Goldberg-style replacement) so the game finds a valid session. This works " +
                    "for games that use the Steam API but are not DRM-wrapped. If the game has " +
                    "start_protected_game.exe or strong DRM, a real Steam login is required — " +
                    "enable 'Launch with Steam' in the container settings.",
            )

            FixLadder.FailureClass.STEAM_OVERLAY -> CrashSuggestion(
                message = "Steam overlay is crashing the game — tap to disable it",
                actionLabel = if (container != null) "Disable" else null,
                action = if (container != null) {
                    {
                        try {
                            DllOverrideFix(mapOf("GameOverlayRenderer64" to "n,b"))
                                .apply(context, appId, installPath, "", container)
                        } catch (e: Exception) {
                            Timber.tag("CrashClassifier").e(e, "GameOverlay DllOverrideFix action failed")
                        }
                    }
                } else null,
                diagnosis = "What happened: the Steam overlay DLL (GameOverlayRenderer64.dll) " +
                    "crashed. This overlay hooks into the game's rendering pipeline; under Wine " +
                    "on Android it sometimes fails to inject cleanly and brings the game down " +
                    "with it.\n\n" +
                    "What the fix does: adds a DLL override that tells Wine to ignore " +
                    "GameOverlayRenderer64 (block it from loading). The Steam overlay will " +
                    "no longer appear in-game, but everything else continues to work normally.",
            )

            FixLadder.FailureClass.EOS_CRASH -> CrashSuggestion(
                message = "Epic Online Services is crashing — tap to disable it",
                actionLabel = if (container != null) "Disable" else null,
                action = if (container != null) {
                    {
                        try {
                            DllOverrideFix(mapOf("EOSSDK-Win64-Shipping" to "n,b"))
                                .apply(context, appId, installPath, "", container)
                        } catch (e: Exception) {
                            Timber.tag("CrashClassifier").e(e, "EOS DllOverrideFix action failed")
                        }
                    }
                } else null,
                diagnosis = "What happened: the Epic Online Services SDK (EOSSDK-Win64-Shipping.dll) " +
                    "crashed during initialisation. EOS is used for multiplayer, friends lists, " +
                    "and achievements in Epic-published and third-party games. It doesn't run " +
                    "reliably under Wine on Android and often faults early at startup.\n\n" +
                    "What the fix does: blocks EOSSDK-Win64-Shipping from loading via a DLL " +
                    "override. The game starts without EOS. Multiplayer lobbies and EOS-backed " +
                    "features will be unavailable, but single-player content and most of the " +
                    "game will work.",
            )

            FixLadder.FailureClass.SEH_ANTICHEAT -> {
                // Additional CrashClassifier-specific guard: only show this suggestion when
                // the log is short (near-launch crash), same as the original behaviour.
                if (logLines.size < 80) {
                    CrashSuggestion(
                        message = "This game may use anti-cheat that blocks Wine — try launching in Steam Offline Mode",
                        actionLabel = null,
                        action = null,
                        diagnosis = "What happened: Wine raised a structured exception (SEH) very " +
                            "early in the launch — a pattern strongly associated with kernel-level " +
                            "anti-cheat software (EAC, BattlEye, Denuvo, etc.) that probes the " +
                            "process environment for virtualisation/emulation and terminates if it " +
                            "detects something unexpected.\n\n" +
                            "What you can try: launch the game in Steam Offline Mode, which " +
                            "sometimes bypasses online anti-cheat checks. If the game requires " +
                            "kernel-level anti-cheat to be active, it is unlikely to run under " +
                            "Wine on Android and may not be supported at this time.",
                    )
                } else null
            }

            FixLadder.FailureClass.D3D9_RENDER -> CrashSuggestion(
                message = "Old DirectX 9 game failed to render — tap to try the wined3d renderer",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: this is an older DirectX 9 game (mid-2000s). It failed " +
                    "to create its D3D9 device or crashed in rendering — usually because the fast " +
                    "DXVK D3D9 path doesn't agree with the game, or a d3dx9 helper library is " +
                    "missing or the wrong version.\n\n" +
                    "What the auto-fixer does: forces Wine's built-in d3dx9 helpers and, if needed, " +
                    "switches this game to the wined3d renderer (D3D9 → OpenGL) — the standard " +
                    "fallback that makes these old titles boot.",
            )

            FixLadder.FailureClass.BOX64_DYNAREC -> CrashSuggestion(
                message = "CPU translation fault — tap to use the Box64 compatibility preset",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: Box64 (the x86 → ARM CPU translator) hit a fault while " +
                    "running this game's code. Older or unusual games can trip the faster " +
                    "translation presets.\n\n" +
                    "What the auto-fixer does: switches Box64 to its COMPATIBILITY preset, which " +
                    "favours correctness over speed and is the most reliable way to get a stubborn " +
                    "game to at least boot.",
            )

            FixLadder.FailureClass.BLACK_SCREEN_NOFIX -> CrashSuggestion(
                message = "The game started but showed a black screen — we couldn't pinpoint the cause",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: the game process launched and Wine initialised " +
                    "successfully, but nothing appeared on screen before it stopped. This can be " +
                    "caused by an unsupported graphics feature, a missing shader cache, a " +
                    "first-boot setup step that hung, or an intro cutscene that failed silently.\n\n" +
                    "What you can try: run the auto-fixer from the game's settings — it will " +
                    "walk through a sequence of known fixes (skip intro videos, disable the Steam " +
                    "overlay, enable DXVK async shader compilation, and more) to find a " +
                    "combination that works for this game.",
            )

            FixLadder.FailureClass.UNKNOWN_CRASH -> CrashSuggestion(
                message = "The game crashed unexpectedly — we couldn't identify the cause from the log",
                actionLabel = null,
                action = null,
                diagnosis = "What happened: the game exited with a non-zero status but the Wine " +
                    "debug log didn't contain any of the known error patterns (missing DLLs, " +
                    "codec failures, anti-cheat exceptions, etc.). The crash may have been " +
                    "triggered by a timing issue, an unhandled Windows exception inside the " +
                    "game's own code, or a driver-level fault.\n\n" +
                    "What you can try: run the auto-fixer from the game's settings — it cycles " +
                    "through a sequence of common fixes automatically. You can also enable Wine " +
                    "debug logging in Settings → Advanced and re-launch to capture more detail " +
                    "for a bug report.",
            )
        }

        // ---- Pattern 7 (CrashClassifier-only): Wrong / missing executable ----
        // Checked before returning the generic UNKNOWN_CRASH / BLACK_SCREEN_NOFIX suggestion
        // so a more specific message is shown when the exe path is clearly wrong.
        if (suggestion == null ||
            failureClass == FixLadder.FailureClass.UNKNOWN_CRASH ||
            failureClass == FixLadder.FailureClass.BLACK_SCREEN_NOFIX
        ) {
            val joined = logLines.joinToString("\n")
            if (joined.contains(Regex("wine: cannot find|err:module:load_builtin.*\\.exe"))) {
                return CrashSuggestion(
                    message = "The game executable may be wrong — check Executable Path in Settings",
                    actionLabel = null,
                    action = null,
                    diagnosis = "What happened: Wine tried to load the game executable but " +
                        "couldn't find it at the path that is configured in the container. " +
                        "This typically means the game was installed in a different folder than " +
                        "expected, or the executable name has changed after an update.\n\n" +
                        "What to do: open the container's Settings, go to Executable Path, and " +
                        "browse to the correct .exe file for the game.",
                )
            }
        }

        if (suggestion != null) return suggestion

        return null
    }
}
