package ir.talayar.app.domain.model

/** Alert kinds supported by the price alert engine. */
enum class AlertKind(val id: String, val label: String) {
    ABOVE("above", "عبور از حد بالا"),
    BELOW("below", "عبور از حد پایین"),
    PERCENT("percent", "تغییر درصدی شدید");

    companion object {
        fun fromId(id: String?): AlertKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A user-defined price alert. Alerts are evaluated by [PriceSyncWorker]
 * against the latest cached prices; a triggered alert is disabled (one-shot)
 * so the user is not spammed.
 */
data class AlertRule(
    val id: Long = 0L,
    val symbol: String,
    val kind: AlertKind,
    val threshold: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val triggeredAt: Long? = null,
    val enabled: Boolean = true,
)

/** Result of an alert evaluation round (used by the notification pipeline). */
data class TriggeredAlert(
    val rule: AlertRule,
    val asset: MarketAsset,
)
