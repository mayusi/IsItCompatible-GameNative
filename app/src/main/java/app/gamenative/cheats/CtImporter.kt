package app.gamenative.cheats

import app.gamenative.data.GameSource
import org.w3c.dom.Element
import org.w3c.dom.Node
import timber.log.Timber
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Imports a Cheat Engine **.CT** table (its open XML format) into the app's own
 * [CheatTable] schema.
 *
 * WHY .CT AND NOT FLiNG:
 *   FLiNG trainers ship as compiled `.exe` files — they cannot be parsed or run on
 *   Android/Box64. Cheat Engine `.CT` tables are the OPEN XML format the SAME community
 *   cheats are shared in (most "FLiNG-style" cheats originate as, or have an equivalent,
 *   CE table). So "incorporate FLiNG" realistically means "import CE .CT tables", which
 *   this does.
 *
 * WHAT MAPS (works on our engine):
 *   - **Pointer-chain entries** (`<Address>module+base</Address>` + `<Offsets>`) -> [ChainSpec]
 *     with a pointer walk. See the OFFSET-DIRECTION note below — this is the load-bearing
 *     correctness detail.
 *   - **Static module+offset entries** (`<Address>module.dll+0x1234</Address>`, no offsets)
 *     -> [ChainSpec] with an empty offsets list (recipe kind "static").
 *   - **AOB / AA-script entries from which a clean `aobscan(...)` + offset can be extracted**
 *     -> an [AobPattern] mapped to the new aob round-trip (Part A/B). We parse the
 *     `aobscanmodule`/`aobscan` line and a trailing `+offset` out of an [AssemblerScript].
 *
 * WHAT IS SKIPPED (with a reason, surfaced to the user):
 *   - Entries with an [AssemblerScript] (Auto-Assembler) or Lua that we CANNOT reduce to a
 *     simple aobscan+offset+value-freeze (code injection / mono / custom logic can't run here).
 *   - Bare ABSOLUTE static addresses with no module (ASLR-relocated under Box64 -> unreliable).
 *   - Unsupported value types: f64/Double, strings, byte arrays, custom types (we support
 *     i32 / u32 / f32 only — same set the chain engine accepts).
 *   - Group/header rows with no address and no children worth importing.
 *
 * -----------------------------------------------------------------------------
 * OFFSET DIRECTION — verified against [chain_resolve] in cheat.c (DO NOT GUESS).
 * -----------------------------------------------------------------------------
 * Our engine ([chain_resolve]) resolves a chain as:
 *     ptr = *(moduleBase + base)            // first deref
 *     for off in offsets:  ptr = *(ptr + off)   // deref at EACH level, in list order
 *     valueAddr = ptr + valueOffset         // final add, NOT dereferenced
 * So our `offsets` are applied OUTERMOST-FIRST (the offset closest to the module base
 * comes first in the list), and `valueOffset` is the LAST hop to the value.
 *
 * Cheat Engine stores a pointer in `.CT` as `<Offsets>` where the offsets are listed
 * INNERMOST-FIRST: `<Offset>` element [0] is the offset added at the FINAL dereference
 * (closest to the value), and the LAST `<Offset>` element is applied to the base pointer
 * first. CE resolves it as:
 *     addr = baseAddress
 *     for off in offsets REVERSED:  addr = *(addr) + off
 *     value is at the final addr   (the [0] offset is the last one added; no final deref)
 *
 * Reconciling the two: CE's reversed list IS our forward list, and CE's offset[0] (the
 * innermost, last-added, points straight at the value) is OUR [valueOffset]. Therefore:
 *
 *   ourValueOffset = ceOffsets.first()                       // CE innermost == our final add
 *   ourOffsets     = ceOffsets.drop(1).reversed()            // the rest, reversed to outer-first
 *   ourBase        = the module-relative base from <Address>
 *
 * Concretely, for a CE entry  Address = "game.exe+0xABC", Offsets = [0x10, 0x20, 0x30]:
 *   CE means:  v = [ [ [game.exe+0xABC] + 0x30 ] + 0x20 ] + 0x10
 *   We encode: base=0xABC, offsets=[0x30,0x20], valueOffset=0x10
 *   chain_resolve:  ptr=*(mod+0xABC); ptr=*(ptr+0x30); ptr=*(ptr+0x20); value=ptr+0x10  ✓ identical
 *
 * A CE entry with ZERO offsets (a plain `module+base`) maps to base=0xABC,
 * offsets=[], valueOffset=0 (recipe kind "static").
 * -----------------------------------------------------------------------------
 */
object CtImporter {

    private const val TAG = "CtImporter"

    /** Hard cap on the .CT file size we will parse (defensive — matches the task spec). */
    const val MAX_CT_BYTES = 2 * 1024 * 1024 // 2 MB

    /** A single entry we could NOT import, with a human-readable reason for the UI. */
    data class SkippedEntry(val description: String, val reason: String)

    /** Full result of an import: the table (possibly with 0 cheats) + the skip list. */
    data class ImportResult(
        val table: CheatTable,
        val skipped: List<SkippedEntry>,
    ) {
        val importedCount: Int get() = table.cheats.size
        val skippedCount: Int get() = skipped.size
    }

    /**
     * Parse a `.CT` XML byte payload into an [ImportResult] for [source]/[gameId].
     *
     * Defensive: a malformed individual `<CheatEntry>` is skipped (logged + added to the
     * skip list), the rest import. Returns null only when the whole document is unparseable
     * or exceeds [MAX_CT_BYTES] (the caller surfaces "couldn't read this .CT file").
     *
     * @param xmlBytes The raw `.CT` file contents.
     * @param source   The store source to key the resulting table under (e.g. STEAM).
     * @param gameId   The registry game id to key the resulting table under.
     * @param title    Display title for the imported table (fallback derived from gameId).
     */
    fun import(
        xmlBytes: ByteArray,
        source: GameSource,
        gameId: String,
        title: String,
    ): ImportResult? {
        if (xmlBytes.size > MAX_CT_BYTES) {
            Timber.tag(TAG).w("CT file too large (${xmlBytes.size} bytes > $MAX_CT_BYTES) — rejecting")
            return null
        }
        val doc = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                // Harden the XML parser against XXE / entity-expansion (untrusted file).
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse .CT XML")
            return null
        }

        val root = doc.documentElement ?: return null
        // Accept either <CheatTable>...<CheatEntries> or a bare <CheatEntries> root.
        val entriesContainer: Element = when {
            root.tagName.equals("CheatEntries", true) -> root
            else -> firstChildElement(root, "CheatEntries") ?: run {
                Timber.tag(TAG).w("No <CheatEntries> in .CT — nothing to import")
                return ImportResult(CheatTable(source, gameId, title, emptyList()), emptyList())
            }
        }

        val cheats = mutableListOf<Cheat>()
        val skipped = mutableListOf<SkippedEntry>()
        val usedIds = mutableSetOf<String>()

        // Walk top-level <CheatEntry> nodes, recursing into <CheatEntries> groups.
        collectEntries(entriesContainer, cheats, skipped, usedIds)

        Timber.tag(TAG).i("CT import: ${cheats.size} cheats, ${skipped.size} skipped")
        return ImportResult(
            table = CheatTable(source = source, gameId = gameId, title = title, cheats = cheats),
            skipped = skipped,
        )
    }

    // -------------------------------------------------------------------------
    // Entry walking
    // -------------------------------------------------------------------------

    /** Recursively process <CheatEntry> children of [container] (a <CheatEntries>). */
    private fun collectEntries(
        container: Element,
        cheats: MutableList<Cheat>,
        skipped: MutableList<SkippedEntry>,
        usedIds: MutableSet<String>,
    ) {
        val children = container.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (!el.tagName.equals("CheatEntry", true)) continue
            try {
                processEntry(el, cheats, skipped, usedIds)
            } catch (e: Exception) {
                val desc = textOf(el, "Description")?.trim()?.trim('"') ?: "(unnamed)"
                Timber.tag(TAG).w(e, "Skipping malformed CheatEntry '$desc'")
                skipped.add(SkippedEntry(desc, "Malformed entry (${e.message ?: "parse error"})"))
            }
        }
    }

    /** Process one <CheatEntry>: import it if mappable, else add to [skipped]. Recurses into groups. */
    private fun processEntry(
        el: Element,
        cheats: MutableList<Cheat>,
        skipped: MutableList<SkippedEntry>,
        usedIds: MutableSet<String>,
    ) {
        val description = (textOf(el, "Description") ?: "").trim().trim('"').ifBlank { "(unnamed cheat)" }

        // Nested group: a <CheatEntry> may itself contain a <CheatEntries> of children.
        val nested = firstChildElement(el, "CheatEntries")

        val assembler = firstChildElement(el, "AssemblerScript")
        val variableType = (textOf(el, "VariableType") ?: "").trim()
        val addressText = (textOf(el, "Address") ?: "").trim()

        // --- Auto-Assembler / Lua entries: try to salvage a simple AOB; else skip. ---
        if (assembler != null && assembler.textContent.isNotBlank()) {
            val salvaged = trySalvageAobFromScript(assembler.textContent, description, variableType)
            if (salvaged != null) {
                addCheat(cheats, usedIds, salvaged)
            } else {
                skipped.add(SkippedEntry(description, "Auto-Assembler / script cheat — can't run on this engine"))
            }
            // AA entries don't also carry a plain address; recurse into any children and return.
            nested?.let { collectEntries(it, cheats, skipped, usedIds) }
            return
        }

        // --- A row with no address: either a group header (recurse) or a non-importable label. ---
        if (addressText.isBlank()) {
            if (nested != null) {
                collectEntries(nested, cheats, skipped, usedIds)
            } else {
                skipped.add(SkippedEntry(description, "No address (group header or label)"))
            }
            return
        }

        // --- Value type gate (we support i32 / u32 / f32 only). ---
        val vtype = mapVariableType(variableType)
        if (vtype == null) {
            skipped.add(
                SkippedEntry(
                    description,
                    "Unsupported type '${variableType.ifBlank { "?" }}' (only 4 Bytes / Float supported; " +
                        "Double, strings, arrays, 8-byte and custom types are skipped)",
                ),
            )
            nested?.let { collectEntries(it, cheats, skipped, usedIds) }
            return
        }

        // --- Parse the address: "module.dll"+base, "module.exe+0x..", or a bare hex address. ---
        val parsedAddr = parseAddress(addressText)
        if (parsedAddr == null) {
            skipped.add(SkippedEntry(description, "Unparseable address '$addressText'"))
            nested?.let { collectEntries(it, cheats, skipped, usedIds) }
            return
        }
        if (parsedAddr.module.isBlank()) {
            // Bare absolute address (no module) — unreliable under Box64 ASLR.
            skipped.add(
                SkippedEntry(description, "Bare absolute address (no module) — unreliable under ASLR"),
            )
            nested?.let { collectEntries(it, cheats, skipped, usedIds) }
            return
        }

        // --- CE offsets list -> our (offsets, valueOffset) per the verified direction. ---
        val ceOffsets = parseCeOffsets(el)
        val chain = ceOffsetsToChain(parsedAddr.module, parsedAddr.base, ceOffsets)

        val kind = if (chain.offsets.isEmpty()) "static" else "pointer_chain"

        // Default freeze value: max-ish for the type. The CT itself rarely carries a desired
        // value; the user can re-aim it, but a sensible default makes the toggle immediately useful.
        val freezeValue = defaultFreezeValue(vtype)

        val cheat = Cheat(
            id = makeId(usedIds, description),
            name = description,
            category = "imported",
            vtype = vtype,
            recipe = CheatRecipe(
                kind = kind,
                prompt = "Imported from a Cheat Engine table.",
                narrowPrompt = null,
                freezeValue = freezeValue,
            ),
            aob = null,
            chain = chain,
        )
        cheats.add(cheat)

        // A pointer entry can ALSO have nested children — import them too.
        nested?.let { collectEntries(it, cheats, skipped, usedIds) }
    }

    private fun addCheat(cheats: MutableList<Cheat>, usedIds: MutableSet<String>, proto: Cheat) {
        cheats.add(proto.copy(id = makeId(usedIds, proto.name)))
    }

    // -------------------------------------------------------------------------
    // Address + offsets parsing
    // -------------------------------------------------------------------------

    /** A parsed CE <Address>: a module name (possibly empty) + a base offset/address. */
    private data class ParsedAddress(val module: String, val base: Long)

    /**
     * Parse a CE address expression.
     *   "Tutorial-x86_64.exe+1AB30C"  -> module="Tutorial-x86_64.exe", base=0x1AB30C
     *   "game.dll+0x1234"             -> module="game.dll", base=0x1234
     *   "01AB30C0"                    -> module="", base=0x1AB30C0   (bare absolute)
     *   "\"game.exe\"+10"             -> quotes tolerated
     * CE address values are hex (no 0x prefix is the CE norm; "0x" is also tolerated).
     * Only the simple `module(+base)?` form is supported — complex multi-term expressions
     * (e.g. "[base]+x", arithmetic with more than one '+') return null and the entry is skipped.
     */
    private fun parseAddress(raw: String): ParsedAddress? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // CE sometimes wraps the module in quotes when it contains spaces.
        s = s.replace("\"", "")

        // Reject expressions with dereferences/brackets or more than one '+' term — too complex.
        if (s.contains('[') || s.contains(']')) return null

        val plusIdx = s.indexOf('+')
        return if (plusIdx < 0) {
            // No '+': either a bare module name (no offset, base 0) or a bare hex address.
            if (looksLikeHex(s)) {
                ParsedAddress(module = "", base = parseHex(s) ?: return null)
            } else {
                // A pure module reference with no offset.
                ParsedAddress(module = s, base = 0L)
            }
        } else {
            val left = s.substring(0, plusIdx).trim()
            val right = s.substring(plusIdx + 1).trim()
            // Disallow a second '+' (multi-term arithmetic).
            if (right.contains('+')) return null
            val base = parseHex(right) ?: return null
            // If the left side is itself hex (no module), treat as bare absolute (module empty).
            if (left.isEmpty()) return null
            if (looksLikeHex(left) && !left.contains('.')) {
                // "1AB30C+10" — both hex; treat as a bare absolute base (no module).
                val b = parseHex(left) ?: return null
                ParsedAddress(module = "", base = b + base)
            } else {
                ParsedAddress(module = left, base = base)
            }
        }
    }

    /** Read the CE <Offsets> list (innermost-first, as CE stores it) as raw hex Longs. */
    private fun parseCeOffsets(entry: Element): List<Long> {
        val offsetsEl = firstChildElement(entry, "Offsets") ?: return emptyList()
        val out = mutableListOf<Long>()
        val kids = offsetsEl.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType != Node.ELEMENT_NODE) continue
            val e = n as Element
            if (!e.tagName.equals("Offset", true)) continue
            val v = parseHex(e.textContent.trim()) ?: continue
            out.add(v)
        }
        return out
    }

    /**
     * Convert a CE offsets list (innermost-first) + module/base into our [ChainSpec], applying
     * the verified direction transform documented at the top of this file:
     *   ourValueOffset = ceOffsets.first()
     *   ourOffsets     = ceOffsets.drop(1).reversed()
     */
    private fun ceOffsetsToChain(module: String, base: Long, ceOffsets: List<Long>): ChainSpec {
        if (ceOffsets.isEmpty()) {
            return ChainSpec(module = module, base = base, offsets = emptyList(), valueOffset = 0L)
        }
        val valueOffset = ceOffsets.first()
        val ourOffsets = ceOffsets.drop(1).reversed()
        return ChainSpec(
            module = module,
            base = base,
            offsets = ourOffsets,
            valueOffset = valueOffset,
        )
    }

    // -------------------------------------------------------------------------
    // Auto-Assembler salvage (best-effort AOB extraction)
    // -------------------------------------------------------------------------

    /**
     * Try to reduce an Auto-Assembler script to a simple aobscan-able cheat.
     *
     * Many AA scripts begin with `aobscanmodule(label, module.exe, AA BB ?? CC ...)` to locate
     * a code site. If the script is essentially "find this signature, then the value/code is at
     * label+N", we can map it to our aob round-trip (Part A/B) — an [AobPattern] with the parsed
     * signature and an extracted `+offset`.
     *
     * We ONLY salvage the simple case (single aobscan, an optional `label+N` reference). Anything
     * with code injection (`alloc`, `newmem`, `registersymbol`, mono/luacall, multiple aobscans)
     * is genuinely un-runnable here and returns null so the caller skips it with a reason.
     *
     * The resulting cheat uses recipe kind "aob_freeze" with [AobPattern] populated; the executor
     * already supports aob_freeze, and the address is resolved at activation time.
     */
    private fun trySalvageAobFromScript(script: String, description: String, variableType: String): Cheat? {
        val lower = script.lowercase()
        // Bail on anything that needs real code injection / scripting.
        if (lower.contains("alloc(") || lower.contains("newmem") || lower.contains("registersymbol") ||
            lower.contains("mono_") || lower.contains("luacall") || lower.contains("createthread") ||
            lower.contains("loadlibrary")
        ) {
            return null
        }
        // Require exactly one aobscan/aobscanmodule.
        val aobRegex = Regex("aobscan(?:module|region)?\\s*\\(([^)]*)\\)", RegexOption.IGNORE_CASE)
        val matches = aobRegex.findAll(script).toList()
        if (matches.size != 1) return null

        // aobscanmodule(label, module, BYTES...)  OR  aobscan(label, BYTES...)
        val inner = matches.first().groupValues[1]
        val parts = inner.split(',').map { it.trim() }
        if (parts.size < 2) return null

        // The last argument(s) form the byte pattern; the first is the label, and (for
        // aobscanmodule) the second is the module. Bytes may themselves contain commas? No —
        // CE byte patterns are space-separated, so the pattern is the final comma-field.
        val pattern = parts.last()
        if (!looksLikeAobPattern(pattern)) return null

        // Validate the pattern parses (mirrors TrainerProto.parseAob's accepted token set).
        if (!aobPatternIsValid(pattern)) return null

        // Try to find a trailing offset, e.g. "label+0C:" or "mov [label+10],eax".
        val offset = extractLabelOffset(script) ?: 0

        val vtype = mapVariableType(variableType) ?: "i32"
        return Cheat(
            id = "", // assigned by addCheat
            name = description,
            category = "imported",
            vtype = vtype,
            recipe = CheatRecipe(
                kind = "aob_freeze",
                prompt = "Imported AOB cheat from a Cheat Engine script.",
                narrowPrompt = null,
                freezeValue = defaultFreezeValue(vtype),
            ),
            aob = AobPattern(
                pattern = normalizeAobPattern(pattern),
                offset = offset,
                vtype = vtype,
                patchBytes = null,
            ),
            chain = null,
        )
    }

    /** Heuristic: does this token look like a space-separated AOB byte pattern? */
    private fun looksLikeAobPattern(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return false
        val tokens = t.split(Regex("\\s+"))
        if (tokens.size < 4) return false // too short to be a meaningful signature
        return tokens.all { tok ->
            tok == "?" || tok == "??" || tok == "*" ||
                (tok.length == 2 && tok.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
        }
    }

    /** Validate exactly with the token set TrainerProto.parseAob / cheat.c aob_parse accept. */
    private fun aobPatternIsValid(s: String): Boolean = looksLikeAobPattern(s)

    /** Normalize wildcard spelling to "??" so the round-trip / executor see a canonical form. */
    private fun normalizeAobPattern(s: String): String =
        s.trim().split(Regex("\\s+")).joinToString(" ") { tok ->
            if (tok == "?" || tok == "*" || tok == "??") "??" else tok.uppercase()
        }

    /**
     * Best-effort: pull a trailing offset from the script, e.g. a "label+0C:" anchor or
     * a "[label+10]" memory operand. Returns null if no clear single offset is present
     * (the caller then uses 0).
     */
    private fun extractLabelOffset(script: String): Int? {
        // Look for the most common forms: "<label>+<hex>:" (a relabeled site) or "+<hex>]".
        val plusHex = Regex("\\+\\s*([0-9A-Fa-f]{1,4})\\s*[:\\]]")
        val m = plusHex.find(script) ?: return null
        return m.groupValues[1].toIntOrNull(16)
    }

    // -------------------------------------------------------------------------
    // Type + value helpers
    // -------------------------------------------------------------------------

    /**
     * Map a CE <VariableType> string to our supported vtype, or null if unsupported.
     * Supported: "4 Bytes" -> i32, "Float" -> f32. (We expose u32 only when CE marks it
     * unsigned, which CE doesn't do in VariableType — so 4 Bytes always maps to i32.)
     * Skipped: "Double", "8 Bytes", "2 Bytes", "Byte", "String", "Array of byte", custom.
     */
    private fun mapVariableType(raw: String): String? = when (raw.trim().lowercase()) {
        "4 bytes", "dword", "integer", "int" -> "i32"
        "float" -> "f32"
        else -> null
    }

    /**
     * A sensible default freeze value per type for an imported cheat (user can re-aim).
     *
     * NOTE the encoding contract: this is stored as [CheatRecipe.freezeValue] and consumed by
     * [CheatExecutor.applyChainFreeze], which for f32 does `rawValue.toFloat()` then
     * `floatToRawIntBits(...)`. So this must be the PLAIN numeric value (999999), NOT pre-encoded
     * IEEE-754 bits — identical to how the bundled registry.json stores `"freezeValue": 20000`
     * for f32 cheats. (For guided/scan cheats the same plain-value contract applies via
     * CheatExecutor.encodeValue.)
     */
    private fun defaultFreezeValue(vtype: String): Long = 999999L // plain value; same for i32/u32/f32

    // -------------------------------------------------------------------------
    // ID generation + XML helpers
    // -------------------------------------------------------------------------

    /** Build a stable, unique, lowercase id from a description (prefixed to avoid catalog clashes). */
    private fun makeId(used: MutableSet<String>, description: String): String {
        val slug = description.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "cheat" }
        var candidate = "ct_$slug"
        var n = 2
        while (candidate in used) {
            candidate = "ct_${slug}_$n"
            n++
        }
        used.add(candidate)
        return candidate
    }

    /** First direct child element with [name] (case-insensitive), or null. */
    private fun firstChildElement(parent: Element, name: String): Element? {
        val kids = parent.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tagName.equals(name, true)) return n
        }
        return null
    }

    /** Text content of the first direct child element [name], or null. */
    private fun textOf(parent: Element, name: String): String? =
        firstChildElement(parent, name)?.textContent

    /** True if [s] is a plausible hex literal (with or without 0x), no other chars. */
    private fun looksLikeHex(s: String): Boolean {
        val t = s.removePrefix("0x").removePrefix("0X")
        return t.isNotEmpty() && t.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** Parse a hex literal (with optional 0x) to Long, or null. */
    private fun parseHex(s: String): Long? {
        val t = s.trim().removePrefix("0x").removePrefix("0X")
        if (t.isEmpty()) return null
        return t.toLongOrNull(16)
    }
}
