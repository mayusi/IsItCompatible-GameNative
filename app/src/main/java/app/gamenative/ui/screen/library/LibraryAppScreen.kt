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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import app.gamenative.ui.theme.NovaAccentDeep
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
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "primaryActionScale",
    )

    val buttonColor = when {
        isDownloading -> PluviaTheme.colors.statusDownloading
        isInstalled -> PluviaTheme.colors.statusInstalled
        else -> PluviaTheme.colors.statusAvailable
    }

    Box(
        modifier = modifier
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
                        Brush.verticalGradient(
                            colors = listOf(
                                NovaAccent,
                                NovaAccentDeep,
                            ),
                        ),
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
 * Icon-only action button for the overlay action bar
 */
@Composable
private fun ActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "actionIconScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isFocused) {
                    Color.White.copy(alpha = 0.2f)
                } else {
                    Color.White.copy(alpha = 0.1f)
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                NovaAccent,
                                NovaAccentDeep,
                            ),
                        ),
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
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Full-width "Make It Work" button: one-tap COMPAT_PROBE entry point.
 *
 * Styled as a secondary action (slightly recessed background with a Tune icon)
 * so it reads as first-class but doesn't compete with the primary Play button.
 */
@Composable
private fun MakeItWorkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "makeItWorkScale",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = if (isFocused) 0.18f else 0.1f))
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                PluviaTheme.colors.statusInstalled,
                                PluviaTheme.colors.statusInstalled.copy(alpha = 0.6f),
                            ),
                        ),
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = PluviaTheme.colors.statusInstalled,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.make_it_work),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = PluviaTheme.colors.statusInstalled,
            )
        }
    }
}

