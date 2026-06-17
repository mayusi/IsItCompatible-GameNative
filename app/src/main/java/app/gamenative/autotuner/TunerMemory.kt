package app.gamenative.autotuner

import android.content.Context
import android.os.Build
import com.winlator.container.ContainerData
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * TunerMemory — a small, device-level persisted learning store for the auto-tuner.
 *
 * Backed by a single JSON blob at `context.filesDir/autotuner_memory_v1.json`, written
 * atomically (temp + rename) and parsed defensively. The whole store degrades to "empty"
 * on any read/parse error so a corrupt or absent file can never throw into a live sweep —
 * the engine simply behaves exactly as it did before learning existed.
 *
 * It records two independent things:
 *
 *   1. FIX PRIORS — for each [FixLadder.FailureClass], which fix rung id historically
 *      turned that class of crash into a STABLE / booting result on THIS device, with
 *      win/loss counts so a stale prior (after a driver or game update) self-heals as
 *      losses accumulate. Read via [preferredRungFor] (best-first), written via
 *      [recordWin]/[recordLoss] from the crash-fix chain in AutoTunerEngine.
 *
 *   2. GAME CONFIGS — for each appId, the winning flat config JSON + avgFps + timestamp,
 *      so a future sweep of the same game (or a device-nearest-neighbour game) can
 *      warm-start from it instead of from the bare default. Written via [recordWin] at
 *      applyWinner; read via [bestConfigForGame] / [deviceNearestGoodConfig].
 *
 * Schema (autotuner_memory_v1.json):
 * {
 *   "version": 1,
 *   "deviceFingerprint": "<brand/model/device/sdk>",
 *   "fixPriors": {
 *     "<FailureClass.name>": { "<rungId>": { "wins": <int>, "losses": <int> }, ... },
 *     ...
 *   },
 *   "gameConfigs": {
 *     "<appId>": { "config": { <flat config json> }, "avgFps": <double>,
 *                  "appliedFixes": [ ... ], "ts": <long> },
 *     ...
 *   }
 * }
 *
 * NOTE: The flat config JSON uses the SAME key set as
 * [app.gamenative.iic.AutoTunerResultBroadcaster] emits for the GameNative
 * `<Game>_config.json` schema, so the two serialization formats stay aligned.
 */
object TunerMemory {

    private const val TAG = "TunerMemory"
    private const val FILE_NAME = "autotuner_memory_v1.json"
    private const val SCHEMA_VERSION = 1

    // Cap how many game configs we keep so the blob can't grow without bound. Oldest by
    // timestamp are dropped first. Generous — this is small text per entry.
    private const val MAX_GAME_CONFIGS = 200

    // -------------------------------------------------------------------------
    // Public read API
    // -------------------------------------------------------------------------

