package app.gamenative.utils

import app.gamenative.enums.Marker
import app.gamenative.data.GameSource
import com.winlator.container.Container
import java.io.File

/**
 * Windows paths and silent-install arguments for bundled .NET Framework / .NET runtime
 * installers that ship inside some game packages.
 *
 * Pattern mirrors [VcRedistStep] exactly:
 *   - Keys are Windows-style paths (relative to the A: drive root).
 *   - Values are silent-install arguments.
 *   - Each path is translated to a host-filesystem File at runtime and only included
 *     in the install command if the file actually exists on disk.
 */
private val dotNetMap: Map<String, String> = mapOf(
    // Classic .NET Framework bundled installers (dotNetFx*.exe)
    "A:\\_CommonRedist\\dotNET\\dotNetFx40_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx40_Full_x86_x64.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx45_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx451_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx452_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx46_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx461_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx462_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx47_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx471_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx472_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\dotNetFx48_Full_setup.exe" to "/q /norestart",
    // NDP (Microsoft .NET 3.5 / 4.x web/offline bootstrappers)
    "A:\\_CommonRedist\\dotNET\\ndp40-kb2544514-x86-x64.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\NDP451-KB2858728-x86-x64-AllOS-ENU.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\NDP462-KB3151800-x86-x64-AllOS-ENU.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\NDP472-KB4054530-x86-x64-AllOS-ENU.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNET\\NDP48-x86-x64-AllOS-ENU.exe" to "/q /norestart",
    // Modern .NET (dotnet-*.exe runtime / desktop-runtime / sdk)
    "A:\\_CommonRedist\\dotNET\\dotnet-runtime-6.0-win-x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\dotNET\\dotnet-runtime-7.0-win-x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\dotNET\\dotnet-runtime-8.0-win-x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\dotNET\\dotnet-desktop-runtime-6.0-win-x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\dotNET\\dotnet-desktop-runtime-7.0-win-x64.exe" to "/passive /norestart",
    "A:\\_CommonRedist\\dotNET\\dotnet-desktop-runtime-8.0-win-x64.exe" to "/passive /norestart",
    // Alternate capitalisation variants (game bundles are inconsistent)
    "A:\\_CommonRedist\\DotNet\\dotNetFx40_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\DotNet\\dotNetFx45_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\DotNet\\dotNetFx48_Full_setup.exe" to "/q /norestart",
    // Redist sub-folder variants
    "A:\\redist\\dotNet\\dotNetFx40_Full_setup.exe" to "/q /norestart",
    "A:\\redist\\dotNet\\dotNetFx45_Full_setup.exe" to "/q /norestart",
    "A:\\redist\\dotNet\\dotNetFx48_Full_setup.exe" to "/q /norestart",
    "A:\\redist\\dotnet-runtime-6.0-win-x64.exe" to "/passive /norestart",
    "A:\\redist\\dotnet-runtime-8.0-win-x64.exe" to "/passive /norestart",
    // Alternate _CommonRedist\dotNet* paths used by some Unity / Unreal bundles
    "A:\\_CommonRedist\\dotNet\\dotNetFx40_Full_setup.exe" to "/q /norestart",
    "A:\\_CommonRedist\\dotNet\\dotNetFx48_Full_setup.exe" to "/q /norestart",
)

object DotNetBundleStep : PreInstallStep {
    override val marker: Marker = Marker.DOTNET_INSTALLED

    override fun appliesTo(
        container: Container,
        gameSource: GameSource,
        gameDirPath: String,
    ): Boolean {
        return !MarkerUtils.hasMarker(gameDirPath, Marker.DOTNET_INSTALLED)
    }

    override fun buildCommand(
        container: Container,
        appId: String,
        gameSource: GameSource,
        gameDir: File,
        gameDirPath: String,
    ): String? {
        val parts = mutableListOf<String>()
        for ((winPath, args) in dotNetMap) {
            if (winPath.length < 4 || winPath[1] != ':' || winPath[2] != '\\') continue
            val rest = winPath.substring(3)
            val hostFile = File(gameDir, rest.replace('\\', '/'))
            if (!hostFile.isFile) continue
            parts.add(if (args.isEmpty()) winPath else "$winPath $args")
        }
        return if (parts.isEmpty()) null else parts.joinToString(" & ")
    }
}