/**
 * Info card for game details with optional status indicator
 */
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
                if (state.isFocused) {
                    scope.launch { bringIntoViewRequester.bringIntoView() }
                }
            }
            .focusable()
            .then(
                if (isFocused) {
                    Modifier.border(
                        2.dp,
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.tertiary,
                            ),
                        ),
                        RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                },
            )
    } else {
        modifier
    }

    Surface(
        modifier = cardModifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(if (isCompact) 14.dp else 18.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (statusColor != null) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(statusColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = value,
                    style = if (isCompact) {
                        MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    },
                    color = if (statusColor != null) statusColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
    // Get the appropriate screen model based on game source
    val screenModel = remember(libraryItem.gameSource) {
        when (libraryItem.gameSource) {
            app.gamenative.data.GameSource.STEAM -> SteamAppScreen()
            app.gamenative.data.GameSource.CUSTOM_GAME -> CustomGameAppScreen()
            app.gamenative.data.GameSource.GOG -> GOGAppScreen()
            app.gamenative.data.GameSource.EPIC -> EpicAppScreen()
            app.gamenative.data.GameSource.AMAZON -> AmazonAppScreen()
        }
    }

    // Render the content using the model
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
 * Uses binary units (1024 base).
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
 * Stable holder for the action callbacks wired into [AppScreenContent].
 *
 * Bundling these into a single @Immutable parameter keeps the Composable's value-param
 * count low enough that the Compose compiler's generated changed-flag bitmask params stay
 * within the ART bytecode verifier's limits (a large param count previously triggered a
 * runtime VerifyError when the game-detail screen rendered).
 */
@Immutable
data class AppScreenActions(
    val onDownloadInstallClick: () -> Unit,
    val onPauseResumeClick: () -> Unit,
    val onDeleteDownloadClick: () -> Unit,
    val onUpdateClick: () -> Unit,
    val onBack: () -> Unit = {},
    /** If non-null, a "Make It Work" CTA is shown below the primary action bar (only when installed). */
    val onMakeItWork: (() -> Unit)? = null,
)

/**
 * Stable holder for the install/download/connectivity scalar state wired into [AppScreenHero].
 *
 * Same rationale as [AppScreenActions]: bundling these into a single @Immutable parameter keeps
 * the Composable's value-param count (and thus the Compose-generated changed-flag bitmask params)
 * low enough to stay within the ART bytecode verifier's limits. A large param count previously
 * triggered a runtime VerifyError when the game-detail screen rendered.
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
    /** If non-null, rendered above the action bar inside the hero section (collection sub-game list). */
    collectionSlot: (@Composable () -> Unit)? = null,
    vararg optionsMenu: AppMenuOption,
) {
    // reactive — recomposes when network state changes
    val hasInternet by NetworkMonitor.hasInternet.collectAsState()
    val hasWifiOrEthernet by NetworkMonitor.hasWifiOrEthernet.collectAsState()
    val downloadAllowed = !PrefManager.downloadOnWifiOnly || hasWifiOrEthernet
    val scrollState = rememberScrollState()

    var optionsMenuVisible by remember { mutableStateOf(false) }
    var showCheatsDialog by remember { mutableStateOf(false) }

    val cheatTable = remember(displayInfo.appId) {
        CheatTableRegistry.tableForAppId(displayInfo.appId)
    }
    val hasCheatTable = cheatTable != null
    val cheatCount = cheatTable?.cheats?.size ?: 0

    // Track the original progress bar bounds for ambient mode morph animation
    var progressBarBounds by remember { mutableStateOf<Rect?>(null) }
    var ambientInteractionCounter by remember { mutableStateOf(0) }

    // Focus requesters for gamepad navigation
    val playButtonFocusRequester = remember { FocusRequester() }

    // Calculate parallax offset based on scroll
    val parallaxOffset = scrollState.value * 0.5f

    LaunchedEffect(displayInfo.appId) {
        scrollState.animateScrollTo(0)
    }

    LaunchedEffect(Unit) {
        playButtonFocusRequester.requestFocus()
    }

    // Button state calculations (needed by key event handler)
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

    // Download progress texts hoisted here so they can be shown inside the button.
    // Extracted into a helper so these vals don't all live in this function's register frame.
    val downloadTexts = rememberDownloadProgressText(
        appId = displayInfo.appId,
        gameId = displayInfo.gameId,
        downloadInfo = downloadInfo,
        downloadProgress = downloadProgress,
        isDownloading = isDownloading,
    )
    val downloadTimeLeftText = downloadTexts.timeLeftText
    val downloadSizeText = downloadTexts.sizeText

    // Handle gamepad button presses
    val handleKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // SELECT button - open options menu
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    optionsMenuVisible = true
                    true
                }

                // START button - primary action (play/download/pause)
                KeyEvent.KEYCODE_BUTTON_START -> {
                    if (!optionsMenuVisible && startActionEnabled) {
                        onStartAction()
                    }
                    true
                }

                // B button - back
                KeyEvent.KEYCODE_BUTTON_B -> {
                    if (optionsMenuVisible) {
                        optionsMenuVisible = false
                    } else {
                        actions.onBack()
                    }
                    true
                }

                else -> false
            }
        } else {
            false
        }
    }

    // Handle back press when options panel is open
    BackHandler(enabled = optionsMenuVisible) {
        optionsMenuVisible = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
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
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    ambientInteractionCounter++
                }
                handleKeyEvent(it.nativeKeyEvent)
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // Hero Section (Parallax) — extracted so its many nested layers don't inflate
            // AppScreenContent's register frame past the ART bytecode verifier's ceiling.
            AppScreenHero(
                displayInfo = displayInfo,
                parallaxOffset = parallaxOffset,
                hero = HeroState(
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
                ),
                actions = actions,
                collectionSlot = collectionSlot,
                playButtonFocusRequester = playButtonFocusRequester,
                onProgressBarPositioned = { progressBarBounds = it },
                onOpenOptions = { optionsMenuVisible = true },
                onShowCheats = { showCheatsDialog = true },
            )

            // Content section below hero — extracted to keep AppScreenContent thin.
            AppScreenInfoSection(
                displayInfo = displayInfo,
                isInstalled = isInstalled,
                isDownloading = isDownloading,
                isUpdatePending = isUpdatePending,
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

        // Cheats discoverability dialog
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

        // Options panel - slides in from right
        GameOptionsPanel(
            isOpen = optionsMenuVisible,
            onDismiss = { optionsMenuVisible = false },
            options = optionsMenu.toList(),
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        // Ambient mode during downloads
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

/** Immutable holder for the download progress strings shown in the action bar. */
@Immutable
private data class DownloadProgressText(
    val sizeText: String,
    val timeLeftText: String,
)

/**
 * Computes the download size / ETA strings.
 *
 * Extracted out of [AppScreenContent] so the several `remember`d vals and the flow
 * collection that back these strings live in their own register frame instead of
 * inflating the parent's, which previously contributed to an ART verifier rejection.
 */
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

/**
 * Parallax hero section: full-bleed hero image, gradient overlays, back button and the
 * bottom title / action-bar overlay.
 *
 * Extracted from [AppScreenContent] — this is the deepest-nested block in the screen and
 * its lowered `invoke` register pressure is what previously pushed the parent over the
 * ART bytecode verifier's ceiling.
 */
@Composable
private fun AppScreenHero(
    displayInfo: GameDisplayInfo,
    parallaxOffset: Float,
    hero: HeroState,
    actions: AppScreenActions,
    collectionSlot: (@Composable () -> Unit)?,
    playButtonFocusRequester: FocusRequester,
    onProgressBarPositioned: (Rect) -> Unit,
    onOpenOptions: () -> Unit,
    onShowCheats: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        // Hero background image
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    translationY = parallaxOffset
                },
        ) {
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
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            NovaAccent,
                                            NovaAccentDeep,
                                        ),
                                    ),
                                ),
                        )
                    },
                    previewPlaceholder = painterResource(R.drawable.testhero),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    NovaAccent,
                                    NovaAccentDeep,
                                ),
                            ),
                        ),
                )
            }
        }

        // Gradient overlay (bottom, for title/action bar)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        // Top gradient overlay (so back button is visible on light/white images)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Black.copy(alpha = 0.15f),
                            Color.Transparent,
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
                    ),
                ),
        )

        // Back button (top left).
        // The hero image is intentionally drawn full-bleed through the status bar
        // and any display cutout (notch / hole-punch / side cutout). The button
        // itself, however, has to stay tappable, so it's pushed inwards by whichever
        // is larger of the status bar inset or the cutout inset on each affected
        // edge before the visual 16dp padding is applied.
        ActionIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            onClick = actions.onBack,
            modifier = Modifier
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(16.dp),
        )

        // Bottom overlay with title and action bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 128.dp, start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            // Game title
            Text(
                text = displayInfo.name,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.6f),
                        offset = Offset(0f, 2f),
                        blurRadius = 8f,
                    ),
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Developer and year
            val releaseYear = remember(displayInfo.releaseDate) {
                if (displayInfo.releaseDate > 0) {
                    SimpleDateFormat("yyyy", Locale.getDefault()).format(Date(displayInfo.releaseDate * 1000))
                } else {
                    ""
                }
            }
            Text(
                text = "${displayInfo.developer} • $releaseYear",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // "Games in this collection" section — shown above action bar when installed
            if (collectionSlot != null) {
                collectionSlot()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Integrated action bar - overlaid on hero
            HeroActionBar(
                isInstalled = hero.isInstalled,
                isDownloading = hero.isDownloading,
                hasPartialDownload = hero.hasPartialDownload,
                downloadProgress = hero.downloadProgress,
                hasInternet = hero.hasInternet,
                hasWifiOrEthernet = hero.hasWifiOrEthernet,
                buttonEnabled = hero.buttonEnabled,
                pauseResumeEnabled = hero.pauseResumeEnabled,
                downloadSizeText = hero.downloadSizeText,
                downloadTimeLeftText = hero.downloadTimeLeftText,
                actions = actions,
                playButtonFocusRequester = playButtonFocusRequester,
                onProgressBarPositioned = onProgressBarPositioned,
                onOpenOptions = onOpenOptions,
            )

            // Compatibility status (if applicable)
            if (displayInfo.compatibilityMessage != null && displayInfo.compatibilityColor != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displayInfo.compatibilityMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(displayInfo.compatibilityColor),
                )
            }

            // Cheats discoverability row
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .clickable { onShowCheats() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.FlashOn,
                    contentDescription = null,
                    tint = if (hero.hasCheatTable) Color(0xFFFFD700) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (hero.hasCheatTable) {
                        stringResource(R.string.cheat_detail_available_count, hero.cheatCount)
                    } else {
                        stringResource(R.string.cheat_detail_diy_available)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hero.hasCheatTable) Color(0xFFFFD700) else Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

/**
 * The integrated action bar overlaid on the hero: primary action button, inline / stacked
 * download-progress text, secondary icon buttons and the "Make It Work" CTA.
 *
 * Split out of [AppScreenHero] to keep each lowered method small for the ART verifier.
 */
@Composable
private fun HeroActionBar(
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
    onOpenOptions: () -> Unit,
) {
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Primary action button (left-aligned)
            if (isDownloading || hasPartialDownload) {
                PrimaryActionButton(
                    text = if (isDownloading) {
                        stringResource(R.string.pause_download)
                    } else {
                        stringResource(R.string.resume_download)
                    },
                    onClick = actions.onPauseResumeClick,
                    enabled = pauseResumeEnabled,
                    isInstalled = false,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    focusRequester = playButtonFocusRequester,
                    onProgressBarPositioned = onProgressBarPositioned,
                )
            } else {
                val text = when {
                    isInstalled -> stringResource(R.string.run_app)
                    !hasInternet -> stringResource(R.string.library_need_internet)
                    !hasWifiOrEthernet && PrefManager.downloadOnWifiOnly -> stringResource(R.string.library_wifi_only_enabled)
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

            // Download size / ETA text — inline only in landscape
            if (isDownloading && !isPortrait) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (downloadSizeText.isNotEmpty()) {
                        Text(
                            text = downloadSizeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (downloadTimeLeftText.isNotEmpty()) {
                        Text(
                            text = downloadTimeLeftText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Secondary action icons (right-aligned)
            ActionIconButton(
                icon = Icons.Default.Settings,
                contentDescription = stringResource(R.string.options),
                onClick = onOpenOptions,
            )

            if (isInstalled || hasPartialDownload) {
                ActionIconButton(
                    icon = Icons.Default.Delete,
                    contentDescription = if (isInstalled) stringResource(R.string.uninstall) else stringResource(R.string.delete_app),
                    onClick = actions.onDeleteDownloadClick,
                )
            }
        }

        if (isDownloading && isPortrait) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (downloadSizeText.isNotEmpty()) {
                    Text(
                        text = downloadSizeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                    )
                }
                if (downloadTimeLeftText.isNotEmpty()) {
                    Text(
                        text = downloadTimeLeftText,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
            }
        }

        // "Make It Work" CTA — shown when the game is installed and the
        // one-tap COMPAT_PROBE path is wired in.  Intentionally a full-width
        // secondary row so it reads as a first-class action, not a buried option.
        if (isInstalled && actions.onMakeItWork != null) {
            Spacer(modifier = Modifier.height(8.dp))
            MakeItWorkButton(onClick = actions.onMakeItWork)
        }
    }
}

/**
 * The solid-background info section below the hero: update banner and the game-information
 * cards (status, size, developer, release date, install location, play time).
 *
 * Extracted from [AppScreenContent] to keep the parent's lowered method small.
 */
@Composable
private fun AppScreenInfoSection(
    displayInfo: GameDisplayInfo,
    isInstalled: Boolean,
    isDownloading: Boolean,
    isUpdatePending: Boolean,
    onUpdateClick: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        // Update available banner
        if (isUpdatePending) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.update_available),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Button(
                        onClick = onUpdateClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(stringResource(R.string.update_now))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Game information section
        Text(
            text = stringResource(R.string.game_information),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(bottom = 12.dp),
        )

        // Info cards in 2-column grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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

        // Install location (when installed)
        if (isInstalled && displayInfo.installLocation != null) {
            Spacer(modifier = Modifier.height(10.dp))
            InfoCard(
                label = stringResource(R.string.location),
                value = displayInfo.installLocation,
                isCompact = true,
                modifier = Modifier.fillMaxWidth(),
                focusableForNavigation = true,
            )
        }

        // Play time and last played
        if (displayInfo.playtimeText != null || displayInfo.lastPlayedText != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
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
    }
}

@Composable
fun GameMigrationDialog(
    progress: Float,
    currentFile: String,
    movedFiles: Int,
    totalFiles: Int,
) {
    AlertDialog(
        onDismissRequest = {
            // We don't allow dismissal during move.
        },
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
// CollectionSubGamesSection — "Games in this collection" UX
// ---------------------------------------------------------------------------

/**
 * Renders a "Games in this collection" card with a Play button for each sub-game.
 * The last-played sub-game gets a filled Play button; others get outlined buttons.
 *
 * @param collection        The [GameCollection] whose sub-games to list.
 * @param lastPlayedExePath The exe path that was last played (from PrefManager), or null.
 * @param onPlaySubGame     Callback when the user taps Play for a specific sub-game.
 */
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
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
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

/***********
 * PREVIEW *
 ***********/

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
