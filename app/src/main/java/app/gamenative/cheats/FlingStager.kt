package app.gamenative.cheats

import android.content.Context
import android.net.Uri
import app.gamenative.PrefManager
import app.gamenative.service.SteamService
import app.gamenative.utils.Net
import com.winlator.container.Container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Stages a USER-SUPPLIED FLiNG trainer's `dinput8.dll` into a game folder, the way
 * OnexConsole / desktop players do it: a FLiNG trainer IS a proxy `dinput8.dll` — you
 * drop it next to the game exe, Wine loads it, and the trainer shows its OWN in-game
 * hotkey overlay (Num1 = infinite health, etc.). Our app does not render the trainer's
 * cheats; we only enable + stage the DLL.
 *
 * COEXISTENCE WITH OUR ENGINE (chain-load) — our own cheat engine IS the game's
 * `dinput8.dll` (see [CheatStager]); it is the PROVEN slot on arm64ec Proton. A Windows
 * game loads exactly one dinput8.dll, so the FLiNG trainer canNOT also be dinput8.dll.
 * Instead we stage the FLiNG trainer next to the exe under a DIFFERENT name —
 * `dinput8.fling.dll` — and our proxy `dinput8.dll` LoadLibraryW's it in DllMain (the
 * chain-load), so the trainer's hotkey overlay initialises in the same process. No
 * system32 staging and no dinput8 override here — Wine loads OUR dinput8 (which CheatStager
 * already overrides), and our dinput8 pulls in the fling dll.
 *
 * GATING NOTE: because the FLiNG dll is loaded by OUR proxy (not by a Wine override), the
 * proxy must be present — i.e. the Trainer engine must be ON. trainerEnabled defaults true,
 * so our dinput8 proxy is present to chain-load the fling dll in the common case. (A FLiNG
 * trainer therefore requires the engine on; acceptable since that is the default.)
 *
 * HONEST LIMITATIONS (surfaced in the UI): FLiNG trainers are GAME-VERSION-SPECIFIC and
 * are the user's own file — we do NOT bundle copyrighted trainers. Some trainers expect
 * to be launched before/after the game; running under Box64/Wine is best-effort and not
 * guaranteed for every trainer.
 *
 * PERSISTENCE:
 *   - DLL bytes:  filesDir/fling_trainers/<appId>/dinput8.dll
 *   - enabled flag: PrefManager.isFlingTrainerEnabled(appId) (set true on a successful import).
 *
 * Like [CheatStager], every external operation is wrapped so no exception can propagate
 * into the game-launch critical path.
 */
object FlingStager {

    private const val TAG = "FlingStager"
    private const val STORE_DIR = "fling_trainers"

    /**
     * The filename the trainer is STORED under in our per-game store. A FLiNG trainer IS a
     * proxy dinput8.dll, so that is what the user imports and what we keep on disk.
     */
    private const val DLL_NAME = "dinput8.dll"

    /**
     * The filename the trainer is STAGED under NEXT TO THE GAME EXE. It is NOT dinput8.dll
     * (that slot is OUR engine — see [CheatStager]); our proxy chain-loads this sibling in
     * its DllMain. No Wine override is set for it — Wine loads OUR dinput8, which pulls this in.
     */
    private const val STAGED_DLL_NAME = "dinput8.fling.dll"

    /** Max accepted trainer DLL size (FLiNG dinput8 proxies are tiny, a few hundred KB). */
    private const val MAX_DLL_BYTES = 16L * 1024 * 1024

    /**
     * Hard ceiling on a downloaded trainer body, independent of the catalog's declared
     * sizeBytes. Generous vs MAX_DLL_BYTES because the network read is capped before the
     * 16MB store-validation runs (see [downloadTrainer]); 32MB stops a malicious catalog
     * entry from streaming an unbounded body into memory.
     */
    private const val MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024

    private const val CONNECT_TIMEOUT_SEC = 15L
    private const val READ_TIMEOUT_SEC = 60L

