package app.gamenative.ui.screen.autotuner

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrStrong
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.autotuner.GoalWeights
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.SweepMode
import app.gamenative.autotuner.TunerGoal

private fun goalIcon(goal: TunerGoal): ImageVector = when (goal) {
    TunerGoal.COMPAT_PROBE -> Icons.Default.Visibility
    TunerGoal.MAX_FPS -> Icons.Default.FlashOn
    TunerGoal.FPS_STABLE -> Icons.Default.HdrStrong
    TunerGoal.FPS_BATTERY -> Icons.Default.BatteryAlert
    TunerGoal.FPS_COOL -> Icons.Default.Thermostat
    TunerGoal.LOW_END -> Icons.Default.Speed
    TunerGoal.CUSTOM -> Icons.Default.Tune
}

private fun goalEstTime(goal: TunerGoal, mode: SweepMode): String = when {
    goal.isFastProbe -> "~5-9 min"
    mode == SweepMode.QUICK -> "~10-15 min"
    mode == SweepMode.STANDARD -> "~35-45 min"
    mode == SweepMode.THOROUGH -> "~50-65 min"
    else -> ""
}

/**
 * Bundles the four CUSTOM goal weight sliders and their callbacks into a single
 * object so [GoalCard] stays well under the Compose dex-verifier register limit.
 */
private data class GoalCardCustomWeights(
    val fpsW: Float,
    val stabW: Float,
    val battW: Float,
    val tempW: Float,
    val onFpsW: (Float) -> Unit,
    val onStabW: (Float) -> Unit,
    val onBattW: (Float) -> Unit,
    val onTempW: (Float) -> Unit,
)

/**
 * Redesigned intent-picker dialog.
 *
 * Each of the 7 goals is shown as a selectable card: icon + bold title +
 * 1-line description + est-time badge. Selecting a card expands it in-place
 * to show step-2 inline (time-budget + measurement mode, or COMPAT_PROBE
 * just shows a Start button). CUSTOM goal adds weight sliders.
 *
 * onStart signature extended to carry customWeights for CUSTOM goal.
 */
