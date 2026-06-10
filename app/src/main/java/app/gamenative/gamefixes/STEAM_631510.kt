package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Devil May Cry HD Collection (Steam App ID 631510)
 *
 * The game includes DMC1, DMC2, and DMC3 (Dante's Awakening). DMC3 crashes on startup
 * when playing its intro video (.wmv files). This is a known issue with Wine/Proton
 * and corrupted or incompatible WMV codec handling.
 *
 * Fix: Rename all .wmv files in the Video directory to .wmv.bak on launch.
 * This skips the intro video playback and allows the game to start normally.
 * The fix is reversible and idempotent (skips already-backed-up files).
 */
val STEAM_Fix_631510: KeyedGameFix = KeyedDeleteGameFilesFix(
    gameSource = GameSource.STEAM,
    gameId = "631510",
    installPathRelativeGlobs = listOf(
        // DMC3: Dante's Awakening intro videos (primary issue)
        "data/dmc3/Video/*.wmv",
        // DMC1 and DMC2: cover both known capitalisation variants
        "data/dmc1/Video/*.wmv",
        "data/dmc2/Video/*.wmv",
        "data/dmc1/movie/*.wmv",
        "data/dmc2/movie/*.wmv",
    ),
)
