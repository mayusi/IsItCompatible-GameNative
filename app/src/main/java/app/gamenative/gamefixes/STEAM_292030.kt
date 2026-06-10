package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * The Witcher 3: Wild Hunt (Steam)
 *
 * The game uses d3dcompiler_47 for shader compilation. Using the native DLL
 * avoids shader compilation failures under Wine/Proton.
 */
val STEAM_Fix_292030: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "292030",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "d3dcompiler_47=n,b",
    ),
)
