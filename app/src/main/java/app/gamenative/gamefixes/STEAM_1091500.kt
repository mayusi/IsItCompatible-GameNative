package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Cyberpunk 2077 (Steam)
 *
 * Caps the DXVK frame rate to 60 fps to prevent GPU overload and thermal
 * throttling on mobile hardware running under DXVK/Wine. Community-documented
 * fix for stable handheld play.
 */
val STEAM_Fix_1091500: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "1091500",
    envVarsToSet = mapOf(
        "DXVK_FRAME_RATE" to "60",
    ),
)
