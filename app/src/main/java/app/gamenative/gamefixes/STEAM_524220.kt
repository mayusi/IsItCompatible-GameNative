package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * NieR: Automata (Steam)
 *
 * The game's intro movie files (.bik) can cause hangs during Wine video
 * playback. Renaming them to .bak skips intro playback. Fix is reversible
 * and idempotent.
 */
val STEAM_Fix_524220: KeyedGameFix = KeyedDeleteGameFilesFix(
    gameSource = GameSource.STEAM,
    gameId = "524220",
    installPathRelativeGlobs = listOf(
        "data/movie/*.bik",
    ),
)
