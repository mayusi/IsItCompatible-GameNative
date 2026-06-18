package app.gamenative.ui.screen.library.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.icons.Amazon
import app.gamenative.ui.icons.Steam
import app.gamenative.ui.theme.NovaAccent
import app.gamenative.ui.theme.NovaAccentBright

// Rail width constant — a single source of truth used in LibraryScreen.
val NovaRailWidth = 64.dp

/**
 * NovaGN left source-filter rail.
 *
 * Replaces the top LibraryTabBar. Shows source tabs as icon-only items on a 64dp
 * vertical strip. Active item gets the indigo fill and a 3px left-edge indicator;
 * inactive items are dim (#9CA0AD). Search and Add icons sit at the bottom of the
 * rail so they remain reachable without leaving the left edge.
 *
 * Gamepad focus: the rail is a focusGroup. When focus enters from the right (grid),
 * the first item captures it. DPAD_RIGHT from inside the rail passes focus back to
 * the content area via LocalFocusManager.moveFocus(FocusDirection.Right). This is
 * the same technique the rest of the app uses — no new focus system invented.
 */
@Composable
fun LibrarySourceRail(
    currentTab: LibraryTab,
    tabCounts: Map<LibraryTab, Int>,
    onTabSelected: (LibraryTab) -> Unit,
    onSearchClick: () -> Unit,
    onAddGameClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .width(NovaRailWidth)
            .fillMaxHeight()
            .background(Color(0xFF13131B)) // Panel surface per spec
            .focusGroup()
            .onPreviewKeyEvent { keyEvent ->
                // DPAD_RIGHT exits rail → content area
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                ) {
                    focusManager.moveFocus(FocusDirection.Right)
                    true
                } else {
                    false
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        // ── NovaGN monogram mark at top ──────────────────────────────────
        Box(
            modifier = Modifier
                .height(52.dp)
                .width(NovaRailWidth),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "N",
                color = NovaAccentBright,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        // Hairline divider below mark
        HorizontalDivider(
            color = Color(0xFF1A1A24),
            thickness = 1.dp,
            modifier = Modifier.width(40.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Source filter items ──────────────────────────────────────────
        val tabs = LibraryTab.entries
        tabs.forEach { tab ->
            SourceRailItem(
                tab = tab,
                isSelected = tab == currentTab,
                count = tabCounts[tab] ?: 0,
                onClick = { onTabSelected(tab) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        // Hairline above action buttons
        HorizontalDivider(
            color = Color(0xFF1A1A24),
            thickness = 1.dp,
            modifier = Modifier.width(40.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ── Bottom action icons ──────────────────────────────────────────
        RailActionButton(
            icon = Icons.Default.Search,
            contentDescription = stringResource(R.string.search),
            onClick = onSearchClick,
        )

        Spacer(modifier = Modifier.height(4.dp))

        RailActionButton(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.action_add_game),
            onClick = onAddGameClick,
        )

        Spacer(modifier = Modifier.height(12.dp))
    }
}

// ── Individual source tab item ────────────────────────────────────────────────

@Composable
private fun SourceRailItem(
    tab: LibraryTab,
    isSelected: Boolean,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgAlpha by animateFloatAsState(
        targetValue = when {
            isSelected -> 1f
            isFocused -> 0.18f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 80),
        label = "railItemBgAlpha",
    )

    val iconAlpha by animateFloatAsState(
        targetValue = when {
            isSelected || isFocused -> 1f
            else -> 0.45f // muted per spec #9CA0AD approx
        },
        animationSpec = tween(durationMillis = 80),
        label = "railItemIconAlpha",
    )

    Box(
        modifier = modifier
            .width(NovaRailWidth)
            .height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        // 3px left-edge active indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(NovaAccent),
            )
        }

        // Icon container with indigo fill when active
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(NovaAccent.copy(alpha = bgAlpha))
                .selectable(
                    selected = isSelected,
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SourceTabIcon(
                tab = tab,
                tint = if (isSelected || isFocused) Color.White else Color(0xFF9CA0AD),
                iconSize = 20,
                modifier = Modifier.alpha(iconAlpha),
            )
        }
    }
}

// ── Action icon (Search / Add) at rail bottom ─────────────────────────────────

@Composable
private fun RailActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgAlpha by animateFloatAsState(
        targetValue = if (isFocused) 0.18f else 0f,
        animationSpec = tween(durationMillis = 80),
        label = "actionBtnBgAlpha",
    )

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NovaAccent.copy(alpha = bgAlpha))
            .selectable(
                selected = false,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) Color.White else Color(0xFF9CA0AD),
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── Source-specific icons ─────────────────────────────────────────────────────

@Composable
private fun SourceTabIcon(
    tab: LibraryTab,
    tint: Color,
    iconSize: Int,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        LibraryTab.ALL -> Icon(
            imageVector = Icons.Default.Storage,
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
        LibraryTab.STEAM -> Icon(
            imageVector = Icons.Filled.Steam,
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
        LibraryTab.GOG -> Icon(
            painter = painterResource(R.drawable.ic_gog),
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
        LibraryTab.EPIC -> Icon(
            painter = painterResource(R.drawable.ic_epic),
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
        LibraryTab.AMAZON -> Icon(
            imageVector = Icons.Filled.Amazon,
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
        LibraryTab.LOCAL -> Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = stringResource(tab.labelResId),
            tint = tint,
            modifier = modifier.size(iconSize.dp),
        )
    }
}