@Composable
fun AutoTunerSetupDialog(
    gameName: String,
    batteryAvailable: Boolean = false,
    onDismiss: () -> Unit,
    onStart: (goal: TunerGoal, mode: SweepMode, measurementMode: MeasurementMode, customWeights: GoalWeights?) -> Unit,
) {
    var selectedGoal by rememberSaveable { mutableStateOf(TunerGoal.COMPAT_PROBE) }
    var selectedMode by rememberSaveable { mutableStateOf(SweepMode.STANDARD) }
    var selectedMeasurement by rememberSaveable { mutableStateOf(MeasurementMode.AUTO) }

    // CUSTOM weight sliders (0-100 int range, normalised before passing to engine)
    var customFpsW by rememberSaveable { mutableFloatStateOf(50f) }
    var customStabW by rememberSaveable { mutableFloatStateOf(30f) }
    var customBattW by rememberSaveable { mutableFloatStateOf(10f) }
    var customTempW by rememberSaveable { mutableFloatStateOf(10f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Auto-tune: $gameName",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Warning banner
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "GameNative will repeatedly launch and close $gameName to measure " +
                            "FPS under different configs. Keep the device cool during the sweep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Choose goal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )

                val customWeights = GoalCardCustomWeights(
                    fpsW = customFpsW,
                    stabW = customStabW,
                    battW = customBattW,
                    tempW = customTempW,
                    onFpsW = { customFpsW = it },
                    onStabW = { customStabW = it },
                    onBattW = { customBattW = it },
                    onTempW = { customTempW = it },
                )
                TunerGoal.entries.forEach { goal ->
                    val isSelected = selectedGoal == goal
                    GoalCard(
                        goal = goal,
                        isSelected = isSelected,
                        onClick = { selectedGoal = goal },
                        expanded = isSelected,
                        batteryAvailable = batteryAvailable,
                        selectedMode = selectedMode,
                        onModeSelected = { selectedMode = it },
                        selectedMeasurement = selectedMeasurement,
                        onMeasurementSelected = { selectedMeasurement = it },
                        customWeights = customWeights,
                        onStart = {
                            val weights = if (goal == TunerGoal.CUSTOM) {
                                val total = customFpsW + customStabW + customBattW + customTempW
                                val s = if (total > 0f) total else 1f
                                GoalWeights(customFpsW / s, customStabW / s, customBattW / s, customTempW / s)
                            } else null
                            onStart(goal, selectedMode, selectedMeasurement, weights)
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun GoalCard(
    goal: TunerGoal,
    isSelected: Boolean,
    onClick: () -> Unit,
    expanded: Boolean,
    batteryAvailable: Boolean,
    selectedMode: SweepMode,
    onModeSelected: (SweepMode) -> Unit,
    selectedMeasurement: MeasurementMode,
    onMeasurementSelected: (MeasurementMode) -> Unit,
    customWeights: GoalCardCustomWeights,
    onStart: () -> Unit,
) {
    val estTime = goalEstTime(goal, selectedMode)
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    else MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = goalIcon(goal),
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = goal.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = goal.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // est-time badge
                if (estTime.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = estTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // Inline step-2 expansion
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider()

                    if (goal.isFastProbe) {
                        // COMPAT_PROBE: fixed PROBE sweep, no time-budget picker
                        Text(
                            text = "Tests ~6 archetype configs for basic stability. " +
                                "No FPS quality ranking. Stops at first successful boot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text("Start Auto-tune")
                        }
                    } else {
                        // FPS_BATTERY charging warning
                        if (goal == TunerGoal.FPS_BATTERY && !batteryAvailable) {
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
                                        imageVector = Icons.Default.BatteryAlert,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "Device is charging — battery metric needs it unplugged. " +
                                            "Will fall back to FPS + Stability. Unplug for accurate battery results.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }

                        // Time budget picker (PROBE not included per spec)
                        Text(
                            text = "Time Budget",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.selectableGroup()) {
                            SweepMode.entries
                                .filter { it != SweepMode.PROBE }
                                .forEach { mode ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = selectedMode == mode,
                                                onClick = { onModeSelected(mode) },
                                                role = Role.RadioButton,
                                            )
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = selectedMode == mode, onClick = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = mode.label,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Text(
                                                text = mode.description,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                        }

                        // Measurement mode
                        Text(
                            text = "Measurement Mode",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.selectableGroup()) {
                            MeasurementModeRow(
                                label = "Auto (hands-off)",
                                subtitle = "Engine reads menu/early-game FPS automatically.",
                                selected = selectedMeasurement == MeasurementMode.AUTO,
                                onClick = { onMeasurementSelected(MeasurementMode.AUTO) },
                            )
                            MeasurementModeRow(
                                label = "Manual (you play)",
                                subtitle = "You play during each trial and tap Stop Recording.",
                                selected = selectedMeasurement == MeasurementMode.MANUAL,
                                onClick = { onMeasurementSelected(MeasurementMode.MANUAL) },
                            )
                        }

                        // CUSTOM: weight sliders
                        if (goal == TunerGoal.CUSTOM) {
                            HorizontalDivider()
                            Text(
                                text = "Custom Weights (auto-normalised)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            WeightSliderRow("FPS", customWeights.fpsW, customWeights.onFpsW, enabled = true)
                            WeightSliderRow("Stability", customWeights.stabW, customWeights.onStabW, enabled = true)
                            WeightSliderRow("Battery", customWeights.battW, customWeights.onBattW, enabled = batteryAvailable)
                            if (!batteryAvailable) {
                                Text(
                                    text = "Battery weight disabled — device is charging.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            WeightSliderRow("Temperature", customWeights.tempW, customWeights.onTempW, enabled = true)
                        }

                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text("Start Auto-tune")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeasurementModeRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WeightSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            Text(
                text = value.toInt().toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        Slider(
            value = if (enabled) value else 0f,
            onValueChange = { if (enabled) onValueChange(it) },
            valueRange = 0f..100f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
