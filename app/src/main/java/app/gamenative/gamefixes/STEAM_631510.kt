package app.gamenative.gamefixes

import app.gamenative.data.GameSource

/**
 * Devil May Cry HD Collection (Steam App ID 631510)
 *
 * The collection ships THREE separate games (DMC1, DMC2, DMC3 Dante's Awakening), each
 * launched by its own exe: dmc1.exe, dmc2.exe, dmc3.exe. A shared launcher (dmcLauncher.exe)
 * also exists but crashes the Wine child process when you pick a game from it, so the
 * recommended approach is launching each game's exe directly.
 *
 * All three games crash on startup (or on the collection launcher attract screen) when Wine
 * tries to play their pre-rendered .wmv intro / cutscene videos; Wine's WMV3/VC-1 decode
 * fails with 'Unrecognised format WMV3'. This is a known Wine/Proton limitation.
 *
 * Fix: Rename all .wmv files under each game's video directory to .wmv.bak on launch.
 * This skips the pre-rendered FMV playback and allows all three games (and the launcher,
 * if used) to start normally. Real-time in-engine cutscenes are unaffected.
 * The fix is reversible and idempotent (skips already-backed-up files).
 *
 * Directory layout confirmed on device:
 *   data/dmc1/Video/   -- 31 .wmv files (DMC1 cutscenes)
 *   data/dmc2/Video/   -- 13 .wmv files (DMC2 cutscenes)
 *   data/dmc3/Video/   -- 33 .wmv files (DMC3 cutscenes)
 *   data/dmclauncher/video/  -- 3 .wmv attract-mode videos (launcher intro loop)
 */
val STEAM_Fix_631510: KeyedGameFix = KeyedDeleteGameFilesFix(
    gameSource = GameSource.STEAM,
    gameId = "631510",
    installPathRelativeGlobs = listOf(
        // DMC3: Dante's Awakening intro / cutscene videos (primary crash source)
        "data/dmc3/Video/*.wmv",
        // DMC1: intro and cutscene videos
        "data/dmc1/Video/*.wmv",
        // DMC2: intro and cutscene videos
        "data/dmc2/Video/*.wmv",
        // Additional capitalisation variants seen on some installs
        "data/dmc1/video/*.wmv",
        "data/dmc2/video/*.wmv",
        "data/dmc1/movie/*.wmv",
        "data/dmc2/movie/*.wmv",
        // dmcLauncher attract-mode videos (crash the launcher on the attract loop)
        "data/dmclauncher/video/*.wmv",
    ),
)
