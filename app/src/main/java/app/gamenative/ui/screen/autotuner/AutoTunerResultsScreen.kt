package app.gamenative.ui.screen.autotuner

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.SweepMode
import app.gamenative.autotuner.TunerOutcome
import app.gamenative.autotuner.TunerResult
import app.gamenative.autotuner.TunerShareUtils

/**
 * Shows the final sweep results:
 *  - Winner card with human-readable description + measured stats
 *  - Honest disclaimer (measured on menu / early-game only)
 *  - Apply This Config button
 *  - Expandable all-results table
 *  - Dismiss button
 *
 *  NOTE: "Apply This Config" only persists the winner config to disk and
 *  stores the summary in extraData — it does NOT relaunch the game.
 *  The user must manually launch the game afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoTunerResultsScreen(
    viewModel: AutoTunerViewModel,
    gameName: String,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val outcome = state.outcome ?: run {
        // Fallback: if we landed here without a completed outcome, dismiss immediately.
        onDismiss()
        return
    }

    var showAllResults by remember { mutableStateOf(false) }
    var applyDone by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-tune Results: $gameName") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Dismiss",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val winner = outcome.winner
            if (winner == null) {
                NoWinnerCard(outcome = outcome)
            } else {
                WinnerCard(winner = winner, goal = outcome.goal.label)

                // Apply button
                if (!applyDone) {
                    Button(
                        onClick = {
                            viewModel.applyWinner(context)
                            applyDone = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Apply This Config")
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Config applied — launch the game to use it.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                // Share / Contribute button row
                ShareResultSection(
                    gameName = gameName,
                    appId = state.appId,
                    outcome = outcome,
                    sweepMode = state.mode,
                    measurementMode = state.measurementMode,
                )
            }

            // Disclaimer
            DisclaimerCard()

            // All results toggle
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "All Results (${outcome.completedTrials}/${outcome.totalTrials} trials)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showAllResults = !showAllResults }) {
                    Icon(
                        imageVector = if (showAllResults) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (showAllResults) "Collapse" else "Expand",
                    )
                }
            }

            AnimatedVisibility(visible = showAllResults) {
                AllResultsTable(results = outcome.allResults)
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Dismiss")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Share / Contribute section shown below the Apply button.
 *
 * HONEST DESIGN NOTE:
 * There is no "contribute tuner result" API endpoint on api.gamenative.app —
 * /api/best-config and /api/game-runs are GET/fetch-only, and /api/game-run accepts
 * user-played ratings, not auto-tuner sweep results.
 *
 * This section uses an Android ACTION_SEND share intent instead:
 *   - "Share result" fires a system share sheet with the full formatted result +
 *     IIC-compatible config JSON. The user can paste it wherever they like.
 *   - "Open GitHub issue" opens a pre-filled issue on mayusi/IsItCompatible
 *     (the repo where authored-windows-runners.json lives) so the maintainer
 *     can review and merge the result into the authored config DB.
 *
 * The shared JSON matches the gameNativeConfig schema in authored-windows-runners.json,
 * so merging a contributed result is a straightforward copy-paste.
 */
@Composable
private fun ShareResultSection(
    gameName: String,
    appId: String,
    outcome: TunerOutcome,
    sweepMode: SweepMode,
    measurementMode: MeasurementMode,
) {
    val context = LocalContext.current

    // Build the share payload once (only when winner exists).
    val payload = remember(outcome, gameName, appId) {
        TunerShareUtils.buildSharePayload(
            context = context,
            gameName = gameName,
            appId = appId,
            outcome = outcome,
            sweepMode = sweepMode,
            measurementMode = measurementMode,
        )
    }

    if (payload == null) return

    HorizontalDivider()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Share device-measured result",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "These results were empirically measured on this device (menu/early-game FPS only). " +
                    "Sharing them lets others see what worked on your hardware. " +
                    "The config JSON matches the IIC authored-config format for easy merging.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ACTION_SEND share intent — routes to any installed share target.
                OutlinedButton(
                    onClick = { TunerShareUtils.shareViaIntent(context, payload) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("Share result")
                }

                // Pre-filled GitHub issue on mayusi/IsItCompatible.
                OutlinedButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload.githubIssueUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Open GitHub issue")
                }
            }
        }
    }
}

@Composable
private fun WinnerCard(winner: TunerResult, goal: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Winner — $goal",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = winner.description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem(label = "Avg FPS", value = "${winner.avgFps.toInt()}")
                StatItem(label = "Min FPS", value = "${winner.minFps.toInt()}")
                StatItem(label = "Max FPS", value = "${winner.maxFps.toInt()}")
                StatItem(label = "Stability", value = "${(100f - winner.fpsStdDev.coerceAtMost(100f)).toInt()}%")
            }
            if (winner.throttleSuspect) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(0.dp),
                    )
                    Text(
                        text = "Thermal throttling suspected during this trial",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun NoWinnerCard(outcome: TunerOutcome) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "No stable config found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "All ${outcome.completedTrials} trials either crashed, hung, or produced " +
                    "FPS below the playable threshold. " +
                    "This may indicate a game compatibility issue rather than a config problem.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun DisclaimerCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Note: Performance was measured on menu / early-game only. " +
                "In-game performance at heavier scenes may differ significantly. " +
                "Results are specific to this device, temperature, and battery state " +
                "at the time of the sweep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun AllResultsTable(results: List<TunerResult>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        results.forEachIndexed { idx, result ->
            Surface(
                color = when (result.status) {
                    TunerResult.TrialStatus.STABLE -> MaterialTheme.colorScheme.surfaceVariant
                    TunerResult.TrialStatus.UNSTABLE -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                },
                shape = MaterialTheme.shapes.small,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${idx + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.description,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = result.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (result.status) {
                                TunerResult.TrialStatus.STABLE -> MaterialTheme.colorScheme.primary
                                TunerResult.TrialStatus.UNSTABLE -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    if (result.avgFps > 0f) {
                        Text(
                            text = "${result.avgFps.toInt()} fps",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
