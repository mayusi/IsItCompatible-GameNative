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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.gamenative.R
import app.gamenative.cheats.Cheat
import app.gamenative.cheats.CheatExecutor
import app.gamenative.cheats.CheatTableRegistry
import app.gamenative.trainer.TrainerShm
import app.gamenative.ui.theme.PluviaTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Guided flow state machine
// ---------------------------------------------------------------------------

private enum class CheatFlowState {
    /** User hasn't started scanning yet. */
    Idle,
    /** A scan is running right now. */
    Scanning,
    /** We have results — too many to freeze, need user to narrow. */
    NeedNarrow,
    /** Match count is small enough to freeze. */
    ReadyToFreeze,
    /** Cheat is active (addresses are frozen). */
    Frozen,
    /** Engine reported an error during scan/freeze. */
    Error,
}

/** Per-cheat runtime state. Keyed by cheat.id inside CheatTab. */
private data class CheatState(
    val flow: CheatFlowState = CheatFlowState.Idle,
    val matchCount: Int = 0,
    val addresses: LongArray = LongArray(0),
    val tooMany: Boolean = false,
    val frozenAddresses: LongArray = LongArray(0),
    val errorMsg: String? = null,
) {
    val isActive: Boolean get() = flow == CheatFlowState.Frozen

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CheatState) return false
        return flow == other.flow &&
            matchCount == other.matchCount &&
            addresses.contentEquals(other.addresses) &&
            tooMany == other.tooMany &&
            frozenAddresses.contentEquals(other.frozenAddresses) &&
            errorMsg == other.errorMsg
    }

    override fun hashCode(): Int {
        var result = flow.hashCode()
        result = 31 * result + matchCount
        result = 31 * result + addresses.contentHashCode()
        result = 31 * result + tooMany.hashCode()
        result = 31 * result + frozenAddresses.contentHashCode()
        result = 31 * result + (errorMsg?.hashCode() ?: 0)
        return result
    }
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/**
 * Cheat tab — guided memory-scan front-end to [TrainerShm].
 *
 * Degrades gracefully:
 *  - [appId] == null -> "Launch a game" empty state.
 *  - No [CheatTable] for [appId] -> "No cheat table" empty state.
 *  - [trainerShm] == null or !available -> "Enable trainer + relaunch" message.
 *  - Table exists + engine available -> full guided cheat list.
 *
 * SINGLE-ACTIVE-SCAN constraint: TrainerShm maintains ONE global result set.
 * While a cheat's scan flow is in progress (Scanning / NeedNarrow /
 * ReadyToFreeze), starting another cheat's scan is disabled with a message.
 * Freezing is independent — multiple cheats can be frozen at once because
 * freeze entries are tracked per-address server-side.
 */
