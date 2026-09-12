package ir.talayar.app.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.talayar.app.domain.model.UpdateErrorKind
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Result of validating a downloaded file before it may reach the installer. */
sealed interface ApkCheck {

    /** The file is a complete, authentic, installable package. */
    data object Ok : ApkCheck

    /**
     * The file must NOT be installed.
     * @param kind precise reason (drives the Persian copy shown to the user).
     * @param detail technical detail — logcat only, never rendered.
     */
    data class Rejected(val kind: UpdateErrorKind, val detail: String) : ApkCheck
}

/**
 * Pure file-level validation (no Android framework needed, so it is unit-testable):
 * existence, non-empty, published size, ZIP/APK magic and the published SHA-256.
 *
 * A rejected file never reaches [ApkInstaller]; the partial file is deleted by
 * [ApkDownloader].
 */
object ApkVerifier {

    /**
     * @param expectedSizeBytes published asset size (<= 0 = unknown, size check skipped).
     * @param sha256Hex published checksum (null = checksum check skipped).
     */
    fun check(file: File, expectedSizeBytes: Long, sha256Hex: String?): ApkCheck {
        if (!file.exists()) return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "file does not exist")
        val size = file.length()
        if (size <= 0L) return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "file is empty")
        if (expectedSizeBytes > 0L && size != expectedSizeBytes) {
            return ApkCheck.Rejected(
                UpdateErrorKind.INCOMPLETE_DOWNLOAD,
                "expected $expectedSizeBytes bytes, got $size",
            )
        }

        // APKs are ZIP archives — they must start with the "PK\x03\x04" magic.
        val magic = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(magic) }
        } catch (t: Throwable) {
            return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "unreadable: ${t.javaClass.simpleName}")
        }
        if (read != 4) return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "too short for a ZIP header")
        if (magic[0] != 'P'.code.toByte() || magic[1] != 'K'.code.toByte() ||
            magic[2] != 3.toByte() || magic[3] != 4.toByte()
        ) {
            return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "missing ZIP/APK magic")
        }

        if (!sha256Hex.isNullOrBlank()) {
            val actual = try {
                sha256Of(file)
            } catch (t: Throwable) {
                return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "hashing failed: ${t.javaClass.simpleName}")
            }
            if (!actual.equals(sha256Hex.trim(), ignoreCase = true)) {
                return ApkCheck.Rejected(
                    UpdateErrorKind.CHECKSUM_MISMATCH,
                    "expected $sha256Hex, got $actual",
                )
            }
        }
        return ApkCheck.Ok
    }

    /** Backwards-compatible boolean form. */
    fun verify(file: File, expectedSizeBytes: Long, sha256Hex: String?): Boolean =
        check(file, expectedSizeBytes, sha256Hex) is ApkCheck.Ok

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } >= 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Package-level validation with the framework: the downloaded file must be a
 * package Android can actually parse, it must be *this* application, and it must
 * not be older than what is already installed (no downgrade through the updater).
 *
 * Runs after [ApkVerifier] and before [ApkInstaller].
 */
@Singleton
class ApkPackageInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** @return [ApkCheck.Ok] only when the archive is an installable, newer build of this app. */
    @Suppress("DEPRECATION") // getPackageArchiveInfo(String, Int): the flags overload is fine on every API we support
    fun inspect(file: File): ApkCheck {
        if (!file.exists() || file.length() <= 0L) {
            return ApkCheck.Rejected(UpdateErrorKind.INVALID_APK, "file missing before inspection")
        }
        val info = try {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "getPackageArchiveInfo failed: ${t.javaClass.simpleName}")
            null
        }
        if (info == null) {
            return ApkCheck.Rejected(
                UpdateErrorKind.INVALID_APK,
                "PackageManager could not read ${file.name}",
            )
        }

        val packageName = info.packageName
        if (packageName != context.packageName) {
            return ApkCheck.Rejected(
                UpdateErrorKind.INVALID_APK,
                "package '$packageName' is not '${context.packageName}'",
            )
        }

        val downloadedCode = PackageInfoCompat.getLongVersionCode(info)
        val installedCode = installedVersionCode()
        if (installedCode > 0L && downloadedCode < installedCode) {
            return ApkCheck.Rejected(
                UpdateErrorKind.INVALID_APK,
                "downloaded versionCode $downloadedCode is older than installed $installedCode",
            )
        }

        Log.i(TAG, "package inspection ok: $packageName versionCode=$downloadedCode")
        return ApkCheck.Ok
    }

    /** versionCode of the running app (0 when it cannot be read, which disables the downgrade guard). */
    @Suppress("DEPRECATION") // getPackageInfo(String, Int)
    private fun installedVersionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info)
    } catch (e: PackageManager.NameNotFoundException) {
        // Most specific first: it is also an Exception, so it must precede the catch-all.
        Log.w(TAG, "own package not found: ${e.message}")
        0L
    } catch (t: Throwable) {
        Log.w(TAG, "installed versionCode unavailable: ${t.javaClass.simpleName}")
        0L
    }

    private companion object {
        const val TAG = "ApkPackageInspector"
    }
}
