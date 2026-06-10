package app.gamenative.utils

import android.content.Context
import app.gamenative.PrefManager
import com.winlator.box86_64.Box86_64Preset
import com.winlator.core.GPUInformation
import timber.log.Timber

/**
 * IIC fork: Applies Adreno-830 / Snapdragon-8-Elite optimized default preferences
 * for new containers on first run.
 *
 * Detection: uses the existing GPUInformation.isAdreno8Elite() method, which matches
 * renderer strings containing "adreno" with model numbers 830–859 (regex \b8(3[0-9]|4[0-9]|5[0-9])\b).
 * Adreno 830 (Snapdragon 8 Elite) satisfies this check.
 *
 * When the device matches:
 *  - graphicsDriver      = "Wrapper" (adrenotools/Turnip path, same as setContainerDefaults uses)
 *  - graphicsDriverVersion = "Turnip_Gen8_V25" (the adrenotools-compatible wrapper Turnip build)
 *  - box64Preset         = PERFORMANCE (instead of COMPATIBILITY)
 *  - dxWrapper           = "dxvk"
 *  - videoMemorySize     = "4096"
 *  - rendererPresentMode = "mailbox"
 *
 * If GPU detection is not available at init time (EGL not yet ready), this is
 * a no-op; setContainerDefaults() in ContainerUtils will have already set the
 * graphicsDriver by the time MainActivity calls it.
 *
 * The IIC_ADRENO_TUNED pref key is used as a one-shot flag so these defaults
 * are only stamped once (not re-applied after the user customises them).
 */
object DeviceProfileDetector {

    private const val TAG = "DeviceProfileDetector"

    /** One-shot flag key; once set we never overwrite user customisations again. */
    private const val IIC_ADRENO_TUNED_KEY = "iic_adreno_830_tuned"

    /**
     * Call from Application.onCreate (after PrefManager.init) to apply tuned defaults
     * once on the first run for Adreno-830-class devices.
     *
     * Uses a float pref (value 1.0 = applied, 0.0 = not yet applied) as the one-shot
     * flag because PrefManager exposes public getFloat/setFloat but not setBoolean.
     */
    fun applyAdrenoTunedDefaultsIfNeeded(context: Context) {
        // Already applied — respect any user overrides since then.
        if (PrefManager.getFloat(IIC_ADRENO_TUNED_KEY, 0f) >= 1f) return

        val isAdreno8Elite = try {
            GPUInformation.isAdreno8Elite(context)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "GPU detection failed; skipping Adreno tuned defaults")
            false
        }

        if (!isAdreno8Elite) {
            Timber.tag(TAG).d("Device is not Adreno 8 Elite class; skipping tuned defaults")
            // Mark as checked so we don't retry the EGL query on every launch.
            PrefManager.setFloat(IIC_ADRENO_TUNED_KEY, 2f)
            return
        }

        Timber.tag(TAG).i("Adreno 8 Elite (830+) detected — applying IIC tuned defaults")

        // Wrapper + adrenotools Turnip driver path (matches what setContainerDefaults sets
        // for Adreno8Elite, which runs Turnip via adrenotools wrapper).
        PrefManager.graphicsDriver = "Wrapper"
        PrefManager.graphicsDriverVersion = "Turnip_Gen8_V25"

        // Performance preset for Box64 (instead of COMPATIBILITY default).
        PrefManager.box64Preset = Box86_64Preset.PERFORMANCE

        // DXVK is already the default but set explicitly to ensure it sticks.
        PrefManager.dxWrapper = "dxvk"

        // 4 GB VRAM allocation — Adreno 830 has ample UMA memory.
        PrefManager.videoMemorySize = "4096"

        // Mailbox present mode for lower display latency vs FIFO.
        PrefManager.rendererPresentMode = "mailbox"

        // Stamp the one-shot flag (1f = applied).
        PrefManager.setFloat(IIC_ADRENO_TUNED_KEY, 1f)
        Timber.tag(TAG).i("IIC Adreno 830 tuned defaults applied successfully")
    }
}
