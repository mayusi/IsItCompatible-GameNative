package app.gamenative.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.gamenative.BuildConfig
import app.gamenative.PrefManager
import app.gamenative.cheats.CheatExecutor
import app.gamenative.cheats.DiyResult
import app.gamenative.trainer.TrainerEngine
import app.gamenative.trainer.TrainerShm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DEBUG-ONLY adb-driven test hook for the host-side cheat engine + speedhack research.
 *
 * PURPOSE
 * -------
 * Lets a developer exercise the EXACT host-engine path the UI uses (TrainerEngine +
 * TrainerShm + CheatExecutor) against the currently-running game with a single
 * `adb shell am broadcast`, with NO UI tapping. The result is logged to logcat under
 * the tag [TAG] so a test is one command + one `adb logcat -s CheatTest`.
 *
 * This is a TEST HARNESS, never a shipped feature: although the manifest declares the
 * receiver `exported="true"` (so adb can reach it), every action returns immediately
 * unless [BuildConfig.DEBUG] is set. In a release build the receiver is inert — it
 * logs a single "ignored in release build" line and does nothing else.
 *
 * USAGE (modern/debug applicationId = app.gamenative.iic)
 * -------------------------------------------------------
 *   # First scan for an int value of 12345:
 *   adb shell am broadcast \
 *     -a app.gamenative.debug.CHEAT_TEST \
 *     -e op scan -e vtype i32 -e value 12345 \
 *     -n app.gamenative.iic/app.gamenative.debug.CheatTestReceiver
 *
 *   # Narrow to a new value (after the in-game number changed):
 *   adb shell am broadcast -a app.gamenative.debug.CHEAT_TEST \
 *     -e op narrow -e vtype i32 -e value 12300 \
 *     -n app.gamenative.iic/app.gamenative.debug.CheatTestReceiver
 *
 *   # Reset the engine result set + clear freezes:
 *   adb shell am broadcast -a app.gamenative.debug.CHEAT_TEST -e op reset \
 *     -n app.gamenative.iic/app.gamenative.debug.CheatTestReceiver
 *
 *   # Resolve + log the running game pid only (no scan), plus speedhack-research probe:
 *   adb shell am broadcast -a app.gamenative.debug.CHEAT_TEST -e op findpid \
 *     -n app.gamenative.iic/app.gamenative.debug.CheatTestReceiver
 *
 *   adb shell am broadcast -a app.gamenative.debug.CHEAT_TEST -e op probe_clock \
 *     -n app.gamenative.iic/app.gamenative.debug.CheatTestReceiver
 *
 * EXTRAS
 * ------
 *   op     : "scan" | "narrow" | "reset" | "findpid" | "probe_clock"   (default "findpid")
 *   vtype  : "i32" | "f32" (and any other vtypeToTvt token)            (default "i32")
 *   value  : the value to scan/narrow for, as a string                 (default "")
 *
 * ENGINE WIRING (mirrors QuickMenu's LaunchedEffect exactly)
 * ----------------------------------------------------------
 *   1. TrainerEngine.loadAndStart() — load libtrainer.so + nativeStart() in-app (idempotent).
 *   2. Resolve the game's leaf exe from PrefManager.lastGameTargetExe (the field the
 *      launcher stashes), then TrainerEngine.findGamePid(targetExe) to get the game pid.
 *   3. TrainerShm.create(context) + connect() + setTargetPid(pid).
 *   4. CheatExecutor.diyFirstScan / diyNarrow (the SAME calls the DIY scanner UI makes).
 *
 * A FRESH TrainerShm is created here rather than reusing the UI's instance. The native
 * worker is a process-singleton keyed on the mmap'd trainer.mem file, so a fresh
 * TrainerShm wrapper talks to the SAME worker. The worker arbitrates by owner_pid, so a
 * second wrapper in the same process is safe for a one-shot test. (If two live wrappers
 * ever contend on the owner CAS this would surface as a connect/ping failure logged below.)
 */
class CheatTestReceiver : BroadcastReceiver() {

