package app.gamenative.ui.component

import android.view.KeyEvent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.trainer.MacroShm
import app.gamenative.trainer.ScanResult
import app.gamenative.trainer.SpeedHackShm
import app.gamenative.trainer.TrainerProto
import app.gamenative.trainer.TrainerShm
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Value-type descriptor
// ---------------------------------------------------------------------------

private data class VTypeEntry(
    val vtype: Int,
    val labelResId: Int,
    val isFloat: Boolean = false,
    val isDouble: Boolean = false,
)

private val VALUE_TYPES = listOf(
    VTypeEntry(TrainerProto.TVT_I32, R.string.trainer_vtype_int32),
    VTypeEntry(TrainerProto.TVT_F32, R.string.trainer_vtype_float, isFloat = true),
    VTypeEntry(TrainerProto.TVT_I64, R.string.trainer_vtype_int64),
    VTypeEntry(TrainerProto.TVT_F64, R.string.trainer_vtype_double, isDouble = true),
    VTypeEntry(TrainerProto.TVT_I16, R.string.trainer_vtype_int16),
    VTypeEntry(TrainerProto.TVT_U16, R.string.trainer_vtype_uint16),
    VTypeEntry(TrainerProto.TVT_I8, R.string.trainer_vtype_int8),
    VTypeEntry(TrainerProto.TVT_U8, R.string.trainer_vtype_uint8),
    VTypeEntry(TrainerProto.TVT_U32, R.string.trainer_vtype_uint32),
    VTypeEntry(TrainerProto.TVT_U64, R.string.trainer_vtype_uint64),
)

private val NEXT_SCAN_FILTERS = listOf(
    TrainerProto.TFLT_EXACT to R.string.trainer_filter_exact,
    TrainerProto.TFLT_INCREASED to R.string.trainer_filter_increased,
    TrainerProto.TFLT_DECREASED to R.string.trainer_filter_decreased,
    TrainerProto.TFLT_CHANGED to R.string.trainer_filter_changed,
    TrainerProto.TFLT_UNCHANGED to R.string.trainer_filter_unchanged,
)

// ---------------------------------------------------------------------------
// State helpers
// ---------------------------------------------------------------------------

private data class AddressEntry(
    val addr: Long,
    val displayValue: String = "…",
    val frozen: Boolean = false,
)

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

// Tab indices for the 4-capability chip switcher
private object TrainerCapTab {
    const val MEMORY = 0
    const val SPEED  = 1
    const val MACROS = 2
    const val CHEATS = 3
}

