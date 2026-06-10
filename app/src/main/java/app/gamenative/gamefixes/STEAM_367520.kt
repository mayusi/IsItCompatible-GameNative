package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Hollow Knight (Steam)
 *
 * Forcing the Vulkan rendering driver avoids OpenGL/ANGLE issues that can
 * cause rendering artifacts or crashes on Android under Wine/XServer.
 */
val STEAM_Fix_367520: KeyedGameFix = KeyedLaunchArgFix(
    gameSource = GameSource.STEAM,
    gameId = "367520",
    launchArgs = "--rendering-driver vulkan",
)
