package app.gamenative.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.backup.LibraryBackupManager
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.ui.util.SnackbarManager
import com.alorma.compose.settings.ui.SettingsMenuLink
import kotlinx.coroutines.launch

/**
 * Export / restore surface for the installed-game library backup feature.
 *
 * Self-contained: drop a single [LibraryBackupSection] call into [SettingsGroupIIC] to add two
 * rows — "Export library backup" and "Restore library backup". Export writes a versioned JSON
 * manifest to public Downloads (survives uninstall); restore re-links only games whose files
 * physically exist on disk. Never downloads, never deletes.
 */
@Composable
fun LibraryBackupSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isBusy by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    // SAF import picker — ACTION_OPEN_DOCUMENT, works on Android 9+ with no storage permission.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) pendingRestoreUri = uri
    }

    // ── Confirmation dialog before applying a restore ───────────────────────
    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!isBusy) pendingRestoreUri = null },
            title = { Text(text = stringResource(R.string.iic_backup_restore_confirm_title)) },
            text = { Text(text = stringResource(R.string.iic_backup_restore_confirm_body)) },
            confirmButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = {
                        scope.launch {
                            isBusy = true
                            SnackbarManager.show(context.getString(R.string.iic_backup_restore_running))
                            val result = LibraryBackupManager.importLibrary(context, uri)
                            isBusy = false
                            pendingRestoreUri = null
                            val msg = result.errorMessage
                                ?: context.getString(
                                    R.string.iic_backup_restore_result,
                                    result.restored,
                                    result.skippedMissingFiles,
                                    result.failed,
                                )
                            SnackbarManager.show(msg)
                        }
                    },
                ) { Text(text = stringResource(R.string.iic_backup_restore_confirm_action)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !isBusy,
                    onClick = { pendingRestoreUri = null },
                ) { Text(text = stringResource(R.string.iic_backup_cancel)) }
            },
        )
    }

    // ── Export row ──────────────────────────────────────────────────────────
    SettingsMenuLink(
        colors = settingsTileColorsAlt(),
        title = { Text(text = stringResource(R.string.iic_backup_export_title)) },
        subtitle = { Text(text = stringResource(R.string.iic_backup_export_subtitle)) },
        onClick = {
            if (isBusy) return@SettingsMenuLink
            scope.launch {
                isBusy = true
                SnackbarManager.show(context.getString(R.string.iic_backup_export_running))
                val result = LibraryBackupManager.exportLibrary(context)
                isBusy = false
                result
                    .onSuccess {
                        SnackbarManager.show(
                            context.getString(
                                R.string.iic_backup_export_success,
                                it.gameCount,
                                it.displayName,
                            ),
                        )
                    }
                    .onFailure {
                        SnackbarManager.show(
                            context.getString(
                                R.string.iic_backup_export_failed,
                                it.message ?: "",
                            ),
                        )
                    }
            }
        },
    )

    // ── Restore row ─────────────────────────────────────────────────────────
    SettingsMenuLink(
        colors = settingsTileColorsAlt(),
        title = { Text(text = stringResource(R.string.iic_backup_restore_title)) },
        subtitle = { Text(text = stringResource(R.string.iic_backup_restore_subtitle)) },
        onClick = {
            if (isBusy) return@SettingsMenuLink
            // application/json plus a wildcard fallback — some providers tag .json as octet-stream.
            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        },
    )
}
