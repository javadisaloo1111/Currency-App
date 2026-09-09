package ir.talayar.app.data.repository

import ir.talayar.app.data.local.daos.AlertDao
import ir.talayar.app.data.local.daos.FavoriteDao
import ir.talayar.app.data.local.daos.PriceDao
import ir.talayar.app.data.local.entities.FavoriteEntity
import ir.talayar.app.data.local.entities.PriceHistoryEntity
import ir.talayar.app.data.mapper.toDomain
import ir.talayar.app.data.mapper.toEntity
import ir.talayar.app.data.remote.HistoryDto
import ir.talayar.app.data.remote.MarketApi
import ir.talayar.app.domain.repository.SettingsRepository
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.HistoryPoint
import ir.talayar.app.domain.model.HistoryRange
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.model.PriceHistory
import ir.talayar.app.domain.model.TriggeredAlert
import ir.talayar.app.domain.repository.MarketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline-first market repository.
 *
 *  - Room is the single source of truth for the UI.
 *  - refresh() pulls the gateway envelope into Room and records local history
 *    points (giving charts 15s-density between gateway snapshots).
 *  - getHistory() merges gateway history into Room, then serves Room (so a
 *    failed network call still returns the last cached series).
 *  - All network errors surface as Result.failure without ever crashing.
 */
