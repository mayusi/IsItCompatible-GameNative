package app.gamenative.autotuner

import android.content.Context
import android.content.Intent
import android.os.Build
import com.winlator.core.GPUInformation
import com.winlator.core.KeyValueSet
import org.json.JSONObject

/**
 * Formats and dispatches the winning tuner result as a shareable artifact.
 *
 * HONEST DESIGN NOTE:
 * ─────────────────────────────────────────────────────────────────────
 * There is NO "contribute tuner result" backend endpoint on api.gamenative.app.
 *   - /api/best-config  : POST to FETCH a best config, not accept contributions.
 *   - /api/game-runs    : GET to read compatibility counts.
 *   - /api/game-run     : POST to submit "user played + rated" feedback.
 *
 * The honest, working path is an Android ACTION_SEND share intent:
 *   1. Format the winner as a clean JSON block that matches the
 *      gameNativeConfig schema used in authored-windows-runners.json
 *      (the IIC app's authored config DB at mayusi/IsItCompatible).
 *   2. Include measured stats (avgFps, stability) and device context.
 *   3. Add a GitHub new-issue URL pre-filled with the game + GPU title
 *      so the user can tap "Open GitHub" and paste the JSON directly.
 *
 * This is genuinely useful because:
 *   - The data is REAL (measured empirically on the device).
 *   - The JSON format matches what gets merged into authored-windows-runners.json.
 *   - The user (the maintainer) owns the repo and can merge it themselves.
 * ─────────────────────────────────────────────────────────────────────
 */
object TunerShareUtils {

