@file:OptIn(ExperimentalFoundationApi::class)

package app.gamenative.ui.screen.library

import android.content.Intent
import android.content.res.Configuration
import app.gamenative.gamefixes.CollectionSubGame
import app.gamenative.gamefixes.GameCollection
import app.gamenative.ui.screen.library.components.ambient.AmbientDownloadOverlay
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.NetworkMonitor
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.cheats.CheatTableRegistry
import app.gamenative.data.LibraryItem
import app.gamenative.service.SteamService
import app.gamenative.ui.component.GamepadAction
import app.gamenative.ui.component.GamepadActionBar
import app.gamenative.ui.component.GamepadButton
import app.gamenative.ui.component.LoadingScreen
import app.gamenative.ui.data.AppMenuOption
import app.gamenative.ui.data.GameDisplayInfo
import app.gamenative.ui.enums.AppOptionMenuType
import app.gamenative.ui.internal.fakeAppInfo
import app.gamenative.ui.screen.library.appscreen.AmazonAppScreen
import app.gamenative.ui.screen.library.appscreen.CustomGameAppScreen
import app.gamenative.ui.screen.library.appscreen.EpicAppScreen
import app.gamenative.ui.screen.library.appscreen.GOGAppScreen
import app.gamenative.ui.screen.library.appscreen.SteamAppScreen
import app.gamenative.ui.screen.library.components.GameOptionsPanel
import app.gamenative.ui.theme.NovaAccent
import app.gamenative.ui.theme.NovaAccentBright
import app.gamenative.ui.theme.NovaAccentDeep
import app.gamenative.ui.theme.NovaInk
import app.gamenative.ui.theme.PluviaSurface
import app.gamenative.ui.theme.PluviaSurfaceElevated
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.utils.formatEtaMs
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

// https://partner.steamgames.com/doc/store/assets/libraryassets#4

// ---------------------------------------------------------------------------
// Nova design tokens (kept local so hot-reload stays fast)
// ---------------------------------------------------------------------------
private val NovaPanelSurface   = Color(0xFF13131B) // dashboard panel bg
private val NovaPanelElevated  = Color(0xFF16161F) // focused card bg
private val NovaDivider        = Color(0xFF1A1A24)
private val NovaTextPrimary    = Color(0xFFFAFAFA)
private val NovaTextSecondary  = Color(0xFFC0C0CC)
private val NovaTextMuted      = Color(0xFF9CA0AD)
// Chip semantics
private val NovaChipIndigoBg   = Color(0xFF6D5BF6).copy(alpha = 0.18f)
private val NovaChipIndigoText = Color(0xFF9B8FFA)
private val NovaChipGreenBg    = Color(0xFF1A2A1A)
private val NovaChipGreenText  = Color(0xFF5CB85C)
private val NovaChipAmberBg    = Color(0xFF2A1A08)
private val NovaChipAmberText  = Color(0xFFE29060)

@Composable
private fun SkeletonText(
    modifier: Modifier = Modifier,
    lines: Int = 1,
    lineHeight: Int = 16,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    val color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    Column(modifier = modifier) {
        repeat(lines) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (index == lines - 1) 0.7f else 1f)
                    .height(lineHeight.dp)
                    .background(
                        color = color,
                        shape = RoundedCornerShape(4.dp),
                    ),
            )
            if (index < lines - 1) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Focus-aware button primitives (unchanged logic, kept for ART register budget)
// ---------------------------------------------------------------------------

