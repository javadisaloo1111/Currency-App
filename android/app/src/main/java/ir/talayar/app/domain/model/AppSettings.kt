package ir.talayar.app.domain.model

import ir.talayar.app.data.settings.PriceUnit
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.ThemeMode

/** User settings snapshot (defaults are safe). */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val refreshInterval: RefreshInterval = RefreshInterval.S15,
    val priceUnit: PriceUnit = PriceUnit.TOMAN,
    val notificationsEnabled: Boolean = true,
    val serverUrl: String? = null,
)

/** Price display format resolved from settings and provided via CompositionLocal. */
data class PriceFormat(
    val persianDigits: Boolean = true,
    val unit: PriceUnit = PriceUnit.TOMAN,
)

/** Fake-default helper so the JVM unit tests don't need the data layer enum defaults. */
val AppSettings.defaultFormat: PriceFormat
    get() = PriceFormat(persianDigits = true, unit = priceUnit)
