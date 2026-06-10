package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * The Elder Scrolls V: Skyrim Special Edition (Steam)
 *
 * Skyrim SE reads its install path from the registry on startup.
 * Without this key the game launcher may fail to locate its data directory.
 */
val STEAM_Fix_489830: KeyedGameFix = KeyedRegistryKeyFix(
    gameSource = GameSource.STEAM,
    gameId = "489830",
    registryKey = "Software\\Wow6432Node\\Bethesda Softworks\\Skyrim Special Edition",
    defaultValues = mapOf(
        "Installed Path" to INSTALL_PATH_PLACEHOLDER,
    ),
)
