package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Devil May Cry 5 (Steam)
 *
 * The English-language intro cutscenes (.usm files) can cause crashes or
 * hangs during Wine video playback. Renaming them to .bak skips intro
 * playback and allows the game to start normally. Fix is reversible and
 * idempotent.
 */
val STEAM_Fix_601150: KeyedGameFix = KeyedDeleteGameFilesFix(
    gameSource = GameSource.STEAM,
    gameId = "601150",
    installPathRelativeGlobs = listOf(
        "nativePC/movie/en/*.usm",
    ),
)
