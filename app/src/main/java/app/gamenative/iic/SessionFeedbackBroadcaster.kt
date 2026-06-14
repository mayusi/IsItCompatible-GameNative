package app.gamenative.iic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import timber.log.Timber

/**
 * Best-effort broadcaster: fires `io.github.mayusi.isitcompatible.GAME_SESSION_ENDED`
 * when a game session ends in the IIC fork so the IIC app can pre-fill a journal entry.
 *
 * Rules:
 *  - Wrapped in try/catch at every callsite — never allowed to crash the fork.
 *  - Checks PackageManager visibility before sending; on Android 11+ the fork
 *    must declare a <queries> element for the IIC package (done in AndroidManifest).
 *  - Only fires when the IIC app is installed; silently no-ops otherwise.
 */
object SessionFeedbackBroadcaster {

    const val IIC_PACKAGE_RELEASE = "io.github.mayusi.isitcompatible"
    const val IIC_PACKAGE_DEBUG = "io.github.mayusi.isitcompatible.debug"
    const val ACTION_GAME_SESSION_ENDED = "io.github.mayusi.isitcompatible.GAME_SESSION_ENDED"

    // Extra keys — must match what SessionResultReceiver.kt expects.
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    const val EXTRA_SESSION_MINUTES = "session_minutes"
    const val EXTRA_SHOWED_FPS = "showed_fps"
    const val EXTRA_AVG_FPS = "avg_fps"
    const val EXTRA_STABILITY = "stability"

    /**
     * Fire the broadcast.  All parameters are best-effort: pass 0 / empty strings
     * when data is unavailable; the receiver validates and degrades gracefully.
     *
     * @param context    Application or activity context.
     * @param appId      Numeric game/app id (e.g. Steam app id). 0 when unknown.
     * @param gameSource Store name: "STEAM", "GOG", "EPIC", "AMAZON", or "CUSTOM_GAME".
     * @param sessionStartMs  System.currentTimeMillis() when the session started.
     *                        Pass 0 to omit session-length (will broadcast 0 minutes).
     * @param showedFps  Whether the FPS HUD was active during this session.
     * @param avgFps     Average FPS for the session (integer). 0 when not measured.
     * @param stability  Stability label ("PERFECT", "PLAYABLE", "GLITCHY", or "").
     *                   Empty string when FPS was not measured this session.
     */
    fun send(
        context: Context,
        appId: Int,
        gameSource: String,
        sessionStartMs: Long,
        showedFps: Boolean,
        avgFps: Int = 0,
        stability: String = "",
    ) {
        try {
            // Android 11+: PackageManager.getPackageInfo throws NameNotFoundException
            // when the package isn't declared in <queries>; guard against that too.
            // Try to detect which variant of the IIC app is installed: release or debug.
            val resolvedPackage = try {
                context.packageManager.getPackageInfo(IIC_PACKAGE_RELEASE, 0)
                IIC_PACKAGE_RELEASE
            } catch (_: PackageManager.NameNotFoundException) {
                try {
                    context.packageManager.getPackageInfo(IIC_PACKAGE_DEBUG, 0)
                    IIC_PACKAGE_DEBUG
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }

            if (resolvedPackage == null) {
                Timber.d("[IIC] IIC app not installed — skipping session feedback broadcast")
                return
            }

            val sessionMinutes = if (sessionStartMs > 0L) {
                val elapsedMs = System.currentTimeMillis() - sessionStartMs
                // Clamp to [0, 1440] (0..24 h).  Guards against a corrupt/missing start
                // timestamp producing a nonsensical or overflowing minute value.
                (elapsedMs / 60_000L).toInt().coerceIn(0, 1440)
            } else {
                0
            }

            val intent = Intent(ACTION_GAME_SESSION_ENDED).apply {
                setPackage(resolvedPackage)
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_GAME_SOURCE, gameSource)
                putExtra(EXTRA_SESSION_MINUTES, sessionMinutes)
                putExtra(EXTRA_SHOWED_FPS, showedFps)
                putExtra(EXTRA_AVG_FPS, avgFps)
                if (stability.isNotBlank()) putExtra(EXTRA_STABILITY, stability)
            }
            context.sendBroadcast(intent)
            Timber.i("[IIC] Sent session-ended broadcast to $resolvedPackage: appId=$appId src=$gameSource mins=$sessionMinutes fps=$showedFps avgFps=$avgFps stability=$stability")
        } catch (e: Exception) {
            // Best-effort: never crash the fork if the broadcast fails.
            Timber.w(e, "[IIC] Failed to send session-ended broadcast (non-fatal)")
        }
    }
}