    private val httpClient by lazy {
        Net.http.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    // -------------------------------------------------------------------------
    // Store paths
    // -------------------------------------------------------------------------

    /** The per-game directory under filesDir where this game's trainer DLL is stored. */
    private fun storeDir(context: Context, appId: String): File =
        File(File(context.filesDir, STORE_DIR), sanitize(appId))

    /** The stored trainer DLL file for [appId] (may not exist). */
    fun storedDll(context: Context, appId: String): File =
        File(storeDir(context, appId), DLL_NAME)

    /** True if a FLiNG trainer DLL has been imported AND enabled for [appId]. */
    fun hasTrainer(context: Context, appId: String): Boolean =
        PrefManager.isFlingTrainerEnabled(appId) && storedDll(context, appId).isFile

    // -------------------------------------------------------------------------
    // Import (called from the UI's SAF picker)
    // -------------------------------------------------------------------------

    /**
     * Copy a user-picked FLiNG `dinput8.dll` (a SAF [uri]) into this game's store and
     * mark the trainer enabled. Returns true on success.
     *
     * Validates the file is a PE/DLL (MZ magic) and within the size cap; rejects anything
     * else so a wrong pick doesn't get staged into a game folder.
     */
    fun importFromUri(context: Context, appId: String, uri: Uri): Boolean {
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: run {
                    Timber.tag(TAG).w("Could not open picked trainer uri for $appId")
                    return false
                }
            validateAndStore(context, appId, bytes, "manual import")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to import FLiNG trainer for $appId — suppressed")
            false
        }
    }

