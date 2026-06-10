package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import com.winlator.box86_64.Box86_64Preset
import com.winlator.core.GPUInformation
import timber.log.Timber

/**
 * IIC fork: Applies GPU-class-specific optimised default preferences on a per-device basis.
 *
 * Each GPU class has its own one-shot flag key stored via PrefManager.setFloat so that:
 *  - defaults are applied at most once per class
 *  - user overrides made after first boot are not overwritten on subsequent app upgrades
 *
 * Detection order (most powerful first — first match wins):
 *  1. Adreno 830 / 8 Elite  (830-829 range as matched by isAdreno8Elite)
 *  2. Adreno 750 / 8 Gen 3
 *  3. Adreno 740 / 8 Gen 2
 *  4. Adreno 6xx / 865-888 class
 *  5. Mali-G7xx / Dimensity 9300-9000 class
 *  6. Mali lower / Helio class
 *
 * If no class matches, detection does nothing (stock defaults remain).
 *
 * ContainerData field names used (all verified against Container.java + PrefManager.kt):
 *  graphicsDriver       — "Wrapper" | "System"
 *  graphicsDriverVersion — "Turnip_Gen8_V25" (Wrapper path) | "" (System)
 *  box64Preset          — Box86_64Preset.PERFORMANCE | Box86_64Preset.COMPATIBILITY
 *  dxWrapper            — "dxvk"
 *  videoMemorySize      — "4096" | "3072" | "2048" | "1024"
 *  rendererPresentMode  — "mailbox" | "fifo"
 */
object DeviceProfileDetector {

    private const val TAG = "DeviceProfileDetector"

    // One-shot flag keys — one per GPU class.
    // Values: 1f = applied; 2f = checked, not applicable; 0f = not yet run.
    private const val KEY_ADRENO_830  = "iic_tuned_adreno830"
    private const val KEY_ADRENO_750  = "iic_tuned_adreno750"
    private const val KEY_ADRENO_740  = "iic_tuned_adreno740"
    private const val KEY_ADRENO_6XX  = "iic_tuned_adreno6xx"
    private const val KEY_MALI_G7XX   = "iic_tuned_mali_g7xx"
    private const val KEY_MALI_LOWER  = "iic_tuned_mali_lower"

    /**
     * Call from Application.onCreate (after PrefManager.init) to apply GPU-class tuned defaults
     * exactly once per device. Each class is checked independently via its own one-shot flag.
     */
    fun applyAdrenoTunedDefaultsIfNeeded(context: Context) {
        applyProfileIfNeeded(context)
    }

    private fun applyProfileIfNeeded(context: Context) {
        val renderer = try {
            GPUInformation.getRenderer(context).lowercase()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "GPU renderer read failed; skipping profile detection")
            return
        }

        // --- Adreno 830 / 8 Elite (830–859) ---
        if (PrefManager.getFloat(KEY_ADRENO_830, 0f) < 1f) {
            val is830 = try { GPUInformation.isAdreno8Elite(context) } catch (e: Exception) { false }
            if (is830) {
                Timber.tag(TAG).i("Adreno 8 Elite (830+) detected — applying IIC tuned defaults")
                PrefManager.graphicsDriver = "Wrapper"
                PrefManager.graphicsDriverVersion = "Turnip_Gen8_V25"
                PrefManager.box64Preset = Box86_64Preset.PERFORMANCE
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "4096"
                PrefManager.rendererPresentMode = "mailbox"
                PrefManager.setFloat(KEY_ADRENO_830, 1f)
                Timber.tag(TAG).i("IIC Adreno 830 tuned defaults applied successfully")
                return
            } else {
                // Mark as checked so we skip the EGL query on every launch for non-matching devices.
                PrefManager.setFloat(KEY_ADRENO_830, 2f)
            }
        }

        // --- Adreno 750 / Snapdragon 8 Gen 3 ---
        if (PrefManager.getFloat(KEY_ADRENO_750, 0f) < 1f) {
            val is750 = try { GPUInformation.isAdreno750(context) } catch (e: Exception) { false }
            if (is750) {
                Timber.tag(TAG).i("Adreno 750 (8 Gen 3) detected — applying IIC tuned defaults")
                PrefManager.graphicsDriver = "Wrapper"
                PrefManager.graphicsDriverVersion = "Turnip_Gen8_V25"
                PrefManager.box64Preset = Box86_64Preset.PERFORMANCE
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "3072"
                PrefManager.rendererPresentMode = "mailbox"
                PrefManager.setFloat(KEY_ADRENO_750, 1f)
                Timber.tag(TAG).i("IIC Adreno 750 tuned defaults applied successfully")
                return
            } else {
                PrefManager.setFloat(KEY_ADRENO_750, 2f)
            }
        }

