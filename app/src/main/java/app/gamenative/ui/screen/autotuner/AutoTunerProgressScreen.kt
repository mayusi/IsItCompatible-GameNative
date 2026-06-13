package app.gamenative.ui.screen.autotuner

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.TunerGoal
import app.gamenative.autotuner.TunerResult
import kotlin.math.roundToInt

/**
 * Rich live progress screen — 3 zones:
 *
 * Zone 1 (top)   : Current trial panel — header, trial N/total + progress bar,
 *                  current config label, phase, live FPS (large, coloured),
 *                  GPU temp pill, power pill (when available).
 * Zone 2 (middle): Live leaderboard — allResults/rankedResults sorted by goal,
 *                  relative FPS bars, stability %, temp, power, status badge.
 *                  COMPAT_PROBE shows pass/fail instead of fps ranking.
 * Zone 3 (bottom): Stop Recording (MANUAL + MEASURING only) + Cancel.
 */
@Composable
fun AutoTunerProgressScreen(
    viewModel: AutoTunerViewModel,
    gameName: String,
    onCancel: () -> Unit,
    onSweepComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.phase) {
        if (state.phase == AutoTunerPhase.COMPLETE || state.phase == AutoTunerPhase.ERROR) {
            onSweepComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Zone 1: current trial panel ──────────────────────────────────────
            CurrentTrialPanel(state = state, gameName = gameName)

            HorizontalDivider()

            // ── Zone 2: live leaderboard (fills remaining space) ─────────────────
            val leaderboardResults = if (state.rankedResults.isNotEmpty()) {
                state.rankedResults
            } else {
                state.allResults
            }

            if (leaderboardResults.isNotEmpty()) {
                Text(
                    text = "Live Leaderboard",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                val isCompatProbe = state.goal == TunerGoal.COMPAT_PROBE
                // Find the currently-measuring config label
                val activeLabelLower = state.currentDescription.lowercase()
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 4.dp,
                    ),
                ) {
                    itemsIndexed(leaderboardResults) { rank, result ->
                        val isMeasuringNow = state.trialIsRunning &&
                            (result.description.lowercase() == activeLabelLower ||
                                result.shortLabel.lowercase() == activeLabelLower)
                        LeaderboardRow(
                            rank = rank + 1,
                            result = result,
                            maxFps = leaderboardResults.maxOfOrNull { it.avgFps }?.coerceAtLeast(1f) ?: 1f,
                            isCompatProbe = isCompatProbe,
                            isMeasuringNow = isMeasuringNow,
                        )
                    }
                }
            } else {
                // No results yet
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Waiting for first trial...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // ── Zone 3: controls ─────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.measurementMode == MeasurementMode.MANUAL &&
                    state.phase == AutoTunerPhase.MEASURING
                ) {
                    Button(
                        onClick = viewModel::onManualStopRecording,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Stop Recording")
                    }
                }

                OutlinedButton(
                    onClick = {
                        viewModel.cancel()
                        onCancel()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Cancel Auto-tune")
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zone 1 — Current trial panel
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CurrentTrialPanel(state: AutoTunerUiState, gameName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$gameName · Auto-tuning: ${state.goal.label}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        // Trial counter + progress bar
        if (state.totalTrials > 0) {
            val progress = state.trialIndex.toFloat() / state.totalTrials.toFloat()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Trial ${state.trialIndex + 1} / ${state.totalTrials}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val minsLeft = ((state.totalTrials - state.trialIndex) * 2).coerceAtLeast(1)
                Text(
                    text = "~$minsLeft min remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Current config short label
        if (state.currentDescription.isNotBlank()) {
            val shortLabel = deriveShortLabel(state.currentDescription)
            Text(
                text = shortLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Phase label
        val phaseLabel = phaseLabel(state)
        Text(
            text = phaseLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Live metrics row: FPS + temp + power
        if (state.phase == AutoTunerPhase.MEASURING || state.phase == AutoTunerPhase.WARMUP) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Large live FPS
                if (state.currentFps > 0f || state.phase == AutoTunerPhase.MEASURING) {
                    val fps = state.currentFps.roundToInt()
                    val fpsColor = when {
                        fps >= 45 -> Color(0xFF43A047) // green
                        fps >= 25 -> Color(0xFFF9A825) // yellow
                        else -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = "$fps fps",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = fpsColor,
                    )
                }

                // GPU temp pill
                if (state.gpuTempC >= 0) {
                    TempPill(tempC = state.gpuTempC)
                }

                // Power pill
                state.currentPowerW?.let { pw ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = "~${String.format("%.1f", pw)}W",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }

                // Spinner for active phases
                if (state.phase in listOf(
                        AutoTunerPhase.PREPARING,
                        AutoTunerPhase.WAITING_LAUNCH,
                        AutoTunerPhase.WARMUP,
                        AutoTunerPhase.MEASURING,
                        AutoTunerPhase.COOLDOWN,
                    )
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        // Fix-retry banner
        if (!state.fixRetryMessage.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        text = state.fixRetryMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
        }

        // Error / aborted surface
        if (state.phase == AutoTunerPhase.ERROR || state.phase == AutoTunerPhase.TRIAL_ABORTED) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (state.phase == AutoTunerPhase.ERROR)
                            state.errorMessage ?: "Unknown error"
                        else "Trial aborted — config crashed or timed out",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun TempPill(tempC: Int) {
    val color = when {
        tempC < 55 -> Color(0xFF43A047)
        tempC < 70 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = "${tempC}°C",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

private fun phaseLabel(state: AutoTunerUiState): String = when (state.phase) {
    AutoTunerPhase.IDLE -> "Ready"
    AutoTunerPhase.PREPARING -> "Preparing trial..."
    AutoTunerPhase.WAITING_LAUNCH -> "Launching game..."
    AutoTunerPhase.WARMUP -> "Warmup — settling in (${state.remainingSeconds}s)"
    AutoTunerPhase.MEASURING -> {
        val rem = if (state.measurementMode == MeasurementMode.AUTO && state.remainingSeconds > 0)
            " · ${state.remainingSeconds}s left" else ""
        "Measuring$rem"
    }
    AutoTunerPhase.COOLDOWN -> "Cooling down · ${state.remainingSeconds}s · GPU ${state.gpuTempC}°C"
    AutoTunerPhase.TRIAL_ABORTED -> "Trial aborted"
    AutoTunerPhase.BETWEEN_TRIALS -> "Processing result..."
    AutoTunerPhase.COMPLETE -> "Sweep complete"
    AutoTunerPhase.ERROR -> "Error"
    AutoTunerPhase.CANCELLED -> "Cancelled"
}

private fun deriveShortLabel(description: String): String {
    // Strip "Pass N · DimName:" prefix if present
    val colonIdx = description.lastIndexOf(": ")
    return if (colonIdx >= 0 && description.contains("·")) {
        description.substring(colonIdx + 2).trim()
    } else {
        description.trim()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Zone 2 — Leaderboard row
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LeaderboardRow(
    rank: Int,
    result: TunerResult,
    maxFps: Float,
    isCompatProbe: Boolean,
    isMeasuringNow: Boolean,
) {
    val shimmerAlpha by if (isMeasuringNow) {
        val inf = rememberInfiniteTransition(label = "shimmer")
        inf.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
            label = "shimmerAlpha",
        )
    } else {
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    }

    val rowColor = when {
        isMeasuringNow -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        result.status == TunerResult.TrialStatus.STABLE -> MaterialTheme.colorScheme.surfaceVariant
        result.status == TunerResult.TrialStatus.UNSTABLE -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        result.status == TunerResult.TrialStatus.CRASHED ||
            result.status == TunerResult.TrialStatus.HUNG -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        result.status == TunerResult.TrialStatus.BLACK_SCREEN -> Color(0xFF6A1B9A).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        color = rowColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(shimmerAlpha),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Rank number
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(26.dp),
                )

                // Config label
                val label = result.shortLabel.ifBlank { deriveShortLabel(result.description) }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (result.throttleSuspect) {
                            Text(
                                text = "🌡", // thermometer emoji
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (result.outlierFlagged) {
                            Text(
                                text = "~",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // COMPAT_PROBE: show pass/fail
                if (isCompatProbe) {
                    val passColor = if (result.bootSucceeded) Color(0xFF43A047) else MaterialTheme.colorScheme.error
                    Surface(
                        color = passColor.copy(alpha = 0.15f),
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = if (result.bootSucceeded) "PASS" else "FAIL",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = passColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    // FPS figure
                    if (result.avgFps > 0f) {
                        val fpsColor = when {
                            result.avgFps >= 45f -> Color(0xFF43A047)
                            result.avgFps >= 25f -> Color(0xFFF9A825)
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = "${result.avgFps.roundToInt()} fps",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = fpsColor,
                        )
                    }
                    // Status badge
                    StatusBadge(result.status, isMeasuringNow)
                }
            }

            // FPS relative bar (non-probe only)
            if (!isCompatProbe && result.avgFps > 0f && result.status == TunerResult.TrialStatus.STABLE) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Use fpsNormScore if available, otherwise relative to maxFps
                    val barFraction = if (result.fpsNormScore > 0f) result.fpsNormScore
                    else (result.avgFps / maxFps).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                MaterialTheme.shapes.extraSmall,
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barFraction)
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.shapes.extraSmall,
                                ),
                        )
                    }
                    // Stability %
                    val stabPct = (100f - result.fpsStdDev.coerceAtMost(100f)).roundToInt()
                    Text(
                        text = "$stabPct% stab",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Temp
                    if (result.gpuTempEndC >= 0) {
                        TempPill(tempC = result.gpuTempEndC)
                    }
                    // Power
                    result.avgPowerW?.let { pw ->
                        Text(
                            text = "${String.format("%.1f", pw)}W",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TunerResult.TrialStatus, isMeasuringNow: Boolean) {
    if (isMeasuringNow) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = "MEASURING",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        return
    }
    val (label, color) = when (status) {
        TunerResult.TrialStatus.STABLE -> "STABLE" to Color(0xFF43A047)
        TunerResult.TrialStatus.UNSTABLE -> "UNSTABLE" to Color(0xFFF9A825)
        TunerResult.TrialStatus.CRASHED -> "CRASHED" to Color(0xFFE53935)
        TunerResult.TrialStatus.HUNG -> "HUNG" to Color(0xFFE53935)
        TunerResult.TrialStatus.BLACK_SCREEN -> "BLACK SCREEN" to Color(0xFF6A1B9A)
        TunerResult.TrialStatus.SKIPPED -> "SKIPPED" to Color(0xFF9E9E9E)
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
