package ir.talayar.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import ir.talayar.app.BuildConfig
import ir.talayar.app.domain.model.AppSettings
import ir.talayar.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** App theme preference. */
enum class ThemeMode(val label: String) {
    SYSTEM("همراه سیستم"),
    LIGHT("روشن"),
    DARK("تیره");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.name == id } ?: SYSTEM
    }
}

/** Foreground auto-refresh cadence. */
enum class RefreshInterval(val seconds: Int, val label: String) {
    S15(15, "۱۵ ثانیه"),
    S30(30, "۳۰ ثانیه"),
    S60(60, "۱ دقیقه"),
    MANUAL(0, "دستی (فقط با کشیدن صفحه)");

    companion object {
        fun fromId(id: String?): RefreshInterval = entries.firstOrNull { it.name == id } ?: S15
    }
}

/** Price display unit. */
enum class PriceUnit(val label: String, val tomanFactor: Double) {
    TOMAN("تومان", 1.0),
    RIAL("ریال", 10.0);

    companion object {
        fun fromId(id: String?): PriceUnit = entries.firstOrNull { it.name == id } ?: TOMAN
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "talayar_settings")

/**
 * DataStore-backed settings repository. All values are safe defaults; the
 * server URL overrides the build-time [BuildConfig.DEFAULT_API_BASE_URL].
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val REFRESH = stringPreferencesKey("refresh_interval")
        val UNIT = stringPreferencesKey("price_unit")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SERVER_URL = stringPreferencesKey("server_url")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.fromId(prefs[Keys.THEME]),
            refreshInterval = RefreshInterval.fromId(prefs[Keys.REFRESH]),
            priceUnit = PriceUnit.fromId(prefs[Keys.UNIT]),
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            serverUrl = prefs[Keys.SERVER_URL]?.takeIf { it.isNotBlank() },
        )
    }

    /** Currently configured gateway base URL (never blank). */
    override suspend fun baseUrl(): String {
        val prefs = context.dataStore.data.first()
        val custom = prefs[Keys.SERVER_URL]?.takeIf { it.isNotBlank() }
        return normalizeBaseUrl(custom ?: BuildConfig.DEFAULT_API_BASE_URL)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        context.dataStore.edit { it[Keys.REFRESH] = interval.name }
    }

    override suspend fun setPriceUnit(unit: PriceUnit) {
        context.dataStore.edit { it[Keys.UNIT] = unit.name }
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    override suspend fun setServerUrl(url: String?) {
        context.dataStore.edit { prefs ->
            val cleaned = url?.trim()?.takeIf { it.isNotBlank() }
            if (cleaned == null) prefs.remove(Keys.SERVER_URL) else prefs[Keys.SERVER_URL] = cleaned
        }
    }

    companion object {
        /** Accepts "example.com" -> "https://example.com/", ensures trailing slash. */
        fun normalizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return BuildConfig.DEFAULT_API_BASE_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            if (!url.endsWith("/")) url += "/"
            return url
        }
    }
}
