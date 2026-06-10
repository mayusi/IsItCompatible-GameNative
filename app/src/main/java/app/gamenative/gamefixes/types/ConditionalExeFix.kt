package app.gamenative.gamefixes.types

import android.content.Context
import app.gamenative.gamefixes.GameFix
import com.winlator.container.Container
import timber.log.Timber

/**
 * A [GameFix] decorator that gates application of [inner] on whether the container's
 * current [executablePath] matches [exePathPattern] (case-insensitive substring match).
 *
 * This lets a single game entry ship per-sub-game fixes via the JSON registry without
 * duplicating the full fix set for every exe.
 *
 * Example JSON:
 * ```json
 * {
 *   "source": "STEAM",
 *   "gameId": "631510",
 *   "type": "conditional_exe",
 *   "exePattern": "dmc1.exe",
 *   "inner": { "type": "wine_env", "envVars": { "SOME_VAR": "1" } }
 * }
 * ```
 *
 * If [container.executablePath] does not contain [exePathPattern] the fix is
 * skipped (returns true / no-op) so it never blocks other fixes.
 *
 * @param exePathPattern Case-insensitive substring to match against executablePath.
 * @param inner          The actual fix to apply when the pattern matches.
 */
class ConditionalExeFix(
    private val exePathPattern: String,
    private val inner: GameFix,
) : GameFix {

    override fun apply(
        context: Context,
        gameId: String,
        installPath: String,
        installPathWindows: String,
        container: Container,
    ): Boolean {
        val currentExe = container.executablePath ?: ""
        if (!currentExe.contains(exePathPattern, ignoreCase = true)) {
            Timber.tag("ConditionalExeFix")
                .d("Skipping fix for game $gameId — executablePath '$currentExe' does not match pattern '$exePathPattern'")
            return true // no-op, not a failure
        }
        Timber.tag("ConditionalExeFix")
            .d("Applying inner fix for game $gameId — executablePath '$currentExe' matches '$exePathPattern'")
        return inner.apply(context, gameId, installPath, installPathWindows, container)
    }
}