    /**
     * Returns the learned-prior rung ids for [failureClass], best-first.
     *
     * Ordering: by (wins - losses) descending, then by wins descending. Rungs whose net
     * score is <= 0 (more losses than wins, i.e. a fully-decayed prior) are excluded so a
     * known-bad rung is never preferred. Returns an empty list when no prior exists, in
     * which case the caller falls back to the static LADDER order unchanged.
     */
    fun preferredRungFor(context: Context, failureClass: FixLadder.FailureClass): List<String> {
        return try {
            val root = read(context)
            val priors = root.optJSONObject("fixPriors") ?: return emptyList()
            val classObj = priors.optJSONObject(failureClass.name) ?: return emptyList()
            val scored = ArrayList<Triple<String, Int, Int>>() // rungId, net, wins
            val keys = classObj.keys()
            while (keys.hasNext()) {
                val rungId = keys.next()
                val rec = classObj.optJSONObject(rungId) ?: continue
                val wins = rec.optInt("wins", 0)
                val losses = rec.optInt("losses", 0)
                val net = wins - losses
                if (net > 0) scored.add(Triple(rungId, net, wins))
            }
            scored.sortWith(
                compareByDescending<Triple<String, Int, Int>> { it.second }
                    .thenByDescending { it.third },
            )
            scored.map { it.first }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "preferredRungFor failed — returning empty (no prior)")
            emptyList()
        }
    }

    /**
     * Returns the winning [ContainerData] previously learned for [appId] on this device,
     * or null if none is stored / parse fails.
     *
     * Reconstructs the config by starting from [ContainerData] defaults and overlaying the
     * stored flat-config keys, so missing/extra keys never throw.
     */
    fun bestConfigForGame(context: Context, appId: String): ContainerData? {
        return try {
            val root = read(context)
            val games = root.optJSONObject("gameConfigs") ?: return null
            val entry = games.optJSONObject(appId) ?: return null
            val configJson = entry.optJSONObject("config") ?: return null
            configFromFlatJson(configJson)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "bestConfigForGame failed for $appId — returning null")
            null
        }
    }

    /**
     * Returns the best-known good config across ALL games stored for this device, intended
     * as a device-level warm-start seed for an unknown game. "Best" = highest stored avgFps.
     * Returns null if the store is empty / parse fails.
     *
     * This is a coarse nearest-neighbour: same device, any game. It only helps once at least
     * one real sweep has populated the store, and the caller (flagged warm-start wiring) is
     * responsible for deciding whether to trust it.
     */
    fun deviceNearestGoodConfig(context: Context): ContainerData? {
        return try {
            val root = read(context)
            val games = root.optJSONObject("gameConfigs") ?: return null
            var bestFps = Double.NEGATIVE_INFINITY
            var bestConfigJson: JSONObject? = null
            val keys = games.keys()
            while (keys.hasNext()) {
                val entry = games.optJSONObject(keys.next()) ?: continue
                val fps = entry.optDouble("avgFps", 0.0)
                val cfg = entry.optJSONObject("config") ?: continue
                if (fps > bestFps) {
                    bestFps = fps
                    bestConfigJson = cfg
                }
            }
            bestConfigJson?.let { configFromFlatJson(it) }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "deviceNearestGoodConfig failed — returning null")
            null
        }
    }

    // -------------------------------------------------------------------------
    // Public write API — fix priors
    // -------------------------------------------------------------------------

    /**
     * Records that [rungId] turned a [failureClass] crash into a STABLE / booting result
     * (wins++). Best-effort: never throws.
     */
    fun recordWin(context: Context, failureClass: FixLadder.FailureClass, rungId: String) {
        bumpPrior(context, failureClass, rungId, winDelta = 1, lossDelta = 0)
    }

    /**
     * Records that [rungId] was applied for a [failureClass] crash but the retry still
     * failed (losses++), so a stale prior decays. Best-effort: never throws.
     */
    fun recordLoss(context: Context, failureClass: FixLadder.FailureClass, rungId: String) {
        bumpPrior(context, failureClass, rungId, winDelta = 0, lossDelta = 1)
    }

    // -------------------------------------------------------------------------
    // Public write API — game configs
    // -------------------------------------------------------------------------

    /**
     * Records the winning [config] for [appId] on this device, for future warm-starts.
     * Best-effort: never throws. Keeps at most [MAX_GAME_CONFIGS] entries (oldest dropped).
     */
    fun recordWin(
        context: Context,
        appId: String,
        config: ContainerData,
        appliedFixes: List<String>,
        avgFps: Float,
    ) {
        try {
            val root = read(context)
            val games = root.optJSONObject("gameConfigs") ?: JSONObject()
            val entry = JSONObject().apply {
                put("config", flatConfigJson(config))
                put("avgFps", avgFps.toDouble())
                put("appliedFixes", org.json.JSONArray(appliedFixes))
                put("ts", System.currentTimeMillis())
            }
            games.put(appId, entry)
            trimGameConfigs(games)
            root.put("gameConfigs", games)
            write(context, root)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "recordWin(game) failed for $appId (non-fatal)")
        }
    }

    // -------------------------------------------------------------------------
    // Internal — prior bump
    // -------------------------------------------------------------------------

    private fun bumpPrior(
        context: Context,
        failureClass: FixLadder.FailureClass,
        rungId: String,
        winDelta: Int,
        lossDelta: Int,
    ) {
        try {
            val root = read(context)
            val priors = root.optJSONObject("fixPriors") ?: JSONObject()
            val classObj = priors.optJSONObject(failureClass.name) ?: JSONObject()
            val rec = classObj.optJSONObject(rungId) ?: JSONObject()
            rec.put("wins", rec.optInt("wins", 0) + winDelta)
            rec.put("losses", rec.optInt("losses", 0) + lossDelta)
            classObj.put(rungId, rec)
            priors.put(failureClass.name, classObj)
            root.put("fixPriors", priors)
            write(context, root)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "bumpPrior failed for ${failureClass.name}/$rungId (non-fatal)")
        }
    }

    /** Drops oldest game-config entries (by ts) until at most [MAX_GAME_CONFIGS] remain. */
    private fun trimGameConfigs(games: JSONObject) {
        if (games.length() <= MAX_GAME_CONFIGS) return
        val entries = ArrayList<Pair<String, Long>>()
        val keys = games.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val ts = games.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
            entries.add(k to ts)
        }
        entries.sortBy { it.second } // oldest first
        val toRemove = entries.size - MAX_GAME_CONFIGS
        for (i in 0 until toRemove) {
            games.remove(entries[i].first)
        }
    }

    // -------------------------------------------------------------------------
    // Internal — file IO (atomic write, empty-on-error read)
    // -------------------------------------------------------------------------

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /**
     * Reads the store. Returns a fresh empty root (correct version + device fingerprint)
     * on any error or absence, so callers never see an exception from a missing/corrupt file.
     */
    private fun read(context: Context): JSONObject {
        val f = file(context)
        if (!f.exists()) return emptyRoot()
        return try {
            val text = f.readText(Charsets.UTF_8)
            if (text.isBlank()) emptyRoot() else JSONObject(text)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "read failed / corrupt — degrading to empty store")
            emptyRoot()
        }
    }

    /**
     * Writes [root] atomically: serialize to a temp file in the same directory, then rename
     * over the target. A crash mid-write leaves the previous good file (or no file) intact,
     * never a half-written one — matching risk R5's atomic-write requirement.
     */
    private fun write(context: Context, root: JSONObject) {
        try {
            if (!root.has("version")) root.put("version", SCHEMA_VERSION)
            if (!root.has("deviceFingerprint")) root.put("deviceFingerprint", deviceFingerprint())
            val target = file(context)
            val tmp = File(target.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                // Fallback: renameTo can fail on some filesystems — copy then delete temp.
                target.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "write failed (non-fatal) — memory not persisted this time")
        }
    }

    private fun emptyRoot(): JSONObject = JSONObject().apply {
        put("version", SCHEMA_VERSION)
        put("deviceFingerprint", deviceFingerprint())
        put("fixPriors", JSONObject())
        put("gameConfigs", JSONObject())
    }

    private fun deviceFingerprint(): String =
        "${Build.BRAND}/${Build.MODEL}/${Build.DEVICE}/sdk${Build.VERSION.SDK_INT}"

    // -------------------------------------------------------------------------
    // Internal — config (de)serialization (flat GameNative <Game>_config.json schema)
    // -------------------------------------------------------------------------

    /**
     * Serializes the reusable fields of [config] into the flat GameNative config schema —
     * the SAME key set [app.gamenative.iic.AutoTunerResultBroadcaster.buildConfigJson]
     * emits. Instance-specific fields (name/id/installPath/executablePath/drives) are blanked
     * so a stored config is clean and reusable as a warm-start seed.
     */
    private fun flatConfigJson(config: ContainerData): JSONObject = JSONObject().apply {
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
        // Reusable config fields
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
    }

    /**
     * Reconstructs a [ContainerData] from a flat-config JSON object by overlaying stored keys
     * onto the defaults. Missing keys keep their default value; unknown keys are ignored.
     * Never throws — returns at least a default ContainerData.
     */
    private fun configFromFlatJson(json: JSONObject): ContainerData {
        val d = ContainerData()
        return d.copy(
            emulator = json.optString("emulator", d.emulator),
            wineVersion = json.optString("wineVersion", d.wineVersion),
            graphicsDriver = json.optString("graphicsDriver", d.graphicsDriver),
            dxwrapper = json.optString("dxwrapper", d.dxwrapper),
            screenSize = json.optString("screenSize", d.screenSize),
            envVars = json.optString("envVars", d.envVars),
            cpuList = json.optString("cpuList", d.cpuList),
            cpuListWoW64 = json.optString("cpuListWoW64", d.cpuListWoW64),
            graphicsDriverVersion = json.optString("graphicsDriverVersion", d.graphicsDriverVersion),
            graphicsDriverConfig = json.optString("graphicsDriverConfig", d.graphicsDriverConfig),
            dxwrapperConfig = json.optString("dxwrapperConfig", d.dxwrapperConfig),
            audioDriver = json.optString("audioDriver", d.audioDriver),
            wincomponents = json.optString("wincomponents", d.wincomponents),
            wow64Mode = json.optBoolean("wow64Mode", d.wow64Mode),
            containerVariant = json.optString("containerVariant", d.containerVariant),
            box64Version = json.optString("box64Version", d.box64Version),
            box64Preset = json.optString("box64Preset", d.box64Preset),
            box86Version = json.optString("box86Version", d.box86Version),
            box86Preset = json.optString("box86Preset", d.box86Preset),
            fexcoreVersion = json.optString("fexcoreVersion", d.fexcoreVersion),
            fexcorePreset = json.optString("fexcorePreset", d.fexcorePreset),
            execArgs = json.optString("execArgs", d.execArgs),
        )
    }
}
