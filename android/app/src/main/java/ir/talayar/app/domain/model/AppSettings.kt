package ir.talayar.app.domain.model

import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.ThemeMode

/** User settings snapshot (defaults are safe). Prices are always shown in Toman. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val refreshInterval: RefreshInterval = RefreshInterval.S15,
    val notificationsEnabled: Boolean = true,
    val serverUrl: String? = null,
)

/** Price display format resolved from settings and provided via CompositionLocal. */
data class PriceFormat(
    val persianDigits: Boolean = true,
)

/** Fake-default helper so the JVM unit tests don't need the data layer defaults. */
val AppSettings.defaultFormat: PriceFormat
    get() = PriceFormat(persianDigits = true)
