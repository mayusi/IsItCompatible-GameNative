package app.gamenative.cheats

/**
 * Process-level holder for which one-tap cheats are currently ACTIVE, keyed by
 * (appId -> cheatId -> active).
 *
 * WHY THIS EXISTS: the Cheats tab UI ([app.gamenative.ui.component.CheatTab])
 * lives inside the in-game QuickMenu, which is fully torn down and recreated
 * every time the player closes and reopens the menu. Anything stored in the
 * composable's `remember {}` is therefore lost on each reopen — so a toggle the
 * user flipped ON would render OFF again next time the menu opens, even though
 * the in-game cheat DLL is still happily freezing the value (the DLL holds the
 * freeze in its own slot table, independent of the UI).
 *
 * This holder survives across QuickMenu open/close (it's a plain object, alive
 * for the whole app process), so the UI can restore the correct toggle state.
 * It is the UI's source of truth for "is this cheat showing as on"; the DLL is
 * the source of truth for "is the value actually being frozen". We keep them in
 * sync: toggle on -> mark active here + send `chain=` to the DLL; toggle off ->
 * clear here + send `chainoff=`.
 *
 * Cleared when a game stops (the DLL dies with the game, so stale active flags
 * must not persist into the next launch) — call [clearGame] on game exit, or
 * [clearAll] defensively.
 */
object CheatUiState {

    // appId -> (cheatId -> active)
    private val active = HashMap<String, HashMap<String, Boolean>>()

    @Synchronized
    fun isActive(appId: String, cheatId: String): Boolean =
        active[appId]?.get(cheatId) == true

    @Synchronized
    fun setActive(appId: String, cheatId: String, on: Boolean) {
        val m = active.getOrPut(appId) { HashMap() }
        if (on) m[cheatId] = true else m.remove(cheatId)
    }

    /** Snapshot of the currently-active cheat ids for a game (empty if none). */
    @Synchronized
    fun activeIds(appId: String): Set<String> =
        active[appId]?.filterValues { it }?.keys?.toSet() ?: emptySet()

    /** Drop all active flags for one game (call when that game's process stops). */
    @Synchronized
    fun clearGame(appId: String) {
        active.remove(appId)
    }

    @Synchronized
    fun clearAll() {
        active.clear()
    }
}
