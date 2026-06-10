package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Cuphead (Steam)
 *
 * DXVK async pipeline compilation reduces stutter during shader compilation.
 * Cuphead is a Unity-based game that benefits from async on Wine/Proton.
 */
val STEAM_Fix_268910: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "268910",
    envVarsToSet = mapOf(
        "DXVK_ASYNC" to "1",
    ),
)