@Composable
private fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isInstalled: Boolean = false,
    isDownloading: Boolean = false,
    downloadProgress: Float = 0f,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onProgressBarPositioned: ((Rect) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(120, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)),
        label = "primaryActionScale",
    )

    // Launch button always uses indigo — brand accent per spec.
    // Download/pause states preserve their existing semantic colors.
    val buttonColor = when {
        isDownloading -> PluviaTheme.colors.statusDownloading
        else -> NovaAccent
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (enabled) buttonColor else buttonColor.copy(alpha = 0.5f),
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        Brush.verticalGradient(listOf(NovaAccentBright, NovaAccentDeep)),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .focusRequester(focusRequester)
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isDownloading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                LinearProgressIndicator(
                    progress = { downloadProgress },
                    modifier = Modifier
                        .width(80.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .then(
                            if (onProgressBarPositioned != null) {
                                Modifier.onGloballyPositioned { coordinates ->
                                    onProgressBarPositioned(coordinates.boundsInRoot())
                                }
                            } else {
                                Modifier
                            },
                        ),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
                Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum",
                        ),
                        color = Color.White,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (isInstalled) Icons.Default.PlayArrow else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Icon-only action button — used for the back button on the art canvas and for
 * Settings/Delete at the bottom of the dashboard.
 */
@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(120, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)),
        label = "actionIconScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) Color.White.copy(alpha = 0.2f)
                else Color.White.copy(alpha = 0.1f),
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        Brush.verticalGradient(listOf(NovaAccent, NovaAccentDeep)),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Dashboard action card — compact row used for Make It Work / Speed / Cheats
// ---------------------------------------------------------------------------

/**
 * A focusable dashboard card: icon · title · subtitle/badge · right arrow/value.
 * All dashboard peers (Make It Work, Speed, Cheats) use this primitive.
 */
@Composable
private fun DashboardActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = NovaTextMuted,
    subtitleColor: Color = NovaTextMuted,
    chipBg: Color? = null,
    chipText: String? = null,
    chipTextColor: Color = NovaChipIndigoText,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = tween(120, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)),
        label = "dashCardScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isFocused) NovaPanelElevated else NovaPanelSurface,
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) NovaAccent else NovaDivider,
                shape = RoundedCornerShape(10.dp),
            )
            .then(
                if (isFocused) {
                    // spec: ONE glow — 2px #6D5BF6 border + 0 0 16px #6D5BF640
                    // The border above handles the ring; the shadow would need graphicsLayer
                    // but the border + bg change is enough without a second draw pass.
                    Modifier
                } else Modifier,
            )
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) NovaAccentBright else iconTint,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = if (isFocused) NovaTextPrimary else NovaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Optional chip badge on the right
            if (chipBg != null && chipText != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(chipBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = chipText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp,
                        ),
                        color = chipTextColor,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// InfoCard (restyled to NovaGN spec: 10dp radius, 8dp grid, panel surface)
// ---------------------------------------------------------------------------

@Composable
private fun InfoCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    statusColor: Color? = null,
    isCompact: Boolean = false,
    focusableForNavigation: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val cardModifier = if (focusableForNavigation) {
        modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
            }
            .focusable()
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, NovaAccent, RoundedCornerShape(10.dp))
                } else {
                    Modifier.border(1.dp, NovaDivider, RoundedCornerShape(10.dp))
                },
            )
    } else {
        modifier.border(1.dp, NovaDivider, RoundedCornerShape(10.dp))
    }

    Box(
        modifier = cardModifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFocused) NovaPanelElevated else NovaPanelSurface)
            .padding(if (isCompact) 10.dp else 14.dp),
    ) {
        Column {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.08.sp,
                ),
                color = Color(0xFF555566),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (statusColor != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                    ),
                    color = if (statusColor != null) statusColor else NovaTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AppScreen entry point (unchanged)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    libraryItem: LibraryItem,
    onClickPlay: (Boolean) -> Unit,
    onTestGraphics: () -> Unit,
    onAutoTune: () -> Unit = {},
    onMakeItWork: (() -> Unit)? = null,
    onBack: () -> Unit,
) {
    val screenModel = remember(libraryItem.gameSource) {
        when (libraryItem.gameSource) {
            app.gamenative.data.GameSource.STEAM -> SteamAppScreen()
            app.gamenative.data.GameSource.CUSTOM_GAME -> CustomGameAppScreen()
            app.gamenative.data.GameSource.GOG -> GOGAppScreen()
            app.gamenative.data.GameSource.EPIC -> EpicAppScreen()
            app.gamenative.data.GameSource.AMAZON -> AmazonAppScreen()
        }
    }

    screenModel.Content(
        libraryItem = libraryItem,
        onClickPlay = onClickPlay,
        onTestGraphics = onTestGraphics,
        onAutoTune = onAutoTune,
        onMakeItWork = onMakeItWork,
        onBack = onBack,
    )
}

/**
 * Formats bytes into a human-readable string (KB, MB, GB).
 */
private fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes >= gb -> String.format("%.1f GB", bytes / gb)
        bytes >= mb -> String.format("%.1f MB", bytes / mb)
        bytes >= kb -> String.format("%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

/**
 * Stable holder for action callbacks.
 * Kept @Immutable to stay within ART bytecode verifier's changed-flag bitmask limit.
 */
@Immutable
data class AppScreenActions(
    val onDownloadInstallClick: () -> Unit,
    val onPauseResumeClick: () -> Unit,
    val onDeleteDownloadClick: () -> Unit,
    val onUpdateClick: () -> Unit,
    val onBack: () -> Unit = {},
    val onMakeItWork: (() -> Unit)? = null,
)

/**
 * Stable holder for install/download/connectivity state.
 * Same ART-register-pressure rationale as [AppScreenActions].
 */
@Immutable
data class HeroState(
    val isInstalled: Boolean,
    val isDownloading: Boolean,
    val hasPartialDownload: Boolean,
    val downloadProgress: Float,
    val hasInternet: Boolean,
    val hasWifiOrEthernet: Boolean,
    val buttonEnabled: Boolean,
    val pauseResumeEnabled: Boolean,
    val downloadSizeText: String,
    val downloadTimeLeftText: String,
    val hasCheatTable: Boolean,
    val cheatCount: Int,
)

// ---------------------------------------------------------------------------
// Main screen composable
// ---------------------------------------------------------------------------

@Composable
internal fun AppScreenContent(
    modifier: Modifier = Modifier,
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isValidToDownload: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float,
    hasPartialDownload: Boolean,
    isUpdatePending: Boolean,
    downloadInfo: app.gamenative.data.DownloadInfo? = null,
    actions: AppScreenActions,
    collectionSlot: (@Composable () -> Unit)? = null,
    vararg optionsMenu: AppMenuOption,
) {
    val hasInternet by NetworkMonitor.hasInternet.collectAsState()
    val hasWifiOrEthernet by NetworkMonitor.hasWifiOrEthernet.collectAsState()
    val downloadAllowed = !PrefManager.downloadOnWifiOnly || hasWifiOrEthernet

    var optionsMenuVisible by remember { mutableStateOf(false) }
    var showCheatsDialog by remember { mutableStateOf(false) }

    val cheatTable = remember(displayInfo.appId) {
        CheatTableRegistry.tableForAppId(displayInfo.appId)
    }
    val hasCheatTable = cheatTable != null
    val cheatCount = cheatTable?.cheats?.size ?: 0

    var progressBarBounds by remember { mutableStateOf<Rect?>(null) }
    var ambientInteractionCounter by remember { mutableStateOf(0) }

    val playButtonFocusRequester = remember { FocusRequester() }

    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT

    LaunchedEffect(displayInfo.appId) { /* no-op scroll reset — dashboard is its own panel */ }

    LaunchedEffect(Unit) {
        playButtonFocusRequester.requestFocus()
    }

    val isResume = !isDownloading && hasPartialDownload
    val pauseResumeEnabled = if (isResume) downloadAllowed else true
    val isInstall = !isInstalled
    val installEnabled = if (isInstall) downloadAllowed && hasInternet else true
    val buttonEnabled = if (isInstalled) {
        installEnabled
    } else {
        installEnabled && isValidToDownload
    }
    val startActionEnabled = if (isDownloading || hasPartialDownload) {
        pauseResumeEnabled
    } else {
        buttonEnabled
    }
    val onStartAction = {
        if (isDownloading || hasPartialDownload) {
            actions.onPauseResumeClick()
        } else {
            actions.onDownloadInstallClick()
        }
    }

    val downloadTexts = rememberDownloadProgressText(
        appId = displayInfo.appId,
        gameId = displayInfo.gameId,
        downloadInfo = downloadInfo,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading,
    )
    val downloadTimeLeftText = downloadTexts.timeLeftText
    val downloadSizeText = downloadTexts.sizeText

    val handleKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    optionsMenuVisible = true
                    true
                }
                KeyEvent.KEYCODE_BUTTON_START -> {
                    if (!optionsMenuVisible && startActionEnabled) onStartAction()
                    true
                }
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (optionsMenuVisible) optionsMenuVisible = false
                    else actions.onBack()
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }

    BackHandler(enabled = optionsMenuVisible) {
        optionsMenuVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NovaInk)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val pointerEvent = awaitPointerEvent(PointerEventPass.Initial)
                        if (pointerEvent.changes.any { it.changedToDownIgnoreConsumed() }) {
                            ambientInteractionCounter++
                        }
                    }
                }
            }
            .onKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) ambientInteractionCounter++
                handleKeyEvent(it.nativeKeyEvent)
            },
    ) {
        if (isPortrait) {
            // Portrait: stacked — art on top (40% height), dashboard below
            AppScreenPortrait(
                displayInfo = displayInfo,
                isInstalled = isInstalled,
                isDownloading = isDownloading,
                hasPartialDownload = hasPartialDownload,
                downloadProgress = downloadProgress,
                hasInternet = hasInternet,
                hasWifiOrEthernet = hasWifiOrEthernet,
                buttonEnabled = buttonEnabled,
                pauseResumeEnabled = pauseResumeEnabled,
                downloadSizeText = downloadSizeText,
                downloadTimeLeftText = downloadTimeLeftText,
                hasCheatTable = hasCheatTable,
                cheatCount = cheatCount,
                isUpdatePending = isUpdatePending,
                actions = actions,
                collectionSlot = collectionSlot,
                playButtonFocusRequester = playButtonFocusRequester,
                onProgressBarPositioned = { progressBarBounds = it },
                onOpenOptions = { optionsMenuVisible = true },
                onShowCheats = { showCheatsDialog = true },
                onUpdateClick = actions.onUpdateClick,
            )
        } else {
            // Landscape: side-by-side split — art left 2/3, dashboard right 1/3
            AppScreenLandscape(
                displayInfo = displayInfo,
                isInstalled = isInstalled,
                isDownloading = isDownloading,
                hasPartialDownload = hasPartialDownload,
                downloadProgress = downloadProgress,
                hasInternet = hasInternet,
                hasWifiOrEthernet = hasWifiOrEthernet,
                buttonEnabled = buttonEnabled,
                pauseResumeEnabled = pauseResumeEnabled,
                downloadSizeText = downloadSizeText,
                downloadTimeLeftText = downloadTimeLeftText,
                hasCheatTable = hasCheatTable,
                cheatCount = cheatCount,
                isUpdatePending = isUpdatePending,
                actions = actions,
                collectionSlot = collectionSlot,
                playButtonFocusRequester = playButtonFocusRequester,
                onProgressBarPositioned = { progressBarBounds = it },
                onOpenOptions = { optionsMenuVisible = true },
                onShowCheats = { showCheatsDialog = true },
                onUpdateClick = actions.onUpdateClick,
            )
        }

        GamepadActionBar(
            actions = listOf(
                if (isInstalled) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.run_app,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else if (isDownloading) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.pause_download,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else if (hasPartialDownload) {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.resume_download,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                } else {
                    GamepadAction(
                        button = GamepadButton.START,
                        labelResId = R.string.install_app,
                        onClick = { if (startActionEnabled) onStartAction() },
                    )
                },
                GamepadAction(
                    button = GamepadButton.SELECT,
                    labelResId = R.string.options,
                    onClick = { optionsMenuVisible = true },
                ),
                GamepadAction(
                    button = GamepadButton.B,
                    labelResId = R.string.back,
                    onClick = actions.onBack,
                ),
            ),
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = !optionsMenuVisible,
        )

        if (showCheatsDialog) {
            AlertDialog(
                onDismissRequest = { showCheatsDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FlashOn,
                        contentDescription = null,
                        tint = Color(0xFFFFD700),
                    )
                },
                title = { Text(stringResource(R.string.cheat_detail_dialog_title)) },
                text = { Text(stringResource(R.string.cheat_detail_dialog_body)) },
                confirmButton = {
                    TextButton(onClick = { showCheatsDialog = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        GameOptionsPanel(
            isOpen = optionsMenuVisible,
            onDismiss = { optionsMenuVisible = false },
            options = optionsMenu.toList(),
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        if (isDownloading) {
            AmbientDownloadOverlay(
                gameName = displayInfo.name,
                downloadProgress = downloadProgress,
                iconUrl = displayInfo.iconUrl,
                originBounds = progressBarBounds,
                userInteractionCounter = ambientInteractionCounter,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Landscape layout: art canvas (weight 2) | dashboard panel (weight 1)
// ---------------------------------------------------------------------------

@Composable
private fun AppScreenLandscape(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    hasPartialDownload: Boolean,
    downloadProgress: Float,
    hasInternet: Boolean,
    hasWifiOrEthernet: Boolean,
    buttonEnabled: Boolean,
    pauseResumeEnabled: Boolean,
    downloadSizeText: String,
    downloadTimeLeftText: String,
    hasCheatTable: Boolean,
    cheatCount: Int,
    isUpdatePending: Boolean,
    actions: AppScreenActions,
    collectionSlot: (@Composable () -> Unit)?,
    playButtonFocusRequester: FocusRequester,
    onProgressBarPositioned: (Rect) -> Unit,
    onOpenOptions: () -> Unit,
    onShowCheats: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // LEFT: atmospheric art canvas — no action controls live on it
        ArtCanvas(
            displayInfo = displayInfo,
            collectionSlot = collectionSlot,
            onBack = actions.onBack,
            modifier = Modifier
                .weight(2f)
                .fillMaxHeight(),
            // Right-to-left vignette gradient blends art edge into the dashboard panel
            vignetteToRight = true,
        )

        // 1dp hairline divider (spec: #1A1A24)
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(NovaDivider),
        )

        // RIGHT: scrollable action dashboard
        ActionDashboard(
            displayInfo = displayInfo,
            isInstalled = isInstalled,
            isDownloading = isDownloading,
            hasPartialDownload = hasPartialDownload,
            downloadProgress = downloadProgress,
            hasInternet = hasInternet,
            hasWifiOrEthernet = hasWifiOrEthernet,
            buttonEnabled = buttonEnabled,
            pauseResumeEnabled = pauseResumeEnabled,
            downloadSizeText = downloadSizeText,
            downloadTimeLeftText = downloadTimeLeftText,
            hasCheatTable = hasCheatTable,
            cheatCount = cheatCount,
            isUpdatePending = isUpdatePending,
            actions = actions,
            playButtonFocusRequester = playButtonFocusRequester,
            onProgressBarPositioned = onProgressBarPositioned,
            onOpenOptions = onOpenOptions,
            onShowCheats = onShowCheats,
            onUpdateClick = onUpdateClick,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(NovaPanelSurface),
        )
    }
}

// ---------------------------------------------------------------------------
// Portrait layout: art on top (40%), dashboard below (60%)
// ---------------------------------------------------------------------------

@Composable
private fun AppScreenPortrait(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    hasPartialDownload: Boolean,
    downloadProgress: Float,
    hasInternet: Boolean,
    hasWifiOrEthernet: Boolean,
    buttonEnabled: Boolean,
    pauseResumeEnabled: Boolean,
    downloadSizeText: String,
    downloadTimeLeftText: String,
    hasCheatTable: Boolean,
    cheatCount: Int,
    isUpdatePending: Boolean,
    actions: AppScreenActions,
    collectionSlot: (@Composable () -> Unit)?,
    playButtonFocusRequester: FocusRequester,
    onProgressBarPositioned: (Rect) -> Unit,
    onOpenOptions: () -> Unit,
    onShowCheats: () -> Unit,
    onUpdateClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Art — 40% height, bottom vignette toward the panel
        ArtCanvas(
            displayInfo = displayInfo,
            collectionSlot = collectionSlot,
            onBack = actions.onBack,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.4f),
            vignetteToRight = false,
        )

        // Hairline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(NovaDivider),
        )

        // Dashboard — fills remaining space
        ActionDashboard(
            displayInfo = displayInfo,
            isInstalled = isInstalled,
            isDownloading = isDownloading,
            hasPartialDownload = hasPartialDownload,
            downloadProgress = downloadProgress,
            hasInternet = hasInternet,
            hasWifiOrEthernet = hasWifiOrEthernet,
            buttonEnabled = buttonEnabled,
            pauseResumeEnabled = pauseResumeEnabled,
            downloadSizeText = downloadSizeText,
            downloadTimeLeftText = downloadTimeLeftText,
            hasCheatTable = hasCheatTable,
            cheatCount = cheatCount,
            isUpdatePending = isUpdatePending,
            actions = actions,
            playButtonFocusRequester = playButtonFocusRequester,
            onProgressBarPositioned = onProgressBarPositioned,
            onOpenOptions = onOpenOptions,
            onShowCheats = onShowCheats,
            onUpdateClick = onUpdateClick,
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.6f)
                .background(NovaPanelSurface),
        )
    }
}

// ---------------------------------------------------------------------------
// Art canvas — the atmospheric left/top pane, NO action controls
// ---------------------------------------------------------------------------

/**
 * Pure art canvas: hero image, gradient overlays, back button, game identity
 * (title + developer) anchored to the bottom-left. No play/action buttons.
 *
 * [vignetteToRight] = true adds an extra horizontal gradient darkening the right
 * edge so it blends seamlessly into the adjacent dashboard panel.
 */
@Composable
private fun ArtCanvas(
    displayInfo: GameDisplayInfo,
    collectionSlot: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vignetteToRight: Boolean = false,
) {
    Box(modifier = modifier) {
        // Hero image
        if (displayInfo.heroImageUrl != null) {
            CoilImage(
                modifier = Modifier.fillMaxSize(),
                imageModel = { displayInfo.heroImageUrl },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                loading = { LoadingScreen() },
                failure = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(listOf(NovaAccent, NovaAccentDeep)),
                            ),
                    )
                },
                previewPlaceholder = painterResource(R.drawable.testhero),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(NovaAccent, NovaAccentDeep))),
            )
        }

        // Bottom vignette — title legibility
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.25f),
                            Color.Black.copy(alpha = 0.80f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        // Top gradient — back button visibility on light images
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.45f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        // Right-edge vignette (landscape only) — blends art into dashboard panel
        if (vignetteToRight) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(80.dp)
                    .align(Alignment.CenterEnd)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                NovaPanelSurface.copy(alpha = 0.90f),
                            ),
                        ),
                    ),
            )
        }

        // Back button — top-left, respects status bar / cutout insets
        ActionIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            onClick = onBack,
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(16.dp),
        )

        // Game identity anchored to bottom-left
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, end = 24.dp, bottom = 20.dp, top = 80.dp),
        ) {
            // Collection slot lives here too (e.g. DMC sub-game picker)
            if (collectionSlot != null) {
                collectionSlot()
                Spacer(modifier = Modifier.height(12.dp))
            }

            Text(
                text = displayInfo.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f,
                    ),
                ),
                color = NovaTextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val releaseYear = remember(displayInfo.releaseDate) {
                if (displayInfo.releaseDate > 0) {
                    SimpleDateFormat("yyyy", Locale.getDefault())
                        .format(Date(displayInfo.releaseDate * 1000))
                } else {
                    ""
                }
            }
            Text(
                text = "${displayInfo.developer} • $releaseYear",
                style = MaterialTheme.typography.bodyMedium,
                color = NovaTextPrimary.copy(alpha = 0.80f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Action dashboard — the right/bottom pane: all game actions
// ---------------------------------------------------------------------------

/**
 * Scrollable action dashboard panel. Dashboard order per spec:
 *   1. Launch/Install (primary, default focus, indigo)
 *   2. Make It Work (with tuner status)
 *   3. Speed hack (persisted multiplier readout)
 *   4. Cheats (availability chip)
 *   5. Settings + Delete icon buttons (footer)
 *   6. Game information cards
 *
 * Extracted into its own function to keep [AppScreenLandscape] / [AppScreenPortrait]
 * below the ART bytecode verifier's register-frame ceiling.
 */
@Composable
private fun ActionDashboard(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    hasPartialDownload: Boolean,
    downloadProgress: Float,
    hasInternet: Boolean,
    hasWifiOrEthernet: Boolean,
    buttonEnabled: Boolean,
    pauseResumeEnabled: Boolean,
    downloadSizeText: String,
    downloadTimeLeftText: String,
    hasCheatTable: Boolean,
    cheatCount: Int,
    isUpdatePending: Boolean,
    actions: AppScreenActions,
    playButtonFocusRequester: FocusRequester,
    onProgressBarPositioned: (Rect) -> Unit,
    onOpenOptions: () -> Unit,
    onShowCheats: () -> Unit,
    onUpdateClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── 1. Launch / Install / Download (PRIMARY) ──────────────────────────
        DashboardLaunchButton(
            isInstalled = isInstalled,
            isDownloading = isDownloading,
            hasPartialDownload = hasPartialDownload,
            downloadProgress = downloadProgress,
            hasInternet = hasInternet,
            hasWifiOrEthernet = hasWifiOrEthernet,
            buttonEnabled = buttonEnabled,
            pauseResumeEnabled = pauseResumeEnabled,
            downloadSizeText = downloadSizeText,
            downloadTimeLeftText = downloadTimeLeftText,
            actions = actions,
            playButtonFocusRequester = playButtonFocusRequester,
            onProgressBarPositioned = onProgressBarPositioned,
        )

        // ── 2. Make It Work ───────────────────────────────────────────────────
        if (isInstalled && actions.onMakeItWork != null) {
            val hasTunerConfig = remember(displayInfo.appId) {
                // TunerMemory.bestConfigForGame needs a Context — read once.
                false // evaluated below via LaunchedEffect; initially false
            }
            // We read the TunerMemory status inside the composable via remember(key).
            // To avoid needing a Context ref here we rely on the subtitle text produced
            // by the card state helper below (see DashboardMakeItWorkCard).
            DashboardMakeItWorkCard(
                appId = displayInfo.appId,
                onClick = actions.onMakeItWork,
            )
        }

        // ── 3. Speed hack ─────────────────────────────────────────────────────
        DashboardSpeedCard(
            appId = displayInfo.appId,
            onOpenOptions = onOpenOptions,
        )

        // ── 4. Cheats ─────────────────────────────────────────────────────────
        DashboardCheatsCard(
            hasCheatTable = hasCheatTable,
            cheatCount = cheatCount,
            onClick = onShowCheats,
        )

        // ── 5. Settings + Delete icon row ─────────────────────────────────────
        Spacer(modifier = Modifier.height(4.dp))
        DashboardIconRow(
            isInstalled = isInstalled,
            hasPartialDownload = hasPartialDownload,
            onOpenOptions = onOpenOptions,
            onDelete = actions.onDeleteDownloadClick,
        )

        // Hairline before info cards
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = NovaDivider,
            thickness = 1.dp,
        )

        // ── 6. Game information cards ─────────────────────────────────────────
        DashboardGameInfo(
            displayInfo = displayInfo,
            isInstalled = isInstalled,
            isDownloading = isDownloading,
            isUpdatePending = isUpdatePending,
            onUpdateClick = onUpdateClick,
        )

        // Bottom padding so content clears the GamepadActionBar
        Spacer(modifier = Modifier.height(56.dp))
    }
}

