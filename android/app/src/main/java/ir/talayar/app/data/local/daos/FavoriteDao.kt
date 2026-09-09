package ir.talayar.app.data.local.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ir.talayar.app.data.local.entities.FavoriteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites ORDER BY symbol")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT symbol FROM favorites ORDER BY symbol")
    fun observeSymbols(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE symbol = :symbol)")
    suspend fun isFavorite(symbol: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE symbol = :symbol")
    suspend fun delete(symbol: String)
}
