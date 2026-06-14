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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import app.gamenative.trainer.MacroShm
import app.gamenative.ui.theme.PluviaTheme

// ---------------------------------------------------------------------------
// Button definitions (SDL order, Guide(5) excluded from UI)
// ---------------------------------------------------------------------------

private data class GameButton(val index: Int, val labelResId: Int)

private val TURBO_BUTTONS = listOf(
    GameButton(0, R.string.macro_btn_a),
    GameButton(1, R.string.macro_btn_b),
    GameButton(2, R.string.macro_btn_x),
    GameButton(3, R.string.macro_btn_y),
    GameButton(9, R.string.macro_btn_lb),
    GameButton(10, R.string.macro_btn_rb),
    GameButton(7, R.string.macro_btn_l3),
    GameButton(8, R.string.macro_btn_r3),
)

// Trigger button choices for macros (A/B/X/Y/L3/R3)
private val MACRO_TRIGGER_BUTTONS = listOf(
    GameButton(0, R.string.macro_btn_a),
    GameButton(1, R.string.macro_btn_b),
    GameButton(2, R.string.macro_btn_x),
    GameButton(3, R.string.macro_btn_y),
    GameButton(7, R.string.macro_btn_l3),
    GameButton(8, R.string.macro_btn_r3),
)

// Turbo rate presets
private val TURBO_RATE_OPTIONS = listOf(5, 10, 15, 20)

// ---------------------------------------------------------------------------
// Preset macros (step = Pair<buttonMask, durationMs>)
// buttonMask = 1 shl buttonIndex
// ---------------------------------------------------------------------------

private data class MacroPreset(val labelResId: Int, val steps: List<Pair<Int, Int>>)

private val MACRO_PRESETS = listOf(
    MacroPreset(
        R.string.macro_preset_rapid_a5,
        // Press A (mask=1) 5 times with 80 ms hold + 80 ms gap
        listOf(1 to 80, 0 to 80, 1 to 80, 0 to 80, 1 to 80, 0 to 80, 1 to 80, 0 to 80, 1 to 80, 0 to 80),
    ),
    MacroPreset(
        R.string.macro_preset_a_then_b,
        listOf(1 to 100, 0 to 50, 2 to 100, 0 to 50),  // A then B
    ),
    MacroPreset(
        R.string.macro_preset_mash_x,
        // Mash X (mask=4) 8 times
        listOf(4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60, 4 to 60, 0 to 60),
    ),
    MacroPreset(
        R.string.macro_preset_y_b_combo,
        listOf(8 to 80, 0 to 40, 2 to 80, 0 to 40),   // Y then B
    ),
)

/**
 * Macros/Turbo capability section shown inside TrainerTab.
 * Handles not-enabled / not-available gating.
 */
