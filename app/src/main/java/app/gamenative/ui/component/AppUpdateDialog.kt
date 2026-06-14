package app.gamenative.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.gamenative.update.AppInstallState
import app.gamenative.update.PendingUpdate

/**
 * Patch-notes dialog shown when an app self-update is available.
 *
 * Ported from io.github.mayusi.isitcompatible.ui.common.AppUpdateDialog.
 * Uses MaterialTheme.colorScheme.* which PluviaTheme wraps — renders fine.
 *
 * Markdown is NOT rendered via a library (no new dep required). Instead we
 * preprocess the GitHub release body:
 *   - Lines starting with "## " → bold header
 *   - Lines starting with "- " or "* " → bullet "• "
 *   - Blank lines → paragraph break
 *   - Everything else → normal body text
 */
@Composable
fun AppUpdateDialog(
    update: PendingUpdate,
    installState: AppInstallState,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isInstalling = installState is AppInstallState.Resolving ||
        installState is AppInstallState.Downloading

    AlertDialog(
        onDismissRequest = { if (!isInstalling) onDismiss() },
        title = {
            Column {
                Text(
                    "Update to ${update.version}",
                    style = MaterialTheme.typography.titleLarge,
                )
                if (update.releaseTitle != update.version) {
                    Text(
                        update.releaseTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val sizeMb = if (update.apkSizeBytes > 0) {
                    "%.1f MB".format(update.apkSizeBytes / 1_000_000.0)
                } else null
                if (sizeMb != null) {
                    Text(
                        "Size: $sizeMb",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (update.patchNotes.isNotBlank()) {
                    Text(
                        renderPatchNotes(update.patchNotes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(12.dp))

                when (installState) {
                    AppInstallState.Resolving -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(18.dp)
                                    .height(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Preparing download…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is AppInstallState.Downloading -> {
                        Text(
                            "Downloading ${installState.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { installState.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppInstallState.ReadyToInstall -> {
                        Text(
                            "Download complete — the system installer is open.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    is AppInstallState.Failed -> {
                        Text(
                            installState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    AppInstallState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onInstall,
                enabled = !isInstalling && installState !is AppInstallState.ReadyToInstall,
            ) {
                Text(if (installState is AppInstallState.ReadyToInstall) "Installing…" else "Update now")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        },
    )
}

/**
 * Minimal markdown preprocessor — no external dependency.
 *
 * Handles the subset of GitHub release-body markdown most commonly used:
 * headings (##), bullets (- / *), and blank-line paragraph breaks.
 */
private fun renderPatchNotes(raw: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val lines = raw.split('\n')
        for ((idx, line) in lines.withIndex()) {
            val trimmed = line.trimEnd()
            when {
                trimmed.startsWith("## ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(trimmed.removePrefix("## "))
                    }
                }
                trimmed.startsWith("### ") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(trimmed.removePrefix("### "))
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("• " + trimmed.drop(2))
                }
                trimmed.isEmpty() -> {
                    if (idx < lines.lastIndex) append("\n")
                }
                else -> append(trimmed)
            }
            if (idx < lines.lastIndex && trimmed.isNotEmpty()) append("\n")
        }
    }
}
