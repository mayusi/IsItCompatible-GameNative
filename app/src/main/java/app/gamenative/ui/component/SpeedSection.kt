package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.trainer.SpeedHackShm
import app.gamenative.ui.theme.PluviaTheme

// Discrete multiplier stops exposed in the UI
private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)

/** Snap a raw slider value to the nearest preset. */
private fun snapToPreset(raw: Float): Float =
    SPEED_PRESETS.minByOrNull { kotlin.math.abs(it - raw) } ?: 1.0f

/**
 * Speed-hack capability section shown inside TrainerTab.
 * Handles its own not-enabled / not-available gating.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedSection(
    speedHackShm: SpeedHackShm?,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    var prefEnabled by remember { mutableStateOf(PrefManager.speedHackEnabled) }

    Column(
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            !prefEnabled -> {
                // Not enabled — show explainer + enable toggle
                SpeedNotEnabledSection(
                    accentColor = accentColor,
                    onEnable = {
                        PrefManager.speedHackEnabled = true
                        prefEnabled = true
                    },
                )
            }

            speedHackShm == null || !speedHackShm.available -> {
                // Enabled but not loaded
                SpeedMenuSectionHeader(title = stringResource(R.string.speed_not_loaded_title))
                Text(
                    text = stringResource(R.string.speed_not_loaded_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            else -> {
                // Available — show the multiplier control
                SpeedControlSection(
                    speedHackShm = speedHackShm,
                    accentColor = accentColor,
                )
            }
        }
    }
}

@Composable
private fun SpeedNotEnabledSection(
    accentColor: androidx.compose.ui.graphics.Color,
    onEnable: () -> Unit,
) {
    SpeedMenuSectionHeader(title = stringResource(R.string.speed_not_enabled_title))

    Text(
        text = stringResource(R.string.speed_not_enabled_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    Spacer(modifier = Modifier.height(4.dp))

    // Reuse TrainerToggleRow-alike from TrainerTab via internal visibility
    SpeedToggleRow(
        title = stringResource(R.string.speed_enable_toggle),
        subtitle = stringResource(R.string.speed_enable_note),
        enabled = false,
        accentColor = accentColor,
        onToggle = onEnable,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedControlSection(
    speedHackShm: SpeedHackShm,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    // Initialise from shm so the UI reflects the current engine state.
    // Track as index into SPEED_PRESETS so the slider stays clean.
    val initialValue = snapToPreset(speedHackShm.getMultiplier())
    var sliderIndex by remember {
        mutableFloatStateOf(SPEED_PRESETS.indexOf(initialValue).coerceAtLeast(0).toFloat())
    }
    val currentValue = SPEED_PRESETS[sliderIndex.toInt().coerceIn(0, SPEED_PRESETS.size - 1)]

    SpeedMenuSectionHeader(title = stringResource(R.string.speed_multiplier_label))

    // Big readout
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.speed_multiplier_value, formatMultiplier(currentValue)),
            style = MaterialTheme.typography.headlineMedium,
            color = if (currentValue == 1.0f) MaterialTheme.colorScheme.onSurface else accentColor,
            fontWeight = FontWeight.Bold,
        )
    }

    // Slider over [0, SPEED_PRESETS.size - 1] — each integer = one preset.
    // steps = SPEED_PRESETS.size - 2 gives exactly (size-1) selectable positions.
    Slider(
        value = sliderIndex,
        onValueChange = { raw ->
            val newIndex = raw.toInt().coerceIn(0, SPEED_PRESETS.size - 1)
            sliderIndex = newIndex.toFloat()
            speedHackShm.setMultiplier(SPEED_PRESETS[newIndex])
        },
        valueRange = 0f..(SPEED_PRESETS.size - 1).toFloat(),
        steps = SPEED_PRESETS.size - 2,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        colors = SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    )

    Spacer(modifier = Modifier.height(4.dp))

    // Quick-preset buttons
    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(0.5f, 1.0f, 2.0f).forEach { preset ->
            SpeedPresetChip(
                text = stringResource(R.string.speed_multiplier_value, formatMultiplier(preset)),
                selected = currentValue == preset,
                accentColor = accentColor,
                onClick = {
                    val idx = SPEED_PRESETS.indexOf(preset).coerceAtLeast(0)
                    sliderIndex = idx.toFloat()
                    speedHackShm.setMultiplier(preset)
                },
            )
        }

        // Reset button
        SpeedPresetChip(
            text = stringResource(R.string.speed_reset),
            selected = currentValue == 1.0f,
            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                val idx = SPEED_PRESETS.indexOf(1.0f).coerceAtLeast(0)
                sliderIndex = idx.toFloat()
                speedHackShm.setMultiplier(1.0f)
            },
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    AnimatedVisibility(
        visible = currentValue != 1.0f,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Text(
            text = if (currentValue < 1.0f)
                stringResource(R.string.speed_hint_slowmo)
            else
                stringResource(R.string.speed_hint_fast),
            style = MaterialTheme.typography.bodySmall,
            color = accentColor.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private fun formatMultiplier(value: Float): String {
    return if (value == value.toLong().toFloat()) {
        "${value.toLong()}×"
    } else {
        "${value}×"
    }
}

// ---------------------------------------------------------------------------
// Local sub-components (mirror TrainerTab style)
// ---------------------------------------------------------------------------

@Composable
private fun SpeedMenuSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun SpeedToggleRow(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(accentColor.copy(alpha = 0.16f), accentColor.copy(alpha = 0.08f)),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused) Modifier.border(2.dp, accentColor.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onToggle,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(0.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (enabled) accentColor
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                )
                .border(
                    1.dp,
                    if (enabled) accentColor.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (enabled) "ON" else "OFF",
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) androidx.compose.ui.graphics.Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SpeedPresetChip(
    text: String,
    selected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .height(36.dp)
            .then(
                if (isFocused) Modifier.border(2.dp, accentColor.copy(alpha = 0.7f), shape)
                else Modifier.border(
                    1.dp,
                    if (selected) accentColor.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    shape,
                ),
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                role = Role.Button,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
