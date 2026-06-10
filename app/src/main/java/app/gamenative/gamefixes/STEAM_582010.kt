package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Monster Hunter: World (Steam)
 *
 * WINE_LARGE_ADDRESS_AWARE enables 4GB address space for 32-bit processes
 * under Wine, preventing out-of-memory crashes in heavily modded or
 * long-session Monster Hunter: World runs.
 */
val STEAM_Fix_582010: KeyedGameFix = KeyedWineEnvVarFix(
    gameSource = GameSource.STEAM,
    gameId = "582010",
    envVarsToSet = mapOf(
        "WINE_LARGE_ADDRESS_AWARE" to "1",
    ),
)
