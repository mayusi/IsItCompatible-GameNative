package app.gamenative.trainer

import android.util.Log
import com.winlator.core.ProcessHelper

/**
 * Host-side entry point for the cross-process memory-cheat engine (libtrainer.so).
 *
 * ARCHITECTURE
 * ------------
 * The engine runs INSIDE the app process (NOT inside the game / Wine). It reads
 * and writes the game's memory via `/proc/<game-pid>/mem` — same-uid, no ptrace,
 * no root (proven open on-device: yama unrestricted, SELinux does not deny
 * per-process /proc/<pid>/mem|maps for the app's own children).
 *
 * The in-Wine LD_PRELOAD proxy-DLL path is dead on arm64ec; this object replaces
 * it. The flow is:
 *
 *   1. [loadAndStart] — System.loadLibrary("trainer") + nativeStart() (idempotent).
 *      The .so constructor is a no-op; nativeStart() mmaps the shm and spawns the
 *      worker/freeze threads in THIS process. No mem fd is opened yet.
 *   2. [findGamePid] — enumerate same-uid child pids (ProcessHelper.listSubProcesses)
 *      and ask native which one has the game's PE (e.g. "dmc3.exe") mapped. The game
 *      pid appears LATE after launch, so the caller polls (a few retries).
 *   3. TrainerShm.create(context) + connect() + shm.setTargetPid(pid). From then on
 *      every TrainerShm command writes cmd_target_pid and the native worker retargets
 *      /proc/<pid>/mem — the existing CheatExecutor scan/freeze paths then operate on
 *      the GAME's address space unchanged.
 *
 * When [findGamePid] is never given a pid (targetPid stays 0) the engine targets
 * /proc/self/mem — that is the in-app self-test path (TrainerSelfTest), unchanged.
 */
object TrainerEngine {

    private const val TAG = "TrainerEngine"

    @Volatile
    private var started = false

    /** Marshalled by the JVM to Java_app_gamenative_trainer_TrainerEngine_nativeStart. */
    @JvmStatic
    external fun nativeStart()

    /**
     * Marshalled to Java_app_gamenative_trainer_TrainerEngine_nativeFindGamePid.
     * Returns the first [candidates] pid whose /proc/<pid>/maps file-maps a module
     * named [exe] (case-insensitive basename), or -1 if none match.
     */
    @JvmStatic
    external fun nativeFindGamePid(exe: String, candidates: IntArray): Int

    /**
     * Load libtrainer.so and start the engine in this process. Idempotent and safe
     * to call repeatedly (System.loadLibrary refcounts; nativeStart no-ops once up).
     * Returns true if the engine is up after the call, false if loading/starting
     * threw (feature then silently unavailable).
     */
    @Synchronized
    fun loadAndStart(): Boolean {
        if (started) return true
        return try {
            System.loadLibrary("trainer")
            nativeStart()
            started = true
            Log.i(TAG, "loadAndStart: trainer engine started in app process")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "loadAndStart: failed to load/start libtrainer.so", t)
            false
        }
    }

    /**
     * Find the running game's pid by scanning same-uid child processes for one whose
     * maps contain [exe] (e.g. "dmc3.exe"). Returns the pid, or -1 if not found yet
     * (the caller should poll — the game pid appears a moment after launch).
     *
     * Candidate pids come from [ProcessHelper.listSubProcesses], which lists every
     * same-uid process except our own (the Box64/Wine tree runs under the app's uid).
     */
    fun findGamePid(exe: String): Int {
        if (exe.isBlank()) return -1
        if (!started) {
            // Native isn't up yet — nothing to query.
            return -1
        }
        val candidates: IntArray = try {
            ProcessHelper.listSubProcesses()
                .map { it.pid }
                .filter { it > 0 }
                .toIntArray()
        } catch (t: Throwable) {
            Log.w(TAG, "findGamePid: listSubProcesses threw", t)
            return -1
        }
        if (candidates.isEmpty()) return -1
        return try {
            nativeFindGamePid(exe, candidates)
        } catch (t: Throwable) {
            Log.w(TAG, "findGamePid: nativeFindGamePid threw", t)
            -1
        }
    }
}
