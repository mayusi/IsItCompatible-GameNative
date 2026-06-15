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

            // DX12 / vkd3d device-creation failure — the device's Vulkan driver cannot satisfy
            // D3D feature level 12 (e.g. Lies of P on ARM Adreno/Mali devices).
            // Note: the game's own "DX12 not supported" MessageBox is app-rendered and may never
            // reach Wine stderr, so we key off the vkd3d/Vulkan device-create failure that
            // PRECEDES it. We require an actual failure/not-supported token alongside the
            // vkd3d/d3d12 context to avoid false-positives on benign fixme:vkd3d lines.
            joined.contains("VK_ERROR_FEATURE_NOT_PRESENT") ||
                joined.contains(Regex("(?i)failed to create.*device")) ||
                joined.contains("D3D_FEATURE_LEVEL_12") ||
                joined.contains(Regex("(?i)feature level 12")) ||
                (joined.contains("vkd3d", ignoreCase = true) &&
                    (joined.contains("not supported", ignoreCase = true) ||
                        joined.contains("Adapter does not support", ignoreCase = true) ||
                        joined.contains("no Vulkan", ignoreCase = true))) ||
                (joined.contains("err:vkd3d") && joined.contains(Regex("(?i)(fail|error|unsupported|not present)"))) ||
                (joined.contains("fixme:vkd3d") && joined.contains(Regex("(?i)(fail|error|unsupported|not present)"))) ->
                FixLadder.FailureClass.D3D12_UNSUPPORTED

            // Steam API / Steam client initialisation failure.
            //
            // FALSE-POSITIVE GUARDS: do NOT match bare "Steam" or "SteamGameId" — the launcher
            // sets SteamGameId=0 on every launch and Wine fixme lines mention Steam constantly.
            // Only the specific high-signal tokens below are matched.
            //
            // Note: the game's own "Could not initialize Steam" MessageBox may be app-rendered
            // (not always on stderr), so we also key off the lsteamclient/steamclient bridge
            // failures that precede it.  STEAM_INIT_FAILED is placed BEFORE STEAM_OVERLAY
            // because init failure is the root cause; overlay crashes are a later symptom.
            joined.contains("SteamAPI_Init() failed", ignoreCase = true) ||
                joined.contains("Steam must be running to play this game", ignoreCase = true) ||
                joined.contains("Could not initialize Steam", ignoreCase = true) ||
                joined.contains("SteamAPI_RestartAppIfNecessary", ignoreCase = true) ||
                (joined.contains("lsteamclient", ignoreCase = true) &&
                    joined.contains("steamclient_main.c") &&
                    joined.contains("Assertion")) ||
                (joined.contains("steamclient", ignoreCase = true) &&
                    joined.contains(Regex("(?i)(failed to load|dlopen FAILED|not found|init.*fail)"))) ->
                FixLadder.FailureClass.STEAM_INIT_FAILED

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
