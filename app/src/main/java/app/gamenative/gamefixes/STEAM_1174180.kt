package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Red Dead Redemption 2 (Steam)
 *
 * -ignorepipelinecache forces the game to skip its cached pipeline state,
 * preventing crashes caused by stale or incompatible Vulkan pipeline caches
 * on first launch or after driver updates.
 */
val STEAM_Fix_1174180: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "1174180",
    launchArgs = "-ignorepipelinecache",
)
