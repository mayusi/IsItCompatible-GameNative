package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * God of War (Steam)
 *
 * The EOS (Epic Online Services) SDK DLL is not needed for Steam play and
 * can cause crashes or hangs during initialization under Wine. Loading the
 * native stub prevents the DLL from interfering with startup.
 */
val STEAM_Fix_1593500: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "1593500",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "eossdk-win64-shipping=n,b",
    ),
)
