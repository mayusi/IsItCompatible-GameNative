package app.gamenative.ui.screen.autotuner

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.TunerResult

/**
 * Full-screen auto-tuner progress display.
 *
 * Layout strategy:
 * ─────────────────────────────────────────────────────────────────────
 * While trialIsRunning=true the actual game runs in XServerScreen
 * (underneath, or navigated away from this screen temporarily).
 * The PluviaMain composable observes [pendingLaunch] from the ViewModel
 * and calls the same preLaunchApp/launchApp path it normally uses, then
 * navigates to XServerScreen. When the trial session ends, PluviaMain
 * calls vm.onExitComplete() which flips trialIsRunning back to false,
 * the nav stack pops back here, and the cooldown phase is displayed.
 *
 * MANUAL mode adds a "Stop Recording" button that is only shown when
 * phase == MEASURING.
 */
@Composable
fun AutoTunerProgressScreen(
    viewModel: AutoTunerViewModel,
    gameName: String,
    onCancel: () -> Unit,
    onSweepComplete: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate to results when sweep finishes
    LaunchedEffect(state.phase) {
        if (state.phase == AutoTunerPhase.COMPLETE || state.phase == AutoTunerPhase.ERROR) {
            onSweepComplete()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = gameName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Auto-tuning in progress",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            HorizontalDivider()

            // Trial counter + config description
            if (state.totalTrials > 0) {
                Text(
                    text = "Testing config ${state.trialIndex + 1} / ${state.totalTrials}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                LinearProgressIndicator(
                    progress = { state.trialIndex.toFloat() / state.totalTrials.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.currentDescription.isNotBlank()) {
                Text(
                    text = state.currentDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Phase status card
            PhaseStatusCard(state = state)

            // Best-so-far
            state.bestSoFar?.let { best ->
                BestSoFarCard(result = best)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Manual stop recording button (MANUAL mode + MEASURING phase)
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

            // Cancel button
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

@Composable
private fun PhaseStatusCard(state: AutoTunerUiState) {
    val (label, detail) = when (state.phase) {
        AutoTunerPhase.IDLE -> "Ready" to ""
        AutoTunerPhase.PREPARING -> "Preparing trial..." to ""
        AutoTunerPhase.WAITING_LAUNCH -> "Launching game..." to "Applying config and starting trial"
        AutoTunerPhase.WARMUP ->
            "Warmup" to "Settling in — discarding early readings (${state.remainingSeconds}s remaining)"
        AutoTunerPhase.MEASURING -> {
            val fps = if (state.currentFps > 0f) " — ${state.currentFps.toInt()} fps" else ""
            val rem = if (state.measurementMode == MeasurementMode.AUTO && state.remainingSeconds > 0)
                " (${state.remainingSeconds}s left)" else ""
            "Measuring$fps" to "Recording FPS data$rem"
        }
        AutoTunerPhase.COOLDOWN -> {
            val temp = if (state.gpuTempC >= 0) " — GPU ${state.gpuTempC}°C" else ""
            "Cooling down$temp" to "${state.remainingSeconds}s until next trial"
        }
        AutoTunerPhase.TRIAL_ABORTED -> "Trial aborted" to "Config crashed or timed out — skipping"
        AutoTunerPhase.BETWEEN_TRIALS -> "Processing result..." to ""
        AutoTunerPhase.COMPLETE -> "Sweep complete" to "All trials finished"
        AutoTunerPhase.ERROR -> "Error" to (state.errorMessage ?: "Unknown error")
        AutoTunerPhase.CANCELLED -> "Cancelled" to ""
    }

    Surface(
        color = when (state.phase) {
            AutoTunerPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
            AutoTunerPhase.TRIAL_ABORTED -> MaterialTheme.colorScheme.errorContainer
            AutoTunerPhase.MEASURING -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state.phase) {
                AutoTunerPhase.ERROR, AutoTunerPhase.TRIAL_ABORTED ->
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp),
                    )
                AutoTunerPhase.COMPLETE -> {}
                else ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 3.dp,
                    )
            }

            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = when (state.phase) {
                        AutoTunerPhase.ERROR, AutoTunerPhase.TRIAL_ABORTED ->
                            MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (detail.isNotBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BestSoFarCard(result: TunerResult) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            Text(
                text = "Best so far",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${result.avgFps.toInt()} avg fps — ${result.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
