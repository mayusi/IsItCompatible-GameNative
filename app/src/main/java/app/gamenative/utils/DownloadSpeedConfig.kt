package app.gamenative.utils

import app.gamenative.PrefManager

class DownloadSpeedConfig {
    private data class Ratios(val download: Double, val decompress: Double)

    private val ratios: Ratios
        get() = when (PrefManager.downloadSpeed) {
            8 -> {
                Ratios(download = 0.6, decompress = 0.2)
            }

            16 -> {
                Ratios(download = 1.2, decompress = 0.4)
            }

            24 -> {
                Ratios(download = 1.5, decompress = 0.5)
            }

            32 -> {
                Ratios(download = 2.4, decompress = 0.8)
            }

            else -> {
                Ratios(download = 0.6, decompress = 0.2)
            }
        }

    val cpuCores: Int
        get() = Runtime.getRuntime().availableProcessors()

    val maxDownloads: Int
        get() = (cpuCores * ratios.download).toInt().coerceAtLeast(1)

    // Hard ceiling on concurrent decompression (Inflater) workers, independent of
    // downloadSpeed. On an 8-core handheld this yields 4; on a 4-core, 2. Capping
    // CPU-bound inflate concurrency keeps cores from pinning at peak clock (and the
    // device from cooking) during downloads, which are network-bound anyway.
    val maxDecompress: Int
        get() = (cpuCores / 2).coerceIn(1, 4)

    // Gate for thermal-friendly decompression (background thread priority). Toggleable
    // later if needed; defaults on.
    val thermalFriendlyDecompress: Boolean
        get() = true
}
