package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Grand Theft Auto V (Steam)
 *
 * Running in windowed borderless mode avoids fullscreen-exclusive issues
 * under Wine/XServer that can cause black screens or input capture problems.
 */
val STEAM_Fix_271590: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "271590",
    launchArgs = "-windowed -noborder",
)
