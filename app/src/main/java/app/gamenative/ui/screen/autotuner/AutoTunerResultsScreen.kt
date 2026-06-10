package app.gamenative.ui.screen.autotuner

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.SweepMode
import app.gamenative.autotuner.TunerGoal
import app.gamenative.autotuner.TunerOutcome
import app.gamenative.autotuner.TunerResult
import app.gamenative.autotuner.TunerShareUtils
import kotlin.math.abs
import kotlin.math.roundToInt

/** Column to sort the full table by. */
private enum class SortColumn { FPS, STAB, TEMP, POWER, STATUS }

/**
 * Rich results screen — 4 sections:
 *
 * Section 1  WINNER card      : shortLabel + all axes + advisory + Apply button.
 *                               COMPAT_PROBE: pass/fail summary instead.
 * Section 2  TRADEOFF         : top-3 STABLE configs side-by-side with 1-line "why"
 *                               + per-row "Apply #N instead" buttons.
 * Section 3  FULL TABLE       : every config, sortable by column header, row-tap
 *                               expands to full details + per-row Apply.
 * Section 4  CHARTS           : stubbed (follow-up noted in report).
 *
 * Keep: Share / GitHub-issue section wired to user-selected applied config.
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
        onDismiss()
        return
    }

    // Track which result was most recently applied (for Share section + "applied" badge)
    var appliedResult by remember { mutableStateOf(outcome.winner) }
    var appliedDone by rememberSaveable { mutableStateOf(false) }

    var sortColumn by rememberSaveable { mutableStateOf(SortColumn.FPS) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-tune Results: $gameName") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Dismiss")
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
            // ── Section 1: WINNER ────────────────────────────────────────────────
            val isCompatProbe = outcome.goal == TunerGoal.COMPAT_PROBE
            if (isCompatProbe) {
                CompatProbeWinnerCard(outcome = outcome)
            } else {
                val winner = outcome.winner
                if (winner == null) {
                    NoWinnerCard(outcome = outcome)
                } else {
                    WinnerCard(
                        winner = winner,
                        outcome = outcome,
                        appliedDone = appliedDone,
                        onApply = {
                            viewModel.applyWinner(context)
                            appliedResult = winner
                            appliedDone = true
                        },
                    )
                }
            }

            // ── Section 2: TRADEOFF ──────────────────────────────────────────────
            if (!isCompatProbe) {
                val top3 = outcome.rankedResults
                    .filter { it.status == TunerResult.TrialStatus.STABLE }
                    .take(3)
                if (top3.size >= 2) {
                    TradeoffSection(
                        results = top3,
                        onApply = { r ->
                            viewModel.applyConfig(context, r)
                            appliedResult = r
                            appliedDone = true
                        },
                        appliedResult = appliedResult,
                    )
                }
            }

            // Applied confirmation banner
            if (appliedDone) {
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
                        val name = appliedResult?.shortLabel?.ifBlank { appliedResult?.description } ?: "Config"
                        Text(
                            text = "$name applied — launch the game to use it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            // Share section (wired to most recently applied config / winner)
            if (!isCompatProbe) {
                val effectiveOutcome = if (appliedResult != null && appliedResult != outcome.winner) {
                    outcome.copy(winner = appliedResult)
                } else outcome
                ShareResultSection(
                    gameName = gameName,
                    appId = state.appId,
                    outcome = effectiveOutcome,
                    sweepMode = state.mode,
                    measurementMode = state.measurementMode,
                )
            }

            // Disclaimer
            DisclaimerCard()

            HorizontalDivider()

            // ── Section 3: FULL SORTABLE TABLE ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "All Results (${outcome.completedTrials}/${outcome.totalTrials} trials)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // Sort header row
            SortHeaderRow(sortColumn = sortColumn, onSort = { sortColumn = it })

            val sortedResults = sortResults(outcome.allResults, sortColumn)
            sortedResults.forEachIndexed { idx, result ->
                FullTableRow(
                    index = idx + 1,
                    result = result,
                    isCompatProbe = isCompatProbe,
                    maxFps = sortedResults.maxOfOrNull { it.avgFps }?.coerceAtLeast(1f) ?: 1f,
                    appliedResult = appliedResult,
                    onApply = { r ->
                        viewModel.applyConfig(context, r)
                        appliedResult = r
                        appliedDone = true
                    },
                )
            }

            // ── Section 4: Charts — stubbed ─────────────────────────────────────
            // TODO(follow-up): Canvas FPS bar chart + temp-vs-fps scatter.
            // Skipped in this pass; the leaderboard bars already give relative
            // FPS context. A dedicated chart composable can be added in v1.12.0.

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

// ─────────────────────────────────────────────────────────────────────────────
// Section 1 — Winner card
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WinnerCard(
    winner: TunerResult,
    outcome: TunerOutcome,
    appliedDone: Boolean,
    onApply: () -> Unit,
) {
    // Check for near-tie advisory
    val secondBest = outcome.rankedResults
        .filter { it.status == TunerResult.TrialStatus.STABLE && it != winner }
        .firstOrNull()

    val showAdvisory = winner.throttleSuspect ||
        (secondBest != null && secondBest.gpuTempEndC >= 0 && winner.gpuTempEndC >= 0 &&
            secondBest.gpuTempEndC < winner.gpuTempEndC - 5 &&
            abs(secondBest.avgFps - winner.avgFps) < winner.avgFps * 0.05f)

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
                text = "Winner — ${outcome.goal.label}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            val name = winner.shortLabel.ifBlank { winner.description }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                StatItem("Avg FPS", "${winner.avgFps.roundToInt()}")
                StatItem("Min FPS", "${winner.minFps.roundToInt()}")
                StatItem("Max FPS", "${winner.maxFps.roundToInt()}")
                StatItem("Stability", "${(100f - winner.fpsStdDev.coerceAtMost(100f)).roundToInt()}%")
                if (winner.gpuTempEndC >= 0) {
                    StatItem("GPU Temp", "${winner.gpuTempEndC}°C")
                }
                winner.avgPowerW?.let { pw ->
                    StatItem("Power", "${String.format("%.1f", pw)}W")
                }
            }

            // Advisory
            if (showAdvisory) {
                val msg = buildString {
                    if (winner.throttleSuspect) append("Runs hot (throttle suspected)")
                    if (secondBest != null && secondBest.gpuTempEndC < winner.gpuTempEndC - 5) {
                        if (isNotEmpty()) append(" — ")
                        append("#2 (${secondBest.avgFps.roundToInt()} fps, ${secondBest.gpuTempEndC}°C) may be better for long sessions.")
                    }
                }
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
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }

            // Apply button
            if (!appliedDone) {
                Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Apply This Config")
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
            style = MaterialTheme.typography.titleSmall,
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
private fun CompatProbeWinnerCard(outcome: TunerOutcome) {
    val passing = outcome.allResults.filter { it.bootSucceeded }
    val failing = outcome.allResults.filter { !it.bootSucceeded }

    Surface(
        color = if (passing.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (passing.isNotEmpty()) "Compatibility Probe — Success"
                else "Compatibility Probe — Failed",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (passing.isNotEmpty()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error,
            )
            if (passing.isNotEmpty()) {
                val first = passing.first()
                val name = first.shortLabel.ifBlank { first.description }
                Text(
                    text = "Runs with: $name (${passing.size}/${outcome.allResults.size} configs passed)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                if (failing.isNotEmpty()) {
                    Text(
                        text = "Failed configs: ${failing.joinToString { it.shortLabel.ifBlank { it.description } }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            } else {
                Text(
                    text = "Couldn't get it running — all ${outcome.allResults.size} configs failed. " +
                        "This may be a game compatibility issue.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
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
                text = "All ${outcome.completedTrials} trials crashed, hung, or produced " +
                    "FPS below the playable threshold.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section 2 — Tradeoff comparison
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TradeoffSection(
    results: List<TunerResult>,
    onApply: (TunerResult) -> Unit,
    appliedResult: TunerResult?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Tradeoffs — Top ${results.size} Stable Configs",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Apply a different tradeoff instead of the auto-winner.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            results.forEachIndexed { idx, result ->
                val isApplied = appliedResult == result
                val why = buildTradeoffWhy(idx, result, results)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "#${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = result.shortLabel.ifBlank { result.description },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // metrics
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${result.avgFps.roundToInt()} fps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${(100f - result.fpsStdDev.coerceAtMost(100f)).roundToInt()}% stab",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (result.gpuTempEndC >= 0) {
                                Text(
                                    text = "${result.gpuTempEndC}°C",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tempColor(result.gpuTempEndC),
                                )
                            }
                            result.avgPowerW?.let { pw ->
                                Text(
                                    text = "${String.format("%.1f", pw)}W",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // why
                        if (why.isNotBlank()) {
                            Text(
                                text = why,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            )
                        }
                    }
                    if (isApplied) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Applied",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    } else if (idx > 0) {
                        TextButton(
                            onClick = { onApply(result) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 4.dp,
                            ),
                        ) {
                            Text(
                                text = "Apply #${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                if (idx < results.size - 1) HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

private fun buildTradeoffWhy(idx: Int, result: TunerResult, all: List<TunerResult>): String {
    if (idx == 0) return "Auto-winner"
    val winner = all.first()
    val fpsDiff = result.avgFps - winner.avgFps
    val tempDiff = if (result.gpuTempEndC >= 0 && winner.gpuTempEndC >= 0)
        result.gpuTempEndC - winner.gpuTempEndC else null
    return buildString {
        if (fpsDiff > 0) append("← ${fpsDiff.roundToInt()} fps faster") else if (fpsDiff < -1) append("← ${(-fpsDiff).roundToInt()} fps slower")
        if (tempDiff != null) {
            if (isNotEmpty()) append(", ")
            if (tempDiff < -2) append("${(-tempDiff)}°C cooler") else if (tempDiff > 2) append("${tempDiff}°C hotter")
            else append("similar temp")
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Section 3 — Full sortable table
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SortHeaderRow(sortColumn: SortColumn, onSort: (SortColumn) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(22.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Config",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortHeader("FPS", SortColumn.FPS, sortColumn, onSort)
        SortHeader("Stab", SortColumn.STAB, sortColumn, onSort)
        SortHeader("Temp", SortColumn.TEMP, sortColumn, onSort)
        SortHeader("Status", SortColumn.STATUS, sortColumn, onSort)
    }
}

@Composable
private fun SortHeader(label: String, col: SortColumn, current: SortColumn, onSort: (SortColumn) -> Unit) {
    Text(
        text = if (current == col) "$label ▾" else label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (current == col) FontWeight.Bold else FontWeight.Normal,
        color = if (current == col) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clickable { onSort(col) }
            .padding(horizontal = 2.dp),
    )
}

private fun sortResults(results: List<TunerResult>, col: SortColumn): List<TunerResult> = when (col) {
    SortColumn.FPS -> results.sortedByDescending { it.avgFps }
    SortColumn.STAB -> results.sortedBy { it.fpsStdDev }
    SortColumn.TEMP -> results.sortedBy { if (it.gpuTempEndC < 0) Int.MAX_VALUE else it.gpuTempEndC }
    SortColumn.POWER -> results.sortedBy { it.avgPowerW ?: Float.MAX_VALUE }
    SortColumn.STATUS -> results.sortedWith(compareBy<TunerResult> { statusSortKey(it.status) }.thenByDescending { it.avgFps })
}

private fun statusSortKey(s: TunerResult.TrialStatus): Int = when (s) {
    TunerResult.TrialStatus.STABLE -> 0
    TunerResult.TrialStatus.UNSTABLE -> 1
    TunerResult.TrialStatus.CRASHED -> 2
    TunerResult.TrialStatus.HUNG -> 3
    TunerResult.TrialStatus.SKIPPED -> 4
}

@Composable
private fun FullTableRow(
    index: Int,
    result: TunerResult,
    isCompatProbe: Boolean,
    maxFps: Float,
    appliedResult: TunerResult?,
    onApply: (TunerResult) -> Unit,
) {
    var expanded by rememberSaveable(result.trialIndex) { mutableStateOf(false) }
    val isApplied = appliedResult == result

    val rowColor = when (result.status) {
        TunerResult.TrialStatus.STABLE -> MaterialTheme.colorScheme.surfaceVariant
        TunerResult.TrialStatus.UNSTABLE -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
    }

    Surface(
        color = if (isApplied) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else rowColor,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "$index.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(22.dp),
                )

                val label = result.shortLabel.ifBlank { result.description }
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
                            Text("🌡", style = MaterialTheme.typography.labelSmall)
                        }
                        if (result.outlierFlagged) {
                            Text(
                                "~",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (isApplied) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Applied",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }

                // Key metrics inline
                if (isCompatProbe) {
                    val c = if (result.bootSucceeded) Color(0xFF43A047) else MaterialTheme.colorScheme.error
                    Text(
                        text = if (result.bootSucceeded) "PASS" else "FAIL",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = c,
                    )
                } else {
                    if (result.avgFps > 0f) {
                        Text(
                            text = "${result.avgFps.roundToInt()} fps",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = fpsColor(result.avgFps),
                        )
                    }
                    if (result.gpuTempEndC >= 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "${result.gpuTempEndC}°C",
                            style = MaterialTheme.typography.labelSmall,
                            color = tempColor(result.gpuTempEndC),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = result.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(result.status),
                    )
                }
            }

            // Expanded detail
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HorizontalDivider()
                    if (!isCompatProbe && result.avgFps > 0f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            DetailStat("Avg FPS", "${result.avgFps.roundToInt()}")
                            DetailStat("Min FPS", "${result.minFps.roundToInt()}")
                            DetailStat("Max FPS", "${result.maxFps.roundToInt()}")
                            DetailStat("StdDev", "${result.fpsStdDev.roundToInt()}")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (result.gpuTempEndC >= 0) DetailStat("GPU temp", "${result.gpuTempEndC}°C")
                        result.avgPowerW?.let { DetailStat("Power", "${String.format("%.1f", it)}W") }
                        if (result.runsCompleted > 1) DetailStat("Runs avg", "${result.runsCompleted}")
                    }
                    Text(
                        text = "Config: ${result.description}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isApplied) {
                        OutlinedButton(
                            onClick = { onApply(result) },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 6.dp,
                            ),
                        ) {
                            Text(
                                text = "Apply this config",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Share section (kept from original, wired to appliedResult)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ShareResultSection(
    gameName: String,
    appId: String,
    outcome: TunerOutcome,
    sweepMode: SweepMode,
    measurementMode: MeasurementMode,
) {
    val context = LocalContext.current
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
                text = "Results were measured on this device (menu/early-game FPS only). " +
                    "The config JSON matches the IIC authored-config format.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
private fun DisclaimerCard() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = "Note: Performance was measured on menu / early-game only. " +
                "In-game performance at heavier scenes may differ. " +
                "Results are specific to this device, temperature, and battery state at the time of the sweep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Colour helpers
// ─────────────────────────────────────────────────────────────────────────────

private fun fpsColor(fps: Float): Color = when {
    fps >= 45f -> Color(0xFF43A047)
    fps >= 25f -> Color(0xFFF9A825)
    else -> Color(0xFFE53935)
}

@Composable
private fun tempColor(tempC: Int): Color = when {
    tempC < 55 -> Color(0xFF43A047)
    tempC < 70 -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.error
}

@Composable
private fun statusColor(status: TunerResult.TrialStatus): Color = when (status) {
    TunerResult.TrialStatus.STABLE -> Color(0xFF43A047)
    TunerResult.TrialStatus.UNSTABLE -> Color(0xFFF9A825)
    else -> MaterialTheme.colorScheme.error
}
