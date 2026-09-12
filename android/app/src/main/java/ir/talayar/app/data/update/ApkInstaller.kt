package ir.talayar.app.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the downloaded release file with the system package installer.
 * Never installs silently — the user always confirms in the Android installer.
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Launches the installer for [apkFile].
     *
     * @return true when the installer was opened; false when the app first
     *   needs the "install unknown apps" permission (the settings page was
     *   opened in that case, or the intent failed).
     */
    fun install(apkFile: File): Boolean {
        return try {
            if (!context.packageManager.canRequestPackageInstalls()) {
                openUnknownSourcesSettings()
                return false
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open the package installer", t)
            false
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
    }
}