@Singleton
class MarketRepositoryImpl @Inject constructor(
    private val api: MarketApi,
    private val settings: SettingsRepository,
    private val priceDao: PriceDao,
    private val favoriteDao: FavoriteDao,
    private val alertDao: AlertDao,
) : MarketRepository {

    private suspend fun url(path: String): String = settings.baseUrl().trimEnd('/') + path

    // ---------------------------------------------------------------------
    // observe
    // ---------------------------------------------------------------------

    override fun observeAssets(): Flow<List<MarketAsset>> =
        combine(
            priceDao.observeAll(),
            favoriteDao.observeSymbols(),
        ) { prices, favorites ->
            val favSet = favorites.toHashSet()
            prices.map { it.toDomain(isFavorite = it.symbol in favSet) }
        }

    override fun observeAsset(symbol: String): Flow<MarketAsset?> =
        combine(
            priceDao.observe(symbol),
            favoriteDao.observeSymbols(),
        ) { entity, favorites ->
            entity?.toDomain(isFavorite = entity.symbol in favorites)
        }

    override fun observeFavorites(): Flow<List<MarketAsset>> =
        observeAssets().map { list -> list.filter { it.isFavorite } }

    override fun observeAlerts(): Flow<List<AlertRule>> =
        alertDao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeAlertsFor(symbol: String): Flow<List<AlertRule>> =
        alertDao.observeFor(symbol).map { rows -> rows.mapNotNull { it.toDomain() } }

    // ---------------------------------------------------------------------
    // refresh
    // ---------------------------------------------------------------------

    override suspend fun refresh(): Result<Unit> = safeApi {
        val envelope = api.prices(url(API_PRICES), System.currentTimeMillis())
        val entities = envelope.data.mapNotNull { it.toEntity() }
        priceDao.upsertAll(entities)

        // Record local history points for chart density between gateway updates.
        val now = System.currentTimeMillis()
        for (entity in entities) {
            val last = priceDao.lastHistoryPoint(entity.symbol)
            if (last == null || last.p != entity.price || now - last.t >= LOCAL_POINT_MIN_GAP_MS) {
                priceDao.insertHistory(listOf(PriceHistoryEntity(entity.symbol, now, entity.price)))
            }
        }
        pruneHistoryIfDue(now)
    }

    // ---------------------------------------------------------------------
    // history
    // ---------------------------------------------------------------------

    override suspend fun getHistory(symbol: String): Result<PriceHistory> {
        val network = safeApi {
            api.history(url(API_HISTORY.format(symbol)), System.currentTimeMillis())
        }

        // Merge gateway points into the local store (IGNORE keeps local points).
        network.onSuccess { dto ->
            val points = dto.allPoints()
            if (points.isNotEmpty()) {
                priceDao.insertHistory(points.map { PriceHistoryEntity(symbol, it.t, it.p) })
            }
        }

        val now = System.currentTimeMillis()
        val ranges = HistoryRange.entries.associate { range ->
            range.id to priceDao
                .historySince(symbol, now - range.windowMs - SLACK_MS)
                .map { HistoryPoint(it.t, it.p) }
        }
        return Result.success(
            PriceHistory(
                symbol = symbol,
                ranges = ranges,
                updatedAt = now,
                isStale = network.getOrNull()?.isStale ?: true,
                fromNetwork = network.isSuccess,
            ),
        )
    }

    // ---------------------------------------------------------------------
    // favorites
    // ---------------------------------------------------------------------

    override suspend fun toggleFavorite(symbol: String) {
        if (favoriteDao.isFavorite(symbol)) {
            favoriteDao.delete(symbol)
        } else {
            favoriteDao.insert(FavoriteEntity(symbol))
        }
    }

    // ---------------------------------------------------------------------
    // alerts
    // ---------------------------------------------------------------------

    override suspend fun addAlert(rule: AlertRule) {
        alertDao.insert(rule.toEntity())
    }

    override suspend fun removeAlert(id: Long) {
        alertDao.delete(id)
    }

    override suspend fun evaluateAlerts(): List<TriggeredAlert> {
        val rules = alertDao.enabledAlerts().mapNotNull { it.toDomain() }
        if (rules.isEmpty()) return emptyList()
        val triggered = mutableListOf<TriggeredAlert>()
        for (rule in rules) {
            val entity = priceDao.get(rule.symbol) ?: continue
            val asset = entity.toDomain()
            val fired = when (rule.kind) {
                AlertKind.ABOVE -> asset.price >= rule.threshold
                AlertKind.BELOW -> asset.price <= rule.threshold
                AlertKind.PERCENT -> {
                    val cp = asset.changePercent
                    cp != null && kotlin.math.abs(cp) >= rule.threshold
                }
            }
            if (fired) {
                alertDao.markTriggered(rule.id, System.currentTimeMillis())
                triggered += TriggeredAlert(rule = rule.copy(enabled = false), asset = asset)
            }
        }
        return triggered
    }

    // ---------------------------------------------------------------------

    private var lastPruneAt = 0L

    private suspend fun pruneHistoryIfDue(now: Long) {
        if (now - lastPruneAt < PRUNE_INTERVAL_MS) return
        lastPruneAt = now
        priceDao.pruneBefore(now - PRUNE_AGE_MS)
    }

    private fun HistoryDto.allPoints(): List<HistoryPoint> {
        val fromRanges = ranges?.values?.flatten() ?: emptyList()
        val direct = points ?: emptyList()
        return (fromRanges + direct)
            .filter { it.t > 0 && it.p > 0.0 }
            .distinctBy { it.t }
            .sortedBy { it.t }
            .map { HistoryPoint(it.t, it.p) }
    }

    companion object {
        private const val API_PRICES = "/api/v1/market/prices.json"
        private const val API_HISTORY = "/api/v1/market/history/%s.json"

        /** Minimum gap between identical local history points. */
        private const val LOCAL_POINT_MIN_GAP_MS = 60_000L

        /** Keep 400 days of chart history. */
        private const val PRUNE_AGE_MS = 400L * 24 * 60 * 60 * 1000
        private const val PRUNE_INTERVAL_MS = 6L * 60 * 60 * 1000

        /** Query window slack so boundary points are not cut off. */
        private const val SLACK_MS = 30_000L
    }
}

/** Runs [block], converting any failure into Result.failure without swallowing cancellation. */
suspend fun <T> safeApi(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (t: Throwable) {
    Result.failure(t)
}
