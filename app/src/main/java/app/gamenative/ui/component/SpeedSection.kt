package app.gamenative.ui.component

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.cheats.SpeedhackControl
import app.gamenative.trainer.TrainerShm
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.launch

// Discrete multiplier stops exposed in the UI. Extends to 8x (the patched ntdll + SpeedhackControl
// support up to 16x) so grinding / cutscene-skipping has real headroom, while keeping fine control
// around 1x. The speed hotkeys (SPEED_CYCLE etc.) use their own preset subset.
private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 6.0f, 8.0f)

/**
 * Given a persisted multiplier, return the SPEED_PRESETS index whose value is closest
 * to it (as a Float, ready to seed the slider). Used to re-seed the slider from the
 * per-game saved speed so reopening the trainer shows the active multiplier instead of
 * snapping back to 1.0×. Falls back to the 1.0× index if the list somehow has no exact
 * match for normal speed.
 */
private fun nearestPresetIndex(mult: Float): Float {
    var bestIdx = SPEED_PRESETS.indexOf(1.0f).coerceAtLeast(0)
    var bestDist = Float.MAX_VALUE
    SPEED_PRESETS.forEachIndexed { idx, preset ->
        val dist = kotlin.math.abs(preset - mult)
        if (dist < bestDist) {
            bestDist = dist
            bestIdx = idx
        }
    }
    return bestIdx.toFloat()
}

/**
 * Speed-hack capability section shown inside TrainerTab.
 * Handles its own not-enabled / not-available gating.
 *
 * LIVE: the runtime speed-hack is now UNIVERSAL and ACTIVE. GameNative IIC ships a patched
 * Wine unix ntdll.so that scales monotonic_counter() — the counter QueryPerformanceCounter /
 * GetTickCount / interrupt time all derive from — by a live multiplier read from a control
 * file ([SpeedhackControl]). Moving the slider writes that file; the running game follows
 * within ~250 ms. The old in-Wine ProxyCtrl.setSpeed bridge (dead on arm64ec) is gone. The
 * per-game multiplier is also persisted (PrefManager.getSpeedMultiplier) so the slider
 * remembers its position across menu opens and relaunches.
 *
 * Gating: [TrainerShm].available is reused as the "a game is running / engine up" signal —
 * the speed file is only meaningful while a game is live, so the control is shown under the
 * same gate the memory scanner uses.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SpeedSection(
    trainerShm: TrainerShm?,
    appId: String? = null,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentBrand
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

            trainerShm == null || !trainerShm.available -> {
                // Enabled but the host engine isn't ready (no game running yet / engine
                // not loaded). Same gate the memory scanner uses.
                SpeedMenuSectionHeader(title = stringResource(R.string.speed_not_loaded_title))
                Text(
                    text = stringResource(R.string.speed_not_loaded_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            else -> {
                // Engine available — show the multiplier control. The slider persists its
                // position per game AND drives the live game clock via the control file.
                SpeedControlSection(
                    appId = appId,
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
    appId: String?,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    // The runtime speed-hack is LIVE: the patched ntdll scales the game clock from the control
    // file written by SpeedhackControl. The UI owns the slider state for persistence (saved per
    // game in PrefManager, seeded via rememberSaveable(appId) so reopening remembers position)
    // AND drives the live multiplier — every change both persists and writes the control file.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // prefKey is the persistence key. When appId is null we DON'T persist (in-memory only) so two
    // null-appId games can't share one saved multiplier and silently inherit each other's speed.
    val prefKey = appId
    var sliderIndex by rememberSaveable(appId ?: "") {
        mutableFloatStateOf(nearestPresetIndex(if (prefKey != null) PrefManager.getSpeedMultiplier(prefKey) else 1.0f))
    }
    val currentValue = SPEED_PRESETS[sliderIndex.toInt().coerceIn(0, SPEED_PRESETS.size - 1)]

    // Apply a chosen multiplier: persist it per-game (only when we have a real appId) AND write the
    // live control file the patched ntdll reads. The file write is dispatched off the main thread
    // (the slider's onValueChange runs on the UI thread). Centralised so the slider, chips, and
    // reset all stay consistent.
    fun apply(mult: Float) {
        if (prefKey != null) PrefManager.setSpeedMultiplier(prefKey, mult)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            SpeedhackControl.setMultiplier(context, mult)
        }
    }

    // On (re)entry, push the persisted multiplier to the control file so a slider that already
    // shows e.g. 2× actually drives the game even before the user touches it again. Only when we
    // have a real appId — a null appId has no persisted value to restore (defaults to 1.0).
    androidx.compose.runtime.LaunchedEffect(appId) {
        if (prefKey != null) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SpeedhackControl.setMultiplier(context, PrefManager.getSpeedMultiplier(prefKey))
            }
        }
    }

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
            // Persist + drive the live game clock via the control file.
            apply(SPEED_PRESETS[newIndex])
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
                    apply(preset)
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
                apply(1.0f)
            },
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    // Live status hint — the speed-hack is active, so reflect the current effect honestly.
    if (currentValue != 1.0f) {
        val hint = if (currentValue < 1.0f) {
            stringResource(R.string.speed_active_slowmo, formatMultiplier(currentValue))
        } else {
            stringResource(R.string.speed_active_fast, formatMultiplier(currentValue))
        }
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = accentColor,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

    Spacer(modifier = Modifier.height(4.dp))
    // Discoverability: tell the user they can drive speed from a controller button without the menu.
    Text(
        text = stringResource(R.string.speed_hotkey_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
