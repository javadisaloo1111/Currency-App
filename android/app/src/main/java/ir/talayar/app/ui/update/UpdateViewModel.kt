package ir.talayar.app.ui.update

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.data.update.ApkDownloader
import ir.talayar.app.data.update.ApkInstaller
import ir.talayar.app.data.update.InstallOutcome
import ir.talayar.app.data.update.UpdateDownloadException
import ir.talayar.app.data.update.classifyUpdateFailure
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.repository.UpdateCheckResult
import ir.talayar.app.domain.repository.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Actions the update dialog can trigger (implemented by [UpdateViewModel]). */
interface UpdateActions {
    fun dismiss()
    fun checkNow()
    fun startDownload()
    fun cancelDownload()
    fun retryDownload()
    fun retryInstall()
    fun openPermissionSettings()
}

/**
 * Drives the global update dialog.
 *
 * States map one-to-one onto the flow
 * `CHECKING → AVAILABLE → DOWNLOADING → (verify) → installer`, and every failure
 * carries a classified [UpdateError] so the user sees the real reason
 * («no internet» vs. «the update server could not be reached» vs. «the downloaded
 * file is damaged») instead of one blanket message.
 *
 * The automatic check on start never raises an error dialog: on a restricted
 * network that would greet the user with a failure they did not ask for. The
 * precise reason is shown where the check was requested (Settings →
 * «بررسی بروزرسانی», see `SettingsViewModel`), and here whenever a user-initiated
 * [checkNow] or a download fails.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
) : ViewModel(), UpdateActions {

    sealed interface State {
        data object Hidden : State

        /** A user-initiated check is in flight. */
        data object Checking : State

        data class Available(val update: AppUpdate) : State

        data class Downloading(val percent: Int) : State

        /** Downloaded, verified and handed to the system installer. */
        data object Ready : State

        data class NeedsPermission(val update: AppUpdate) : State

        /**
         * @param update non-null when the failure happened while downloading,
         *   verifying or installing a known update («تلاش مجدد» restarts the
         *   download); null for a check failure («تلاش مجدد» re-runs the check).
         */
        data class Error(val error: UpdateError, val update: AppUpdate? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Hidden)
    val state: StateFlow<State> = _state

    private var pendingUpdate: AppUpdate? = null
    private var downloadedFile: File? = null
    private var downloadJob: Job? = null
    private var checkJob: Job? = null

    init {
        // Automatic check — the repository limits it to once every 12 hours, so
        // re-entering the Home screen never spams the release service.
        viewModelScope.launch {
            when (val result = updateRepository.checkForUpdate(force = false)) {
                is UpdateCheckResult.Available -> Log.i(TAG, "update available: ${result.update.latestVersion}")
                is UpdateCheckResult.Error -> Log.w(TAG, "automatic update check failed: ${result.error}")
                UpdateCheckResult.NoUpdate -> Log.d(TAG, "installed version is current")
                UpdateCheckResult.NotDueYet -> Log.d(TAG, "periodic update check not due yet")
            }
        }

        // Manual checks from Settings publish here too.
        viewModelScope.launch {
            updateRepository.availableUpdate.collect { update ->
                if (update != null) {
                    pendingUpdate = update
                    val current = _state.value
                    if (current is State.Hidden || current is State.Error) {
                        _state.value = State.Available(update)
                    }
                }
            }
        }
    }

    override fun dismiss() {
        val current = _state.value
        if ((current as? State.Available)?.update?.forced == true) return
        downloadJob?.cancel()
        checkJob?.cancel()
        _state.value = State.Hidden
    }

    /** Explicit re-check (dialog's «تلاش مجدد» after a failed check). */
    override fun checkNow() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            _state.value = State.Checking
            when (val result = updateRepository.checkForUpdate(force = true)) {
                is UpdateCheckResult.Available -> {
                    pendingUpdate = result.update
                    _state.value = State.Available(result.update)
                }

                is UpdateCheckResult.Error -> _state.value = State.Error(result.error)
                // Verified "nothing newer" and "not due yet" both mean: no dialog.
                UpdateCheckResult.NoUpdate, UpdateCheckResult.NotDueYet -> _state.value = State.Hidden
            }
        }
    }

    override fun startDownload() {
        val update = pendingUpdate ?: return
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.value = State.Downloading(0)
            try {
                val file = downloader.download(update) { percent ->
                    _state.value = State.Downloading(percent)
                }
                downloadedFile = file
                when (val outcome = installer.install(file)) {
                    InstallOutcome.Started -> _state.value = State.Ready
                    is InstallOutcome.NeedsPermission -> _state.value = State.NeedsPermission(update)
                    is InstallOutcome.Failed -> _state.value = State.Error(outcome.error, update)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: UpdateDownloadException) {
                downloadedFile = null
                _state.value = State.Error(e.error, update)
            } catch (t: Throwable) {
                downloadedFile = null
                Log.w(TAG, "unexpected download failure", t)
                _state.value = State.Error(classifyUpdateFailure(t, online = true), update)
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = State.Hidden
    }

    override fun retryDownload() {
        val update = pendingUpdate
        _state.value = State.Hidden
        if (update == null) {
            checkNow()
            return
        }
        startDownload()
    }

    override fun retryInstall() {
        val file = downloadedFile
        if (file == null || !file.exists()) {
            // Nothing verified on disk any more: fetch it again instead of
            // handing a missing/partial file to the installer.
            downloadedFile = null
            startDownload()
            return
        }
        val update = pendingUpdate
        when (val outcome = installer.install(file)) {
            InstallOutcome.Started -> _state.value = State.Ready
            is InstallOutcome.NeedsPermission -> {
                if (update != null) _state.value = State.NeedsPermission(update)
            }

            is InstallOutcome.Failed -> _state.value = State.Error(outcome.error, update)
        }
    }

    override fun openPermissionSettings() {
        installer.openUnknownSourcesSettings()
    }

    private companion object {
        const val TAG = "UpdateViewModel"
    }
}