    // Long-lived scope so a goAsync() pending result survives past onReceive().
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        // HARD GATE: inert in release builds even though the manifest exports the receiver.
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "CHEAT_TEST broadcast ignored — not a debug build")
            return
        }

        val op = intent.getStringExtra("op")?.trim()?.lowercase() ?: "findpid"
        val vtype = intent.getStringExtra("vtype")?.trim()?.lowercase() ?: "i32"
        val value = intent.getStringExtra("value")?.trim() ?: ""
        // Extra params used by scanset/scanadjust/readbytes ops.
        val extras = mapOf(
            "to" to (intent.getStringExtra("to") ?: ""),
            "delta" to (intent.getStringExtra("delta") ?: ""),
            "inc" to (intent.getStringExtra("inc") ?: ""),
            "addr" to (intent.getStringExtra("addr") ?: ""),
            "len" to (intent.getStringExtra("len") ?: ""),
        )

        Log.i(TAG, "onReceive: op=$op vtype=$vtype value='$value'")

        // Scans can take seconds — keep the broadcast alive while the coroutine runs.
        val pending = goAsync()
        scope.launch {
            try {
                handle(context.applicationContext, op, vtype, value, extras)
            } catch (t: Throwable) {
                Log.e(TAG, "handler threw for op=$op", t)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, op: String, vtype: String, value: String, extras: Map<String, String> = emptyMap()) {
        // ---- Bring the engine up in this (app) process (idempotent) ----
        val started = TrainerEngine.loadAndStart()
        Log.i(TAG, "TrainerEngine.loadAndStart() -> $started")

        // ---- Resolve the running game pid (mirrors QuickMenu's resolution) ----
        val targetExe = PrefManager.lastGameTargetExe
        if (targetExe.isBlank()) {
            Log.w(TAG, "no PrefManager.lastGameTargetExe stashed — launch a game first. Aborting.")
            return
        }
        val pid = TrainerEngine.findGamePid(targetExe)
        Log.i(TAG, "findGamePid('$targetExe') -> pid=$pid")

        if (op == "probe_clock") {
            // Speedhack-research probe (vDSO interposer work is separate). Log what we know:
            // the resolved game pid + whether the LD_PRELOAD timescale interposer env is set.
            val libtimescale = System.getenv("LD_PRELOAD")?.let { it.contains("timescale") } == true
            Log.i(
                TAG,
                "PROBE_CLOCK: targetExe='$targetExe' gamePid=$pid " +
                    "libtimescalePreloaded=$libtimescale (host-side vDSO clock probe is a separate task)",
            )
            return
        }

        if (op == "findpid") {
            Log.i(TAG, "FINDPID: targetExe='$targetExe' gamePid=$pid")
            return
        }

        if (pid <= 0) {
            Log.w(TAG, "game pid not resolved (pid=$pid) — is the game running? Aborting op=$op")
            return
        }

        // ---- Open a fresh TrainerShm wrapper onto the shared worker + retarget the game ----
        val shm = TrainerShm.create(context)
        if (shm == null) {
            Log.e(TAG, "TrainerShm.create returned null — engine unavailable. Aborting.")
            return
        }
        try {
            val ready = shm.connect()
            Log.i(TAG, "TrainerShm.connect() -> available=$ready")
            if (!ready) {
                Log.e(TAG, "worker did not respond to PING — aborting op=$op")
                return
            }
            shm.setTargetPid(pid)
            Log.i(TAG, "setTargetPid($pid) — engine now retargeted at the game")

            when (op) {
                "scan" -> {
                    Log.i(TAG, "SCAN: diyFirstScan(vtype=$vtype, value='$value')")
                    val r: DiyResult = CheatExecutor.diyFirstScan(shm, vtype, value)
                    logDiy("SCAN", r)
                }
                "narrow" -> {
                    Log.i(TAG, "NARROW: diyNarrow(vtype=$vtype, value='$value')")
                    val r: DiyResult = CheatExecutor.diyNarrow(shm, vtype, value)
                    logDiy("NARROW", r)
                }
                "reset" -> {
                    val ok = CheatExecutor.diyClearAll(shm)
                    Log.i(TAG, "RESET: diyClearAll() -> $ok (result set dropped + freezes cleared)")
                }
                "scanset" -> {
                    // End-to-end write-mode test: scan for `value`, then SET those
                    // matches to `to` (extra: -e to <n>). Proves read+write cross-process.
                    val toValue = extras["to"]?.takeIf { it.isNotEmpty() } ?: value
                    Log.i(TAG, "SCANSET: scan vtype=$vtype value='$value' then SET to '$toValue'")
                    val scan = CheatExecutor.diyFirstScan(shm, vtype, value)
                    logDiy("SCANSET.scan", scan)
                    if (scan.ok && scan.addresses.isNotEmpty()) {
                        val r = CheatExecutor.diySet(shm, scan.addresses, vtype, toValue)
                        logDiy("SCANSET.set", r)
                    } else {
                        Log.w(TAG, "SCANSET: no addresses to set")
                    }
                }
                "scanadjust" -> {
                    // Scan for `value`, then INCREASE each match by `delta` (-e delta <n>).
                    val delta = extras["delta"]?.takeIf { it.isNotEmpty() } ?: "1"
                    val inc = extras["inc"]?.lowercase() != "false"
                    Log.i(TAG, "SCANADJUST: scan value='$value' then ${if (inc) "+" else "-"}$delta")
                    val scan = CheatExecutor.diyFirstScan(shm, vtype, value)
                    logDiy("SCANADJUST.scan", scan)
                    if (scan.ok && scan.addresses.isNotEmpty()) {
                        val r = CheatExecutor.diyAdjust(shm, scan.addresses, vtype, delta, inc)
                        logDiy("SCANADJUST.adjust", r)
                    } else {
                        Log.w(TAG, "SCANADJUST: no addresses to adjust")
                    }
                }
                "readbytes" -> {
                    // Probe TCMD_READ_BYTES against the game at addr (-e addr 0xHEX, -e len N).
                    val addr = (extras["addr"]?.takeIf { it.isNotEmpty() } ?: "0").removePrefix("0x").toLongOrNull(16) ?: 0L
                    val len = extras["len"]?.toIntOrNull() ?: 32
                    val bytes = shm.readBytes(addr, len)
                    if (bytes != null) {
                        Log.i(TAG, "READBYTES: addr=0x${addr.toString(16)} len=$len -> ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02x".format(it) }}")
                    } else {
                        Log.w(TAG, "READBYTES: read failed at 0x${addr.toString(16)}")
                    }
                }
                else -> {
                    Log.w(TAG, "unknown op='$op' — expected scan|narrow|reset|scanset|scanadjust|readbytes|findpid|probe_clock")
                }
            }
        } finally {
            shm.close()
        }
    }

    private fun logDiy(label: String, r: DiyResult) {
        if (!r.ok) {
            Log.w(TAG, "$label result: ok=false count=${r.count} error='${r.error}'")
            return
        }
        val preview = r.addresses.take(8).joinToString(", ") { "0x${it.toString(16).uppercase()}" }
        Log.i(
            TAG,
            "$label result: ok=true count=${r.count} addresses=[${preview}]" +
                if (r.addresses.size > 8) " (+${r.addresses.size - 8} more)" else "",
        )
    }

    companion object {
        const val TAG = "CheatTest"

        /** Broadcast action — must match the manifest intent-filter. */
        const val ACTION = "app.gamenative.debug.CHEAT_TEST"
    }
}
