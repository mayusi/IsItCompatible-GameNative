package app.gamenative.utils

import android.content.Context
import app.gamenative.gamefixes.types.DllOverrideFix
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
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

    private fun classifyInternal(
        logLines: List<String>,
        context: Context,
        appId: String,
        container: Container?,
        installPath: String,
    ): CrashSuggestion? {
        if (logLines.isEmpty()) return null

        val joined = logLines.joinToString("\n")

        // ---- Pattern 1: MSVC / vcruntime import failure ----
        if (joined.contains(Regex("err:module:import_dll Library.*(MSVC|vcruntime|VCRUNTIME|msvcp|MSVCP)"))) {
            return CrashSuggestion(
                message = "This game needs Visual C++ runtime — re-launching may install it automatically",
                actionLabel = null,
                action = null,
            )
        }

        // ---- Pattern 2: WMV3 / media-format crash ----
        if (joined.contains("Unrecognised format WMV3") ||
            joined.contains("WMV3") && joined.contains("err:mfmediatype") ||
            joined.contains("err:winegstreamer:wg_parser_connect") ||
            joined.contains("err:quartz:") && joined.contains(".wmv")
        ) {
            val action: (() -> Unit)? = if (installPath.isNotEmpty()) {
                {
                    val count = VideoFileAutoFixer.renameIntroVideos(installPath)
                    Timber.tag("CrashClassifier").i("Renamed $count intro WMV file(s) for $appId")
                }
            } else null

            return CrashSuggestion(
                message = "Intro videos are crashing the game — tap to rename them and skip on next launch",
                actionLabel = if (action != null) "Rename" else null,
                action = action,
            )
        }

        // ---- Pattern 3: d3dcompiler import failure ----
        if (joined.contains(Regex("err:module:import_dll Library d3dcompiler"))) {
            return CrashSuggestion(
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
        }

        // ---- Pattern 4: Steam overlay crash ----
        if (joined.contains("GameOverlayRenderer64") ||
            joined.contains("GameOverlayRenderer.dll")
        ) {
            return CrashSuggestion(
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
        }

        // ---- Pattern 5: Epic Online Services crash ----
        if (joined.contains("EOS_Platform_Create") ||
            joined.contains("eossdk-win64-shipping") ||
            joined.contains("EOSSDK-Win64-Shipping")
        ) {
            return CrashSuggestion(
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
        }

        // ---- Pattern 6: Anti-cheat / SEH exception near launch (informational) ----
        // We check for seh:setup_exception alongside early log presence (short session = near launch).
        if (joined.contains("err:seh:setup_exception") && logLines.size < 80) {
            return CrashSuggestion(
                message = "This game may use anti-cheat that blocks Wine — try launching in Steam Offline Mode",
                actionLabel = null,
                action = null,
            )
        }

        // ---- Pattern 7: Wrong / missing executable ----
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
