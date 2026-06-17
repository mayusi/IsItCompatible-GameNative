package app.gamenative.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Unit tests for [CheatPaths] — the resolver that makes the Kotlin side agree with
 * the proxy DLL on WHERE the cheat IPC files live, for both the next-to-exe channel
 * and the deterministic C:\ProxyCheat backstop.
 */
class CheatPathsTest {

    // ----- exeDir: the DLL's next-to-exe channel ------------------------------

    @Test
    fun `subfolder exe resolves to the exe parent (the bug case)`() {
        // install-root + "release/NSGP.exe" → ".../release"  — the exact case that
        // used to mismatch (Kotlin at root, DLL in release/).
        val root = File("/data/games/New Star GP")
        assertEquals(File(root, "release"), CheatPaths.exeDir(root, "release/NSGP.exe"))
    }

    @Test
    fun `deep subfolder exe resolves to the full parent chain`() {
        val root = File("/data/games/SomeGame")
        assertEquals(File(root, "bin/x64"), CheatPaths.exeDir(root, "bin/x64/game.exe"))
    }

    @Test
    fun `windows-style backslash separators are honoured`() {
        val root = File("/data/games/Batman")
        assertEquals(File(root, "Binaries"), CheatPaths.exeDir(root, "Binaries\\BmLauncher.exe"))
    }

    @Test
    fun `root exe (DMC3) resolves to the install root — MUST NOT REGRESS`() {
        val root = File("/data/games/DMC3")
        assertEquals(root, CheatPaths.exeDir(root, "dmc3.exe"))
    }

    @Test
    fun `empty executable path falls back to the install root`() {
        val root = File("/data/games/DMC3")
        assertEquals(root, CheatPaths.exeDir(root, ""))
    }

    @Test
    fun `null executable path falls back to the install root`() {
        val root = File("/data/games/DMC3")
        assertEquals(root, CheatPaths.exeDir(root, null))
    }

    @Test
    fun `leading slash on executable path is tolerated`() {
        val root = File("/data/games/G")
        assertEquals(File(root, "release"), CheatPaths.exeDir(root, "/release/g.exe"))
    }

    // ----- backstopDir: the deterministic C:\ProxyCheat channel ---------------

    @Test
    fun `backstop dir maps drive_c ProxyCheat under the container root`() {
        val containerRoot = File("/data/imagefs/home/xuser-STEAM_271590")
        val expected = File(containerRoot, ".wine/drive_c/ProxyCheat")
        assertEquals(expected, CheatPaths.backstopDir(containerRoot))
    }

    @Test
    fun `backstop dir is null when no container root is known`() {
        assertNull(CheatPaths.backstopDir(null))
    }

    @Test
    fun `backstop subdir name matches the DLL's C ProxyCheat constant`() {
        // cheat.c hardcodes BACKSTOP_DIR "C:\\ProxyCheat" — the last path component
        // must match so both sides land in the same Wine folder.
        assertEquals("ProxyCheat", CheatPaths.BACKSTOP_SUBDIR)
    }
}