/**
 * The Trainer tab body, placed inside QuickMenu's content area.
 *
 * [trainerShm] may be null if the trainer engine couldn't be initialised.
 * [speedHackShm] may be null if speed hack is disabled or not yet loaded.
 * [macroShm] may be null if macros are disabled or not yet loaded.
 * [appId] is the compound container id (e.g. "STEAM_271590") used by the Cheats tab
 *          to look up cheat tables.  Null when no game is running.
 * [focusRequester] is the first-item requester used by QuickMenu's LaunchedEffect.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainerTab(
    trainerShm: TrainerShm?,
    speedHackShm: SpeedHackShm?,
    macroShm: MacroShm?,
    appId: String? = null,
    proxyCtrl: app.gamenative.cheats.ProxyCtrl? = null,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val accentColor = PluviaTheme.colors.accentPurple

    // Capability tab selection — persists across recompositions while QuickMenu is open
    var selectedCapTab by remember { mutableIntStateOf(TrainerCapTab.MEMORY) }

    // Ping the trainer worker once on entry so `available` flips true when the
    // engine is loaded (it starts false until a successful round-trip).
    var pinged by remember(trainerShm) { mutableStateOf(false) }
    LaunchedEffect(trainerShm) {
        if (trainerShm != null && PrefManager.trainerEnabled) {
            trainerShm.ping()
            pinged = true
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- 3-chip capability switcher ----
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrainerChip(
                text = stringResource(R.string.trainer_cap_memory),
                selected = selectedCapTab == TrainerCapTab.MEMORY,
                accentColor = accentColor,
                onClick = { selectedCapTab = TrainerCapTab.MEMORY },
                focusRequester = if (selectedCapTab == TrainerCapTab.MEMORY) focusRequester else null,
            )
            TrainerChip(
                text = stringResource(R.string.trainer_cap_speed),
                selected = selectedCapTab == TrainerCapTab.SPEED,
                accentColor = accentColor,
                onClick = { selectedCapTab = TrainerCapTab.SPEED },
                focusRequester = if (selectedCapTab == TrainerCapTab.SPEED) focusRequester else null,
            )
            TrainerChip(
                text = stringResource(R.string.trainer_cap_macros),
                selected = selectedCapTab == TrainerCapTab.MACROS,
                accentColor = accentColor,
                onClick = { selectedCapTab = TrainerCapTab.MACROS },
                focusRequester = if (selectedCapTab == TrainerCapTab.MACROS) focusRequester else null,
            )
            TrainerChip(
                text = stringResource(R.string.trainer_cap_cheats),
                selected = selectedCapTab == TrainerCapTab.CHEATS,
                accentColor = accentColor,
                onClick = { selectedCapTab = TrainerCapTab.CHEATS },
                focusRequester = if (selectedCapTab == TrainerCapTab.CHEATS) focusRequester else null,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )

        // ---- Active capability section ----
        when (selectedCapTab) {
            TrainerCapTab.MEMORY -> {
                when {
                    !PrefManager.trainerEnabled -> {
                        TrainerNotEnabledSection(
                            focusRequester = if (selectedCapTab == TrainerCapTab.MEMORY) null else focusRequester,
                        )
                    }
                    trainerShm == null || !trainerShm.available -> {
                        TrainerNotLoadedSection()
                    }
                    else -> {
                        TrainerScannerSection(
                            trainerShm = trainerShm,
                            focusRequester = null,
                        )
                    }
                }
            }

            TrainerCapTab.SPEED -> {
                SpeedSection(speedHackShm = speedHackShm)
            }

            TrainerCapTab.MACROS -> {
                MacroSection(macroShm = macroShm)
            }

            TrainerCapTab.CHEATS -> {
                CheatTab(
                    appId = appId,
                    trainerShm = trainerShm,
                    proxyCtrl = proxyCtrl,
                    focusRequester = null,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Disclaimer — always visible
        Text(
            text = stringResource(R.string.trainer_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// Not-enabled section
// ---------------------------------------------------------------------------

@Composable
private fun TrainerNotEnabledSection(
    focusRequester: FocusRequester? = null,
) {
    val accentColor = PluviaTheme.colors.accentPurple

    QuickMenuSectionHeader(title = stringResource(R.string.trainer_not_enabled_title))

    Text(
        text = stringResource(R.string.trainer_not_enabled_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )

    Spacer(modifier = Modifier.height(4.dp))

    // Toggle to enable — mirrors QuickMenuToggleRow style
    TrainerToggleRow(
        title = stringResource(R.string.trainer_enable_toggle),
        subtitle = stringResource(R.string.trainer_enable_note),
        enabled = PrefManager.trainerEnabled,
        accentColor = accentColor,
        onToggle = { PrefManager.trainerEnabled = true },
        focusRequester = focusRequester,
    )
}

// ---------------------------------------------------------------------------
// Not-loaded section
// ---------------------------------------------------------------------------

@Composable
private fun TrainerNotLoadedSection() {
    QuickMenuSectionHeader(title = stringResource(R.string.trainer_not_loaded_title))

    Text(
        text = stringResource(R.string.trainer_not_loaded_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Scanner section (main UX when engine is available)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainerScannerSection(
    trainerShm: TrainerShm,
    focusRequester: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    val accentColor = PluviaTheme.colors.accentPurple

    // ---- Scan state ----
    var selectedVtypeIndex by remember { mutableIntStateOf(0) }
    val selectedVtype = VALUE_TYPES[selectedVtypeIndex]

    var searchValueText by remember { mutableStateOf("") }
    var nextScanValueText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableIntStateOf(TrainerProto.TFLT_EXACT) }

    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf<ScanResult?>(null) }
    var hasScanned by remember { mutableStateOf(false) }

    // Shown addresses (capped to 20 for display)
    val shownAddresses = remember { mutableStateListOf<AddressEntry>() }

    // Progress flow collector
    LaunchedEffect(trainerShm) {
        trainerShm.progress.collectLatest { p ->
            scanProgress = p
        }
    }

    // Helper: encode user text to raw bits for the engine
    fun encodeValue(text: String): Long? {
        return when {
            selectedVtype.isFloat -> text.toFloatOrNull()
                ?.let { TrainerProto.floatToRawBits(it) }
            selectedVtype.isDouble -> text.toDoubleOrNull()
                ?.let { TrainerProto.doubleToRawBits(it) }
            else -> text.toLongOrNull()
        }
    }

    // Helper: decode raw bits to display string
    fun decodeValue(raw: Long): String {
        return when {
            selectedVtype.isFloat -> TrainerProto.rawBitsToFloat(raw).toString()
            selectedVtype.isDouble -> TrainerProto.rawBitsToDouble(raw).toString()
            else -> raw.toString()
        }
    }

    // Helper: refresh display values from engine
    fun refreshValues() {
        scope.launch {
            shownAddresses.forEachIndexed { index, entry ->
                val raw = trainerShm.read(entry.addr, selectedVtype.vtype)
                if (raw != null) {
                    shownAddresses[index] = entry.copy(displayValue = decodeValue(raw))
                }
            }
        }
    }

    // Helper: apply scan result to shown addresses
    fun applyResult(result: ScanResult) {
        lastResult = result
        if (result.success) {
            shownAddresses.clear()
            val cap = minOf(result.addresses.size, 20)
            repeat(cap) { i ->
                shownAddresses.add(AddressEntry(addr = result.addresses[i]))
            }
            // Refresh display values
            scope.launch {
                shownAddresses.forEachIndexed { index, entry ->
                    val raw = trainerShm.read(entry.addr, selectedVtype.vtype)
                    if (raw != null) {
                        shownAddresses[index] = entry.copy(displayValue = decodeValue(raw))
                    }
                }
            }
        }
    }

    // ---- How-to-cheat guide (collapsible, collapsed by default) ----
    MemoryGuideCard(accentColor = accentColor)

    // ---- Value type selector ----
    QuickMenuSectionHeader(title = stringResource(R.string.trainer_value_type_label))

    FlowRow(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VALUE_TYPES.forEachIndexed { index, entry ->
            TrainerChip(
                text = stringResource(entry.labelResId),
                selected = selectedVtypeIndex == index,
                accentColor = accentColor,
                onClick = { selectedVtypeIndex = index },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ---- First scan ----
    QuickMenuSectionHeader(title = if (!hasScanned) stringResource(R.string.trainer_first_scan) else stringResource(R.string.trainer_next_scan))

    if (!hasScanned) {
        // First scan: value input + scan button
        TrainerInputRow(
            value = searchValueText,
            onValueChange = { searchValueText = it },
            hint = stringResource(R.string.trainer_value_hint),
            buttonLabel = stringResource(R.string.trainer_first_scan),
            enabled = !isScanning,
            accentColor = accentColor,
            focusRequester = focusRequester,
            onAction = {
                val raw = encodeValue(searchValueText) ?: return@TrainerInputRow
                isScanning = true
                scope.launch {
                    val result = trainerShm.scanNew(raw, selectedVtype.vtype)
                    isScanning = false
                    hasScanned = true
                    applyResult(result)
                }
            },
        )
    } else {
        // Next scan: filter chips + value input (hidden for non-exact filters) + scan button
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NEXT_SCAN_FILTERS.forEach { (filter, labelResId) ->
                TrainerChip(
                    text = stringResource(labelResId),
                    selected = selectedFilter == filter,
                    accentColor = accentColor,
                    onClick = { selectedFilter = filter },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        AnimatedVisibility(
            visible = selectedFilter == TrainerProto.TFLT_EXACT,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TrainerInputRow(
                value = nextScanValueText,
                onValueChange = { nextScanValueText = it },
                hint = stringResource(R.string.trainer_value_hint),
                buttonLabel = stringResource(R.string.trainer_next_scan),
                enabled = !isScanning,
                accentColor = accentColor,
                focusRequester = null,
                onAction = {
                    val raw = if (selectedFilter == TrainerProto.TFLT_EXACT)
                        encodeValue(nextScanValueText) ?: return@TrainerInputRow
                    else 0L
                    isScanning = true
                    scope.launch {
                        val result = trainerShm.scanNext(selectedFilter, raw)
                        isScanning = false
                        applyResult(result)
                    }
                },
            )
        }

        if (selectedFilter != TrainerProto.TFLT_EXACT) {
            Spacer(modifier = Modifier.height(4.dp))
            TrainerActionButton(
                label = stringResource(R.string.trainer_next_scan),
                enabled = !isScanning,
                accentColor = accentColor,
                onClick = {
                    isScanning = true
                    scope.launch {
                        val result = trainerShm.scanNext(selectedFilter, 0L)
                        isScanning = false
                        applyResult(result)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Scan progress bar
    AnimatedVisibility(
        visible = isScanning,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.trainer_scanning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { scanProgress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = accentColor,
                trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ---- Results ----
    val result = lastResult
    if (result != null && hasScanned) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = when {
                    !result.success -> stringResource(R.string.trainer_results_error, result.errorMsg ?: "unknown")
                    result.tooMany -> stringResource(R.string.trainer_results_count_many, result.count)
                    result.count == 0 -> stringResource(R.string.trainer_results_empty)
                    else -> stringResource(R.string.trainer_results_count, result.count)
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    !result.success -> MaterialTheme.colorScheme.error
                    result.count == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> accentColor
                },
                fontWeight = FontWeight.SemiBold,
            )

            if (result.success && shownAddresses.isNotEmpty()) {
                // Refresh button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { refreshValues() },
                            role = Role.Button,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.trainer_refresh_values),
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Address rows — only shown when count is small enough to be useful
        val showDetailRows = result.success && shownAddresses.isNotEmpty() && !result.tooMany
        if (showDetailRows) {
            Spacer(modifier = Modifier.height(4.dp))
            shownAddresses.forEachIndexed { index, entry ->
                TrainerAddressRow(
                    entry = entry,
                    accentColor = accentColor,
                    onWrite = { newValueText ->
                        val raw = encodeValue(newValueText) ?: return@TrainerAddressRow
                        scope.launch {
                            trainerShm.write(entry.addr, raw, selectedVtype.vtype)
                            val updated = trainerShm.read(entry.addr, selectedVtype.vtype)
                            if (updated != null) {
                                shownAddresses[index] = entry.copy(displayValue = decodeValue(updated))
                            }
                        }
                    },
                    onFreezeToggle = {
                        val currentEntry = shownAddresses[index]
                        scope.launch {
                            if (!currentEntry.frozen) {
                                val raw = encodeValue(currentEntry.displayValue)
                                    ?: trainerShm.read(currentEntry.addr, selectedVtype.vtype)
                                    ?: return@launch
                                val ok = trainerShm.freeze(currentEntry.addr, raw, selectedVtype.vtype)
                                if (ok) {
                                    shownAddresses[index] = currentEntry.copy(frozen = true)
                                }
                            } else {
                                val ok = trainerShm.unfreeze(currentEntry.addr)
                                if (ok) {
                                    shownAddresses[index] = currentEntry.copy(frozen = false)
                                }
                            }
                        }
                    },
                )
            }
        } else if (result.success && result.tooMany) {
            Text(
                text = stringResource(R.string.trainer_results_count_many, result.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    // ---- Reset button ----
    if (hasScanned) {
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )
        TrainerActionButton(
            label = stringResource(R.string.trainer_new_scan),
            enabled = !isScanning,
            accentColor = PluviaTheme.colors.accentDanger,
            onClick = {
                scope.launch {
                    trainerShm.reset()
                    hasScanned = false
                    lastResult = null
                    shownAddresses.clear()
                    searchValueText = ""
                    nextScanValueText = ""
                    selectedFilter = TrainerProto.TFLT_EXACT
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Collapsible "How to cheat any game" guide card
// ---------------------------------------------------------------------------

@Composable
private fun MemoryGuideCard(
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(accentColor.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.25f),
                shape = shape,
            ),
    ) {
        // Header row — tappable to toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { expanded = !expanded },
                    role = Role.Button,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.trainer_guide_header),
                style = MaterialTheme.typography.labelLarge,
                color = accentColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
        }

        // Expandable steps — plain Column, no nested scroll
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val stepColor = MaterialTheme.colorScheme.onSurface
                val tipColor = MaterialTheme.colorScheme.onSurfaceVariant

                listOf(
                    R.string.trainer_guide_step1,
                    R.string.trainer_guide_step2,
                    R.string.trainer_guide_step3,
                    R.string.trainer_guide_step4,
                    R.string.trainer_guide_step5,
                ).forEach { resId ->
                    Text(
                        text = stringResource(resId),
                        style = MaterialTheme.typography.bodySmall,
                        color = stepColor,
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = stringResource(R.string.trainer_guide_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = tipColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Address row — compact card per result
// ---------------------------------------------------------------------------

@Composable
private fun TrainerAddressRow(
    entry: AddressEntry,
    accentColor: androidx.compose.ui.graphics.Color,
    onWrite: (String) -> Unit,
    onFreezeToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    var setValueText by remember { mutableStateOf("") }
    var showSetValue by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (entry.frozen) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.22f),
                            accentColor.copy(alpha = 0.10f),
                        ),
                    )
                } else if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
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
                if (isFocused || entry.frozen) {
                    Modifier.border(
                        width = if (entry.frozen) 2.dp else 1.5.dp,
                        color = accentColor.copy(alpha = if (entry.frozen) 0.9f else 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            showSetValue = !showSetValue
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trainer_address_label, entry.addr),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (entry.frozen) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.trainer_value_label, entry.displayValue),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (entry.frozen) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (entry.frozen) FontWeight.SemiBold else FontWeight.Normal,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Set value toggle
                TrainerMiniButton(
                    label = stringResource(R.string.trainer_write_value),
                    accentColor = accentColor,
                    highlighted = showSetValue,
                    onClick = { showSetValue = !showSetValue },
                )

                // Freeze toggle
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (entry.frozen) accentColor.copy(alpha = 0.22f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        )
                        .border(
                            width = if (entry.frozen) 2.dp else 1.dp,
                            color = if (entry.frozen) accentColor.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onFreezeToggle,
                            role = Role.Button,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (entry.frozen) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (entry.frozen)
                            stringResource(R.string.trainer_unfreeze)
                        else
                            stringResource(R.string.trainer_freeze),
                        tint = if (entry.frozen) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // Inline value setter
        AnimatedVisibility(
            visible = showSetValue,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TrainerTextInput(
                    value = setValueText,
                    onValueChange = { setValueText = it },
                    hint = stringResource(R.string.trainer_set_value_hint),
                    accentColor = accentColor,
                    modifier = Modifier.weight(1f),
                    onDone = {
                        if (setValueText.isNotBlank()) {
                            onWrite(setValueText)
                            showSetValue = false
                            setValueText = ""
                        }
                    },
                )
                TrainerMiniButton(
                    label = stringResource(R.string.trainer_write_value),
                    accentColor = accentColor,
                    highlighted = true,
                    onClick = {
                        if (setValueText.isNotBlank()) {
                            onWrite(setValueText)
                            showSetValue = false
                            setValueText = ""
                        }
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable sub-components
// ---------------------------------------------------------------------------

/** Toggle row (mirrors the QuickMenu private version but accessible from this file). */
@Composable
private fun TrainerToggleRow(
    title: String,
    enabled: Boolean,
    onToggle: () -> Unit,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    focusRequester: FocusRequester? = null,
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
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
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
                if (isFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = RoundedCornerShape(14.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(
                if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
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
        // Switch indicator (reuse style)
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (enabled) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                )
                .border(
                    width = 1.dp,
                    color = if (enabled) accentColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(999.dp),
                )
                .padding(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .align(if (enabled) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.CircleShape),
            )
        }
    }
}

