package ir.talayar.app.domain.repository

import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.model.PriceHistory
import ir.talayar.app.domain.model.TriggeredAlert
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for market data.
 *
 * Online: gateway JSON -> Room (offline-first). Offline: Room keeps serving
 * the last known prices with honest staleness/last-updated metadata.
 */
interface MarketRepository {

    /** Live stream of all assets (Room + favorites). */
    fun observeAssets(): Flow<List<MarketAsset>>

    /** Live stream of a single asset. */
    fun observeAsset(symbol: String): Flow<MarketAsset?>

    /** Fetch fresh prices from the gateway into Room. */
    suspend fun refresh(): Result<Unit>

    /**
     * Price history for a symbol. Fetches the gateway history into Room when
     * online and always serves the merged (server + locally recorded) points.
     */
    suspend fun getHistory(symbol: String): Result<PriceHistory>

    /** Live stream of favorite assets. */
    fun observeFavorites(): Flow<List<MarketAsset>>

    /** Add or remove a favorite. */
    suspend fun toggleFavorite(symbol: String)

    /** Live stream of all alert rules. */
    fun observeAlerts(): Flow<List<AlertRule>>

    /** Live stream of alert rules for one symbol. */
    fun observeAlertsFor(symbol: String): Flow<List<AlertRule>>

    suspend fun addAlert(rule: AlertRule)

    suspend fun removeAlert(id: Long)

    /**
     * Evaluate enabled alerts against the latest cached prices.
     * Triggered alerts are marked + disabled and returned for notification.
     */
    suspend fun evaluateAlerts(): List<TriggeredAlert>
}
