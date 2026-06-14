package app.gamenative.utils

import app.gamenative.autotuner.FixLadder

/**
 * Shared Wine debug-log pattern classifier used by both [CrashClassifier] (snackbar suggestions)
 * and [FixLadder] (auto-tuner fix-retry).
 *
 * Single source of truth for pattern→FailureClass mapping so both consumers stay in sync.
 * Adding or updating a pattern here automatically updates both the snackbar classifier and
 * the auto-tuner fix-retry path — they can no longer diverge.
 *
 * Returns a [FixLadder.FailureClass] so [FixLadder.classifyFailure] can delegate directly.
 * [CrashClassifier] uses the returned class to select the right [CrashClassifier.CrashSuggestion].
 */
object WineLogClassifier {

    /**
     * Classify [logLines] into a [FixLadder.FailureClass] by pattern-matching Wine debug output.
     * Returns [FixLadder.FailureClass.UNKNOWN_CRASH] when no pattern matches.
     */
    fun classify(logLines: List<String>): FixLadder.FailureClass {
        if (logLines.isEmpty()) return FixLadder.FailureClass.UNKNOWN_CRASH
        val joined = logLines.joinToString("\n")
        return when {
            // WMV / media-format crash (canonical set — audio/x-wma included)
            joined.contains("Unrecognised format WMV3") ||
                (joined.contains("WMV3") && joined.contains("err:mfmediatype")) ||
                joined.contains("err:winegstreamer:wg_parser_connect") ||
                (joined.contains("err:quartz:") && joined.contains(".wmv")) ||
                joined.contains("audio/x-wma") ||
                (joined.contains("videoconv") && joined.contains("MEDIACONV")) ->
                FixLadder.FailureClass.WMV_CODEC

            // d3dcompiler import failure
            joined.contains(Regex("err:module:import_dll Library d3dcompiler")) ->
                FixLadder.FailureClass.D3D_COMPILER

            // Steam overlay crash
            joined.contains("GameOverlayRenderer64") ||
                joined.contains("GameOverlayRenderer.dll") ->
                FixLadder.FailureClass.STEAM_OVERLAY

            // Epic Online Services crash
            joined.contains("EOS_Platform_Create") ||
                joined.contains("eossdk-win64-shipping") ||
                joined.contains("EOSSDK-Win64-Shipping") ->
                FixLadder.FailureClass.EOS_CRASH

            // MSVC / vcruntime import failure
            joined.contains(Regex("err:module:import_dll Library.*(MSVC|vcruntime|VCRUNTIME|msvcp|MSVCP)")) ->
                FixLadder.FailureClass.MSVC_MISSING

            // Anti-cheat / SEH exception
            joined.contains("err:seh:setup_exception") ->
                FixLadder.FailureClass.SEH_ANTICHEAT

            else -> FixLadder.FailureClass.UNKNOWN_CRASH
        }
    }
}
