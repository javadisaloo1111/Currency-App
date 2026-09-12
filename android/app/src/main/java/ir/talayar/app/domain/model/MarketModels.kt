package ir.talayar.app.domain.model

/** Asset categories shown in the Market tab. */
enum class AssetCategory(val id: String, val label: String) {
    GOLD("gold", "طلا"),
    COIN("coin", "سکه"),
    CURRENCY("currency", "ارز"),
    CRYPTO("crypto", "رمزارز");

    companion object {
        fun fromId(id: String?): AssetCategory = entries.firstOrNull { it.id == id } ?: CURRENCY
    }
}

/** Direction of the latest price change. */
enum class ChangeDirection { UP, DOWN, FLAT }

/**
 * A tradable market asset with its latest quote.
 *
 * Prices are stored in the unit reported by the gateway — Toman for every
 * published asset (the global ounce is converted to Toman in the canonical
 * layer); display always uses Toman.
 */
data class MarketAsset(
    val symbol: String,
    val name: String,
    val category: AssetCategory,
    val price: Double,
    val currency: String = "TOMAN",
    val unit: String? = null,
    val change: Double? = null,
    val changePercent: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val prevPrice: Double? = null,
    val updatedAt: Long,
    val source: String = "",
    val isStale: Boolean = false,
    val isFavorite: Boolean = false,
) {
    val direction: ChangeDirection
        get() {
            val cp = changePercent
            if (cp != null) return if (cp > 0.001) ChangeDirection.UP else if (cp < -0.001) ChangeDirection.DOWN else ChangeDirection.FLAT
            val c = change
            if (c != null) return if (c > 0.0) ChangeDirection.UP else if (c < 0.0) ChangeDirection.DOWN else ChangeDirection.FLAT
            return ChangeDirection.FLAT
        }
}

/** A single point of a price chart (epoch milliseconds + price). */
data class HistoryPoint(val t: Long, val p: Double)

/** Chart ranges supported by the gateway. */
enum class HistoryRange(val id: String, val shortLabel: String, val windowMs: Long) {
    H1("1H", "۱س", 1L * 60 * 60 * 1000),
    H6("6H", "۶س", 6L * 60 * 60 * 1000),
    D1("1D", "۱روز", 24L * 60 * 60 * 1000),
    W1("1W", "۱هفته", 7L * 24 * 60 * 60 * 1000),
    M1("1M", "۱ماه", 30L * 24 * 60 * 60 * 1000),
    M3("3M", "۳ماه", 90L * 24 * 60 * 60 * 1000),
    Y1("1Y", "۱سال", 365L * 24 * 60 * 60 * 1000);

    companion object {
        fun fromId(id: String?): HistoryRange = entries.firstOrNull { it.id == id } ?: D1
    }
}

/** Price history for one asset, bucketed per chart range. */
data class PriceHistory(
    val symbol: String,
    val ranges: Map<String, List<HistoryPoint>>,
    val updatedAt: Long,
    val isStale: Boolean = false,
    val fromNetwork: Boolean = false,
)

/** Snapshot metadata of a market refresh. */
data class MarketSnapshot(
    val assets: List<MarketAsset>,
    val updatedAt: Long?,
    val isStale: Boolean,
)