// ---------------------------------------------------------------------------
// Dashboard sub-composables (each small to respect ART register budget)
// ---------------------------------------------------------------------------

/** Launch / Pause / Resume / Install button — primary dashboard action. */
@Composable
private fun DashboardLaunchButton(
    isInstalled: Boolean,
    isDownloading: Boolean,
    hasPartialDownload: Boolean,
    downloadProgress: Float,
    hasInternet: Boolean,
    hasWifiOrEthernet: Boolean,
    buttonEnabled: Boolean,
    pauseResumeEnabled: Boolean,
    downloadSizeText: String,
    downloadTimeLeftText: String,
    actions: AppScreenActions,
    playButtonFocusRequester: FocusRequester,
    onProgressBarPositioned: (Rect) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isDownloading || hasPartialDownload) {
            PrimaryActionButton(
                text = if (isDownloading) stringResource(R.string.pause_download)
                else stringResource(R.string.resume_download),
                onClick = actions.onPauseResumeClick,
                enabled = pauseResumeEnabled,
                isInstalled = false,
                isDownloading = isDownloading,
                downloadProgress = downloadProgress,
                focusRequester = playButtonFocusRequester,
                onProgressBarPositioned = onProgressBarPositioned,
            )
            // Download size + ETA row
            if (isDownloading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (downloadSizeText.isNotEmpty()) {
                        Text(
                            text = downloadSizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = NovaTextMuted,
                        )
                    }
                    if (downloadTimeLeftText.isNotEmpty()) {
                        Text(
                            text = downloadTimeLeftText,
                            style = MaterialTheme.typography.labelSmall,
                            color = NovaTextMuted,
                        )
                    }
                }
            }
        } else {
            val text = when {
                isInstalled -> stringResource(R.string.run_app)
                !hasInternet -> stringResource(R.string.library_need_internet)
                !hasWifiOrEthernet && PrefManager.downloadOnWifiOnly ->
                    stringResource(R.string.library_wifi_only_enabled)
                else -> stringResource(R.string.install_app)
            }
            PrimaryActionButton(
                text = text,
                onClick = actions.onDownloadInstallClick,
                enabled = buttonEnabled,
                isInstalled = isInstalled,
                focusRequester = playButtonFocusRequester,
            )
        }
    }
}

