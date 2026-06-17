package app.gamenative.cheats

import android.content.Context
import app.gamenative.data.GameSource
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * On-device store for USER-IMPORTED cheat tables (e.g. from a Cheat Engine `.CT` file via
 * [CtImporter]).
 *
 * Layout:  filesDir/cheattables_user/<appId-or-key>.json   — one file per game key.
 *
 * Each file is a single serialized [CheatTable] in the SAME JSON shape the bundled
 * registry.json uses for one table, so the serializer/deserializer here is symmetric with
 * [CheatTableLoader]'s parser. Persisting per-game keeps writes small and lets a game's user
 * table survive app restarts, app updates, and OTA catalog refreshes.
 *
 * PRECEDENCE (resolved in [CheatTableLoader.buildAllTables]):
 *     bundled  <  OTA cache  <  USER import
 * i.e. a user-imported table for a game OVERRIDES the bundled/OTA table for that same game
 * key. This is deliberate: if a user went to the trouble of importing a CE table for a game,
 * that is the table they want to see. (Games the user never imported keep the catalog table.)
 *
 * Thread-safety: callers use this from coroutines on IO; the methods are self-contained file
 * IO with defensive try/catch (a corrupt user file is skipped, never crashes the load).
 */
object CheatTableUserStore {

    private const val TAG = "CheatTableUserStore"
    private const val DIR = "cheattables_user"

    /** The directory holding per-game user table files (created on demand). */
    fun storeDir(context: Context): File = File(context.filesDir, DIR)

    /**
     * Sanitize a game key (source+gameId or an appId) into a safe flat filename.
     * Keeps only [A-Za-z0-9._-]; collapses everything else to '_'.
     */
    private fun fileKey(source: GameSource, gameId: String): String =
        "${source.name}_$gameId".replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Persist an imported [table]. Overwrites any existing user table for the same
     * (source, gameId). Returns true on success.
     */
    fun save(context: Context, table: CheatTable): Boolean {
        return try {
            val dir = storeDir(context)
            dir.mkdirs()
            val file = File(dir, "${fileKey(table.source, table.gameId)}.json")
            file.writeText(serialize(table).toString(), Charsets.UTF_8)
            Timber.tag(TAG).i("Saved user cheat table for ${table.source}/${table.gameId} (${table.cheats.size} cheats)")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save user cheat table for ${table.source}/${table.gameId}")
            false
        }
    }

    /** Remove a user table for a game key (true if a file was deleted). */
    fun delete(context: Context, source: GameSource, gameId: String): Boolean {
        return try {
            File(storeDir(context), "${fileKey(source, gameId)}.json").delete()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to delete user cheat table $source/$gameId")
            false
        }
    }

    /**
     * Load ALL user tables, keyed by (source, gameId). A corrupt individual file is skipped.
     * Returns an empty map if the store dir doesn't exist.
     */
    fun loadAll(context: Context): Map<Pair<GameSource, String>, CheatTable> {
        val dir = storeDir(context)
        if (!dir.isDirectory) return emptyMap()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyMap()
        val out = mutableMapOf<Pair<GameSource, String>, CheatTable>()
        for (f in files) {
            try {
                if (f.length() > MAX_USER_FILE_BYTES) {
                    Timber.tag(TAG).w("User table ${f.name} exceeds size cap — skipping")
                    continue
                }
                val table = deserialize(JSONObject(f.readText(Charsets.UTF_8))) ?: continue
                out[table.source to table.gameId] = table
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Skipping corrupt user table ${f.name}")
            }
        }
        return out
    }

    // -------------------------------------------------------------------------
    // (De)serialization — symmetric with CheatTableLoader's parser shape.
    // -------------------------------------------------------------------------

    private const val MAX_USER_FILE_BYTES = 512 * 1024 // 512 KB per user table — generous

    /** Serialize a [CheatTable] to the registry-table JSON shape (one table object). */
    internal fun serialize(table: CheatTable): JSONObject {
        val obj = JSONObject()
        obj.put("source", table.source.name)
        obj.put("gameId", table.gameId)
        obj.put("title", table.title)
        val arr = JSONArray()
        for (c in table.cheats) {
            val co = JSONObject()
            co.put("id", c.id)
            co.put("name", c.name)
            co.put("category", c.category)
            co.put("vtype", c.vtype)
            val ro = JSONObject()
            ro.put("kind", c.recipe.kind)
            ro.put("prompt", c.recipe.prompt)
            if (c.recipe.narrowPrompt != null) ro.put("narrowPrompt", c.recipe.narrowPrompt)
            if (c.recipe.freezeValue != null) ro.put("freezeValue", c.recipe.freezeValue) else ro.put("freezeValue", JSONObject.NULL)
            co.put("recipe", ro)

            if (c.aob != null) {
                val ao = JSONObject()
                ao.put("pattern", c.aob.pattern)
                ao.put("offset", c.aob.offset)
                ao.put("vtype", c.aob.vtype)
                if (c.aob.patchBytes != null) ao.put("patchBytes", c.aob.patchBytes)
                co.put("aob", ao)
            } else {
                co.put("aob", JSONObject.NULL)
            }

            if (c.chain != null) {
                val cho = JSONObject()
                cho.put("module", c.chain.module)
                cho.put("base", "0x" + c.chain.base.toString(16))
                val offs = JSONArray()
                c.chain.offsets.forEach { offs.put("0x" + it.toString(16)) }
                cho.put("offsets", offs)
                cho.put("valueOffset", "0x" + c.chain.valueOffset.toString(16))
                cho.put("writeMax", c.chain.writeMax)
                cho.put("maxOffset", "0x" + c.chain.maxOffset.toString(16))
                co.put("chain", cho)
            } else {
                co.put("chain", JSONObject.NULL)
            }
            arr.put(co)
        }
        obj.put("cheats", arr)
        return obj
    }

    /**
     * Deserialize one table object. Reuses [CheatTableLoader.parse] by wrapping the table in
     * a `{ "tables": [ ... ] }` envelope so we share the exact same defensive cheat parser
     * (no duplicate parse logic). Returns the single parsed table, or null.
     */
    internal fun deserialize(tableObj: JSONObject): CheatTable? {
        val envelope = JSONObject().put("tables", JSONArray().put(tableObj))
        val parsed = CheatTableLoader.parse(envelope.toString())
        return parsed.values.firstOrNull()
    }
}
