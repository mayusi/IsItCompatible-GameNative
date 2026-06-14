package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.AppUpdateBanner
import app.gamenative.ui.component.AppUpdateDialog
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.update.AppUpdateViewModel
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch

/**
 * Settings section describing IIC Edition features.
 * Shown in Settings so users know what this fork adds over official GameNative.
 *
 * Now also hosts the self-update UI:
 *  - [AppUpdateBanner] when a pending update exists
 *  - "Check for updates" action (manual trigger, not debounced)
 *  - "Auto-check for updates" toggle (default true)
 *  - [AppUpdateDialog] driven by [AppUpdateViewModel]
 */
@Composable
fun SettingsGroupIIC(
    updateVm: AppUpdateViewModel = hiltViewModel(),
) {
    val updateState by updateVm.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    // Full update dialog — opened by banner "See what's new" or remains open after
    // the launch-time dialog was dismissed.
    if (showUpdateDialog && updateState.pendingUpdate != null) {
        AppUpdateDialog(
            update = updateState.pendingUpdate!!,
            installState = updateState.installState,
            onInstall = updateVm::installUpdate,
            onDismiss = { showUpdateDialog = false },
        )
    }

    SettingsGroup {
        // ── Update banner ─────────────────────────────────────────────────────
        val pending = updateState.pendingUpdate
        if (pending != null) {
            AppUpdateBanner(
                update = pending,
                onSeeWhatsNew = { showUpdateDialog = true },
                onDismiss = updateVm::dismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // ── "Check for updates" action ────────────────────────────────────────
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = {
                Text(
                    text = if (updateState.checkInProgress) "Checking…"
                    else updateState.lastCheckMessage ?: "Check for updates",
                )
            },
            onClick = { if (!updateState.checkInProgress) updateVm.checkNow() },
        )

        // ── Auto-check toggle ─────────────────────────────────────────────────
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            state = PrefManager.updateAutoCheckEnabled,
            title = { Text(text = "Auto-check for updates") },
            subtitle = { Text(text = "Check automatically every 12 hours") },
            onCheckedChange = { updateVm.setAutoCheck(it) },
        )

        // ── Disclaimer row ────────────────────────────────────────────────────
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_iic_disclaimer)) },
            onClick = {},
        )

        // Feature list as a readable block
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_fixes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_storage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• " + stringResource(R.string.settings_iic_feature_import),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