        // --- Adreno 740 / Snapdragon 8 Gen 2 ---
        if (PrefManager.getFloat(KEY_ADRENO_740, 0f) < 1f) {
            val is740 = try { GPUInformation.isAdreno740(context) } catch (e: Exception) { false }
            if (is740) {
                Timber.tag(TAG).i("Adreno 740 (8 Gen 2) detected — applying IIC tuned defaults")
                PrefManager.graphicsDriver = "Wrapper"
                PrefManager.graphicsDriverVersion = "Turnip_Gen8_V25"
                PrefManager.box64Preset = Box86_64Preset.COMPATIBILITY
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "2048"
                PrefManager.rendererPresentMode = "mailbox"
                PrefManager.setFloat(KEY_ADRENO_740, 1f)
                Timber.tag(TAG).i("IIC Adreno 740 tuned defaults applied successfully")
                return
            } else {
                PrefManager.setFloat(KEY_ADRENO_740, 2f)
            }
        }

        // --- Adreno 6xx / Snapdragon 865-888 class ---
        // Wrapper (no Turnip on 6xx in this fork — adrenotools blacklist covers it),
        // COMPATIBILITY, VRAM 2048. dxvk is the default wrapper for all classes.
        if (PrefManager.getFloat(KEY_ADRENO_6XX, 0f) < 1f) {
            val is6xx = try { GPUInformation.isAdreno6xx(context) } catch (e: Exception) { false }
            if (is6xx) {
                Timber.tag(TAG).i("Adreno 6xx (865/888 class) detected — applying IIC tuned defaults")
                // System driver: 6xx Turnip support is limited; use System (or Wrapper without
                // custom Turnip build). The fork's blacklist guards against a broken Turnip on
                // these chips, so we leave graphicsDriver as "Wrapper" but omit the Turnip version
                // override — the stock wrapper selection will fall back to the system GLES path.
                PrefManager.graphicsDriver = "Wrapper"
                PrefManager.box64Preset = Box86_64Preset.COMPATIBILITY
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "2048"
                PrefManager.rendererPresentMode = "fifo"
                PrefManager.setFloat(KEY_ADRENO_6XX, 1f)
                Timber.tag(TAG).i("IIC Adreno 6xx tuned defaults applied successfully")
                return
            } else {
                PrefManager.setFloat(KEY_ADRENO_6XX, 2f)
            }
        }

        // --- Mali-G7xx / Dimensity 9300-9000 class ---
        if (PrefManager.getFloat(KEY_MALI_G7XX, 0f) < 1f) {
            val isMaliG7xx = try { GPUInformation.isMaliG7xx(context) } catch (e: Exception) { false }
            if (isMaliG7xx) {
                Timber.tag(TAG).i("Mali-G7xx (Dimensity 9x00 class) detected — applying IIC tuned defaults")
                // Mali cannot use Turnip/adrenotools — system Vulkan driver only.
                PrefManager.graphicsDriver = "System"
                PrefManager.box64Preset = Box86_64Preset.COMPATIBILITY
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "2048"
                PrefManager.rendererPresentMode = "fifo"
                PrefManager.setFloat(KEY_MALI_G7XX, 1f)
                Timber.tag(TAG).i("IIC Mali-G7xx tuned defaults applied successfully")
                return
            } else {
                PrefManager.setFloat(KEY_MALI_G7XX, 2f)
            }
        }

        // --- Mali lower / Helio class ---
        if (PrefManager.getFloat(KEY_MALI_LOWER, 0f) < 1f) {
            val isMaliLower = try { GPUInformation.isMaliLower(context) } catch (e: Exception) { false }
            if (isMaliLower) {
                Timber.tag(TAG).i("Mali lower (Helio class) detected — applying IIC tuned defaults")
                PrefManager.graphicsDriver = "System"
                PrefManager.box64Preset = Box86_64Preset.COMPATIBILITY
                PrefManager.dxWrapper = "dxvk"
                PrefManager.videoMemorySize = "1024"
                PrefManager.rendererPresentMode = "fifo"
                PrefManager.setFloat(KEY_MALI_LOWER, 1f)
                Timber.tag(TAG).i("IIC Mali lower tuned defaults applied successfully")
                return
            } else {
                PrefManager.setFloat(KEY_MALI_LOWER, 2f)
            }
        }

        Timber.tag(TAG).d("No GPU class matched; leaving stock defaults")
    }
}
