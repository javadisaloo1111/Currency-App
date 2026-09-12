package ir.talayar.app.data.update

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when a release download fails or does not pass validation. Always carries a classified [UpdateError]. */
class UpdateDownloadException(val error: UpdateError) : Exception(error.toString(), error.cause)

/**
 * Downloads the official release APK into the app's cache directory with progress
 * reporting, then validates it before handing it to the installer:
 *
 *  1. HTTP status (mapped to a precise [UpdateErrorKind], not a generic failure);
 *  2. content type (an HTML body means a captive portal / filtering page, not an APK);
 *  3. streaming into `<name>.part` so a partial file can never be installed;
 *  4. completeness (byte count vs. the published asset size) and a plausible size floor;
 *  5. [ApkVerifier]: ZIP magic + published SHA-256;
 *  6. [ApkPackageInspector]: PackageManager can read it, it is this application and
 *     it is not older than the installed build;
 *  7. only then the atomic rename to the final file.
 *
 * Transient transport failures are retried a bounded number of times with backoff.
 * The partial file is deleted on every failure path and on cancellation.
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @UpdateDownloads private val client: OkHttpClient,
    private val inspector: ApkPackageInspector,
) {

    /**
     * @param update the update to fetch (its URL is pre-validated by the repository).
     * @param onProgress called with 0..100 as bytes stream in.
     * @return the verified APK file inside `cacheDir/updates`.
     * @throws UpdateDownloadException with the precise reason when it cannot be produced.
     */
    suspend fun download(update: AppUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        var lastError: UpdateError = UpdateError(UpdateErrorKind.DOWNLOAD_FAILED, "download did not start")
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                val backoff = RETRY_BACKOFF_MS * (attempt - 1)
                Log.i(TAG, "retrying download in ${backoff}ms (attempt $attempt/$MAX_ATTEMPTS)")
                delay(backoff)
            }
            try {
                return@withContext transfer(update, onProgress)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: UpdateDownloadException) {
                lastError = e.error
                Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed: $lastError")
                if (!lastError.retryable) throw e
            } catch (t: Throwable) {
                lastError = classifyUpdateFailure(t, online = true)
                Log.w(TAG, "download attempt $attempt/$MAX_ATTEMPTS failed: $lastError")
            }
        }
        throw UpdateDownloadException(lastError)
    }

    /** One full download + validation pass. */
    private suspend fun transfer(update: AppUpdate, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One download at a time: drop stale files from previous attempts.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "app-release-${update.latestVersion}.apk")
        val part = File(dir, "${target.name}.part")

        val expectedSize = if (update.apkSize > 0L) update.apkSize else -1L
        val call = client.newCall(Request.Builder().url(update.apkUrl).build())
        val cancelHook = coroutineContext.job.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw UpdateDownloadException(httpError(response.code))
                }
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html", ignoreCase = true)) {
                    // A portal, proxy or filtering page answered instead of the asset.
                    throw UpdateDownloadException(
                        UpdateError(
                            UpdateErrorKind.INVALID_APK,
                            "server answered with HTML instead of the package",
                        ),
                    )
                }
                val body = response.body
                    ?: throw UpdateDownloadException(
                        UpdateError(UpdateErrorKind.DOWNLOAD_FAILED, "empty response body"),
                    )
                val total = if (body.contentLength() > 0L) body.contentLength() else expectedSize

                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        var written = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } >= 0) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0L) {
                                val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    coroutineContext.ensureActive()
                                    onProgress(percent)
                                }
                            }
                        }
                        if (total > 0L && written != total) {
                            throw UpdateDownloadException(
                                UpdateError(
                                    UpdateErrorKind.INCOMPLETE_DOWNLOAD,
                                    "received $written of $total bytes",
                                ),
                            )
                        }
                        if (written < MIN_APK_BYTES) {
                            throw UpdateDownloadException(
                                UpdateError(
                                    UpdateErrorKind.INVALID_APK,
                                    "$written bytes is too small to be an app package",
                                ),
                            )
                        }
                    }
                }
            }

            // Sidecar asset first; the digest published with the asset itself is the
            // fallback when there is no sidecar (or it could not be read).
            val sidecarSha256 = update.sha256Url?.let { fetchSha256(it) }
            val publishedSha256 = sidecarSha256 ?: update.sha256
            Log.i(
                TAG,
                when {
                    sidecarSha256 != null -> "expected checksum read from the published sidecar"
                    publishedSha256 != null -> "expected checksum taken from the asset digest"
                    else -> "no checksum published — size, ZIP magic and package inspection still enforced"
                },
            )
            when (val fileCheck = ApkVerifier.check(part, expectedSize, publishedSha256)) {
                ApkCheck.Ok -> Unit
                is ApkCheck.Rejected -> throw UpdateDownloadException(
                    UpdateError(fileCheck.kind, fileCheck.detail),
                )
            }
            when (val packageCheck = inspector.inspect(part)) {
                ApkCheck.Ok -> Unit
                is ApkCheck.Rejected -> throw UpdateDownloadException(
                    UpdateError(packageCheck.kind, packageCheck.detail),
                )
            }

            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                throw UpdateDownloadException(
                    UpdateError(UpdateErrorKind.DOWNLOAD_FAILED, "could not finalize the download"),
                )
            }
            Log.i(TAG, "update ${update.latestVersion} downloaded and verified (${target.length()} bytes)")
            return target
        } catch (cancellation: CancellationException) {
            part.delete()
            throw cancellation
        } catch (e: UpdateDownloadException) {
            part.delete()
            throw e
        } catch (t: Throwable) {
            part.delete()
            throw UpdateDownloadException(classifyUpdateFailure(t, online = true))
        } finally {
            cancelHook.dispose()
        }
    }

    /** Maps a non-2xx download response onto the same taxonomy used for the metadata call. */
    private fun httpError(code: Int): UpdateError {
        val kind = when (code) {
            403, 429 -> UpdateErrorKind.RATE_LIMITED
            404 -> UpdateErrorKind.NOT_FOUND
            in 500..599 -> UpdateErrorKind.SERVER_ERROR
            else -> UpdateErrorKind.HTTP_ERROR
        }
        return UpdateError(kind, "HTTP $code while downloading the update")
    }

    /**
     * Fetches the published ".sha256" sidecar asset ("<hex>  <name>" format).
     * A missing/unreadable sidecar is logged and skipped — the size and ZIP magic
     * checks still apply, and a broken sidecar must not make updates impossible.
     */
    private fun fetchSha256(url: String): String? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val text = response.body?.string()?.trim().orEmpty()
            SHA256_HEX.find(text)?.groupValues?.get(1)?.lowercase()
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        Log.w(TAG, "checksum download failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    companion object {
        private const val TAG = "ApkDownloader"

        /** Any real release APK is at least a few hundred kilobytes. */
        const val MIN_APK_BYTES: Long = 500_000L

        /** Bounded retries for transient transport failures (never infinite). */
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_BACKOFF_MS = 1_000L

        private val SHA256_HEX = Regex("""([0-9a-fA-F]{64})""")
    }
}
