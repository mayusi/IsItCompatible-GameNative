package app.gamenative.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helper for the "install unknown apps" permission (REQUEST_INSTALL_PACKAGES).
 * Ported verbatim from io.github.mayusi.isitcompatible.getit.InstallPermission.
 *
 * On API 26+ this is a per-app toggle the user flips in a system settings page —
 * there's no runtime dialog.
 */
object InstallPermission {

    /** True if this app may install APKs right now. */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true // pre-O: governed by the legacy global "unknown sources" setting
        }

    /**
     * Intent that opens the per-app "install unknown apps" settings page.
     * Returns null on pre-O (no such per-app screen).
     */
    fun settingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
