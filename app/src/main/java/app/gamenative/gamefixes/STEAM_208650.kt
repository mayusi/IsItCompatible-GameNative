package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Batman: Arkham Knight (Steam)
 *
 * PhysXDevice is a Nvidia PhysX GPU bridge DLL. Under Wine, loading it as
 * native prevents crashes when Nvidia-specific GPU physics APIs are called
 * on non-Nvidia hardware.
 */
val STEAM_Fix_208650: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "208650",
    envVarsToSet = mapOf(
        "WINEDLLOVERRIDES" to "PhysXDevice=n",
    ),
)
