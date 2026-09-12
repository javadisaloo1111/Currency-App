package ir.talayar.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.data.remote.MarketApi
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.SettingsStore
import ir.talayar.app.data.settings.ThemeMode
import ir.talayar.app.domain.model.AppSettings
import ir.talayar.app.domain.repository.SettingsRepository
import ir.talayar.app.domain.repository.UpdateRepository
import ir.talayar.app.domain.repository.UpdateCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val settingsStore: SettingsStore,
    private val api: MarketApi,
    private val updateRepository: UpdateRepository,
) : ViewModel() {

    sealed interface ServerTestState {
        data object Idle : ServerTestState
        data object Testing : ServerTestState
        data class Ok(val serverTime: String?, val status: String) : ServerTestState
        data class Failed(val message: String) : ServerTestState
    }

    val uiState: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val serverTest = MutableStateFlow<ServerTestState>(ServerTestState.Idle)
    val serverTestState: StateFlow<ServerTestState> = serverTest

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setRefreshInterval(interval: RefreshInterval) = viewModelScope.launch { settingsRepository.setRefreshInterval(interval) }
    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }

    fun saveServerUrl(rawUrl: String?) = viewModelScope.launch {
        settingsRepository.setServerUrl(rawUrl?.trim()?.takeIf { it.isNotBlank() })
        serverTest.value = ServerTestState.Idle
    }

    fun resetServerUrl() = saveServerUrl(null)

    sealed interface UpdateCheckState {
        data object Idle : UpdateCheckState
        data object Checking : UpdateCheckState
        data object Latest : UpdateCheckState
        data class Available(val version: String) : UpdateCheckState
        data object Failed : UpdateCheckState
    }

    private val updateCheck = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateCheckState: StateFlow<UpdateCheckState> = updateCheck

    /** Manual "بررسی بروزرسانی": always bypasses the periodic-check cache. */
    fun checkUpdates() {
        viewModelScope.launch {
            updateCheck.value = UpdateCheckState.Checking
            updateCheck.value = when (val result = updateRepository.checkForUpdate(force = true)) {
                is UpdateCheckResult.NoUpdate -> UpdateCheckState.Latest
                is UpdateCheckResult.Available -> UpdateCheckState.Available(result.update.latestVersion)
                UpdateCheckResult.Failed -> UpdateCheckState.Failed
            }
        }
    }

    /** Pings {base}/api/v1/health.json to validate a custom gateway address. */
    fun testServer(rawUrl: String) {
        viewModelScope.launch {
            serverTest.value = ServerTestState.Testing
            val base = SettingsStore.normalizeBaseUrl(rawUrl)
            serverTest.value = try {
                val health = api.health(base.trimEnd('/') + "/api/v1/health.json", System.currentTimeMillis())
                ServerTestState.Ok(serverTime = health.time, status = health.status)
            } catch (t: Throwable) {
                ServerTestState.Failed("اتصال به سرور برقرار نشد. آدرس را بررسی کنید.")
            }
        }
    }
}