    /**
     * Shared validation + storage for BOTH manual import and OTA download, so a downloaded
     * trainer flows through the identical stage()/launch path as a hand-picked one.
     *
     * Validates the bytes are a PE/DLL (MZ magic) and within [MAX_DLL_BYTES], writes them to
     * the per-game store ([storedDll]) and flips [PrefManager.setFlingTrainerEnabled] to true.
     * Returns false (and stages nothing) on any validation or IO failure.
     */
    private fun validateAndStore(context: Context, appId: String, bytes: ByteArray, originLabel: String): Boolean {
        if (bytes.size < 2 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) {
            Timber.tag(TAG).w("Trainer ($originLabel) for $appId is not a PE/DLL (no MZ magic) — rejecting")
            return false
        }
        if (bytes.size > MAX_DLL_BYTES) {
            Timber.tag(TAG).w("Trainer ($originLabel) for $appId too large (${bytes.size}B) — rejecting")
            return false
        }
        val dir = storeDir(context, appId)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.tag(TAG).w("Could not create trainer store dir for $appId")
            return false
        }
        storedDll(context, appId).writeBytes(bytes)
        PrefManager.setFlingTrainerEnabled(appId, true)
        Timber.tag(TAG).i("Stored FLiNG trainer for $appId via $originLabel (${bytes.size} bytes)")
        return true
    }

    // -------------------------------------------------------------------------
    // On-demand download (OnexConsole-style — fetch from the OTA catalog)
    // -------------------------------------------------------------------------

    /**
     * Download the trainer described by [entry] over HTTPS, verify its SHA-256 and PE magic,
     * then store it at the SAME per-game path [importFromUri] uses — so a downloaded trainer
     * and a manually-imported one converge on the identical stage()/launch path.
     *
     * SECURITY (never relaxed):
     *   - HTTPS only (the URL is also gated to https:// when the catalog is parsed).
     *   - The streamed body is capped at min(entry.sizeBytes if sane, [MAX_DOWNLOAD_BYTES]).
     *   - The downloaded bytes' SHA-256 MUST equal [TrainerEntry.sha256] — a mismatch is
     *     rejected and NOTHING is staged (we never write an unverified binary).
     *   - The bytes must be a PE/DLL (MZ magic), reusing [validateAndStore].
     *
     * Runs the network/IO on [Dispatchers.IO]. Never throws — returns a [Result].
     *
     * @param appId the compound container appId this trainer is for (e.g. "STEAM_367520").
     *              Used as the per-game store key, same as [importFromUri].
     */
    suspend fun downloadTrainer(context: Context, appId: String, entry: TrainerEntry): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!entry.url.startsWith("https://", ignoreCase = true)) {
                    return@withContext Result.failure(IllegalArgumentException("Trainer URL is not HTTPS"))
                }

                // Cap the read: trust the catalog's sizeBytes only when it is sane, else the hard ceiling.
                val declared = entry.sizeBytes
                val readCap = if (declared in 1..MAX_DOWNLOAD_BYTES) declared else MAX_DOWNLOAD_BYTES

                val request = Request.Builder()
                    .url(entry.url)
                    .header("Accept", "application/octet-stream")
                    .build()

                val bytes: ByteArray = httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IllegalStateException("Download failed — HTTP ${response.code}"),
                        )
                    }
                    val body = response.body
                        ?: return@withContext Result.failure(IllegalStateException("Empty response body"))

                    // Reject before buffering if the server advertises an oversized body.
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: -1L
                    if (contentLength > readCap) {
                        return@withContext Result.failure(
                            IllegalStateException("Trainer body $contentLength exceeds cap $readCap bytes"),
                        )
                    }

                    // Stream with a hard cap so a lying Content-Length can't blow memory.
                    body.byteStream().use { input ->
                        readCapped(input, readCap)
                            ?: return@withContext Result.failure(
                                IllegalStateException("Trainer body exceeded cap $readCap bytes mid-stream"),
                            )
                    }
                }

                // Verify SHA-256 BEFORE touching the store. Reject on any mismatch.
                val actualSha = sha256Hex(bytes)
                if (!actualSha.equals(entry.sha256, ignoreCase = true)) {
                    Timber.tag(TAG).w(
                        "SHA-256 mismatch for ${entry.source}/${entry.gameId}: expected ${entry.sha256}, got $actualSha — rejecting",
                    )
                    return@withContext Result.failure(
                        SecurityException("Downloaded trainer failed SHA-256 verification"),
                    )
                }

                // PE-magic + size + store (identical to manual import). Only now do we write.
                val stored = validateAndStore(context, appId, bytes, "OTA download")
                if (stored) {
                    Timber.tag(TAG).i("Downloaded + verified FLiNG trainer for $appId (${bytes.size} bytes)")
                    Result.success(Unit)
                } else {
                    Result.failure(IllegalStateException("Downloaded trainer failed PE/size validation"))
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to download FLiNG trainer for $appId — suppressed")
                Result.failure(e)
            }
        }

    /** Read up to [cap] bytes from [input]; returns null if the stream exceeds [cap]. */
    private fun readCapped(input: java.io.InputStream, cap: Long): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v ushr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString()
    }

    /** Remove the stored trainer DLL and disable the flag for [appId]. */
    fun removeTrainer(context: Context, appId: String) {
        try {
            storedDll(context, appId).delete()
            PrefManager.setFlingTrainerEnabled(appId, false)
            Timber.tag(TAG).i("Removed FLiNG trainer for $appId")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to remove FLiNG trainer for $appId — suppressed")
        }
    }

    // -------------------------------------------------------------------------
    // Launch staging (called from XServerScreen, next to CheatStager.stage)
    // -------------------------------------------------------------------------

    /**
     * If [appId] has an enabled FLiNG trainer, copy the stored trainer dll into the game
     * folder as `dinput8.fling.dll` (install root AND exe dir, like [CheatStager]). It is
     * NOT staged as dinput8.dll — that slot is OUR engine. Our proxy dinput8.dll chain-loads
     * this sibling in its DllMain, so NO Wine override and NO system32 staging are needed
     * here. No-op (returns false) if no trainer is enabled, the install dir is missing, or
     * any step fails. Never throws.
     *
     * Gating note: because our proxy loads the fling dll, the proxy must be present — i.e.
     * the Trainer engine must be ON (it defaults ON via [PrefManager.trainerEnabled]). A
     * FLiNG trainer therefore requires the engine on; acceptable since that is the default.
     */
    fun stage(context: Context, container: Container, appId: String, gameId: Int): Boolean {
        return try {
            if (!hasTrainer(context, appId)) {
                return false
            }
            val src = storedDll(context, appId)

            val gameDirPath = SteamService.getAppDirPath(gameId)
            val gameDir = File(gameDirPath)
            if (!gameDir.exists() || !gameDir.isDirectory) {
                Timber.tag(TAG).w("Game dir missing for gameId=$gameId — skipping FLiNG staging")
                return false
            }

            // Deploy as dinput8.fling.dll into the install root (DMC3-style root exes) AND
            // the exe's own dir (subfolder exes). Our proxy chain-loads it from its own
            // module dir, which is the exe dir; the install-root copy is belt-and-suspenders.
            val rootOk = copyInto(src, gameDir)
            val exeDir = CheatPaths.exeDir(gameDir, container.executablePath)
            if (exeDir.absolutePath != gameDir.absolutePath) {
                copyInto(src, exeDir)   // best-effort — the exe-dir copy is the one the proxy loads
            }
            if (!rootOk) {
                return false
            }

            Timber.tag(TAG).i("FLiNG trainer staged as $STAGED_DLL_NAME for appId=$appId gameId=$gameId")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Unexpected error staging FLiNG trainer for appId=$appId — suppressed")
            false
        }
    }

    private fun copyInto(src: File, destDir: File): Boolean {
        return try {
            val dest = File(destDir, STAGED_DLL_NAME)
            src.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            Timber.tag(TAG).i("Deployed FLiNG $STAGED_DLL_NAME to ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to copy FLiNG $STAGED_DLL_NAME into ${destDir.absolutePath}")
            false
        }
    }

    /** Make an appId safe to use as a single path segment. */
    private fun sanitize(appId: String): String =
        appId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