/**
 * Make It Work card.
 *
 * Shows tuner status subtitle: reads [TunerMemory.bestConfigForGame] via a
 * LaunchedEffect so the Context access happens on the UI coroutine after first frame.
 */
@Composable
private fun DashboardMakeItWorkCard(
    appId: String,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    var hasTunerProfile by remember(appId) { mutableStateOf(false) }

    LaunchedEffect(appId) {
        hasTunerProfile = app.gamenative.autotuner.TunerMemory.bestConfigForGame(context, appId) != null
    }

    val subtitle = if (hasTunerProfile) "Profile applied" else "Not run yet"
    val chipBg = if (hasTunerProfile) NovaChipGreenBg else null
    val chipText = if (hasTunerProfile) "TUNED" else null
    val chipTextColor = NovaChipGreenText

    DashboardActionCard(
        icon = Icons.Default.Tune,
        title = stringResource(R.string.make_it_work),
        subtitle = subtitle,
        onClick = onClick,
        iconTint = if (hasTunerProfile) NovaChipGreenText else NovaTextMuted,
        subtitleColor = if (hasTunerProfile) NovaChipGreenText else NovaTextMuted,
        chipBg = chipBg,
        chipText = chipText,
        chipTextColor = chipTextColor,
    )
}

/**
 * Speed hack card — shows the persisted per-game multiplier.
 *
 * This is an INFORMATIONAL card on the detail screen. The live slider that writes the
 * control file is in the in-game Quick Menu ([SpeedSection] inside [TrainerTab]), because
 * it requires [TrainerShm] which is only available while a game is running.
 * Tapping this card opens the GameOptionsPanel (Settings) where the user can configure
 * speed hack globally; the per-game multiplier readout makes it clear what value is armed.
 */
