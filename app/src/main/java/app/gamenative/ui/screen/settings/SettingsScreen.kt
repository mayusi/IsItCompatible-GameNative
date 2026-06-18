package app.gamenative.ui.screen.settings

import android.content.res.Configuration
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.enums.AppTheme
import app.gamenative.ui.theme.NovaInk
import app.gamenative.ui.theme.PluviaTheme
import com.materialkolor.PaletteStyle

// NovaGN spec: EaseOutCubic — cubic-bezier(0.33, 1, 0.68, 1)
private val EaseOutCubic: Easing = Easing { t ->
    val t1 = t - 1f
    t1 * t1 * t1 + 1f
}

// Spec colors used inline (matches NOVAGN_DESIGN.md)
private val NovaSectionLabel = Color(0xFF9CA0AD)   // Text muted / section labels
private val NovaPanelSurface = Color(0xFF13131B)   // Panel surface — cards/panels
private val NovaDivider = Color(0xFF1A1A24)        // Divider / hairline

@Composable
fun SettingsScreen(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScreenContent(
        appTheme = appTheme,
        paletteStyle = paletteStyle,
        onAppTheme = onAppTheme,
        onPaletteStyle = onPaletteStyle,
        onBack = onBack,
    )
}

@Composable
private fun SettingsScreenContent(
    appTheme: AppTheme,
    paletteStyle: PaletteStyle,
    onAppTheme: (AppTheme) -> Unit,
    onPaletteStyle: (PaletteStyle) -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // Flat near-black ink canvas — no gradient, matches spec "Ink" (#0B0B0F)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NovaInk),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .displayCutoutPadding(),
        ) {
            SettingsHeader(
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )

            // Scrollable section list
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Emulation section
                NovaSettingsSection(
                    label = stringResource(R.string.settings_emulation_title),
                ) {
                    SettingsGroupEmulation()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Interface section
                NovaSettingsSection(
                    label = stringResource(R.string.settings_interface_title),
                ) {
                    SettingsGroupInterface(
                        appTheme = appTheme,
                        paletteStyle = paletteStyle,
                        onAppTheme = onAppTheme,
                        onPaletteStyle = onPaletteStyle,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Info section
                NovaSettingsSection(
                    label = stringResource(R.string.settings_info_title),
                ) {
                    SettingsGroupInfo()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // NovaGN brand section
                NovaSettingsSection(
                    label = stringResource(R.string.settings_iic_section_title),
                ) {
                    SettingsGroupIIC()
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Debug section
                NovaSettingsSection(
                    label = stringResource(R.string.settings_debug_title),
                ) {
                    SettingsGroupDebug()
                }

                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

/**
 * NovaGN spec grouped-settings pattern:
 * UPPERCASE muted section label (11sp / 600 / +0.08em / #9CA0AD) above a flat
 * #13131B panel (12dp radius). Dividers between rows are handled by each
 * SettingsGroup*() composable using the shared NovaDivider color; this shell
 * just provides the outer label + rounded container.
 *
 * The old "Surface card with colored icon-box per section" is intentionally gone —
 * that was the generic-Material tell.
 */
@Composable
private fun NovaSettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // UPPERCASE tracked muted label — spec: 11sp / 600 / +0.08em / #9CA0AD
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                letterSpacing = 0.08.sp * 11f, // +0.08em at 11sp
                color = NovaSectionLabel,
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp),
            textAlign = TextAlign.Start,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Flat grouped panel — #13131B, 12dp radius, no elevation
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = NovaPanelSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsHeader(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackButton(onClick = onBack)

        // Screen title — spec: 28sp / 700 / line-height 1.1
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    lineHeight = (28 * 1.1).sp,
                    letterSpacing = 0.sp,
                ),
                color = Color(0xFFFAFAFA),
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    letterSpacing = 0.4.sp,
                ),
                color = NovaSectionLabel,
            )
        }
        // No decorative gear — spec says indigo accent only on active nav/focus/launch/
        // toggle-ON/slider; a purely decorative icon at the header is off-spec.
    }
}

@Composable
private fun BackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Spec: 120ms EaseOutCubic focus scale (no spring physics — "springs read playful")
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 120, easing = EaseOutCubic),
        label = "backButtonScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    PluviaTheme.colors.accentBrand.copy(alpha = 0.16f)
                } else {
                    NovaDivider // #1A1A24 — elevated panel tier, subtle on ink bg
                },
            )
            .then(
                if (isFocused) {
                    // Spec focused control: 2dp indigo border
                    Modifier.border(2.dp, PluviaTheme.colors.accentBrand, CircleShape)
                } else {
                    Modifier.border(1.dp, Color(0xFF262633), CircleShape)
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
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = if (isFocused) PluviaTheme.colors.accentBrand else Color(0xFFC0C0CC),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(
    uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL,
    device = "spec:width=1920px,height=1080px,dpi=440,orientation=landscape",
)
@Composable
private fun Preview_SettingsScreen() {
    val isPreview = LocalInspectionMode.current
    if (!isPreview) {
        val context = LocalContext.current
        PrefManager.init(context)
    }
    PluviaTheme {
        SettingsScreenContent(
            appTheme = AppTheme.DAY,
            paletteStyle = PaletteStyle.TonalSpot,
            onAppTheme = { },
            onPaletteStyle = { },
            onBack = { },
        )
    }
}
