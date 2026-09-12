package ir.talayar.app.domain.repository

import ir.talayar.app.domain.model.AppSettings
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Read/write access to user settings (DataStore backed). */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setRefreshInterval(interval: RefreshInterval)
    suspend fun setNotificationsEnabled(enabled: Boolean)
    suspend fun setServerUrl(url: String?)

    /** Currently configured gateway base URL (normalized, never blank). */
    suspend fun baseUrl(): String

    /** User-configured gateway base URL, or null when the built-in endpoints are in use. */
    suspend fun customBaseUrl(): String?
}