@Composable
private fun DashboardSpeedCard(
    appId: String,
    onOpenOptions: () -> Unit,
) {
    val mult = remember(appId) { PrefManager.getSpeedMultiplier(appId) }
    val isCustomSpeed = mult != 1.0f
    val readout = if (isCustomSpeed) {
        if (mult == mult.toLong().toFloat()) "${mult.toLong()}×" else "${mult}×"
    } else {
        "Off"
    }

    DashboardActionCard(
        icon = Icons.Default.Speed,
        title = "Speed hack",
        subtitle = if (isCustomSpeed) "Armed — opens on next launch" else "Normal speed",
        onClick = onOpenOptions, // opens GameOptionsPanel where speed can be toggled globally
        iconTint = if (isCustomSpeed) NovaChipIndigoText else NovaTextMuted,
        subtitleColor = if (isCustomSpeed) NovaChipIndigoText else NovaTextMuted,
        chipBg = if (isCustomSpeed) NovaChipIndigoBg else null,
        chipText = if (isCustomSpeed) readout else null,
        chipTextColor = NovaChipIndigoText,
    )
}

/** Cheats discoverability card. */
@Composable
private fun DashboardCheatsCard(
    hasCheatTable: Boolean,
    cheatCount: Int,
    onClick: () -> Unit,
) {
    val subtitle = if (hasCheatTable) {
        stringResource(R.string.cheat_detail_available_count, cheatCount)
    } else {
        stringResource(R.string.cheat_detail_diy_available)
    }

    DashboardActionCard(
        icon = Icons.Rounded.FlashOn,
        title = "Cheats",
        subtitle = subtitle,
        onClick = onClick,
        iconTint = if (hasCheatTable) Color(0xFFFFD700) else NovaTextMuted,
        subtitleColor = if (hasCheatTable) Color(0xFFFFD700) else NovaTextMuted,
        chipBg = if (hasCheatTable) NovaChipIndigoBg else null,
        chipText = if (hasCheatTable) "$cheatCount" else null,
        chipTextColor = NovaChipIndigoText,
    )
}

