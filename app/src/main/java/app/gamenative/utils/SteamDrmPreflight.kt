package app.gamenative.utils

import timber.log.Timber
import java.io.File

/**
 * Pre-launch Steam-DRM detector.
 *
 * Scans a game's install folder (filesystem only, NO execution) and predicts what Steam
 * infrastructure the game will need at runtime. The result is informational — it informs
 * the user and the launch path; it does NOT strip or patch any DRM.
 *
 * Safe to call from any thread; wraps all I/O in try/catch and returns a safe all-false
 * default on any error so it can never throw into a launch or scan path.
 */
object SteamDrmPreflight {

    private const val TAG = "SteamDrmPreflight"

    // Maximum directory depth scanned (guards against symlink cycles and enormous trees)
    private const val MAX_DEPTH = 6

    // Upper bound on files inspected to avoid stalling on 100k+ file installs
    private const val MAX_FILES = 10_000

    /**
     * The result of a preflight scan.
     *
     * @param needsSteamSession   True when any steam_api*.dll / steamclient*.dll is present in
     *                            the tree — the game uses the Steam API and will need either a
     *                            real Steam session or a local emulation layer.
     * @param drmWrapped          True when start_protected_game.exe is present — this is the
     *                            SteamStub DRM wrapper; a real Steam session is required.
     * @param missingAppIdFile    True when [needsSteamSession] is true but no steam_appid.txt
     *                            exists next to any of those steam_api DLLs — the emulation
     *                            layer will need to supply the App ID.
     * @param confidence          Rough [0..1] confidence in the prediction.
     */
    data class SteamDrmPrediction(
        val needsSteamSession: Boolean,
        val drmWrapped: Boolean,
        val missingAppIdFile: Boolean,
        val confidence: Float,
    )

    /** A safe all-false default returned when scanning fails or the directory is unreadable. */
    private val SAFE_DEFAULT = SteamDrmPrediction(
        needsSteamSession = false,
        drmWrapped = false,
        missingAppIdFile = false,
        confidence = 0f,
    )

    /**
     * Scan [gameDir] and return a [SteamDrmPrediction].
     *
     * Pure filesystem walk (depth-limited, file-count-limited). Never executes any binary.
     * Returns [SAFE_DEFAULT] on any exception.
     */
    fun analyze(gameDir: File): SteamDrmPrediction {
        return try {
            analyzeInternal(gameDir)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "analyze() threw unexpectedly for ${gameDir.absolutePath} — returning safe default")
            SAFE_DEFAULT
        }
    }

    /**
     * Returns a one-line UI guidance message for [p], or null when no Steam dependency was
     * detected.
     *
     * Honest about what GameNative can and cannot do. Does NOT mention DRM stripping.
     */
    fun guidanceMessage(p: SteamDrmPrediction): String? {
        return when {
            p.drmWrapped ->
                "This game has Steam DRM (start_protected_game.exe). It needs a real Steam " +
                    "session — log into Steam in GameNative and enable Steam for this game. " +
                    "Local API emulation alone won't satisfy the DRM."

            p.needsSteamSession ->
                // Covers both the plain-API case and the missing-appid case:
                // the emulation step will supply the App ID automatically.
                "This game uses the Steam API. GameNative can run it with local Steam " +
                    "emulation — no Steam login needed."

            else -> null
        }
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun analyzeInternal(gameDir: File): SteamDrmPrediction {
        if (!gameDir.exists() || !gameDir.isDirectory) {
            Timber.tag(TAG).d("analyzeInternal: dir does not exist or is not a directory: ${gameDir.absolutePath}")
            return SAFE_DEFAULT
        }

        Timber.tag(TAG).d("analyzeInternal: scanning ${gameDir.absolutePath} (maxDepth=$MAX_DEPTH)")

        var fileCount = 0
        var needsSteamSession = false
        var drmWrapped = false
        // Track directories that contain a steam_api DLL, to check for steam_appid.txt beside them
        val steamApiDirs = mutableSetOf<File>()

        // walkTopDown visits the root + all descendants in top-down order.
        // We manually track depth by counting path separators relative to the root.
        val rootDepth = gameDir.absolutePath.count { it == File.separatorChar }

        gameDir.walkTopDown()
            .onEnter { dir ->
                // Prune directories beyond MAX_DEPTH
                val depth = dir.absolutePath.count { it == File.separatorChar } - rootDepth
                depth <= MAX_DEPTH
            }
            .forEach { file ->
                if (!file.isFile) return@forEach

                if (++fileCount > MAX_FILES) {
                    Timber.tag(TAG).w(
                        "analyzeInternal: file-count limit ($MAX_FILES) reached at ${file.absolutePath} — stopping early",
                    )
                    return@forEach
                }

                val nameLower = file.name.lowercase()

                // steam_api detection (covers steam_api.dll, steam_api64.dll, any variant)
                if (!needsSteamSession &&
                    (nameLower == "steam_api.dll" ||
                        nameLower == "steam_api64.dll" ||
                        nameLower == "steamclient.dll" ||
                        nameLower == "steamclient64.dll")
                ) {
                    needsSteamSession = true
                    Timber.tag(TAG).d("analyzeInternal: steam_api DLL found: ${file.absolutePath}")

                    // Record the parent dir so we can check for steam_appid.txt beside it
                    file.parentFile?.let { steamApiDirs.add(it) }
                }

                // SteamStub DRM wrapper
                if (!drmWrapped && nameLower == "start_protected_game.exe") {
                    drmWrapped = true
                    Timber.tag(TAG).d("analyzeInternal: SteamStub wrapper found: ${file.absolutePath}")
                }
            }

        // Check whether steam_appid.txt exists in any directory that contains a steam_api DLL
        val missingAppIdFile = needsSteamSession && steamApiDirs.none { dir ->
            File(dir, "steam_appid.txt").exists()
        }

        // Confidence: 0.9 base if the API DLLs are present, boosted when drmWrapped
        val confidence = when {
            drmWrapped -> 0.95f
            needsSteamSession -> 0.90f
            else -> 0.0f
        }

        val prediction = SteamDrmPrediction(
            needsSteamSession = needsSteamSession,
            drmWrapped = drmWrapped,
            missingAppIdFile = missingAppIdFile,
            confidence = confidence,
        )

        Timber.tag(TAG).i(
            "analyzeInternal: result for ${gameDir.name}: " +
                "needsSteamSession=$needsSteamSession drmWrapped=$drmWrapped " +
                "missingAppIdFile=$missingAppIdFile confidence=$confidence " +
                "filesScanned=$fileCount",
        )

        return prediction
    }
}
