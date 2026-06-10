package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Terraria (Steam)
 *
 * DOTNET_EnableWriteXorExecute=0 disables W^X memory protection for .NET,
 * which is required for the Mono/.NET runtime to JIT-compile correctly
 * under Wine on ARM/Android. Without this, the game may fail to start or
 * crash during loading.
 */
val STEAM_Fix_105600: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "105600",
    envVarsToSet = mapOf(
        "DOTNET_EnableWriteXorExecute" to "0",
    ),
)