/** Settings + Delete icon row at the bottom of the dashboard. */
@Composable
private fun DashboardIconRow(
    isInstalled: Boolean,
    hasPartialDownload: Boolean,
    onOpenOptions: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Section label
        Text(
            text = "MANAGE",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 0.08.sp,
            ),
            color = Color(0xFF555566),
            modifier = Modifier.weight(1f),
        )
        ActionIconButton(
            icon = Icons.Default.Settings,
            contentDescription = stringResource(R.string.options),
            onClick = onOpenOptions,
        )
        if (isInstalled || hasPartialDownload) {
            ActionIconButton(
                icon = Icons.Default.Delete,
                contentDescription = if (isInstalled) {
                    stringResource(R.string.uninstall)
                } else {
                    stringResource(R.string.delete_app)
                },
                onClick = onDelete,
                tint = Color(0xFFEF5350).copy(alpha = 0.8f),
            )
        }
    }
}

/** Game info cards section (status, size, developer, release date, install, play time). */
@Composable
private fun DashboardGameInfo(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    isUpdatePending: Boolean,
    onUpdateClick: () -> Unit,
) {
    val context = LocalContext.current

    // Section label
    Text(
        text = "GAME INFORMATION",
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 0.08.sp,
        ),
        color = Color(0xFF555566),
        modifier = Modifier.padding(bottom = 4.dp),
    )

    if (isUpdatePending) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.update_available),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onUpdateClick) {
                    Text(
                        stringResource(R.string.update_now),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Status + Size
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val statusText = when {
            isInstalled -> stringResource(R.string.installed)
            isDownloading -> stringResource(R.string.installing)
            else -> stringResource(R.string.not_installed)
        }
        val statusColor = when {
            isInstalled -> PluviaTheme.colors.statusInstalled
            isDownloading -> MaterialTheme.colorScheme.tertiary
            else -> null
        }
        InfoCard(
            label = stringResource(R.string.status),
            value = statusText,
            statusColor = statusColor,
            isCompact = true,
            modifier = Modifier.weight(1f),
            focusableForNavigation = true,
        )
        InfoCard(
            label = stringResource(R.string.size),
            value = when {
                isInstalled && displayInfo.sizeOnDisk != null -> displayInfo.sizeOnDisk
                !isInstalled && displayInfo.sizeFromStore != null -> displayInfo.sizeFromStore
                else -> stringResource(R.string.library_compatibility_unknown)
            },
            isCompact = true,
            modifier = Modifier.weight(1f),
            focusableForNavigation = true,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Developer + Release date
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoCard(
            label = stringResource(R.string.developer),
            value = displayInfo.developer,
            isCompact = true,
            modifier = Modifier.weight(1f),
            focusableForNavigation = true,
        )
        InfoCard(
            label = stringResource(R.string.release_date),
            value = remember(displayInfo.releaseDate) {
                if (displayInfo.releaseDate > 0) {
                    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        .format(Date(displayInfo.releaseDate * 1000))
                } else {
                    context.getString(R.string.library_compatibility_unknown)
                }
            },
            isCompact = true,
            modifier = Modifier.weight(1f),
            focusableForNavigation = true,
        )
    }

    if (isInstalled && displayInfo.installLocation != null) {
        Spacer(modifier = Modifier.height(8.dp))
        InfoCard(
            label = stringResource(R.string.location),
            value = displayInfo.installLocation,
            isCompact = true,
            modifier = Modifier.fillMaxWidth(),
            focusableForNavigation = true,
        )
    }

    if (displayInfo.playtimeText != null || displayInfo.lastPlayedText != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (displayInfo.playtimeText != null) {
                InfoCard(
                    label = stringResource(R.string.play_time),
                    value = displayInfo.playtimeText,
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                    focusableForNavigation = true,
                )
            }
            if (displayInfo.lastPlayedText != null) {
                InfoCard(
                    label = stringResource(R.string.last_played),
                    value = displayInfo.lastPlayedText,
                    isCompact = true,
                    modifier = Modifier.weight(1f),
                    focusableForNavigation = true,
                )
            }
        }
    }

    // Compatibility message (if any)
    if (displayInfo.compatibilityMessage != null && displayInfo.compatibilityColor != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = displayInfo.compatibilityMessage,
            style = MaterialTheme.typography.labelSmall,
            color = Color(displayInfo.compatibilityColor),
        )
    }
}

// ---------------------------------------------------------------------------
// Download progress text helpers (unchanged — ART register pressure isolation)
// ---------------------------------------------------------------------------

@Immutable
private data class DownloadProgressText(
    val sizeText: String,
    val timeLeftText: String,
)

