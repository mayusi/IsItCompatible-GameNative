package app.gamenative.iic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.gamenative.autotuner.TunerOutcome
import com.winlator.container.ContainerData
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import app.gamenative.iic.IicStability

/**
 * Best-effort broadcaster: fires `io.github.mayusi.isitcompatible.AUTOTUNER_RESULT`
 * when the user applies an auto-tuner winning config in the IIC fork, so the IIC
 * app can store it as a Tier-1 VERIFIED guide for that game+device.
 *
 * Design mirrors [SessionFeedbackBroadcaster]:
 *  - Wrapped in try/catch — never allowed to crash the fork.
 *  - Checks PackageManager visibility before sending; tries release then debug.
 *  - Only fires when the IIC app is installed; silently no-ops otherwise.
 *
 * Config format: the SAME flat GameNative `<Game>_config.json` schema the app's
 * `importGameNativeConfig` / `sanitizeGameNativeConfig` path expects.  Critically:
 *  - Required keys: "emulator", "wineVersion", "graphicsDriver", "dxwrapper", "extraData".
 *  - Instance-specific fields (name, id, installPath, executablePath, drives) are
 *    BLANKED here so the app receives a clean, reusable config, not device leakage.
 *    The app sanitizes again on its side for defence-in-depth.
 *  - dxwrapperConfig is emitted as a FULL CSV string (the on-disk format), not the
 *    bare version the LAUNCH_GAME intent uses. The app's sanitize pass keeps it
 *    verbatim, and the template/stager reads the full CSV.
 *
 * SECURITY: the receiver on the app side must treat this broadcast as UNTRUSTED
 * (exported=true, so any app could send AUTOTUNER_RESULT). It validates and
 * sanitizes on receipt exactly as importGameNativeConfig does. We help by sending
 * a well-formed JSON, but the app must not trust it unconditionally.
 */
object AutoTunerResultBroadcaster {

    private const val TAG = "AutoTunerResultBroadcast"

    const val IIC_PACKAGE_RELEASE = SessionFeedbackBroadcaster.IIC_PACKAGE_RELEASE
    const val IIC_PACKAGE_DEBUG = SessionFeedbackBroadcaster.IIC_PACKAGE_DEBUG

    /** Broadcast action the IIC app's receiver listens for. */
    const val ACTION_AUTOTUNER_RESULT = "io.github.mayusi.isitcompatible.AUTOTUNER_RESULT"

    // Extra keys — must match AutoTunerResultReceiver on the app side.
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_GAME_SOURCE = "game_source"
    const val EXTRA_CONFIG_JSON = "config_json"
    const val EXTRA_AVG_FPS = "avg_fps"
    const val EXTRA_STABILITY = "stability"
    const val EXTRA_GOAL = "goal"
    const val EXTRA_APPLIED_FIXES = "applied_fixes"

