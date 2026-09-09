package ir.talayar.app.data.local.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.data.local.entities.PriceHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PriceDao {

    @Query("SELECT * FROM latest_prices")
    fun observeAll(): Flow<List<LatestPriceEntity>>

    @Query("SELECT * FROM latest_prices WHERE symbol = :symbol LIMIT 1")
    fun observe(symbol: String): Flow<LatestPriceEntity?>

    @Query("SELECT * FROM latest_prices WHERE symbol = :symbol LIMIT 1")
    suspend fun get(symbol: String): LatestPriceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(assets: List<LatestPriceEntity>)

    @Query("SELECT COUNT(*) FROM latest_prices")
    suspend fun count(): Int

    // ---- history ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(points: List<PriceHistoryEntity>)

    @Query("SELECT * FROM price_history WHERE symbol = :symbol AND t >= :since ORDER BY t ASC")
    suspend fun historySince(symbol: String, since: Long): List<PriceHistoryEntity>

    @Query("SELECT * FROM price_history WHERE symbol = :symbol ORDER BY t DESC LIMIT 1")
    suspend fun lastHistoryPoint(symbol: String): PriceHistoryEntity?

    @Query("DELETE FROM price_history WHERE t < :before")
    suspend fun pruneBefore(before: Long)
}
