package app.gamenative.cheats

import java.io.File

/**
 * Pure path resolver shared by the Android (Kotlin) side of the cheat IPC.
 *
 * THE BUG THIS FIXES — the proxy DLL (cheat.c) and this Kotlin bridge used to
 * resolve the IPC files (cheat_ctrl.txt / cheat_out.txt) in DIFFERENT folders:
 *
 *   - DLL: GetModuleFileNameA(NULL) → strips the filename → writes the files
 *          NEXT TO THE GAME EXE (e.g. ".../Game/release/").
 *   - Kotlin: used the install ROOT (".../Game/").
 *
 * Most games launch their exe from a SUBFOLDER (bin/x64/release/Binaries), so the
 * DLL wrote into ".../Game/release/" while Kotlin read ".../Game/" — the two sides
 * never met and every scan timed out after 12s; chain cheats latched ON silently
 * without ever reaching the DLL. (DMC3 worked only because dmc3.exe sits at the
 * install root, so both sides happened to agree there.)
 *
 * TWO CHANNELS, both computed identically on the C and Kotlin sides:
 *
 *   1. BACKSTOP (the reliable channel) — a FIXED Wine path C:\ProxyCheat\ that maps
 *      to a STABLE Android path computable from the container alone, WITHOUT knowing
 *      the exe subfolder:
 *          Wine    C:\ProxyCheat\cheat_ctrl.txt
 *          Android <container.rootDir>/.wine/drive_c/ProxyCheat/cheat_ctrl.txt
 *      The DLL hardcodes C:\ProxyCheat\ (see BACKSTOP_DIR in cheat.c). drive_c for a
 *      container lives at <rootDir>/.wine/drive_c (see Container.getDesktopDir / the
 *      ContainerManager rootDir = <imagefs>/home/xuser-<id> convention), so this path
 *      is fully deterministic on both sides.
 *
 *   2. EXE-DIR (a nice-to-have that keeps DMC3-style root exes working and gives a
 *      second channel when the exe subdir IS known) — install root + the exe's parent
 *      folder. The DLL always writes next-to-exe here too.
 *
 * The bridge prefers the BACKSTOP for the round-trip handshake/ack because it is the
 * one path guaranteed to agree regardless of exe layout.
 */
object CheatPaths {

    const val CTRL_FILE = "cheat_ctrl.txt"
    const val OUT_FILE  = "cheat_out.txt"

    /** Fixed Wine backstop dir name under drive_c (mirrors BACKSTOP_DIR "C:\\ProxyCheat" in cheat.c). */
    const val BACKSTOP_SUBDIR = "ProxyCheat"

    /**
     * Compute the Android directory that maps to the DLL's fixed Wine backstop dir
     * (C:\ProxyCheat\) for a given container root.
     *
     * @param containerRootDir The container's rootDir (e.g. <imagefs>/home/xuser-STEAM_x).
     * @return <rootDir>/.wine/drive_c/ProxyCheat — or null if [containerRootDir] is null.
     */
    fun backstopDir(containerRootDir: File?): File? {
        if (containerRootDir == null) return null
        return File(containerRootDir, ".wine/drive_c/$BACKSTOP_SUBDIR")
    }

    /**
     * Resolve the directory the DLL writes its NEXT-TO-EXE files into, given the
     * install root and the (possibly empty / subfolder) executable path.
     *
     * Examples:
     *   installRoot=".../Game", executablePath="release/NSGP.exe"  -> ".../Game/release"
     *   installRoot=".../Game", executablePath="bin/x64/g.exe"     -> ".../Game/bin/x64"
     *   installRoot=".../DMC3", executablePath="dmc3.exe"          -> ".../DMC3"   (root exe)
     *   installRoot=".../DMC3", executablePath="" / null           -> ".../DMC3"   (unknown → root)
     *
     * Both '/' and '\' separators in [executablePath] are honoured (container exe paths
     * are stored Windows-style; some callers pass POSIX-style).
     *
     * @return The resolved exe directory; never null (falls back to [installRoot]).
     */
    fun exeDir(installRoot: File, executablePath: String?): File {
        val rel = executablePath
            ?.trim()
            ?.replace('\\', '/')
            ?.trimStart('/')
            .orEmpty()
        if (rel.isEmpty()) return installRoot
        val parent = rel.substringBeforeLast('/', "")
        return if (parent.isEmpty()) installRoot else File(installRoot, parent)
    }
}