@Composable
internal fun MacroSection(
    macroShm: MacroShm?,
    modifier: Modifier = Modifier,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    var prefEnabled by remember { mutableStateOf(PrefManager.macroEnabled) }

    Column(
        modifier = modifier.focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            !prefEnabled -> {
                MacroMenuSectionHeader(title = stringResource(R.string.macro_not_enabled_title))
                Text(
                    text = stringResource(R.string.macro_not_enabled_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                MacroToggleRow(
                    title = stringResource(R.string.macro_enable_toggle),
                    subtitle = stringResource(R.string.macro_enable_note),
                    enabled = false,
                    accentColor = accentColor,
                    onToggle = {
                        PrefManager.macroEnabled = true
                        prefEnabled = true
                    },
                )
            }

            macroShm == null || !macroShm.available -> {
                MacroMenuSectionHeader(title = stringResource(R.string.macro_not_loaded_title))
                Text(
                    text = stringResource(R.string.macro_not_loaded_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            else -> {
                MacroControlSection(macroShm = macroShm, accentColor = accentColor)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroControlSection(
    macroShm: MacroShm,
    accentColor: androidx.compose.ui.graphics.Color,
) {
    // Turbo state — read initial mask from shm
    val turboEnabled = remember {
        val initialMask = macroShm.getTurboMask()
        mutableStateListOf<Boolean>().also { list ->
            TURBO_BUTTONS.forEach { btn ->
                list.add((initialMask and (1 shl btn.index)) != 0)
            }
        }
    }
    var turboRateIndex by remember {
        val hz = macroShm.getTurboRateHz()
        mutableIntStateOf(TURBO_RATE_OPTIONS.indexOfFirst { it >= hz }.coerceAtLeast(0))
    }

    // Macro state
    // assigned[slot] = null means unassigned
    val macroAssignments = remember { mutableStateListOf<MacroAssignment?>().also { list -> repeat(4) { list.add(null) } } }
    var expandedMacroSlot by remember { mutableIntStateOf(-1) }

    // ---- Turbo section ----
    MacroMenuSectionHeader(title = stringResource(R.string.macro_turbo_title))

    // Rate stepper
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.macro_turbo_rate_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TURBO_RATE_OPTIONS.forEachIndexed { index, hz ->
                MacroChip(
                    text = stringResource(R.string.macro_turbo_rate_value, hz),
                    selected = turboRateIndex == index,
                    accentColor = accentColor,
                    onClick = {
                        turboRateIndex = index
                        val rate = TURBO_RATE_OPTIONS[index]
                        // Re-apply rate to all currently enabled turbo buttons
                        TURBO_BUTTONS.forEachIndexed { bIdx, btn ->
                            if (turboEnabled[bIdx]) {
                                macroShm.setTurbo(btn.index, true, rate)
                            }
                        }
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Per-button turbo toggles
    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TURBO_BUTTONS.forEachIndexed { index, btn ->
            val on = turboEnabled[index]
            MacroToggleChip(
                text = stringResource(btn.labelResId),
                enabled = on,
                accentColor = accentColor,
                onClick = {
                    val newOn = !on
                    turboEnabled[index] = newOn
                    macroShm.setTurbo(btn.index, newOn, TURBO_RATE_OPTIONS[turboRateIndex])
                },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
    )

    // ---- Macro presets section ----
    MacroMenuSectionHeader(title = stringResource(R.string.macro_presets_title))
    Text(
        text = stringResource(R.string.macro_presets_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )

    Spacer(modifier = Modifier.height(4.dp))

    // 4 slots
    for (slot in 0 until 4) {
        MacroSlotRow(
            slot = slot,
            assignment = macroAssignments[slot],
            accentColor = accentColor,
            expanded = expandedMacroSlot == slot,
            onExpandToggle = {
                expandedMacroSlot = if (expandedMacroSlot == slot) -1 else slot
            },
            onAssign = { presetIndex, triggerButtonIndex ->
                val preset = MACRO_PRESETS[presetIndex]
                val triggerBtn = MACRO_TRIGGER_BUTTONS[triggerButtonIndex]
                macroShm.setMacro(slot, triggerBtn.index, preset.steps)
                macroAssignments[slot] = MacroAssignment(
                    presetIndex = presetIndex,
                    triggerButtonIndex = triggerButtonIndex,
                )
                expandedMacroSlot = -1
            },
            onClear = {
                macroShm.clearSlot(slot)
                macroAssignments[slot] = null
            },
        )
        Spacer(modifier = Modifier.height(4.dp))
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Clear all
    MacroActionButton(
        label = stringResource(R.string.macro_clear_all),
        accentColor = PluviaTheme.colors.accentDanger,
        onClick = {
            macroShm.clearAll()
            for (i in 0 until macroAssignments.size) macroAssignments[i] = null
            for (i in turboEnabled.indices) turboEnabled[i] = false
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Multiplayer warning
    Text(
        text = stringResource(R.string.macro_online_warning),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private data class MacroAssignment(val presetIndex: Int, val triggerButtonIndex: Int)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroSlotRow(
    slot: Int,
    assignment: MacroAssignment?,
    accentColor: androidx.compose.ui.graphics.Color,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    onAssign: (presetIndex: Int, triggerButtonIndex: Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    val isAssigned = assignment != null

    var selectedPreset by remember { mutableIntStateOf(assignment?.presetIndex ?: 0) }
    var selectedTrigger by remember { mutableIntStateOf(assignment?.triggerButtonIndex ?: 0) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(shape)
            .background(
                if (isAssigned) {
                    Brush.horizontalGradient(
                        colors = listOf(accentColor.copy(alpha = 0.14f), accentColor.copy(alpha = 0.07f)),
                    )
                } else if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(accentColor.copy(alpha = 0.10f), accentColor.copy(alpha = 0.05f)),
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
                if (isAssigned || isFocused) Modifier.border(
                    if (isAssigned) 1.5.dp else 1.dp,
                    accentColor.copy(alpha = if (isAssigned) 0.6f else 0.4f),
                    shape,
                ) else Modifier,
            ),
    ) {
        // Header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onExpandToggle,
                    role = Role.Button,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.macro_slot_label, slot + 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isAssigned) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isAssigned) {
                    Text(
                        text = stringResource(
                            R.string.macro_slot_summary,
                            stringResource(MACRO_PRESETS[assignment!!.presetIndex].labelResId),
                            stringResource(MACRO_TRIGGER_BUTTONS[assignment.triggerButtonIndex].labelResId),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = accentColor.copy(alpha = 0.8f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.macro_slot_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isAssigned) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PluviaTheme.colors.accentDanger.copy(alpha = 0.15f))
                            .border(1.dp, PluviaTheme.colors.accentDanger.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onClear,
                                role = Role.Button,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.macro_slot_clear),
                            tint = PluviaTheme.colors.accentDanger,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                MacroMiniButton(
                    label = if (expanded) stringResource(R.string.macro_slot_collapse) else stringResource(R.string.macro_slot_expand),
                    accentColor = accentColor,
                    highlighted = expanded,
                    onClick = onExpandToggle,
                )
            }
        }

        // Expandable assign panel
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Preset picker
                Text(
                    text = stringResource(R.string.macro_choose_preset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MACRO_PRESETS.forEachIndexed { index, preset ->
                        MacroChip(
                            text = stringResource(preset.labelResId),
                            selected = selectedPreset == index,
                            accentColor = accentColor,
                            onClick = { selectedPreset = index },
                        )
                    }
                }

                // Trigger button picker
                Text(
                    text = stringResource(R.string.macro_choose_trigger),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MACRO_TRIGGER_BUTTONS.forEachIndexed { index, btn ->
                        MacroChip(
                            text = stringResource(btn.labelResId),
                            selected = selectedTrigger == index,
                            accentColor = accentColor,
                            onClick = { selectedTrigger = index },
                        )
                    }
                }

                // Assign button
                MacroActionButton(
                    label = stringResource(R.string.macro_assign),
                    accentColor = accentColor,
                    onClick = { onAssign(selectedPreset, selectedTrigger) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Local sub-components
// ---------------------------------------------------------------------------

@Composable
private fun MacroMenuSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun MacroToggleRow(
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
private fun MacroChip(
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
            .height(32.dp)
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
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MacroToggleChip(
    text: String,
    enabled: Boolean,
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
                if (isFocused || enabled) Modifier.border(
                    if (enabled) 2.dp else 1.dp,
                    accentColor.copy(alpha = if (enabled) 0.8f else 0.5f),
                    shape,
                )
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f), shape),
            )
            .clip(shape)
            .background(
                when {
                    enabled -> accentColor.copy(alpha = 0.22f)
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
            color = if (enabled || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (enabled || isFocused) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MacroActionButton(
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .height(40.dp)
            .then(
                if (isFocused) Modifier.border(2.dp, accentColor.copy(alpha = 0.8f), shape)
                else Modifier,
            )
            .clip(shape)
            .background(accentColor.copy(alpha = if (isFocused) 0.90f else 0.75f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                role = Role.Button,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MacroMiniButton(
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(32.dp)
            .clip(shape)
            .background(
                if (highlighted) accentColor.copy(alpha = 0.22f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            )
            .border(
                if (highlighted) 1.5.dp else 1.dp,
                if (highlighted) accentColor.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                role = Role.Button,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
