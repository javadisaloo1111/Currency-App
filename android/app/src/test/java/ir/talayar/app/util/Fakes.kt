package ir.talayar.app.util

import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.local.daos.AlertDao
import ir.talayar.app.data.local.daos.FavoriteDao
import ir.talayar.app.data.local.daos.PriceDao
import ir.talayar.app.data.local.entities.AlertEntity
import ir.talayar.app.data.local.entities.FavoriteEntity
import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.data.local.entities.PriceHistoryEntity
import ir.talayar.app.data.remote.AssetDto
import ir.talayar.app.data.remote.HealthDto
import ir.talayar.app.data.remote.HistoryDto
import ir.talayar.app.data.remote.HistoryPointDto
import ir.talayar.app.data.remote.MarketApi
import ir.talayar.app.data.remote.PricesEnvelopeDto
import ir.talayar.app.data.settings.PriceUnit
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.data.settings.ThemeMode
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.model.HistoryPoint
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.model.PriceHistory
import ir.talayar.app.domain.repository.MarketRepository
import ir.talayar.app.domain.repository.SettingsRepository
import ir.talayar.app.domain.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow

// ---------------------------------------------------------------------------
// DTO builders
// ---------------------------------------------------------------------------

fun assetDto(
    symbol: String = "GOLD_18K",
    name: String = "طلای ۱۸ عیار",
    category: String = "gold",
    price: Double = 6_703_000.0,
    change: Double? = 115_000.0,
    changePercent: Double? = 1.75,
    updatedAt: String = "2026-09-09T09:12:31Z",
    stale: Boolean = false,
) = AssetDto(
    symbol = symbol,
    name = name,
    category = category,
    price = price,
    change = change,
    changePercent = changePercent,
    updatedAt = updatedAt,
    isStale = stale,
)

fun marketAsset(
    symbol: String = "GOLD_18K",
    name: String = "طلای ۱۸ عیار",
    category: AssetCategory = AssetCategory.GOLD,
    price: Double = 6_703_000.0,
    changePercent: Double? = 1.75,
    updatedAt: Long = 1_757_400_000_000,
    isFavorite: Boolean = false,
    isStale: Boolean = false,
) = MarketAsset(
    symbol = symbol,
    name = name,
    category = category,
    price = price,
    change = if (changePercent != null) 100_000.0 else null,
    changePercent = changePercent,
    updatedAt = updatedAt,
    isStale = isStale,
    isFavorite = isFavorite,
)

// ---------------------------------------------------------------------------
// Fakes (pure JVM, no Android dependencies)
// ---------------------------------------------------------------------------

class FakeMarketApi : MarketApi {
    var pricesResponse: PricesEnvelopeDto = PricesEnvelopeDto()
    var pricesError: Throwable? = null
    var historyResponse: HistoryDto = HistoryDto()
    var historyError: Throwable? = null

    override suspend fun prices(url: String, cacheBuster: Long): PricesEnvelopeDto {
        pricesError?.let { throw it }
        return pricesResponse
    }

    override suspend fun history(url: String, cacheBuster: Long): HistoryDto {
        historyError?.let { throw it }
        return historyResponse
    }

    override suspend fun health(url: String, cacheBuster: Long): HealthDto = HealthDto(status = "ok")
}

class FakePriceDao : PriceDao {
    val prices = MutableStateFlow<List<LatestPriceEntity>>(emptyList())
    val history = mutableListOf<PriceHistoryEntity>()

    override fun observeAll(): Flow<List<LatestPriceEntity>> = prices

    override fun observe(symbol: String): Flow<LatestPriceEntity?> =
        prices.map { list -> list.firstOrNull { it.symbol == symbol } }

    override suspend fun get(symbol: String): LatestPriceEntity? =
        prices.value.firstOrNull { it.symbol == symbol }

    override suspend fun upsertAll(assets: List<LatestPriceEntity>) {
        val merged = prices.value.associateBy { it.symbol } + assets.associateBy { it.symbol }
        prices.value = merged.values.toList()
    }

    override suspend fun count(): Int = prices.value.size

    override suspend fun insertHistory(points: List<PriceHistoryEntity>) {
        for (p in points) {
            val idx = history.indexOfFirst { it.symbol == p.symbol && it.t == p.t }
            if (idx >= 0) history[idx] = p else history.add(p)
        }
    }

    override suspend fun historySince(symbol: String, since: Long): List<PriceHistoryEntity> =
        history.filter { it.symbol == symbol && it.t >= since }.sortedBy { it.t }

    override suspend fun lastHistoryPoint(symbol: String): PriceHistoryEntity? =
        history.filter { it.symbol == symbol }.maxByOrNull { it.t }

    override suspend fun pruneBefore(before: Long) {
        history.removeAll { it.t < before }
    }
}

class FakeFavoriteDao : FavoriteDao {
    val favorites = MutableStateFlow<List<FavoriteEntity>>(emptyList())

    override fun observeAll(): Flow<List<FavoriteEntity>> = favorites

    override fun observeSymbols(): Flow<List<String>> = favorites.map { list -> list.map { it.symbol } }

    override suspend fun isFavorite(symbol: String): Boolean =
        favorites.value.any { it.symbol == symbol }

