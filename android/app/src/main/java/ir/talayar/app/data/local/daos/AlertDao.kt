package ir.talayar.app.data.local.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import ir.talayar.app.data.local.entities.AlertEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE symbol = :symbol ORDER BY createdAt DESC")
    fun observeFor(symbol: String): Flow<List<AlertEntity>>

    @Query("SELECT * FROM alerts WHERE enabled = 1")
    suspend fun enabledAlerts(): List<AlertEntity>

    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Query("DELETE FROM alerts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE alerts SET enabled = 0, triggeredAt = :triggeredAt WHERE id = :id")
    suspend fun markTriggered(id: Long, triggeredAt: Long)
}
