package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Elden Ring (Steam)
 *
 * DXVK async pipeline compilation reduces stutter during shader compilation.
 * Well-established community fix for Elden Ring on Wine/Proton.
 */
val STEAM_Fix_1245620: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "1245620",
    envVarsToSet = mapOf(
        "DXVK_ASYNC" to "1",
    ),
)