    /**
     * Builds the shareable text artifact from a sweep outcome.
     *
     * @param context    Android context (for GPU/device info).
     * @param gameName   Human-readable game name (e.g. "Devil May Cry HD Collection").
     * @param appId      Internal app ID (e.g. "STEAM_631510").
     * @param outcome    Completed sweep outcome containing the winner.
     * @param sweepMode  Sweep depth used (QUICK/STANDARD/THOROUGH).
     * @param measurementMode  AUTO or MANUAL.
     * @return [SharePayload] containing formatted text and GitHub pre-fill URL.
     *         Returns null if there is no winner to share.
     */
    fun buildSharePayload(
        context: Context,
        gameName: String,
        appId: String,
        outcome: TunerOutcome,
        sweepMode: SweepMode,
        measurementMode: MeasurementMode,
    ): SharePayload? {
        val winner = outcome.winner ?: return null

        val gpuName = try {
            GPUInformation.getRenderer(context) ?: "Unknown GPU"
        } catch (_: Exception) {
            "Unknown GPU"
        }
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidVersion = Build.VERSION.RELEASE

        // Derive a clean game ID that matches the IIC authored-config ID format.
        // IIC uses: "win:<slug>:gamenative:t2"  where slug is lowercased, spaces→hyphens.
        val gameSlug = gameName
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")

        // Parse the winning ContainerData config fields needed for the gameNativeConfig block.
        val cfg = winner.config

        // Extract dxwrapper version from dxwrapperConfig key-value string.
        val dxwrapperVersion = if (cfg.dxwrapperConfig.isNotEmpty()) {
            try {
                KeyValueSet(cfg.dxwrapperConfig).get("version", "")
            } catch (_: Exception) { "" }
        } else ""

        // Extract adrenotools turnip flag from graphicsDriverConfig.
        val adrenotoolsTurnip = if (cfg.graphicsDriverConfig.isNotEmpty()) {
            try {
                KeyValueSet(cfg.graphicsDriverConfig).get("adrenotoolsTurnip", "0")
            } catch (_: Exception) { "0" }
        } else "0"

        // Build the gameNativeConfig JSON block that matches authored-windows-runners.json schema.
        val gameNativeConfigJson = JSONObject().apply {
            put("id", appId.uppercase())
            put("name", gameName)
            put("graphicsDriver", cfg.graphicsDriver)
            if (cfg.graphicsDriverVersion.isNotEmpty()) put("graphicsDriverVersion", cfg.graphicsDriverVersion)
            if (cfg.graphicsDriverConfig.isNotEmpty()) put("graphicsDriverConfig", cfg.graphicsDriverConfig)
            put("dxwrapper", cfg.dxwrapper)
            if (cfg.dxwrapperConfig.isNotEmpty()) put("dxwrapperConfig", cfg.dxwrapperConfig)
            put("box64Preset", cfg.box64Preset)
            if (cfg.box64Version.isNotEmpty()) put("box64Version", cfg.box64Version)
            put("containerVariant", cfg.containerVariant)
            put("wineVersion", cfg.wineVersion)
            put("videoMemorySize", cfg.videoMemorySize)
            if (cfg.envVars.isNotEmpty()) put("envVars", cfg.envVars)
            put("cpuList", cfg.cpuList)
            if (cfg.wow64Mode) put("wow64Mode", true)
            if (cfg.wincomponents.isNotEmpty()) put("wincomponents", cfg.wincomponents)
        }

        // Build the tuner result stats block.
        val statsJson = JSONObject().apply {
            put("goal", outcome.goal.name)
            put("sweepMode", sweepMode.name)
            put("measurementMode", measurementMode.name)
            put("avgFps", winner.avgFps.toDouble())
            put("minFps", winner.minFps.toDouble())
            put("maxFps", winner.maxFps.toDouble())
            put("stabilityPct", (100f - winner.fpsStdDev.coerceAtMost(100f)).toInt())
            put("runsCompleted", winner.runsCompleted)
            if (winner.throttleSuspect) put("throttleSuspectDuringTrial", true)
            if (winner.outlierFlagged) put("outlierFlagged", true)
            put("trialsCompleted", outcome.completedTrials)
            put("trialsTested", outcome.totalTrials)
        }

        // Build the device block.
        val deviceJson = JSONObject().apply {
            put("device", deviceModel)
            put("gpu", gpuName)
            put("android", androidVersion)
        }

        // Full contribution block — this is the canonical shape for a future merge
        // into authored-windows-runners.json.
        val contributionJson = JSONObject().apply {
            put("id", "win:${gameSlug}:gamenative:tuner")
            put("gameId", "win:${gameSlug}")
            put("emulatorId", "gamenative")
            put("tier", 3)  // tier 3 = device-measured, not yet author-reviewed
            put("sourceLabel", "Auto-tuner result (empirical, menu/early-game only)")
            put("device", deviceJson)
            put("tunerStats", statsJson)
            put("gameNativeConfig", gameNativeConfigJson)
        }

        // Human-readable summary.
        val stabilityPct = (100f - winner.fpsStdDev.coerceAtMost(100f)).toInt()
        val humanSummary = buildString {
            appendLine("# GameNative Auto-tuner Result")
            appendLine()
            appendLine("Game:   $gameName  ($appId)")
            appendLine("Device: $deviceModel  |  GPU: $gpuName  |  Android $androidVersion")
            appendLine("Goal:   ${outcome.goal.label}  |  Sweep: ${sweepMode.label}")
            appendLine()
            appendLine("## Winning Config")
            appendLine("  Description: ${winner.description}")
            appendLine("  Graphics driver:  ${cfg.graphicsDriver}" +
                if (adrenotoolsTurnip == "1") " + Adrenotools Turnip" else "")
            if (cfg.graphicsDriverVersion.isNotEmpty())
                appendLine("  Driver version:   ${cfg.graphicsDriverVersion}")
            appendLine("  DX wrapper:       ${cfg.dxwrapper}" +
                if (dxwrapperVersion.isNotEmpty()) " $dxwrapperVersion" else "")
            appendLine("  Box64 preset:     ${cfg.box64Preset}")
            appendLine("  Container:        ${cfg.containerVariant}  |  Wine: ${cfg.wineVersion}")
            appendLine("  Video memory:     ${cfg.videoMemorySize} MB")
            appendLine()
            appendLine("## Measured Performance  (menu / early-game only)")
            appendLine("  Avg FPS: ${winner.avgFps.toInt()}" +
                "  |  Min: ${winner.minFps.toInt()}" +
                "  |  Max: ${winner.maxFps.toInt()}")
            appendLine("  Stability: ${stabilityPct}%  |  Runs averaged: ${winner.runsCompleted}")
            if (winner.throttleSuspect)
                appendLine("  NOTE: Thermal throttling suspected during this trial.")
            if (winner.outlierFlagged)
                appendLine("  NOTE: Run-to-run FPS varied >40% — one run may be a thermal artifact.")
            appendLine()
            appendLine("IMPORTANT: These measurements reflect menu/early-game FPS only on ONE")
            appendLine("device at the time of the sweep. In-game performance may differ.")
            appendLine()
            appendLine("## IIC-Compatible Config JSON (paste into authored-windows-runners.json)")
            appendLine()
            appendLine(contributionJson.toString(2))
        }

        // GitHub new-issue pre-fill URL targeting mayusi/IsItCompatible.
        // The body is URL-encoded and pre-fills the issue template.
        val issueTitle = "Tuner result: $gameName on $gpuName"
        val issueBody = buildString {
            appendLine("**Device:** $deviceModel")
            appendLine("**GPU:** $gpuName")
            appendLine("**Android:** $androidVersion")
            appendLine("**Goal:** ${outcome.goal.label} | **Sweep:** ${sweepMode.name}")
            appendLine()
            appendLine("**Measured FPS** (menu/early-game only):")
            appendLine("Avg ${winner.avgFps.toInt()} | Min ${winner.minFps.toInt()} | Max ${winner.maxFps.toInt()} | Stability ${stabilityPct}%")
            appendLine()
            appendLine("**Config JSON** (matches gameNativeConfig schema in authored-windows-runners.json):")
            appendLine()
            appendLine("```json")
            append(contributionJson.toString(2))
            appendLine()
            appendLine("```")
            appendLine()
            appendLine("*Generated by the GameNative IIC auto-tuner — results are empirical but reflect menu/early-game only.*")
        }

        // URL-encode the issue body for the GitHub link.
        val encodedTitle = java.net.URLEncoder.encode(issueTitle, "UTF-8")
        val encodedBody = java.net.URLEncoder.encode(issueBody, "UTF-8")
        val githubIssueUrl =
            "https://github.com/mayusi/IsItCompatible/issues/new" +
                "?title=${encodedTitle}" +
                "&body=${encodedBody}" +
                "&labels=tuner-result"

        return SharePayload(
            shareText = humanSummary,
            githubIssueUrl = githubIssueUrl,
            gameName = gameName,
            gpuName = gpuName,
        )
    }

    /**
     * Fires an Android ACTION_SEND intent so the user can share the payload
     * via any installed app (clipboard, messaging, email, Discord, etc.).
     */
    fun shareViaIntent(context: Context, payload: SharePayload) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GameNative tuner result: ${payload.gameName} on ${payload.gpuName}")
            putExtra(Intent.EXTRA_TEXT, payload.shareText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Share tuner result").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    data class SharePayload(
        /** Full human-readable + JSON text to share. */
        val shareText: String,
        /** Pre-filled GitHub new-issue URL. Open via Intent.ACTION_VIEW. */
        val githubIssueUrl: String,
        val gameName: String,
        val gpuName: String,
    )
}
