package app.gamenative.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Verifies that a downloaded APK file is signed with the expected certificate
 * before handing it to the system installer.
 *
 * Ported VERBATIM from io.github.mayusi.isitcompatible.getit.SignatureVerifier.
 * This is intentionally generic — it reads the installed app's own cert at
 * runtime via PackageManager, so it works for the fork's debug key with zero
 * hardcoded cert hashes.
 *
 * Two modes:
 *  1. Self-update (app.gamenative.iic): the downloaded APK must be signed with
 *     the same cert as the currently-running app. Android enforces this at
 *     install time anyway, but checking first gives a clear safety error instead
 *     of the cryptic "App not installed" system dialog.
 *  2. Other packages: if the package is already installed, the downloaded APK's
 *     signer must match the installed version's signer. If not yet installed,
 *     there is no baseline to pin against; proceed with a logged note.
 */
object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    sealed interface VerifyResult {
        /** Certificate matches expectation — safe to install. */
        data object Ok : VerifyResult

        /** Certificate does NOT match — BLOCK the install. */
        data class Mismatch(val expected: String, val got: String) : VerifyResult

        /**
         * Could not verify: no baseline cert available (fresh install of a new package,
         * or the APK could not be parsed). Caller decides whether to proceed or block.
         * fresh=true means no baseline exists (not installed yet); fresh=false means
         * the APK itself couldn't be parsed.
         */
        data class CannotVerify(val reason: String, val fresh: Boolean = false) : VerifyResult
    }

    /**
     * Verifies [apkPath] is signed with the expected cert.
     *
     * @param context application context.
     * @param apkPath absolute path to the downloaded APK file.
     * @param expectedPackageName the package id embedded in the APK (used to look up
     *        the installed version's cert).
     * @param isSelfUpdate when true, the APK must match the running app's own signer
     *        (context.packageName). Pass true for the app's self-update path.
     */
    fun verifyApkSignature(
        context: Context,
        apkPath: String,
        expectedPackageName: String,
        isSelfUpdate: Boolean,
    ): VerifyResult {
        // 1. Read the cert from the downloaded APK on disk.
        val apkCertHash = certHashFromApk(context, apkPath)
            ?: return VerifyResult.CannotVerify(
                "Could not read signing certificate from the downloaded APK.",
                fresh = false,
            )

        // 2. Determine the expected cert.
        val expectedCertHash = if (isSelfUpdate) {
            // Self-update: compare against THIS running app's cert (no hardcoding needed).
            certHashFromInstalledPackage(context, context.packageName)
                ?: return VerifyResult.CannotVerify(
                    "Could not read this app's own signing certificate — very unexpected.",
                    fresh = false,
                )
        } else {
            // Third-party package: compare against installed version's cert (if installed).
            val installed = certHashFromInstalledPackage(context, expectedPackageName)
            if (installed == null) {
                // Package not installed — no baseline to pin against. Fresh install.
                Log.i(TAG, "No installed cert baseline for $expectedPackageName — fresh install, skipping sig check.")
                return VerifyResult.CannotVerify(
                    "Package $expectedPackageName is not installed yet — no cert baseline to pin against.",
                    fresh = true,
                )
            }
            installed
        }

        return if (apkCertHash.equals(expectedCertHash, ignoreCase = true)) {
            Log.d(TAG, "Signature OK for $expectedPackageName: $apkCertHash")
            VerifyResult.Ok
        } else {
            Log.w(TAG, "Signature MISMATCH for $expectedPackageName: expected $expectedCertHash got $apkCertHash")
            VerifyResult.Mismatch(expected = expectedCertHash, got = apkCertHash)
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * Reads the SHA-256 fingerprint of the first signing certificate of an APK
     * file at [apkPath] on disk. Returns null if the APK can't be parsed.
     */
    private fun certHashFromApk(context: Context, apkPath: String): String? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                ) ?: return null
                val signers = pkgInfo.signingInfo ?: return null
                val certs = if (signers.hasMultipleSigners()) {
                    signers.apkContentsSigners
                } else {
                    signers.signingCertificateHistory
                }
                certs.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkPath,
                    PackageManager.GET_SIGNATURES,
                ) ?: return null
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cert from APK at $apkPath", e)
            null
        }
    }

    /**
     * Reads the SHA-256 fingerprint of the first signing certificate of an
     * already-installed package. Returns null if the package isn't installed.
     */
    private fun certHashFromInstalledPackage(context: Context, packageName: String): String? {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                val signers = pkgInfo.signingInfo ?: return null
                val certs = if (signers.hasMultipleSigners()) {
                    signers.apkContentsSigners
                } else {
                    signers.signingCertificateHistory
                }
                certs.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pkgInfo.signatures?.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null // not installed — caller handles this
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cert from installed package $packageName", e)
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
