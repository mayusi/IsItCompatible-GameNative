package app.gamenative.ui.screen.autotuner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.autotuner.MeasurementMode
import app.gamenative.autotuner.SweepMode
import app.gamenative.autotuner.TunerGoal

/**
 * Dialog shown before an auto-tune sweep.
 * Lets the user pick Goal, Time Budget (maps to SweepMode), and Measurement Mode.
 *
 * Presents an honest warning about what the sweep does.
 */
@Composable
fun AutoTunerSetupDialog(
    gameName: String,
    onDismiss: () -> Unit,
    onStart: (goal: TunerGoal, mode: SweepMode, measurementMode: MeasurementMode) -> Unit,
) {
    var selectedGoal by rememberSaveable { mutableStateOf(TunerGoal.MAX_FPS) }
    var selectedMode by rememberSaveable { mutableStateOf(SweepMode.STANDARD) }
    var selectedMeasurement by rememberSaveable { mutableStateOf(MeasurementMode.AUTO) }

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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Warning banner
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = "GameNative will repeatedly launch and close $gameName automatically " +
                            "to measure FPS under different configurations. Results reflect " +
                            "menu/early-game performance on this device only. " +
                            "Keep the device charged and cool during the sweep.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Goal
                Text(
                    text = "Optimization Goal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    TunerGoal.entries.forEach { goal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedGoal == goal,
                                    onClick = { selectedGoal = goal },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedGoal == goal,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = goal.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = goal.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Time budget
                Text(
                    text = "Time Budget",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    SweepMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedMode == mode,
                                    onClick = { selectedMode = mode },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = null,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = mode.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Measurement mode
                Text(
                    text = "Measurement Mode",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.selectableGroup()) {
                    MeasurementModeEntry(
                        label = "Auto (hands-off)",
                        subtitle = "Engine measures menu/early-game FPS automatically for each trial.",
                        selected = selectedMeasurement == MeasurementMode.AUTO,
                        onClick = { selectedMeasurement = MeasurementMode.AUTO },
                    )
                    MeasurementModeEntry(
                        label = "Manual (you play)",
                        subtitle = "You play during each trial and tap Stop Recording when ready.",
                        selected = selectedMeasurement == MeasurementMode.MANUAL,
                        onClick = { selectedMeasurement = MeasurementMode.MANUAL },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onStart(selectedGoal, selectedMode, selectedMeasurement) },
            ) {
                Text("Start Auto-tune")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun MeasurementModeEntry(
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
