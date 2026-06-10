package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Dark Souls III (Steam)
 *
 * The intro movie .bik files can cause hangs or crashes during Wine video
 * playback. Renaming them to .bak skips intro playback and allows the game
 * to reach the main menu normally. Fix is reversible and idempotent.
 */
val STEAM_Fix_374320: KeyedGameFix = KeyedDeleteGameFilesFix(
    gameSource = GameSource.STEAM,
    gameId = "374320",
    installPathRelativeGlobs = listOf(
        "Game/movie/*.bik",
    ),
)
