package app.gamenative.update

import android.content.Context
import app.gamenative.utils.Net
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams an APK from a URL into app-private cache storage with progress.
 *
 * Ported from io.github.mayusi.isitcompatible.getit.download.ApkDownloader.
 * Delta: uses [Net.http] (the fork's OkHttp singleton with DoH + timeouts)
 * instead of building a private OkHttpClient. A new builder is derived from
 * Net.http to extend the read-timeout to 5 min for large APK downloads.
 *
 * App-private cache is the right home for transient APKs:
 *  - No storage permission needed.
 *  - The FileProvider in AndroidManifest exposes cacheDir/. (path=".") to the installer.
 *  - Android can clean it up under pressure if we forget.
 *
 * SHA256 verify (delete APK on mismatch) is intact.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Derive from Net.http (inherits DoH, retries, etc.) with a generous read timeout for APKs.
    private val client = Net.http.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * Downloads [url] into the apks/ subdir of cacheDir.
     * Emits per-chunk progress so the UI can show a real bar.
     *
     * If [expectedSha256] is provided, verifies the downloaded file's SHA-256
     * against it (case-insensitive comparison). On mismatch, deletes the file
     * and emits [Progress.Failed]. On match or when expectedSha256 is null, emits [Progress.Done].
     *
     * Returns a [Progress.Done] containing the local [File], which the
     * installer can then hand to the system installer via FileProvider.
     */
    fun download(url: String, filename: String, expectedSha256: String? = null): Flow<Progress> = flow {
        val outDir = File(context.cacheDir, "apks").apply { mkdirs() }
        val outFile = File(outDir, filename)

        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(Progress.Failed("HTTP ${response.code} ${response.message}"))
                    return@use
                }
                val body = response.body ?: run {
                    emit(Progress.Failed("Empty response body"))
                    return@use
                }

                val total = body.contentLength()
                emit(Progress.Started(total))

                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            downloaded += n
                            emit(Progress.Chunk(downloaded, total))
                        }
                    }
                }

                // Verify SHA256 if expected value provided
                if (expectedSha256 != null) {
                    val computed = computeSha256(outFile)
                    if (!computed.equals(expectedSha256, ignoreCase = true)) {
                        outFile.delete()
                        emit(Progress.Failed("SHA256 mismatch: expected $expectedSha256, got $computed"))
                        return@use
                    }
                }

                emit(Progress.Done(outFile))
            }
        } catch (t: Throwable) {
            // Delete any partial file left behind by a mid-download failure
            // so it is never mistaken for a complete APK on a retry.
            if (outFile.exists()) outFile.delete()
            emit(Progress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Computes the SHA-256 hash of a file.
     * Reads the file in 64 KB chunks and returns the hex-encoded lowercase hash.
     */
    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    sealed interface Progress {
        data class Started(val totalBytes: Long) : Progress
        data class Chunk(val downloaded: Long, val totalBytes: Long) : Progress
        data class Done(val file: File) : Progress
        data class Failed(val message: String) : Progress
    }
}
