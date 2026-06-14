package app.gamenative.autotuner

import android.content.Context
import com.winlator.xenvironment.ImageFs
import org.json.JSONObject
import java.io.File

// =============================================================================
//  GPU TEMPERATURE HELPER
// =============================================================================

internal object GpuTemp {
    private const val KGSL_TEMP_PATH = "/sys/class/kgsl/kgsl-3d0/temp"

    /**
     * Reads GPU temperature from the kgsl sysfs node.
     * Returns -1 if the file is unreadable or the value is out of range.
     * The raw value is in milli-degrees C on most kernels; divide by 1000.
     * Guard: if raw < 1000 assume it was already in degrees C.
     */
    fun readTempC(): Int {
        return try {
            val raw = File(KGSL_TEMP_PATH).readText().trim().toLongOrNull() ?: return -1
            val degrees = if (raw >= 1000L) (raw / 1000L).toInt() else raw.toInt()
            if (degrees in 0..120) degrees else -1
        } catch (_: Exception) {
            -1
        }
    }
}

// =============================================================================
//  GPU BUSY HELPER
// =============================================================================

/**
 * Reads the GPU busy percentage from the kgsl sysfs node.
 *
 * Confirmed readable on Adreno 830 (Snapdragon 8 Elite) at:
 *   /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
 * Format: "N %" — parse leading int, coerce to 0..100.
 * Returns -1 on error or when the file is absent.
 *
 * Needs device verification on non-Adreno boards — see DEVICE-VERIFICATION section.
 */
internal object GpuBusy {
    private const val KGSL_BUSY_PATH = "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"

    /** Returns GPU busy percent 0..100, or -1 if unreadable. */
    fun readPercent(): Int {
        return try {
            val raw = File(KGSL_BUSY_PATH).readText().trim()
            // Format "N %" — grab leading digits
            val n = raw.trimEnd().substringBefore(' ').toIntOrNull() ?: return -1
            n.coerceIn(0, 100)
        } catch (_: Exception) {
            -1
        }
    }
}

// =============================================================================
//  BATTERY / POWER SENSOR HELPER
// =============================================================================

/**
 * Battery and power-draw sensor reader.
 *
 * All reads are wrapped in try/catch — these sysfs paths are world-readable on
 * standard Android kernels (no root required) but may not exist on all devices.
 * All methods return null / false on any failure.
 *
 * Confirmed readable on the Odin 2 Pro (MediaTek / Adreno 8-series) via adb;
 * standard Linux power-supply class paths.  Needs device verification on other
 * boards — see the DEVICE-VERIFICATION section in the engine notes below.
 *
 * Path reference (Linux kernel power_supply class):
 *   /sys/class/power_supply/battery/status       — "Discharging" | "Charging" | "Full" | …
 *   /sys/class/power_supply/battery/power_now    — instantaneous power in µW
 *   /sys/class/power_supply/battery/charge_counter — battery charge in µAh
 */
object BatteryReader {
    private const val BATTERY_PATH = "/sys/class/power_supply/battery"
    private const val STATUS_PATH = "$BATTERY_PATH/status"
    private const val POWER_NOW_PATH = "$BATTERY_PATH/power_now"
    private const val CHARGE_COUNTER_PATH = "$BATTERY_PATH/charge_counter"

    /** Returns true if the battery status file reads "Discharging". */
    fun isDischarging(): Boolean {
        return try {
            File(STATUS_PATH).readText().trim().equals("Discharging", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads instantaneous power draw in watts.
     *
     * Returns null if:
     *  - device is NOT "Discharging" (while charging, power_now reflects charger draw — unreliable)
     *  - the sysfs path does not exist or is unreadable
     *  - the value is outside the plausible range (0..100 W)
     *
     * NOTE: Must only be called/used when [isDischarging] is true; the Discharging guard
     * is also applied inline here for safety.
     */
    fun readPowerW(): Float? {
        return try {
            if (!isDischarging()) return null
            val rawUW = File(POWER_NOW_PATH).readText().trim().toLongOrNull() ?: return null
            val watts = rawUW / 1_000_000f
            if (watts in 0f..100f) watts else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the battery charge counter in µAh.
     * Returns null if the path is unreadable.
     * Use start/end delta to compute energy consumed over a trial.
     */
    fun readChargeCounterUAh(): Int? {
        return try {
            File(CHARGE_COUNTER_PATH).readText().trim().toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }
}

// =============================================================================
//  FPS SESSION FILE READER
// =============================================================================

internal object FpsSessionReader {
    /**
     * Reads fps_session.json written by FrameRating.writeSessionSummary().
     * Returns null if the file does not exist or is unparseable.
     */
    fun read(context: Context): FpsSessionData? {
        return try {
            val imageFs = ImageFs.find(context)
            val file = File(imageFs.getTmpDir(), "fps_session.json")
            if (!file.exists()) return null
            val json = JSONObject(file.readText())
            FpsSessionData(
                avgFps = json.optDouble("avg_fps", 0.0).toFloat(),
                maxFps = json.optInt("max_fps", 0).toFloat(),
                minFps = json.optInt("min_fps", 0).toFloat(),
                readings = json.optInt("readings", 0),
                lengthSec = json.optDouble("length_sec", 0.0).toFloat(),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context) {
        try {
            val imageFs = ImageFs.find(context)
            File(imageFs.getTmpDir(), "fps_session.json").delete()
        } catch (_: Exception) { /* best-effort */ }
    }
}

internal data class FpsSessionData(
    val avgFps: Float,
    val maxFps: Float,
    val minFps: Float,
    val readings: Int,
    val lengthSec: Float,
)
