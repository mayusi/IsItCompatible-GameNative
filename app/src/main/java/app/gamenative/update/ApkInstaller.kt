package app.gamenative.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "ApkInstaller"

/**
 * Hands an APK file to the system package installer via FileProvider + ACTION_VIEW.
 *
 * Ported from io.github.mayusi.isitcompatible.getit.ApkInstaller (launchApkInstaller).
 * The authority resolves to app.gamenative.iic.fileprovider via context.packageName,
 * matching the FileProvider declared in AndroidManifest.xml — no manifest change needed.
 *
 * Returns [Result.success(Unit)] when the installer activity was started,
 * or [Result.failure] (with the underlying exception) when it couldn't be launched.
 */
fun launchApkInstaller(context: Context, apkFile: File): Result<Unit> = runCatching {
    val uri: Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", apkFile,
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    Log.d(TAG, "Launching installer for ${apkFile.name} via $uri")
    context.startActivity(intent)
}
