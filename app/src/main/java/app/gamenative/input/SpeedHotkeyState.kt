package app.gamenative.input

/**
 * Mutable holder for the in-game speed-hotkey state, kept alive across recompositions of the
 * XServer screen via `remember { SpeedHotkeyState() }`.
 *
 * - [lastFast]   the fast speed that SPEED_TOGGLE flips back to (and FAST-FORWARD jumps to).
 *                Updated when the user toggles down from a fast speed so the next toggle restores it.
 * - [beforeHold] the speed captured at the start of a FAST-FORWARD hold, restored on release.
 *
 * Plain mutable fields (not Compose state) — these are read/written only from the controller
 * handler's input callbacks on the main thread; they don't need to trigger recomposition.
 */
class SpeedHotkeyState(
    var lastFast: Float = 4.0f,
    var beforeHold: Float = 1.0f,
)