@Composable
private fun rememberDownloadProgressText(
    appId: String,
    gameId: Int,
    downloadInfo: app.gamenative.data.DownloadInfo?,
    downloadProgress: Float,
    isDownloading: Boolean,
): DownloadProgressText {
    val downloadStatusMessageFlow = remember(downloadInfo) { downloadInfo?.getStatusMessageFlow() }
    val downloadStatusMessage by (
        downloadStatusMessageFlow?.collectAsState(initial = downloadStatusMessageFlow.value)
            ?: remember { mutableStateOf<String?>(null) }
        )
    val downloadingLabel = stringResource(R.string.downloading)
    val etaBucket = run {
        val etaMs = downloadInfo?.getEstimatedTimeRemaining() ?: 0L
        etaMs / 5000L
    }
    val unpackingLabel = stringResource(R.string.unpacking)
    val timeLeftText = remember(appId, etaBucket, downloadInfo, isDownloading, downloadStatusMessage) {
        val etaMs = downloadInfo?.getEstimatedTimeRemaining()
        if (etaMs != null && etaMs > 0L) {
            formatEtaMs(etaMs) + " left"
        } else if (isDownloading && downloadProgress >= 1f) {
            downloadStatusMessage?.takeUnless { it.isBlank() } ?: unpackingLabel
        } else if (downloadProgress in 0f..1f && downloadProgress < 1f) {
            downloadStatusMessage?.takeUnless { it.isBlank() } ?: ""
        } else {
            ""
        }
    }
    val sizeText = remember(gameId, downloadProgress, downloadInfo) {
        val (bytesDone, bytesTotal) = downloadInfo?.getBytesProgress() ?: (0L to 0L)
        if (bytesTotal > 0L) {
            "${formatBytes(bytesDone)} / ${formatBytes(bytesTotal)}"
        } else if (bytesDone > 0L) {
            formatBytes(bytesDone)
        } else {
            downloadingLabel
        }
    }
    return DownloadProgressText(sizeText = sizeText, timeLeftText = timeLeftText)
}

// ---------------------------------------------------------------------------
// GameMigrationDialog (unchanged)
// ---------------------------------------------------------------------------

@Composable
fun GameMigrationDialog(
    progress: Float,
    currentFile: String,
    movedFiles: Int,
    totalFiles: Int,
) {
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null) },
        title = { Text(text = stringResource(R.string.moving_files)) },
        text = {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.library_file_count, movedFiles + 1, totalFiles),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = currentFile,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = { progress },
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
    )
}

// ---------------------------------------------------------------------------
// CollectionSubGamesSection (unchanged — DMC-style collection picker)
// ---------------------------------------------------------------------------

@Composable
internal fun CollectionSubGamesSection(
    collection: GameCollection,
    lastPlayedExePath: String?,
    onPlaySubGame: (CollectionSubGame) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Games in this collection",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 8.dp),
            )
            collection.subGames.forEach { subGame ->
                val isLastPlayed = !lastPlayedExePath.isNullOrEmpty() &&
                    subGame.exePath.equals(lastPlayedExePath, ignoreCase = true)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = subGame.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isLastPlayed) Color.White else Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isLastPlayed) {
                            Text(
                                text = stringResource(R.string.last_played),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    }
                    val playLabel = stringResource(R.string.play_sub_game_fmt, subGame.name)
                    if (isLastPlayed) {
                        Button(
                            onClick = { onPlaySubGame(subGame) },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NovaAccent),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .semantics { contentDescription = playLabel },
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = androidx.compose.ui.Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.run_app))
                        }
                    } else {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onPlaySubGame(subGame) },
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                Color.White.copy(alpha = 0.5f),
                            ),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                contentColor = Color.White.copy(alpha = 0.8f),
                            ),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .semantics { contentDescription = playLabel },
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = androidx.compose.ui.Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.run_app))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    device = "spec:width=1920px,height=1080px,dpi=440",
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
) // Odin2 Mini
@Composable
private fun Preview_AppScreen() {
    val context = LocalContext.current
    PrefManager.init(context)
    val intent = Intent(context, SteamService::class.java)
    context.startForegroundService(intent)
    var isDownloading by remember { mutableStateOf(false) }
    val fakeApp = fakeAppInfo(1)
    val displayInfo = GameDisplayInfo(
        name = fakeApp.name,
        developer = fakeApp.developer,
        releaseDate = fakeApp.releaseDate,
        heroImageUrl = fakeApp.getHeroUrl(),
        iconUrl = fakeApp.iconUrl,
        gameId = fakeApp.id,
        appId = "STEAM_${fakeApp.id}",
        installLocation = null,
        sizeOnDisk = null,
        sizeFromStore = null,
        lastPlayedText = null,
        playtimeText = null,
    )
    PluviaTheme {
        Surface {
            AppScreenContent(
                displayInfo = displayInfo,
                isInstalled = false,
                isValidToDownload = true,
                isDownloading = isDownloading,
                downloadProgress = .50f,
                hasPartialDownload = false,
                isUpdatePending = false,
                downloadInfo = null,
                actions = AppScreenActions(
                    onDownloadInstallClick = { isDownloading = !isDownloading },
                    onPauseResumeClick = { },
                    onDeleteDownloadClick = { },
                    onUpdateClick = { },
                ),
                optionsMenu = AppOptionMenuType.entries.map {
                    AppMenuOption(
                        optionType = it,
                        onClick = { },
                    )
                }.toTypedArray(),
            )
        }
    }
}
