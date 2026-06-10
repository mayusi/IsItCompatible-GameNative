package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Hades (Steam)
 *
 * Forcing the Vulkan rendering driver avoids OpenGL/ANGLE issues that can
 * cause rendering artifacts or crashes on Android under Wine/XServer.
 */
val STEAM_Fix_1145360: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "1145360",
    launchArgs = "--rendering-driver vulkan",
)
