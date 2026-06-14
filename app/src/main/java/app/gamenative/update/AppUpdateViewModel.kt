package app.gamenative.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.gamenative.PrefManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * ViewModel driving the self-update UX.
 *
 * Reads [PrefManager] for pending-update state written by [AppUpdateChecker.checkIfDue] /
 * [checkNow]. Surfaces install progress via [AppUpdateState] so the Settings banner and
 * the launch-time dialog can both subscribe without duplicating logic.
 *
 * Ported from io.github.mayusi.isitcompatible.ui.appupdate.AppUpdateViewModel.
 * Delta: uses fork's [PrefManager] object (synchronous property reads) instead of
 * UserPreferences DataStore flow. The ViewModel re-reads prefs on each relevant action
 * rather than observing a flow, which matches PrefManager's object-singleton design.
 */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val checker: AppUpdateChecker,
    private val downloader: ApkDownloader,
) : ViewModel() {

    private val _state = MutableStateFlow(buildStateFromPrefs())
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** Refresh pending-update state from PrefManager (call after prefs change). */
    fun refreshPendingUpdate() {
        _state.update { buildStateFromPrefs() }
    }

    /**
     * Manual "Check for updates" — not debounced by the 12h gate.
     * Sets [AppUpdateState.checkInProgress] while running and updates
     * [AppUpdateState.lastCheckMessage] on completion.
     */
    fun checkNow() {
        viewModelScope.launch {
            _state.update { it.copy(checkInProgress = true, lastCheckMessage = null) }
            val result = checker.check()
            PrefManager.lastUpdateCheckMs = System.currentTimeMillis()
            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    PrefManager.setPendingUpdate(
                        version = result.version,
                        url = result.apkUrl,
                        filename = result.apkFilename,
                        notes = result.patchNotes,
                        sizeBytes = result.apkSizeBytes,
                        sha256 = result.sha256,
                    )
                    _state.update { buildStateFromPrefs().copy(checkInProgress = false) }
                }
                UpdateCheckResult.UpToDate -> {
                    _state.update {
                        it.copy(
                            checkInProgress = false,
                            lastCheckMessage = "You're on the latest version",
                        )
                    }
                }
                UpdateCheckResult.NoReleasesYet -> {
                    _state.update { it.copy(checkInProgress = false, lastCheckMessage = null) }
                }
                is UpdateCheckResult.Failed -> {
                    _state.update {
                        it.copy(
                            checkInProgress = false,
                            lastCheckMessage = "Check failed: ${result.reason}",
                        )
                    }
                }
            }
        }
    }

    /**
     * Download + hand-off to system installer.
     *
     * Guards on [InstallPermission.canInstall]; if the user hasn't granted
     * "install unknown apps", emits Failed with an actionable message.
     * The caller can surface [InstallPermission.settingsIntent] to let the
     * user fix the permission without leaving the app.
     */
    fun installUpdate() {
        val pending = _state.value.pendingUpdate ?: return

        if (!InstallPermission.canInstall(context)) {
            _state.update {
                it.copy(
                    installState = AppInstallState.Failed(
                        "Allow 'Install unknown apps' for this app in Settings first."
                    )
                )
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(installState = AppInstallState.Resolving) }
            downloader.download(pending.apkUrl, pending.apkFilename, pending.sha256)
                .collect { progress ->
                    when (progress) {
                        is ApkDownloader.Progress.Started ->
                            _state.update { it.copy(installState = AppInstallState.Downloading(0)) }
                        is ApkDownloader.Progress.Chunk -> {
                            val pct = if (progress.totalBytes > 0) {
                                ((progress.downloaded * 100) / progress.totalBytes).toInt()
                            } else 0
                            _state.update { it.copy(installState = AppInstallState.Downloading(pct)) }
                        }
                        is ApkDownloader.Progress.Done -> {
                            // Signature pin: the self-update APK must be signed with the
                            // same cert as the currently running app.
                            val sigResult = SignatureVerifier.verifyApkSignature(
                                context = context,
                                apkPath = progress.file.absolutePath,
                                expectedPackageName = context.packageName,
                                isSelfUpdate = true,
                            )
                            when (sigResult) {
                                is SignatureVerifier.VerifyResult.Mismatch -> {
                                    progress.file.delete()
                                    _state.update {
                                        it.copy(
                                            installState = AppInstallState.Failed(
                                                "This update couldn't be verified and was not installed, to keep you safe. " +
                                                    "The APK's signing certificate doesn't match this app's certificate."
                                            )
                                        )
                                    }
                                    return@collect
                                }
                                is SignatureVerifier.VerifyResult.CannotVerify -> {
                                    // Shouldn't happen for self-update (our own cert is always readable),
                                    // but if it does, block as a precaution.
                                    if (!sigResult.fresh) {
                                        progress.file.delete()
                                        _state.update {
                                            it.copy(
                                                installState = AppInstallState.Failed(
                                                    "This update couldn't be verified and was not installed, to keep you safe. " +
                                                        "Could not read the APK's signing certificate."
                                                )
                                            )
                                        }
                                        return@collect
                                    }
                                }
                                SignatureVerifier.VerifyResult.Ok -> { /* proceed */ }
                            }
                            val launchResult = launchApkInstaller(context, progress.file)
                            if (launchResult.isSuccess) {
                                _state.update { it.copy(installState = AppInstallState.ReadyToInstall) }
                            } else {
                                Log.w("AppUpdateViewModel", "Installer launch failed", launchResult.exceptionOrNull())
                                _state.update {
                                    it.copy(
                                        installState = AppInstallState.Failed(
                                            "Downloaded, but couldn't open the system installer."
                                        )
                                    )
                                }
                            }
                        }
                        is ApkDownloader.Progress.Failed -> {
                            val userMsg = if (progress.message.startsWith("SHA256 mismatch")) {
                                "This update couldn't be verified and was not installed, to keep you safe. " +
                                    "The downloaded file's checksum didn't match the published value."
                            } else {
                                progress.message
                            }
                            _state.update {
                                it.copy(installState = AppInstallState.Failed(userMsg))
                            }
                        }
                    }
                }
        }
    }

    /**
     * Dismiss the pending-update notification (removes pref, hides banner).
     * Does NOT prevent re-detection on next launch if the version is still
     * newer — the checker will re-write the pref on the next 12h cycle.
     */
    fun dismiss() {
        PrefManager.clearPendingUpdate()
        _state.update { it.copy(pendingUpdate = null, installState = AppInstallState.Idle) }
    }

    fun setAutoCheck(enabled: Boolean) {
        PrefManager.updateAutoCheckEnabled = enabled
    }

    fun installPermissionIntent(): Intent? = InstallPermission.settingsIntent(context)

    // ── private helpers ────────────────────────────────────────────────────────

    private fun buildStateFromPrefs(): AppUpdateState {
        val version = PrefManager.pendingUpdateVersion
        val url = PrefManager.pendingUpdateUrl
        val filename = PrefManager.pendingUpdateFilename
        val pending = if (version != null && url != null && filename != null) {
            PendingUpdate(
                version = version,
                releaseTitle = version,
                patchNotes = PrefManager.pendingUpdateNotes ?: "",
                apkUrl = url,
                apkFilename = filename,
                apkSizeBytes = PrefManager.pendingUpdateSize,
                sha256 = PrefManager.pendingUpdateSha256,
            )
        } else null
        return AppUpdateState(pendingUpdate = pending)
    }
}

// ── State types ────────────────────────────────────────────────────────────────

data class AppUpdateState(
    val pendingUpdate: PendingUpdate? = null,
    val installState: AppInstallState = AppInstallState.Idle,
    val checkInProgress: Boolean = false,
    val lastCheckMessage: String? = null,
)

data class PendingUpdate(
    val version: String,
    val releaseTitle: String,
    val patchNotes: String,
    val apkUrl: String,
    val apkFilename: String,
    val apkSizeBytes: Long,
    /** SHA-256 hex parsed from the release body, or null if not published. */
    val sha256: String? = null,
)

sealed interface AppInstallState {
    data object Idle : AppInstallState
    data object Resolving : AppInstallState
    data class Downloading(val percent: Int) : AppInstallState
    data object ReadyToInstall : AppInstallState
    data class Failed(val message: String) : AppInstallState
}
