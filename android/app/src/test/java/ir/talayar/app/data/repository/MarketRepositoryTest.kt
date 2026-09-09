package ir.talayar.app.data.repository

import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.util.FakeAlertDao
import ir.talayar.app.util.FakeFavoriteDao
import ir.talayar.app.util.FakeMarketApi
import ir.talayar.app.util.FakePriceDao
import ir.talayar.app.util.FakeSettingsRepository
import ir.talayar.app.util.assetDto
import ir.talayar.app.util.historyDto
import ir.talayar.app.util.historyPoints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class MarketRepositoryTest {

    private lateinit var api: FakeMarketApi
    private lateinit var settings: FakeSettingsRepository
    private lateinit var priceDao: FakePriceDao
    private lateinit var favoriteDao: FakeFavoriteDao
    private lateinit var alertDao: FakeAlertDao
    private lateinit var repository: MarketRepositoryImpl

    @Before
    fun setUp() {
        api = FakeMarketApi()
        settings = FakeSettingsRepository()
        priceDao = FakePriceDao()
        favoriteDao = FakeFavoriteDao()
        alertDao = FakeAlertDao()
        repository = MarketRepositoryImpl(api, settings, priceDao, favoriteDao, alertDao)
    }

    @Test
    fun `refresh stores prices and records a history point`() = runTest {
        api.pricesResponse = ir.talayar.app.data.remote.PricesEnvelopeDto(
            data = listOf(assetDto(symbol = "USD", name = "دلار آمریکا", category = "currency", price = 104_850.0)),
        )

        val result = repository.refresh()

        assertTrue(result.isSuccess)
        val entity = priceDao.prices.value.first { it.symbol == "USD" }
        assertEquals(104_850.0, entity.price, 0.0)
        assertEquals(1, priceDao.history.count { it.symbol == "USD" })
    }

    @Test
    fun `refresh skips duplicate history points when price is unchanged`() = runTest {
        api.pricesResponse = ir.talayar.app.data.remote.PricesEnvelopeDto(
            data = listOf(assetDto(symbol = "USD", category = "currency", price = 104_850.0)),
        )
        repository.refresh()
        repository.refresh()

        // two refreshes, same price, within the gap window -> single point
        assertEquals(1, priceDao.history.count { it.symbol == "USD" })
    }

    @Test
    fun `refresh failure surfaces as Result failure without crashing`() = runTest {
        api.pricesError = IOException("no network")

        val result = repository.refresh()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `getHistory merges gateway points with local points`() = runTest {
        val now = System.currentTimeMillis()
        val serverPoints = historyPoints(
            now - 40 * 60_000 to 100.0,
            now - 30 * 60_000 to 110.0,
            now - 10 * 60_000 to 120.0,
        )
        api.historyResponse = historyDto(serverPoints)
        priceDao.insertHistory(
            listOf(ir.talayar.app.data.local.entities.PriceHistoryEntity("USD", now - 5 * 60_000, 125.0)),
        )

        val result = repository.getHistory("USD")

        assertTrue(result.isSuccess)
        val hour = result.getOrThrow().ranges["1H"].orEmpty()
        assertEquals(4, hour.size)
        assertEquals(125.0, hour.last().p, 0.0)
    }

    @Test
    fun `getHistory falls back to cached points when offline`() = runTest {
        val now = System.currentTimeMillis()
        api.historyError = IOException("offline")
        priceDao.insertHistory(
            listOf(
                ir.talayar.app.data.local.entities.PriceHistoryEntity("USD", now - 60_000, 100.0),
                ir.talayar.app.data.local.entities.PriceHistoryEntity("USD", now - 30_000, 101.0),
            ),
        )

        val result = repository.getHistory("USD")

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().fromNetwork)
        assertEquals(2, result.getOrThrow().ranges["1H"].orEmpty().size)
    }

    @Test
    fun `toggleFavorite adds then removes`() = runTest {
        priceDao.upsertAll(
            listOf(
                LatestPriceEntity(
                    symbol = "USD",
                    name = "دلار آمریکا",
                    category = "currency",
                    currency = "TOMAN",
                    unit = null,
                    price = 104_850.0,
                    change = null,
                    changePercent = null,
                    dayHigh = null,
                    dayLow = null,
                    prevPrice = null,
                    updatedAt = 1L,
                    source = "test",
                    isStale = false,
                ),
            ),
        )

        repository.toggleFavorite("USD")
        assertTrue(favoriteDao.isFavorite("USD"))

        repository.toggleFavorite("USD")
        assertFalse(favoriteDao.isFavorite("USD"))
    }

    @Test
    fun `evaluateAlerts triggers above-threshold rules and disables them`() = runTest {
        priceDao.upsertAll(
            listOf(priceEntity("USD", 110_000.0)),
            )
        alertDao.insert(
            ir.talayar.app.data.local.entities.AlertEntity(symbol = "USD", kind = "above", threshold = 109_000.0),
        )
        alertDao.insert(
            ir.talayar.app.data.local.entities.AlertEntity(symbol = "USD", kind = "below", threshold = 120_000.0),
        )

        val triggered = repository.evaluateAlerts()

        assertEquals(2, triggered.size)
        assertTrue(triggered.all { !it.rule.enabled })
        assertTrue(alertDao.enabledAlerts().isEmpty())
    }

    @Test
    fun `evaluateAlerts does not fire when condition is not met`() = runTest {
        priceDao.upsertAll(listOf(priceEntity("USD", 104_850.0)))
        alertDao.insert(
            ir.talayar.app.data.local.entities.AlertEntity(symbol = "USD", kind = "above", threshold = 120_000.0),
        )

        val triggered = repository.evaluateAlerts()

        assertTrue(triggered.isEmpty())
        assertTrue(alertDao.enabledAlerts().isNotEmpty())
    }

    @Test
    fun `evaluateAlerts percent kind compares absolute change`() = runTest {
        priceDao.upsertAll(listOf(priceEntity("USD", 104_850.0, changePercent = 2.4)))
        alertDao.insert(
            ir.talayar.app.data.local.entities.AlertEntity(symbol = "USD", kind = "percent", threshold = 2.0),
        )

        val triggered = repository.evaluateAlerts()

        assertEquals(1, triggered.size)
        assertEquals(AlertKind.PERCENT, triggered.first().rule.kind)
    }

    @Test
    fun `add and remove alert rules`() = runTest {
        repository.addAlert(AlertRule(symbol = "USD", kind = AlertKind.ABOVE, threshold = 120_000.0))
        assertEquals(1, alertDao.alerts.value.size)

        val id = alertDao.alerts.value.first().id
        repository.removeAlert(id)
        assertTrue(alertDao.alerts.value.isEmpty())
    }

    private fun priceEntity(symbol: String, price: Double, changePercent: Double? = 0.5): LatestPriceEntity =
        LatestPriceEntity(
            symbol = symbol,
            name = symbol,
            category = "currency",
            currency = "TOMAN",
            unit = null,
            price = price,
            change = null,
            changePercent = changePercent,
            dayHigh = null,
            dayLow = null,
            prevPrice = null,
            updatedAt = 2L,
            source = "test",
            isStale = false,
        )
}
