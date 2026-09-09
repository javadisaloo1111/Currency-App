package ir.talayar.app.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Latest quote per asset — the offline cache. */
@Entity(tableName = "latest_prices")
data class LatestPriceEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val category: String,
    val currency: String,
    val unit: String?,
    val price: Double,
    val change: Double?,
    val changePercent: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val prevPrice: Double?,
    val updatedAt: Long,
    val source: String,
    val isStale: Boolean,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val symbol: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/** Merged price history: points recorded locally + points imported from the gateway. */
@Entity(tableName = "price_history", primaryKeys = ["symbol", "t"], indices = [Index("symbol", "t")])
data class PriceHistoryEntity(
    val symbol: String,
    val t: Long,
    val p: Double,
)

/** User-defined price alerts. */
@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val symbol: String,
    val kind: String,
    val threshold: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val triggeredAt: Long? = null,
    val enabled: Boolean = true,
)
