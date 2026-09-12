package ir.talayar.app.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.talayar.app.domain.model.AppUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a release download fails or does not pass validation. */
class UpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads the official release APK into the app's cache directory with
 * progress reporting, then validates it (size + ZIP magic + SHA-256 when a
 * published checksum exists) before handing it to the installer.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    /**
     * @param update the update to fetch (URL is pre-validated by the repository).
     * @param onProgress called with 0..100 as bytes stream in.
     * @return the verified APK file inside cacheDir/updates.
     */
    suspend fun download(update: AppUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One download at a time: drop stale files from previous attempts.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "app-release-${update.latestVersion}.apk")
        val part = File(dir, "${target.name}.part")

        val expectedSize = if (update.apkSize > 0) update.apkSize else -1L
        val call = client.newCall(Request.Builder().url(update.apkUrl).build())
        val cancelHook = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (response.code != 200) {
                    throw UpdateDownloadException("HTTP ${response.code} while downloading the update")
                }
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    throw UpdateDownloadException("Unexpected HTML response for the update file")
                }
                val body = response.body ?: throw UpdateDownloadException("Empty download response")
                val total = if (body.contentLength() > 0) body.contentLength() else expectedSize

                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var written = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    ensureActive()
                                    onProgress(percent)
                                }
                            }
                        }
                        if (total > 0 && written != total) {
                            throw UpdateDownloadException("Incomplete download ($written of $total bytes)")
                        }
                        if (written <= MIN_APK_BYTES) {
                            throw UpdateDownloadException("Downloaded file is too small to be an app package")
                        }
                    }
                }
            }

            val sha256 = update.sha256Url?.let { fetchSha256(it) }
            if (!ApkVerifier.verify(part, expectedSize, sha256)) {
                throw UpdateDownloadException("The downloaded file failed validation")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw UpdateDownloadException("Could not finalize the download")
            }
            Log.i(TAG, "update ${update.latestVersion} downloaded and verified")
            target
        } catch (c: CancellationException) {
            part.delete()
            throw c
        } catch (e: UpdateDownloadException) {
            part.delete()
            throw e
        } catch (t: Throwable) {
            part.delete()
            throw UpdateDownloadException("Download failed: ${t.message}", t)
        } finally {
            cancelHook.dispose()
        }
    }

    /** Fetches the published ".sha256" sidecar asset ("<hex>  <name>" format). */
    private fun fetchSha256(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.code != 200) return@use null
            val text = response.body?.string()?.trim().orEmpty()
            SHA256_HEX.find(text)?.groupValues?.get(1)?.lowercase()
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Log.w(TAG, "checksum download failed: ${t.message}")
        null
    }

    companion object {
        private const val TAG = "ApkDownloader"

        /** Any real release APK is at least a few hundred kilobytes. */
        const val MIN_APK_BYTES = 500_000L

        private val SHA256_HEX = Regex("""([0-9a-fA-F]{64})""")
    }
}

/** Pure file-level validation used before opening the package installer. */
object ApkVerifier {

    /**
     * @param expectedSizeBytes published asset size (<= 0 = unknown).
     * @param sha256Hex published checksum (null = skip the checksum test).
     */
    fun verify(file: File, expectedSizeBytes: Long, sha256Hex: String?): Boolean {
        if (!file.exists()) return false
        val size = file.length()
        if (size <= 0) return false
        if (expectedSizeBytes > 0 && size != expectedSizeBytes) return false

        // APKs are ZIP archives — they must start with the "PK\x03\x04" magic.
        file.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) != 4) return false
            if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte() ||
                magic[2] != 3.toByte() || magic[3] != 4.toByte()
            ) {
                return false
            }
        }

        if (sha256Hex != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256Hex, ignoreCase = true)) return false
        }
        return true
    }
}
