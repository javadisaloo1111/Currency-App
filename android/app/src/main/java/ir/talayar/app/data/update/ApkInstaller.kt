package ir.talayar.app.data.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when the system package installer was asked to install the update. */
sealed interface InstallOutcome {

    /** The installer is on screen; the user always confirms there (never silent). */
    data object Started : InstallOutcome

    /**
     * The user must first allow "install unknown apps".
     * @param settingsOpened true when the system settings page was launched.
     */
    data class NeedsPermission(val settingsOpened: Boolean) : InstallOutcome

    /** The installer could not be opened. */
    data class Failed(val error: UpdateError) : InstallOutcome
}

/**
 * Opens the downloaded release file with the system package installer.
 *
 * Android 7+ compatibility: the file is exposed through [FileProvider] as a
 * `content://` URI with [Intent.FLAG_GRANT_READ_URI_PERMISSION] — a `file://` URI
 * would raise [android.os.FileUriExposedException] on API 24+. The authority is
 * `${applicationId}.fileprovider` and `res/xml/file_paths.xml` maps `cacheDir/updates`,
 * which is exactly where [ApkDownloader] writes.
 *
 * Never installs silently: the Android installer always asks the user to confirm.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Launches the installer for [apkFile].
     *
     * @return [InstallOutcome.Started] when the installer was opened,
     *   [InstallOutcome.NeedsPermission] when the "install unknown apps" permission
     *   is missing (its settings page was opened in that case), or
     *   [InstallOutcome.Failed] with the precise reason.
     */
    fun install(apkFile: File): InstallOutcome {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallOutcome.Failed(
                UpdateError(UpdateErrorKind.INVALID_APK, "the file to install is missing or empty"),
            )
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            val opened = openUnknownSourcesSettings()
            Log.i(TAG, "REQUEST_INSTALL_PACKAGES not granted; settings opened=$opened")
            return InstallOutcome.NeedsPermission(opened)
        }

        // content:// URI — never file:// (FileUriExposedException on API 24+).
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", apkFile)
        } catch (t: Throwable) {
            Log.e(TAG, "FileProvider could not expose ${apkFile.name}", t)
            return InstallOutcome.Failed(
                UpdateError(
                    UpdateErrorKind.INVALID_APK,
                    "file is outside the configured FileProvider paths: ${t.javaClass.simpleName}",
                    t,
                ),
            )
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            Log.i(TAG, "package installer opened for ${apkFile.name}")
            InstallOutcome.Started
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no activity can install an APK", e)
            InstallOutcome.Failed(
                UpdateError(
                    UpdateErrorKind.INSTALL_FAILED,
                    "no activity handles ACTION_VIEW for $APK_MIME",
                    e,
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open the package installer", t)
            InstallOutcome.Failed(
                UpdateError(
                    UpdateErrorKind.INSTALL_FAILED,
                    "startActivity failed: ${t.javaClass.simpleName}",
                    t,
                ),
            )
        }
    }

    /** Opens the system page where the user allows installing from this app. */
    fun openUnknownSourcesSettings(): Boolean = try {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "failed to open unknown-sources settings", t)
        false
    }

    private companion object {
        const val TAG = "ApkInstaller"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
