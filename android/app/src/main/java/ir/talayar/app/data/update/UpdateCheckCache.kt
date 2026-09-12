package ir.talayar.app.data.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Remembers when the update channel was last queried (anti-spam cache). */
interface UpdateCheckCache {
    suspend fun lastCheckAt(): Long
    suspend fun setLastCheckAt(nowMs: Long)
}

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "talayar_updates")

@Singleton
class DataStoreUpdateCheckCache @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateCheckCache {

    private object Keys {
        val LAST_CHECK = longPreferencesKey("last_check_at")
    }

    override suspend fun lastCheckAt(): Long =
        context.updateDataStore.data.first()[Keys.LAST_CHECK] ?: 0L

    override suspend fun setLastCheckAt(nowMs: Long) {
        context.updateDataStore.edit { it[Keys.LAST_CHECK] = nowMs }
    }
}
