package ir.talayar.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import ir.talayar.app.data.local.daos.AlertDao
import ir.talayar.app.data.local.daos.FavoriteDao
import ir.talayar.app.data.local.daos.PriceDao
import ir.talayar.app.data.local.entities.AlertEntity
import ir.talayar.app.data.local.entities.FavoriteEntity
import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.data.local.entities.PriceHistoryEntity

/**
 * Local database (offline-first cache):
 *  - latest_prices : last known good quotes
 *  - price_history : merged local + gateway chart points
 *  - favorites     : user's watchlist
 *  - alerts        : price alert rules
 */
@Database(
    entities = [
        LatestPriceEntity::class,
        FavoriteEntity::class,
        PriceHistoryEntity::class,
        AlertEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TalayarDatabase : RoomDatabase() {
    abstract fun priceDao(): PriceDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun alertDao(): AlertDao
}