@Composable
fun CheatTab(
    appId: String?,
    trainerShm: TrainerShm?,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    proxyCtrl: app.gamenative.cheats.ProxyCtrl? = null,
) {
    val accentColor = PluviaTheme.colors.accentPurple
    val scope = rememberCoroutineScope()

    // ---- Progress from trainer engine during scans ----
    var scanProgress by remember { mutableIntStateOf(0) }
    LaunchedEffect(trainerShm) {
        trainerShm?.progress?.collectLatest { p ->
            scanProgress = p
        }
    }

    // NOTE: do NOT add .verticalScroll() here. This composable is hosted inside
    // TrainerTab's Column which already has .verticalScroll() — nesting two vertical
    // scrollers gives the inner one infinite-height constraints and crashes Compose
    // with "Vertically scrollable component was measured with an infinity maximum
    // height". The Memory/Speed/Macros sections correctly rely on the parent scroll;
    // Cheats must too. (This was the every-time crash on pressing the Cheats chip.)
    Column(
        modifier = modifier
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- Empty state: no appId ----
        if (appId == null) {
            CheatSectionHeader(stringResource(R.string.cheat_no_table_title))
            Text(
                text = stringResource(R.string.cheat_no_appid_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            CheatDisclaimer()
            return@Column
        }

        // ---- No cheat table — offer the DIY scanner instead ----
        if (!CheatTableRegistry.hasTableFor(appId)) {
            if (proxyCtrl == null) {
                // Proxy engine not loaded — can't run DIY scans
                CheatSectionHeader(stringResource(R.string.cheat_engine_not_loaded_title))
                Text(
                    text = "Enable the Trainer in Settings and relaunch this game to make your own cheats.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
                MemoryGuideCard(accentColor = accentColor)
                Spacer(modifier = Modifier.height(8.dp))
                CheatDisclaimer()
            } else {
                DiyCheatMaker(proxyCtrl = proxyCtrl, accentColor = accentColor)
                Spacer(modifier = Modifier.height(8.dp))
                CheatDisclaimer()
            }
            return@Column
        }

        // ---- Engine not loaded ----
        if (trainerShm == null || !trainerShm.available) {
            CheatSectionHeader(stringResource(R.string.cheat_engine_not_loaded_title))
            Text(
                text = stringResource(R.string.cheat_engine_not_loaded_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            CheatDisclaimer()
            return@Column
        }

        // ---- Full cheat table UX ----
        val table = CheatTableRegistry.tableForAppId(appId)!! // safe: hasTableFor returned true

        CheatSectionHeader(stringResource(R.string.cheat_table_for, table.title))

        Spacer(modifier = Modifier.height(4.dp))

        // Per-cheat runtime state — keyed by cheat.id.
        // SEED from CheatUiState so toggles survive the QuickMenu being closed +
        // reopened (the composable's remember{} is destroyed each time, but the
        // in-game DLL keeps freezing; CheatUiState is the process-level source of
        // truth for "is this cheat showing as on"). Only pointer_chain/static
        // cheats persist this way — their freeze lives entirely in the DLL slot
        // table, independent of UI lifetime. Guided/aob cheats start Idle as before.
        val cheatStates = remember(table) {
            mutableMapOf<String, CheatState>().also { map ->
                table.cheats.forEach { cheat ->
                    val persistedOn = appId != null &&
                        (cheat.recipe.kind == "pointer_chain" || cheat.recipe.kind == "static") &&
                        app.gamenative.cheats.CheatUiState.isActive(appId, cheat.id)
                    map[cheat.id] = if (persistedOn) {
                        CheatState(flow = CheatFlowState.Frozen, frozenAddresses = LongArray(0))
                    } else {
                        CheatState()
                    }
                }
            }
        }
        // Recompose trigger for cheatStates map changes
        var stateVersion by remember { mutableIntStateOf(0) }

        // Which cheat currently owns the engine's result set (mid-scan)?
        // null means the result set is free.
        var activeScanCheatId by remember { mutableStateOf<String?>(null) }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
        )

        // ---- Cheat rows ----
        table.cheats.forEach { cheat ->
            // Force read of stateVersion so recomposition sees map updates
            @Suppress("UNUSED_EXPRESSION") stateVersion

            val state = cheatStates[cheat.id] ?: CheatState()
            val scanBusy = activeScanCheatId != null && activeScanCheatId != cheat.id
            val isScanning = state.flow == CheatFlowState.Scanning

            val kind = cheat.recipe.kind
            if (kind == "aob_freeze" || kind == "aob_patch" || kind == "pointer_chain" || kind == "static") {
                // ---- One-tap cheat row (aob_freeze, aob_patch, pointer_chain, static) ----
                OneTapCheatRow(
                    cheat = cheat,
                    state = state,
                    scanBusy = scanBusy,
                    isScanning = isScanning,
                    scanProgress = if (isScanning) scanProgress else 0,
                    accentColor = accentColor,
                    focusRequester = if (cheat == table.cheats.first() && state.flow == CheatFlowState.Idle)
                        focusRequester else null,
                    onToggleOn = {
                        scope.launch {
                            cheatStates[cheat.id] = CheatState(flow = CheatFlowState.Scanning)
                            activeScanCheatId = cheat.id
                            stateVersion++

                            when (kind) {
                                "aob_patch" -> {
                                    val r = CheatExecutor.aobPatch(trainerShm, cheat)
                                    if (r.success) {
                                        cheatStates[cheat.id] = CheatState(
                                            flow = CheatFlowState.Frozen,
                                            frozenAddresses = r.patchedAddresses,
                                        )
                                    } else {
                                        cheatStates[cheat.id] = CheatState(
                                            flow = CheatFlowState.Error,
                                            errorMsg = r.error,
                                        )
                                    }
                                }
                                "pointer_chain", "static" -> {
                                    if (proxyCtrl == null) {
                                        cheatStates[cheat.id] = CheatState(
                                            flow = CheatFlowState.Error,
                                            errorMsg = "Cheat engine not ready — relaunch the game with the trainer enabled",
                                        )
                                    } else {
                                        val r = CheatExecutor.applyChainFreeze(proxyCtrl, cheat)
                                        if (r.success) {
                                            cheatStates[cheat.id] = CheatState(
                                                flow = CheatFlowState.Frozen,
                                                frozenAddresses = LongArray(0),
                                            )
                                            // Persist so the toggle stays ON after the menu is reopened.
                                            if (appId != null) app.gamenative.cheats.CheatUiState.setActive(appId, cheat.id, true)
                                        } else {
                                            cheatStates[cheat.id] = CheatState(
                                                flow = CheatFlowState.Error,
                                                errorMsg = r.error,
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    // kind == "aob_freeze"
                                    val r = CheatExecutor.aobActivate(trainerShm, cheat)
                                    if (r.success) {
                                        cheatStates[cheat.id] = CheatState(
                                            flow = CheatFlowState.Frozen,
                                            frozenAddresses = r.frozenAddresses,
                                        )
                                    } else {
                                        cheatStates[cheat.id] = CheatState(
                                            flow = CheatFlowState.Error,
                                            errorMsg = r.error,
                                        )
                                    }
                                }
                            }
                            activeScanCheatId = null
                            stateVersion++
                        }
                    },
                    onToggleOff = {
                        val currentState = cheatStates[cheat.id] ?: return@OneTapCheatRow
                        scope.launch {
                            when (kind) {
                                "aob_patch" -> {
                                    CheatExecutor.undoPatch(trainerShm, currentState.frozenAddresses)
                                }
                                "pointer_chain", "static" -> {
                                    if (proxyCtrl != null) {
                                        CheatExecutor.removeChainFreeze(proxyCtrl, cheat)
                                    }
                                    if (appId != null) app.gamenative.cheats.CheatUiState.setActive(appId, cheat.id, false)
                                }
                                else -> {
                                    // kind == "aob_freeze"
                                    CheatExecutor.deactivate(trainerShm, currentState.frozenAddresses)
                                }
                            }
                            cheatStates[cheat.id] = CheatState(flow = CheatFlowState.Idle)
                            stateVersion++
                        }
                    },
                )
            } else {
                // ---- Existing guided cheat row (guided_known and any future kinds) ----
                CheatRow(
                    cheat = cheat,
                    state = state,
                    scanBusy = scanBusy,
                    isScanning = isScanning,
                    scanProgress = if (isScanning) scanProgress else 0,
                    accentColor = accentColor,
                    focusRequester = if (cheat == table.cheats.first() && state.flow == CheatFlowState.Idle)
                        focusRequester else null,
                    onFirstScan = { enteredValue ->
                        scope.launch {
                            cheatStates[cheat.id] = CheatState(flow = CheatFlowState.Scanning)
                            activeScanCheatId = cheat.id
                            stateVersion++

                            val result = CheatExecutor.firstScan(trainerShm, cheat, enteredValue)
                            if (!result.success) {
                                cheatStates[cheat.id] = CheatState(
                                    flow = CheatFlowState.Error,
                                    errorMsg = result.error,
                                )
                                activeScanCheatId = null
                            } else {
                                val nextFlow = when {
                                    result.count == 0 -> CheatFlowState.Idle
                                    result.count <= CheatExecutor.MAX_FREEZE_CANDIDATES && !result.tooMany ->
                                        CheatFlowState.ReadyToFreeze
                                    else -> CheatFlowState.NeedNarrow
                                }
                                cheatStates[cheat.id] = CheatState(
                                    flow = nextFlow,
                                    matchCount = result.count,
                                    addresses = result.addresses,
                                    tooMany = result.tooMany,
                                )
                                // Release the result-set lock only once we're done scanning;
                                // keep it while NeedNarrow so subsequent narrow() calls work.
                                if (nextFlow == CheatFlowState.Idle) activeScanCheatId = null
                            }
                            stateVersion++
                        }
                    },
                    onNarrow = { enteredValue ->
                        scope.launch {
                            cheatStates[cheat.id] = cheatStates[cheat.id]!!.copy(
                                flow = CheatFlowState.Scanning,
                            )
                            stateVersion++

                            val result = CheatExecutor.narrow(trainerShm, cheat, enteredValue)
                            if (!result.success) {
                                cheatStates[cheat.id] = CheatState(
                                    flow = CheatFlowState.Error,
                                    errorMsg = result.error,
                                )
                                activeScanCheatId = null
                            } else {
                                val nextFlow = when {
                                    result.count == 0 -> CheatFlowState.Idle
                                    result.count <= CheatExecutor.MAX_FREEZE_CANDIDATES && !result.tooMany ->
                                        CheatFlowState.ReadyToFreeze
                                    else -> CheatFlowState.NeedNarrow
                                }
                                cheatStates[cheat.id] = CheatState(
                                    flow = nextFlow,
                                    matchCount = result.count,
                                    addresses = result.addresses,
                                    tooMany = result.tooMany,
                                )
                                if (nextFlow == CheatFlowState.Idle) activeScanCheatId = null
                            }
                            stateVersion++
                        }
                    },
                    onActivate = {
                        val currentState = cheatStates[cheat.id] ?: return@CheatRow
                        scope.launch {
                            val freezeResult = CheatExecutor.applyFreeze(
                                trainerShm,
                                cheat,
                                currentState.addresses,
                            )
                            if (freezeResult.success) {
                                cheatStates[cheat.id] = currentState.copy(
                                    flow = CheatFlowState.Frozen,
                                    frozenAddresses = freezeResult.frozenAddresses,
                                )
                                // Release the scan result-set lock — freeze is address-based, not
                                // result-set-based, so other cheats can now start their scans.
                                if (activeScanCheatId == cheat.id) activeScanCheatId = null
                            } else {
                                cheatStates[cheat.id] = currentState.copy(
                                    flow = CheatFlowState.Error,
                                    errorMsg = freezeResult.error,
                                )
                                if (activeScanCheatId == cheat.id) activeScanCheatId = null
                            }
                            stateVersion++
                        }
                    },
                    onDeactivate = {
                        val currentState = cheatStates[cheat.id] ?: return@CheatRow
                        scope.launch {
                            CheatExecutor.deactivate(trainerShm, currentState.frozenAddresses)
                            cheatStates[cheat.id] = CheatState(flow = CheatFlowState.Idle)
                            stateVersion++
                        }
                    },
                    onStartOver = {
                        scope.launch {
                            val currentState = cheatStates[cheat.id] ?: return@launch
                            // If frozen, deactivate first
                            if (currentState.flow == CheatFlowState.Frozen) {
                                CheatExecutor.deactivate(trainerShm, currentState.frozenAddresses)
                            }
                            cheatStates[cheat.id] = CheatState(flow = CheatFlowState.Idle)
                            if (activeScanCheatId == cheat.id) {
                                activeScanCheatId = null
                                // Best-effort reset of the engine result set
                                trainerShm.reset()
                            }
                            stateVersion++
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        CheatDisclaimer()

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ---------------------------------------------------------------------------
// DIY cheat maker — "cheat any game" scanner UI (no pre-made cheat table)
// ---------------------------------------------------------------------------

/** State machine for the DIY free-form scanner. */
private enum class DiyState {
    /** No scan started yet. */
    Idle,
    /** A scan is currently in flight. */
    Scanning,
    /** First scan returned results; user needs to narrow or freeze. */
    Scanned,
    /** User initiated unknown-value flow; snapshot taken; direction buttons shown. */
    UnknownMode,
    /** Narrowing after a scan or unknown-value snapshot. */
    Narrowing,
    /** Addresses frozen; cheat is active. */
    Frozen,
    /** Engine reported an error. */
    Error,
}

/** Goal chip data — sets a label hint and a default value-type. */
private data class GoalChip(val label: String, val defaultVtype: String)

private val DIY_GOALS = listOf(
    GoalChip("Health", "i32"),
    GoalChip("Money / Gold", "i32"),
    GoalChip("Ammo", "i32"),
    GoalChip("Lives", "i32"),
    GoalChip("Other", "i32"),
)

/**
 * DIY cheat maker — OneX-style "make your own cheat" scanner.
 *
 * Hosted inside CheatTab's Column which is itself inside TrainerTab's verticalScroll.
 * DO NOT add any verticalScroll or LazyColumn here — nesting infinite-height
 * containers crashes Compose ("Vertically scrollable component was measured with an
 * infinity maximum height"). Use plain Column/Row with bounded heights only.
 *
 * DIY addresses are NOT persisted across relaunches; Box64 ASLR makes them stale,
 * so all state lives in local remember{} vars only (not CheatUiState).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiyCheatMaker(
    proxyCtrl: app.gamenative.cheats.ProxyCtrl,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // ---- Value-type toggle: "i32" or "f32" ----
    var vtype by remember { mutableStateOf("i32") }

    // ---- Goal chip selection (-1 = none) ----
    var selectedGoalIndex by remember { mutableIntStateOf(-1) }

    // ---- Scanner state machine ----
    var diyState by remember { mutableStateOf(DiyState.Idle) }
    var candidateCount by remember { mutableIntStateOf(0) }
    var candidateAddresses by remember { mutableStateOf(LongArray(0)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // ---- Input fields ----
    var scanValue by remember { mutableStateOf("") }
    var narrowValue by remember { mutableStateOf("") }
    var freezeValue by remember { mutableStateOf("") }

    // Helper: reset to Idle and clear all state
    fun startOver() {
        diyState = DiyState.Idle
        candidateCount = 0
        candidateAddresses = LongArray(0)
        errorMsg = null
        scanValue = ""
        narrowValue = ""
        freezeValue = ""
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ---- Header ----
        CheatSectionHeader("Make your own cheat")

        // ---- How-to guide (collapsible) ----
        MemoryGuideCard(accentColor = accentColor)

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Goal chips ----
        CheatSectionHeader("What do you want to cheat?")
        FlowRow(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DIY_GOALS.forEachIndexed { index, goal ->
                DiyChip(
                    label = goal.label,
                    selected = selectedGoalIndex == index,
                    accentColor = accentColor,
                    onClick = {
                        selectedGoalIndex = index
                        vtype = goal.defaultVtype
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ---- Value-type toggle ----
        CheatSectionHeader("Value type")
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiyChip(
                label = "Whole number (int)",
                selected = vtype == "i32",
                accentColor = accentColor,
                onClick = { vtype = "i32" },
            )
            DiyChip(
                label = "Decimal (float)",
                selected = vtype == "f32",
                accentColor = accentColor,
                onClick = { vtype = "f32" },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ---- State machine body ----
        when (diyState) {

            // ------------------------------------------------------------------
            // Idle — initial scan entry
            // ------------------------------------------------------------------
            DiyState.Idle -> {
                Text(
                    text = "Read the number on screen (your health, gold, ammo, etc.) and type it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    CheatInputRow(
                        value = scanValue,
                        onValueChange = { scanValue = it },
                        hint = stringResource(R.string.cheat_value_input_hint),
                        buttonLabel = stringResource(R.string.cheat_scan_button),
                        enabled = true,
                        accentColor = accentColor,
                        onAction = {
                            if (scanValue.isNotBlank()) {
                                diyState = DiyState.Scanning
                                scope.launch {
                                    val r = CheatExecutor.diyFirstScan(proxyCtrl, vtype, scanValue)
                                    diyState = if (!r.ok) {
                                        errorMsg = r.error ?: "Scan failed"
                                        DiyState.Error
                                    } else {
                                        candidateCount = r.count
                                        candidateAddresses = r.addresses
                                        DiyState.Scanned
                                    }
                                }
                            }
                        },
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Can't see a number?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                CheatTextButton(
                    label = "I can't see a number — use direction mode",
                    accentColor = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onClick = {
                        diyState = DiyState.Scanning
                        scope.launch {
                            val ok = CheatExecutor.diySnapshot(proxyCtrl)
                            diyState = if (ok) DiyState.UnknownMode else {
                                errorMsg = "Could not take memory snapshot — make sure the game is running"
                                DiyState.Error
                            }
                        }
                    },
                )
            }

            // ------------------------------------------------------------------
            // Scanning — spinner / progress message
            // ------------------------------------------------------------------
            DiyState.Scanning -> {
                Text(
                    text = stringResource(R.string.cheat_scanning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }

            // ------------------------------------------------------------------
            // UnknownMode — snapshot taken; show direction buttons
            // ------------------------------------------------------------------
            DiyState.UnknownMode -> {
                Text(
                    text = "Snapshot taken. Now cause the value to change in the game (take damage, spend money, etc.), then tap which direction it moved:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CheatActionButton(
                        label = "Value went UP",
                        enabled = true,
                        accentColor = accentColor,
                        onClick = {
                            diyState = DiyState.Scanning
                            scope.launch {
                                val r = CheatExecutor.diyNarrowDirection(proxyCtrl, "up")
                                diyState = if (!r.ok) {
                                    errorMsg = r.error ?: "Scan failed"
                                    DiyState.Error
                                } else {
                                    candidateCount = r.count
                                    candidateAddresses = r.addresses
                                    DiyState.Narrowing
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    CheatActionButton(
                        label = "Value went DOWN",
                        enabled = true,
                        accentColor = accentColor,
                        onClick = {
                            diyState = DiyState.Scanning
                            scope.launch {
                                val r = CheatExecutor.diyNarrowDirection(proxyCtrl, "down")
                                diyState = if (!r.ok) {
                                    errorMsg = r.error ?: "Scan failed"
                                    DiyState.Error
                                } else {
                                    candidateCount = r.count
                                    candidateAddresses = r.addresses
                                    DiyState.Narrowing
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                CheatActionButton(
                    label = "Value changed (either direction)",
                    enabled = true,
                    accentColor = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    onClick = {
                        diyState = DiyState.Scanning
                        scope.launch {
                            val r = CheatExecutor.diyNarrowDirection(proxyCtrl, "changed")
                            diyState = if (!r.ok) {
                                errorMsg = r.error ?: "Scan failed"
                                DiyState.Error
                            } else {
                                candidateCount = r.count
                                candidateAddresses = r.addresses
                                DiyState.Narrowing
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(4.dp))
                CheatTextButton(
                    label = stringResource(R.string.cheat_start_over_button),
                    accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onClick = { startOver() },
                )
            }

            // ------------------------------------------------------------------
            // Scanned / Narrowing — show result count, offer narrow or freeze
            // ------------------------------------------------------------------
            DiyState.Scanned, DiyState.Narrowing -> {
                val tooMany = candidateCount > CheatExecutor.MAX_DIY_FREEZE

                // Match count badge
                Text(
                    text = if (tooMany)
                        stringResource(R.string.cheat_matches_too_many, candidateCount)
                    else
                        stringResource(R.string.cheat_matches_found, candidateCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )

                if (candidateCount == 0) {
                    // Zero results — nothing to freeze, ask to start over
                    Text(
                        text = stringResource(R.string.cheat_matches_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CheatActionButton(
                        label = stringResource(R.string.cheat_start_over_button),
                        enabled = true,
                        accentColor = accentColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        onClick = { startOver() },
                    )
                } else if (tooMany) {
                    // Too many — narrow with exact value
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Now change the value in the game — take damage or spend money — then type the new number and tap Narrow.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                        CheatInputRow(
                            value = narrowValue,
                            onValueChange = { narrowValue = it },
                            hint = "New in-game value",
                            buttonLabel = stringResource(R.string.cheat_narrow_button),
                            enabled = true,
                            accentColor = accentColor,
                            onAction = {
                                if (narrowValue.isNotBlank()) {
                                    diyState = DiyState.Scanning
                                    scope.launch {
                                        val r = CheatExecutor.diyNarrow(proxyCtrl, vtype, narrowValue)
                                        diyState = if (!r.ok) {
                                            errorMsg = r.error ?: "Narrow failed"
                                            DiyState.Error
                                        } else {
                                            candidateCount = r.count
                                            candidateAddresses = r.addresses
                                            narrowValue = ""
                                            DiyState.Narrowing
                                        }
                                    }
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Keep narrowing until you get 8 or fewer matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    CheatTextButton(
                        label = stringResource(R.string.cheat_start_over_button),
                        accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = { startOver() },
                    )
                } else {
                    // Small enough — show freeze section
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Good — enter the value you want to lock in (e.g. 999 for max health) and tap Freeze.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                        CheatInputRow(
                            value = freezeValue,
                            onValueChange = { freezeValue = it },
                            hint = "Value to freeze at (e.g. 999)",
                            buttonLabel = "Freeze",
                            enabled = true,
                            accentColor = accentColor,
                            onAction = {
                                if (freezeValue.isNotBlank()) {
                                    diyState = DiyState.Scanning
                                    scope.launch {
                                        val ok = CheatExecutor.diyFreeze(
                                            proxyCtrl, vtype, candidateAddresses, freezeValue,
                                        )
                                        diyState = if (ok) DiyState.Frozen else {
                                            errorMsg = "Freeze failed — try starting over"
                                            DiyState.Error
                                        }
                                    }
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    CheatTextButton(
                        label = stringResource(R.string.cheat_start_over_button),
                        accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        onClick = { startOver() },
                    )
                }
            }

            // ------------------------------------------------------------------
            // Frozen — cheat is active
            // ------------------------------------------------------------------
            DiyState.Frozen -> {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Cheat active — value frozen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accentColor,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                CheatActionButton(
                    label = "Stop / Start over",
                    enabled = true,
                    accentColor = PluviaTheme.colors.accentDanger,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    onClick = {
                        scope.launch {
                            proxyCtrl.clearAll()
                            startOver()
                        }
                    },
                )
            }

            // ------------------------------------------------------------------
            // Error — show message and allow retry
            // ------------------------------------------------------------------
            DiyState.Error -> {
                Text(
                    text = errorMsg ?: stringResource(R.string.cheat_activate_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                CheatTextButton(
                    label = "Try again",
                    accentColor = accentColor,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    onClick = { startOver() },
                )
            }
        }
    }
}

/**
 * Small selectable chip for DIY goal/vtype selection.
 * Reuses the visual language of the existing CheatActionButton but in chip style.
 * No verticalScroll — bounded height.
 */
@Composable
private fun DiyChip(
    label: String,
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
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected || isFocused) accentColor else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected || isFocused) androidx.compose.ui.text.font.FontWeight.SemiBold
            else androidx.compose.ui.text.font.FontWeight.Normal,
        )
    }
}

// ---------------------------------------------------------------------------
// Cheat row — expandable card with the guided flow
// ---------------------------------------------------------------------------

@Composable
private fun CheatRow(
    cheat: Cheat,
    state: CheatState,
    scanBusy: Boolean,
    isScanning: Boolean,
    scanProgress: Int,
    accentColor: androidx.compose.ui.graphics.Color,
    focusRequester: FocusRequester?,
    onFirstScan: (String) -> Unit,
    onNarrow: (String) -> Unit,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    var expanded by remember(cheat.id) { mutableStateOf(false) }
    var inputValue by remember(cheat.id) { mutableStateOf("") }

    // Collapse when flow resets to Idle externally (e.g. start-over)
    LaunchedEffect(state.flow) {
        if (state.flow == CheatFlowState.Idle) {
            inputValue = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    state.isActive -> Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.22f),
                            accentColor.copy(alpha = 0.10f),
                        ),
                    )
                    isFocused -> Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                    else -> Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused || state.isActive) {
                    Modifier.border(
                        width = if (state.isActive) 2.dp else 1.5.dp,
                        color = accentColor.copy(alpha = if (state.isActive) 0.9f else 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                        shape = shape,
                    )
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            expanded = !expanded
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { expanded = !expanded },
                role = Role.Button,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // ---- Header row ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cheat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.isActive) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (state.isActive) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.cheat_category_label, cheat.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            // Active indicator (lock icon)
            if (state.isActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.cheat_active_label),
                        tint = accentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.cheat_active_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (state.flow != CheatFlowState.Idle) {
                Icon(
                    imageVector = Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // ---- Expanded guided flow ----
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.flow) {

                    // ----------------------------------------------------------
                    // Idle — show Step 1 prompt
                    // ----------------------------------------------------------
                    CheatFlowState.Idle -> {
                        if (scanBusy) {
                            // Another cheat is mid-scan — block starting this one
                            Text(
                                text = stringResource(R.string.cheat_scan_busy),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Text(
                                text = cheat.recipe.prompt,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            CheatInputRow(
                                value = inputValue,
                                onValueChange = { inputValue = it },
                                hint = stringResource(R.string.cheat_value_input_hint),
                                buttonLabel = stringResource(R.string.cheat_scan_button),
                                enabled = true,
                                accentColor = accentColor,
                                onAction = {
                                    if (inputValue.isNotBlank()) {
                                        onFirstScan(inputValue)
                                    }
                                },
                            )
                        }
                    }

                    // ----------------------------------------------------------
                    // Scanning — show progress bar
                    // ----------------------------------------------------------
                    CheatFlowState.Scanning -> {
                        Text(
                            text = stringResource(R.string.cheat_scanning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

                    // ----------------------------------------------------------
                    // NeedNarrow — Step 2: ask user to change value, then narrow
                    // ----------------------------------------------------------
                    CheatFlowState.NeedNarrow -> {
                        val narrowPrompt = cheat.recipe.narrowPrompt
                            ?: "Change the value in-game, then enter the new value and narrow."
                        Text(
                            text = narrowPrompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (state.tooMany)
                                stringResource(R.string.cheat_matches_too_many, state.matchCount)
                            else
                                stringResource(R.string.cheat_matches_found, state.matchCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                        CheatInputRow(
                            value = inputValue,
                            onValueChange = { inputValue = it },
                            hint = stringResource(R.string.cheat_value_input_hint),
                            buttonLabel = stringResource(R.string.cheat_narrow_button),
                            enabled = true,
                            accentColor = accentColor,
                            onAction = {
                                if (inputValue.isNotBlank()) {
                                    onNarrow(inputValue)
                                    inputValue = ""
                                }
                            },
                        )
                        // Start-over link
                        CheatTextButton(
                            label = stringResource(R.string.cheat_start_over_button),
                            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onStartOver,
                        )
                    }

                    // ----------------------------------------------------------
                    // ReadyToFreeze — Step 3: Activate button
                    // ----------------------------------------------------------
                    CheatFlowState.ReadyToFreeze -> {
                        Text(
                            text = stringResource(R.string.cheat_matches_found, state.matchCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                        )
                        CheatActionButton(
                            label = stringResource(R.string.cheat_activate_button),
                            enabled = true,
                            accentColor = accentColor,
                            onClick = onActivate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CheatTextButton(
                            label = stringResource(R.string.cheat_start_over_button),
                            accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = onStartOver,
                        )
                    }

                    // ----------------------------------------------------------
                    // Frozen — cheat is active; show Deactivate
                    // ----------------------------------------------------------
                    CheatFlowState.Frozen -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = stringResource(R.string.cheat_active_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = accentColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        CheatActionButton(
                            label = stringResource(R.string.cheat_deactivate_button),
                            enabled = true,
                            accentColor = PluviaTheme.colors.accentDanger,
                            onClick = onDeactivate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // ----------------------------------------------------------
                    // Error — show message + start-over
                    // ----------------------------------------------------------
                    CheatFlowState.Error -> {
                        Text(
                            text = state.errorMsg ?: stringResource(R.string.cheat_activate_failed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        CheatTextButton(
                            label = stringResource(R.string.cheat_start_over_button),
                            accentColor = accentColor,
                            onClick = onStartOver,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// One-tap AOB cheat row — WeMod-style toggle card (no value entry, no narrowing)
// ---------------------------------------------------------------------------

/**
 * A simplified card for "aob_freeze" and "aob_patch" cheats. The user taps a toggle
 * to activate or deactivate — no value entry or narrowing steps required. State machine:
 *   Idle (off) -> [onToggleOn] -> Scanning (activating) -> Frozen (on) or Error.
 *   Frozen (on) -> [onToggleOff] -> Idle (off).
 *   Error -> tapping the toggle retries (same as toggling from Idle).
 *
 * The actual executor call (aobActivate/aobPatch and deactivate/undoPatch) is dispatched
 * in the [onToggleOn]/[onToggleOff] lambdas by the caller based on [cheat.recipe.kind].
 * The UI is identical for both kinds — just a toggle + status text.
 * For aob_patch, [CheatState.frozenAddresses] stores the patched addresses (reuse).
 *
 * Uses the same [cheatStates] / [stateVersion] map as the guided [CheatRow] and
 * honours the single-active-scan constraint via [scanBusy].
 *
 * No verticalScroll, no LazyColumn — bounded height, plain Column/Row layout to
 * avoid nesting with TrainerTab's parent verticalScroll.
 */
@Composable
private fun OneTapCheatRow(
    cheat: Cheat,
    state: CheatState,
    scanBusy: Boolean,
    isScanning: Boolean,
    scanProgress: Int,
    accentColor: androidx.compose.ui.graphics.Color,
    focusRequester: FocusRequester?,
    onToggleOn: () -> Unit,
    onToggleOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)

    // The toggle is checked when Frozen; also treat Error as "off" (retry = re-toggle).
    val isActive = state.flow == CheatFlowState.Frozen
    // Disable the toggle while this cheat itself is scanning OR another cheat owns
    // the result set. Also disabled during Scanning state (operation in flight).
    val toggleEnabled = !isScanning && !(scanBusy && !isActive)

    val onToggle = {
        when (state.flow) {
            CheatFlowState.Frozen -> onToggleOff()
            // Idle, Error, and any unexpected state all treat toggle-on as "activate".
            else -> if (!scanBusy) onToggleOn()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                when {
                    isActive -> Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.22f),
                            accentColor.copy(alpha = 0.10f),
                        ),
                    )
                    isFocused -> Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                    else -> Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused || isActive) {
                    Modifier.border(
                        width = if (isActive) 2.dp else 1.5.dp,
                        color = accentColor.copy(alpha = if (isActive) 0.9f else 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f),
                        shape = shape,
                    )
                },
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && isFocused) {
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            if (toggleEnabled) onToggle()
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = toggleEnabled,
                onClick = onToggle,
                role = Role.Switch,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        // ---- Header row: name + category on the left, toggle on the right ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cheat.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.cheat_category_label, cheat.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Toggle — disabled and visually dimmed while scanBusy (another cheat owns the engine)
            Switch(
                checked = isActive,
                onCheckedChange = { if (toggleEnabled) onToggle() },
                enabled = toggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = accentColor,
                    checkedBorderColor = accentColor.copy(alpha = 0.0f),
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    disabledCheckedTrackColor = accentColor.copy(alpha = 0.4f),
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                ),
                // Suppress the default interaction ripple — the card itself handles focus/clicks
                interactionSource = remember { MutableInteractionSource() },
            )
        }

        // ---- Status area below the header (Scanning progress, active badge, error) ----
        when (state.flow) {
            CheatFlowState.Scanning -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cheat_scanning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { scanProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                )
            }

            CheatFlowState.Frozen -> {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(R.string.cheat_active_label),
                        tint = accentColor,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(R.string.cheat_active_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            CheatFlowState.Error -> {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.errorMsg ?: stringResource(R.string.cheat_activate_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (scanBusy) {
                    // Can't retry yet — another cheat owns the engine
                    Text(
                        text = stringResource(R.string.cheat_scan_busy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.cheat_activate_button) + " to retry",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor.copy(alpha = 0.8f),
                    )
                }
            }

            CheatFlowState.Idle -> {
                // No extra content when off — keep the card compact
                if (scanBusy) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.cheat_scan_busy),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            // NeedNarrow / ReadyToFreeze are guided-flow states and should never
            // be reached for aob_freeze or aob_patch cheats, but guard defensively.
            else -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable local sub-components (scoped to CheatTab — not exported)
// ---------------------------------------------------------------------------

@Composable
private fun CheatSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CheatDisclaimer() {
    Text(
        text = stringResource(R.string.cheat_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** Input field + action button side by side. */
@Composable
private fun CheatInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    buttonLabel: String,
    enabled: Boolean,
    accentColor: androidx.compose.ui.graphics.Color,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
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
            keyboardActions = KeyboardActions(onDone = { if (enabled) onAction() }),
            textStyle = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(accentColor),
            modifier = Modifier
                .weight(1f)
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

        CheatActionButton(
            label = buttonLabel,
            enabled = enabled,
            accentColor = accentColor,
            onClick = onAction,
        )
    }
}

/** Standard full-width (or inline) action button. Mirrors TrainerActionButton style. */
@Composable
private fun CheatActionButton(
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
                } else Modifier,
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
            color = if (enabled) androidx.compose.ui.graphics.Color.White
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Small inline text-only button for secondary actions (Start over, etc.). */
@Composable
private fun CheatTextButton(
    label: String,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (isFocused) Modifier.border(
                    width = 1.5.dp,
                    color = accentColor.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ) else Modifier,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                role = Role.Button,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accentColor.copy(alpha = 0.7f),
            fontWeight = FontWeight.Normal,
        )
    }
}