/** Small chip for value type / filter / capability selection. */
@Composable
private fun TrainerChip(
    text: String,
    selected: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .height(36.dp)
            .then(
                if (isFocused) {
                    Modifier.border(width = 2.dp, color = accentColor.copy(alpha = 0.7f), shape = shape)
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = if (selected) accentColor.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        shape = shape,
                    )
                },
            )
            .clip(shape)
            .background(
                when {
                    selected -> accentColor.copy(alpha = 0.18f)
                    isFocused -> accentColor.copy(alpha = 0.12f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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

/** Text input + action button side-by-side row. */
@Composable
private fun TrainerInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    buttonLabel: String,
    enabled: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrainerTextInput(
            value = value,
            onValueChange = onValueChange,
            hint = hint,
            accentColor = accentColor,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            focusRequester = focusRequester,
            onDone = { if (enabled) onAction() },
        )
        TrainerActionButton(
            label = buttonLabel,
            enabled = enabled,
            accentColor = accentColor,
            onClick = onAction,
        )
    }
}

/** Styled text field. */
@Composable
private fun TrainerTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onDone: () -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
        ),
        cursorBrush = SolidColor(accentColor),
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .height(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = accentColor.copy(alpha = 0.4f),
                shape = shape,
            )
            .padding(horizontal = 12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                inner()
            }
        },
    )
}

/** Full-width or inline action button. */
@Composable
private fun TrainerActionButton(
    label: String,
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
            .height(40.dp)
            .then(
                if (isFocused) {
                    Modifier.border(width = 2.dp, color = accentColor.copy(alpha = 0.8f), shape = shape)
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(
                if (enabled) accentColor.copy(alpha = if (isFocused) 0.9f else 0.75f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
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
            color = if (enabled) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Mini button (used inside address rows). */
@Composable
private fun TrainerMiniButton(
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
                width = if (highlighted) 1.5.dp else 1.dp,
                color = if (highlighted) accentColor.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = shape,
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
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// QuickMenuSectionHeader is private in QuickMenu.kt so we redeclare a local alias here.
// This is intentionally named differently to avoid confusion; it has the same visual style.
@Composable
private fun QuickMenuSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
