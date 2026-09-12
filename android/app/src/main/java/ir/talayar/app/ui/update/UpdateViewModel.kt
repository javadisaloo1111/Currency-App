package ir.talayar.app.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.data.update.ApkDownloader
import ir.talayar.app.data.update.ApkInstaller
import ir.talayar.app.domain.model.AppUpdate
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
    fun startDownload()
    fun cancelDownload()
    fun retryDownload()
    fun retryInstall()
    fun openPermissionSettings()
}

/**
 * Drives the global update dialog: check on app start (rate limited by the
 * repository), download with progress, then open the system installer.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
) : ViewModel(), UpdateActions {

    sealed interface State {
        data object Hidden : State
        data class Available(val update: AppUpdate) : State
        data class Downloading(val percent: Int) : State
        data class Ready(val update: AppUpdate) : State
        data class NeedsPermission(val update: AppUpdate) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Hidden)
    val state: StateFlow<State> = _state

    private var pendingUpdate: AppUpdate? = null
    private var downloadedFile: File? = null
    private var downloadJob: Job? = null

    init {
        // Automatic check — the repository limits it to once every 12 hours,
        // so re-entering the Home screen never spams the release service.
        viewModelScope.launch { updateRepository.checkForUpdate(force = false) }

        // Manual checks from Settings publish here too.
        viewModelScope.launch {
            updateRepository.availableUpdate.collect { update ->
                if (update != null) {
                    pendingUpdate = update
                    if (_state.value is State.Hidden) _state.value = State.Available(update)
                }
            }
        }
    }

    override fun dismiss() {
        val current = _state.value
        val forced = (current as? State.Available)?.update?.forced == true
        if (forced) return
        downloadJob?.cancel()
        _state.value = State.Hidden
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
                _state.value = State.Ready(update)
                if (!installer.install(file)) {
                    _state.value = State.NeedsPermission(update)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = State.Failed("دانلود نسخهٔ جدید ناموفق بود. اتصال اینترنت را بررسی کنید و دوباره تلاش کنید.")
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = State.Hidden
    }

    override fun retryDownload() {
        _state.value = State.Hidden
        startDownload()
    }

    override fun retryInstall() {
        val file = downloadedFile ?: return startDownload()
        if (!installer.install(file)) {
            _state.value = State.NeedsPermission(pendingUpdate ?: return)
        } else {
            _state.value = State.Hidden
        }
    }

    override fun openPermissionSettings() {
        installer.openUnknownSourcesSettings()
    }
}
