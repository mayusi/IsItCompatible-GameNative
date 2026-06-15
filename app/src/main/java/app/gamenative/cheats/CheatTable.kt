package app.gamenative.cheats

import app.gamenative.data.GameSource
import app.gamenative.trainer.TrainerProto

// ---------------------------------------------------------------------------
// Data classes for the cheat-table registry
// ---------------------------------------------------------------------------

/**
 * Describes a pointer-chain or static-address target for catalog-driven cheats.
 *
 * The in-game DLL resolves the address as follows:
 *   1. Locate [module] in the process module list (empty string = host/main exe).
 *   2. Add [base] to the module's load address to get the first pointer.
 *   3. Dereference through each value in [offsets] in order (pointer walk).
 *   4. Add [valueOffset] to the final resolved pointer to reach the value address.
 *
 * When [writeMax] is true the DLL instead reads the value at
 * (resolvedValueAddress - [valueOffset] + [maxOffset]) and writes that as the
 * freeze value (i.e. freeze the value to its own maximum field).
 */
data class ChainSpec(
    val module: String,            // e.g. "dmc3.exe"; empty string = host exe
    val base: Long,                // offset from module base to the first pointer (hex in JSON)
    val offsets: List<Long>,       // deref offsets (hex strings in JSON); may be empty for "static"
    val valueOffset: Long,         // offset from final resolved pointer to the value (hex in JSON)
    val writeMax: Boolean = false, // if true, freeze value = the max field at (value - valueOffset + maxOffset)
    val maxOffset: Long = 0L,      // hex in JSON; only used when writeMax is true
)

/**
 * An optional AOB (array-of-bytes) pattern block.
 *
 * The [pattern] field is a space-separated hex string with '?' wildcards
 * (e.g. "48 8B 05 ? ? ? ?").  [offset] is the signed byte offset from the
 * pattern match base to the target value address, and [vtype] overrides the
 * parent cheat's vtype at that address.
 *
 * [patchBytes] is reserved for the "aob_patch" recipe kind (write these bytes
 * at the match address instead of freezing a value).  It is null for
 * "aob_freeze" cheats and may be null even for "aob_patch" until the executor
 * is implemented.
 *
 * All cheats in the seed registry carry `"aob": null` — this block is only
 * instantiated when the JSON contains an actual AOB object.
 */
data class AobPattern(
    val pattern: String,          // CE-style AOB e.g. "48 8B 05 ? ? ? ?"
    val offset: Int,              // signed byte offset from match base to target value
    val vtype: String,            // value type at the resolved address (overrides parent)
    val patchBytes: String? = null, // OPTIONAL: replacement bytes for aob_patch (reserved, may be null)
)

/**
 * Drives the in-process memory scan for a single cheat.
 *
 * [kind] accepts:
 *  - "guided_known"  — user enters the current value; the engine performs
 *                      scanNew then scanNext after a second reading (existing).
 *  - "aob_freeze"    — one-tap: the engine auto-scans the AOB signature from
 *                      [Cheat.aob], resolves the target address via [AobPattern.offset],
 *                      then freezes [freezeValue] at that address.  [Cheat.aob]
 *                      MUST be non-null for this kind.
 *  - "aob_patch"     — (reserved, not yet executed) writes [AobPattern.patchBytes]
 *                      at the match address.  [Cheat.aob] MUST be non-null and
 *                      [AobPattern.patchBytes] MUST be non-null when executed.
 *  - "pointer_chain" — the in-game DLL resolves [Cheat.chain] (module + base →
 *                      deref through [ChainSpec.offsets] → add [ChainSpec.valueOffset])
 *                      every tick and freezes either [freezeValue] (literal) or the
 *                      max field when [ChainSpec.writeMax] is true.
 *                      [Cheat.chain] MUST be non-null for this kind.
 *  - "static"        — same as "pointer_chain" but with an empty [ChainSpec.offsets]
 *                      list (module + base + valueOffset directly, no pointer walk).
 *                      [Cheat.chain] MUST be non-null for this kind.
 *
 * [prompt] — shown before the first value entry (guided_known only).
 * [narrowPrompt] — shown before each subsequent narrowing pass (guided_known only).
 * [freezeValue] — the value written (and frozen) once the address is located.
 *                 For "aob_freeze", this is the value frozen at the resolved address.
 *                 Stored as Long; UI encodes it according to [Cheat.vtype].
 */
data class CheatRecipe(
    val kind: String,
    val prompt: String,
    val narrowPrompt: String?,
    val freezeValue: Long?,
)

/**
 * A single cheat entry within a [CheatTable].
 *
 * [vtype] is the canonical string from the JSON schema ("i8","u8","i16","u16",
 * "i32","u32","i64","u64","f32","f64").  Call [vtypeToTvt] to map it to the
 * [TrainerProto] constant expected by [app.gamenative.trainer.TrainerShm].
 * [aob] is null for recipe-only cheats and populated for AOB-resolved cheats.
 * [chain] is null for all recipe kinds except "pointer_chain" and "static", where
 * it MUST be non-null. Like [aob], it is parsed whenever the JSON key is present.
 */
data class Cheat(
    val id: String,
    val name: String,
    val category: String,
    val vtype: String,
    val recipe: CheatRecipe,
    val aob: AobPattern?,
    val chain: ChainSpec? = null,
)

/**
 * All cheats for a single game, keyed by (source, gameId) — same identity
 * scheme as gamefixes/registry.json.
 */
data class CheatTable(
    val source: GameSource,
    val gameId: String,
    val title: String,
    val cheats: List<Cheat>,
)

// ---------------------------------------------------------------------------
// vtype → TrainerProto.TVT_* mapping
// ---------------------------------------------------------------------------

/**
 * Maps the JSON vtype string to the corresponding [TrainerProto] TVT_* Int
 * constant understood by [app.gamenative.trainer.TrainerShm].
 *
 * TVT values (mirrors trainer_protocol.h enum trainer_vtype):
 *   i8=1, u8=2, i16=3, u16=4, i32=5, u32=6, i64=7, u64=8, f32=9, f64=10
 *
 * Returns [TrainerProto.TVT_NONE] (0) for unrecognised strings.
 */
fun vtypeToTvt(vtype: String): Int = when (vtype.lowercase().trim()) {
    "i8"  -> TrainerProto.TVT_I8
    "u8"  -> TrainerProto.TVT_U8
    "i16" -> TrainerProto.TVT_I16
    "u16" -> TrainerProto.TVT_U16
    "i32" -> TrainerProto.TVT_I32
    "u32" -> TrainerProto.TVT_U32
    "i64" -> TrainerProto.TVT_I64
    "u64" -> TrainerProto.TVT_U64
    "f32" -> TrainerProto.TVT_F32
    "f64" -> TrainerProto.TVT_F64
    else  -> TrainerProto.TVT_NONE
}
