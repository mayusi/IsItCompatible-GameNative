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

    /** Maximum number of Wine debug lines retained in memory per session. */
    const val RING_BUFFER_LINES = 300

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
     */
    data class CrashSuggestion(
        val message: String,
        val actionLabel: String? = null,
        val action: (() -> Unit)? = null,
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
            )

            FixLadder.FailureClass.SEH_ANTICHEAT -> {
                // Additional CrashClassifier-specific guard: only show this suggestion when
                // the log is short (near-launch crash), same as the original behaviour.
                if (logLines.size < 80) {
                    CrashSuggestion(
                        message = "This game may use anti-cheat that blocks Wine — try launching in Steam Offline Mode",
                        actionLabel = null,
                        action = null,
                    )
                } else null
            }

            // UNKNOWN_CRASH and BLACK_SCREEN_NOFIX: fall through to CrashClassifier-only patterns
            else -> null
        }

        if (suggestion != null) return suggestion

        // ---- Pattern 7 (CrashClassifier-only): Wrong / missing executable ----
        val joined = logLines.joinToString("\n")
        if (joined.contains(Regex("wine: cannot find|err:module:load_builtin.*\\.exe"))) {
            return CrashSuggestion(
                message = "The game executable may be wrong — check Executable Path in Settings",
                actionLabel = null,
                action = null,
            )
        }

        return null
    }
}