    override suspend fun insert(favorite: FavoriteEntity) {
        favorites.value = favorites.value + favorite
    }

    override suspend fun delete(symbol: String) {
        favorites.value = favorites.value.filterNot { it.symbol == symbol }
    }
}

class FakeAlertDao : AlertDao {
    var nextId = 1L
    val alerts = MutableStateFlow<List<AlertEntity>>(emptyList())

    override fun observeAll(): Flow<List<AlertEntity>> = alerts

    override fun observeFor(symbol: String): Flow<List<AlertEntity>> =
        alerts.map { list -> list.filter { it.symbol == symbol } }

    override suspend fun enabledAlerts(): List<AlertEntity> = alerts.value.filter { it.enabled }

    override suspend fun insert(alert: AlertEntity): Long {
        val entity = alert.copy(id = nextId++)
        alerts.value = alerts.value + entity
        return entity.id
    }

    override suspend fun delete(id: Long) {
        alerts.value = alerts.value.filterNot { it.id == id }
    }

    override suspend fun markTriggered(id: Long, triggeredAt: Long) {
        alerts.value = alerts.value.map {
            if (it.id == id) it.copy(enabled = false, triggeredAt = triggeredAt) else it
        }
    }
}

class FakeSettingsRepository : SettingsRepository {
    val settingsFlow = MutableStateFlow(AppSettings())

    override val settings: Flow<AppSettings> = settingsFlow

    var savedUrl: String? = null

    override suspend fun setThemeMode(mode: ThemeMode) {
        settingsFlow.value = settingsFlow.value.copy(themeMode = mode)
    }

    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        settingsFlow.value = settingsFlow.value.copy(refreshInterval = interval)
    }

    override suspend fun setPriceUnit(unit: PriceUnit) {
        settingsFlow.value = settingsFlow.value.copy(priceUnit = unit)
    }

    override suspend fun setNotificationsEnabled(enabled: Boolean) {
        settingsFlow.value = settingsFlow.value.copy(notificationsEnabled = enabled)
    }

    override suspend fun setServerUrl(url: String?) {
        savedUrl = url
        settingsFlow.value = settingsFlow.value.copy(serverUrl = url?.takeIf { it.isNotBlank() })
    }

    override suspend fun baseUrl(): String = "https://gateway.example/"
}

class FakeConnectivity(initial: Boolean = true) : Connectivity {
    override val isOnline: Flow<Boolean> = MutableStateFlow(initial)
}

/** Full in-memory MarketRepository for ViewModel tests. */
class FakeMarketRepository : MarketRepository {
    val assets = MutableStateFlow<List<MarketAsset>>(emptyList())
    val favorites = MutableStateFlow<Set<String>>(emptySet())
    val alerts = MutableStateFlow<List<AlertRule>>(emptyList())
    var refreshResult: Result<Unit> = Result.success(Unit)
    var refreshCalls = 0
    var historyResult: Result<PriceHistory> = Result.success(PriceHistory("X", emptyMap(), 0))
    val toggledFavorites = mutableListOf<String>()
    val addedAlerts = mutableListOf<AlertRule>()
    val removedAlerts = mutableListOf<Long>()
    var evaluated: List<ir.talayar.app.domain.model.TriggeredAlert> = emptyList()

    override fun observeAssets(): Flow<List<MarketAsset>> =
        combine(assets, favorites) { list, favs -> list.map { it.copy(isFavorite = it.symbol in favs) } }

    override fun observeAsset(symbol: String): Flow<MarketAsset?> =
        combine(assets, favorites) { list, favs ->
            list.firstOrNull { it.symbol == symbol }?.copy(isFavorite = symbol in favs)
        }

    override suspend fun refresh(): Result<Unit> {
        refreshCalls += 1
        return refreshResult
    }

    override suspend fun getHistory(symbol: String): Result<PriceHistory> = historyResult

    override fun observeFavorites(): Flow<List<MarketAsset>> =
        combine(assets, favorites) { list, favs -> list.filter { it.symbol in favs } }

    override suspend fun toggleFavorite(symbol: String) {
        toggledFavorites += symbol
        favorites.value = if (symbol in favorites.value) favorites.value - symbol else favorites.value + symbol
    }

    override fun observeAlerts(): Flow<List<AlertRule>> = alerts

    override fun observeAlertsFor(symbol: String): Flow<List<AlertRule>> =
        alerts.map { list -> list.filter { it.symbol == symbol } }

    override suspend fun addAlert(rule: AlertRule) {
        addedAlerts += rule
        alerts.value = alerts.value + rule
    }

    override suspend fun removeAlert(id: Long) {
        removedAlerts += id
        alerts.value = alerts.value.filterNot { it.id == id }
    }

    override suspend fun evaluateAlerts(): List<ir.talayar.app.domain.model.TriggeredAlert> = evaluated
}

fun historyPoints(vararg pairs: Pair<Long, Double>): List<HistoryPoint> =
    pairs.map { HistoryPoint(it.first, it.second) }

fun historyDto(points: List<HistoryPoint>, symbol: String? = null): HistoryDto = HistoryDto(
    symbol = symbol,
    ranges = mapOf("1H" to points.map { HistoryPointDto(it.t, it.p) }),
    updatedAt = "2026-09-09T09:12:31Z",
)
