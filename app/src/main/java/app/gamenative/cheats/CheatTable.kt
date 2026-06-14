package app.gamenative.cheats

import app.gamenative.data.GameSource
import app.gamenative.trainer.TrainerProto

// ---------------------------------------------------------------------------
// Data classes for the cheat-table registry
// ---------------------------------------------------------------------------

/**
 * An optional AOB (array-of-bytes) pattern block.
 *
 * Forward-compat placeholder: all fields are present so the schema can be
 * extended later without a format change.  The [pattern] field is a
 * space-separated hex string with '?' wildcards (e.g. "48 8B 05 ? ? ? ?").
 * [offset] is the signed byte offset from the pattern match to the target value,
 * and [vtype] overrides the parent cheat's vtype at the final address.
 * All cheats in the seed registry carry `"aob": null` — this block is only
 * instantiated when the JSON contains an actual AOB object.
 */
data class AobPattern(
    val pattern: String,
    val offset: Int,
    val vtype: String,
)

/**
 * Drives the in-process memory scan for a single cheat.
 *
 * [kind] — currently "guided_known" (user enters the current value, the engine
 *           performs scanNew, then scanNext after a second reading).
 * [prompt] — shown before the first value entry.
 * [narrowPrompt] — shown before each subsequent narrowing pass.
 * [freezeValue] — the value written (and frozen) once the address is located.
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
 */
data class Cheat(
    val id: String,
    val name: String,
    val category: String,
    val vtype: String,
    val recipe: CheatRecipe,
    val aob: AobPattern?,
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