    /**
     * Fire the broadcast. All parameters are best-effort. Fails silently if the
     * IIC app is not installed or if any exception occurs.
     *
     * @param context        Application or activity context.
     * @param appId          Numeric Steam app id (> 0). 0 when unknown — skipped.
     * @param gameSource     Store name: "STEAM", "GOG", "EPIC", "AMAZON", or "CUSTOM_GAME".
     * @param outcome        The completed sweep outcome (contains winner + goal).
     * @param winnerConfig   The winning [ContainerData] to broadcast as a config JSON.
     */
    fun send(
        context: Context,
        appId: Int,
        gameSource: String,
        outcome: TunerOutcome,
        winnerConfig: ContainerData,
    ) {
        if (appId <= 0) {
            Timber.d("[IIC] AutoTuner result: appId <= 0, skipping broadcast")
            return
        }
        val winner = outcome.winner ?: run {
            Timber.d("[IIC] AutoTuner result: no winner in outcome, skipping broadcast")
            return
        }

        try {
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
                Timber.d("[IIC] IIC app not installed — skipping auto-tuner result broadcast")
                return
            }

            // Build the config JSON in the flat GameNative <Game>_config.json schema.
            // Instance-specific fields are blanked for reusability + privacy.
            val configJson = buildConfigJson(winnerConfig, outcome)

            // stability label: std-dev-based pacing steadiness via shared IicStability.label().
            val stability = IicStability.label(
                stdDev = winner.fpsStdDev,
                avgFps = winner.avgFps,
                samples = if (winner.fpsStdDev > 0f) 3 else 1,
            ).ifBlank { "PLAYABLE" }  // probe sweep / boot-success still counts as playable

            // applied fixes as a JSON array string (may be empty)
            val appliedFixesJson = try {
                JSONArray(winner.appliedFixes).toString()
            } catch (_: Exception) {
                "[]"
            }

            val intent = Intent(ACTION_AUTOTUNER_RESULT).apply {
                setPackage(resolvedPackage)
                putExtra(EXTRA_APP_ID, appId)
                putExtra(EXTRA_GAME_SOURCE, gameSource)
                putExtra(EXTRA_CONFIG_JSON, configJson)
                putExtra(EXTRA_AVG_FPS, winner.avgFps)
                putExtra(EXTRA_STABILITY, stability)
                putExtra(EXTRA_GOAL, outcome.goal.name)
                putExtra(EXTRA_APPLIED_FIXES, appliedFixesJson)
            }
            context.sendBroadcast(intent)
            Timber.i(
                "[IIC] Sent auto-tuner result broadcast to $resolvedPackage: " +
                    "appId=$appId src=$gameSource goal=${outcome.goal.name} " +
                    "avgFps=${winner.avgFps} stability=$stability",
            )
        } catch (e: Exception) {
            // Best-effort: never crash the fork if the broadcast fails.
            Timber.w(e, "[IIC] Failed to send auto-tuner result broadcast (non-fatal)")
        }
    }

    /**
     * Build the flat GameNative `<Game>_config.json` JSON from a [ContainerData].
     *
     * The schema must satisfy the app's `REQUIRED_CONFIG_KEYS` check:
     *   ["emulator", "wineVersion", "graphicsDriver", "dxwrapper"]
     * and must include an `extraData` JsonObject (can be empty `{}`).
     *
     * Instance-specific fields are BLANKED so the config is clean and reusable:
     *   name, id, installPath, executablePath, drives → ""
     *   sessionMetadata → omitted
     *
     * The autotuner_result_v1 summary is included in extraData so the app can
     * display what was actually measured on this device.
     */
    private fun buildConfigJson(config: ContainerData, outcome: TunerOutcome): String {
        val winner = outcome.winner!!
        val extraData = JSONObject().apply {
            put("appliedContainerVariant", config.containerVariant)
            put("appliedWineVersion", config.wineVersion)
            put("config_changed", "true")
            // Tuner provenance — the app records this as the sourceLabel
            put("autotuner_result_v1", JSONObject().apply {
                put("goal", outcome.goal.name)
                put("avgFps", winner.avgFps.toDouble())
                put("minFps", winner.minFps.toDouble())
                put("maxFps", winner.maxFps.toDouble())
                put("fpsStdDev", winner.fpsStdDev.toDouble())
                put("totalTrials", outcome.totalTrials)
                put("completedTrials", outcome.completedTrials)
                put("timestamp", System.currentTimeMillis())
                winner.avgPowerW?.let { put("avgPowerW", it.toDouble()) }
                if (winner.appliedFixes.isNotEmpty()) {
                    put("appliedFixes", JSONArray(winner.appliedFixes))
                }
            })
        }

        return JSONObject().apply {
            // Required keys (validated by the app)
            put("emulator", config.emulator)
            put("wineVersion", config.wineVersion)
            put("graphicsDriver", config.graphicsDriver)
            put("dxwrapper", config.dxwrapper)

            // Instance-specific fields: blanked for reusability + privacy
            put("name", "")
            put("id", "")
            put("installPath", "")
            put("executablePath", "")
            put("drives", "")

            // Config fields that ARE reusable across devices
            put("screenSize", config.screenSize)
            put("envVars", config.envVars)
            put("cpuList", config.cpuList)
            put("cpuListWoW64", config.cpuListWoW64)
            put("graphicsDriverVersion", config.graphicsDriverVersion)
            put("graphicsDriverConfig", config.graphicsDriverConfig)
            put("dxwrapperConfig", config.dxwrapperConfig)
            put("audioDriver", config.audioDriver)
            put("wincomponents", config.wincomponents)
            put("wow64Mode", config.wow64Mode)
            put("containerVariant", config.containerVariant)
            put("box64Version", config.box64Version)
            put("box64Preset", config.box64Preset)
            put("box86Version", config.box86Version)
            put("box86Preset", config.box86Preset)
            put("fexcoreVersion", config.fexcoreVersion)
            put("fexcorePreset", config.fexcorePreset)
            put("execArgs", config.execArgs)
            // extraData object (required by the app's schema validator)
            put("extraData", extraData)
        }.toString()
    }
}
