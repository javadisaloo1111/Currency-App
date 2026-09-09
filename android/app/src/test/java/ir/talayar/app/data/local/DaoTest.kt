package ir.talayar.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ir.talayar.app.data.local.entities.AlertEntity
import ir.talayar.app.data.local.entities.FavoriteEntity
import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.data.local.entities.PriceHistoryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Room database tests (in-memory SQLite via Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DaoTest {

    private lateinit var db: TalayarDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TalayarDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun price(symbol: String, price: Double) = LatestPriceEntity(
        symbol = symbol,
        name = symbol,
        category = "currency",
        currency = "TOMAN",
        unit = null,
        price = price,
        change = null,
        changePercent = null,
        dayHigh = null,
        dayLow = null,
        prevPrice = null,
        updatedAt = 1L,
        source = "test",
        isStale = false,
    )

    @Test
    fun `upsertAll replaces existing rows`() = runTest {
        val dao = db.priceDao()
        dao.upsertAll(listOf(price("USD", 100.0), price("EUR", 110.0)))
        dao.upsertAll(listOf(price("USD", 105.0)))

        val all = dao.observeAll().first()
        assertEquals(2, all.size)
        assertEquals(105.0, all.first { it.symbol == "USD" }.price, 0.0)
    }

    @Test
    fun `history insert ignores duplicate timestamps`() = runTest {
        val dao = db.priceDao()
        dao.insertHistory(listOf(PriceHistoryEntity("USD", 1_000L, 100.0)))
        dao.insertHistory(listOf(PriceHistoryEntity("USD", 1_000L, 999.0))) // IGNORE -> keeps 100

        val points = dao.historySince("USD", 0L)
        assertEquals(1, points.size)
        assertEquals(100.0, points.first().p, 0.0)
    }

    @Test
    fun `historySince filters window and sorts ascending`() = runTest {
        val dao = db.priceDao()
        dao.insertHistory(
            listOf(
                PriceHistoryEntity("USD", 500L, 90.0),
                PriceHistoryEntity("USD", 2_000L, 110.0),
                PriceHistoryEntity("USD", 1_000L, 100.0),
            ),
        )

        val points = dao.historySince("USD", 600L)
        assertEquals(listOf(1_000L, 2_000L), points.map { it.t })
        assertEquals(listOf(100.0, 110.0), points.map { it.p })
    }

    @Test
    fun `lastHistoryPoint returns newest`() = runTest {
        val dao = db.priceDao()
        dao.insertHistory(
            listOf(
                PriceHistoryEntity("USD", 1_000L, 100.0),
                PriceHistoryEntity("USD", 3_000L, 300.0),
                PriceHistoryEntity("USD", 2_000L, 200.0),
            ),
        )
        assertEquals(300.0, dao.lastHistoryPoint("USD")?.p ?: 0.0, 0.0)
        assertNull(dao.lastHistoryPoint("NOPE"))
    }

    @Test
    fun `pruneBefore removes old points only`() = runTest {
        val dao = db.priceDao()
        dao.insertHistory(
            listOf(
                PriceHistoryEntity("USD", 100L, 1.0),
                PriceHistoryEntity("USD", 200L, 2.0),
            ),
        )
        dao.pruneBefore(150L)
        assertEquals(listOf(200L), dao.historySince("USD", 0L).map { it.t })
    }

    @Test
    fun `favorites add query and remove`() = runTest {
        val dao = db.favoriteDao()
        assertFalse(dao.isFavorite("USD"))

        dao.insert(FavoriteEntity("USD"))
        dao.insert(FavoriteEntity("GOLD_18K"))
        assertTrue(dao.isFavorite("USD"))
        assertEquals(listOf("USD", "GOLD_18K"), dao.observeSymbols().first())

        dao.delete("USD")
        assertFalse(dao.isFavorite("USD"))
    }

    @Test
    fun `alerts insert trigger and delete`() = runTest {
        val dao = db.alertDao()
        val id = dao.insert(AlertEntity(symbol = "USD", kind = "above", threshold = 110_000.0))
        assertTrue(id > 0)

        dao.insert(AlertEntity(symbol = "EUR", kind = "percent", threshold = 2.0, enabled = false))
        assertEquals(1, dao.enabledAlerts().size)
        assertEquals(2, dao.observeAll().first().size)
        assertEquals(1, dao.observeFor("USD").first().size)

        dao.markTriggered(id, triggeredAt = 123L)
        val triggered = dao.observeFor("USD").first().first()
        assertFalse(triggered.enabled)
        assertNotNull(triggered.triggeredAt)
        assertTrue(dao.enabledAlerts().isEmpty())

        dao.delete(id)
        assertTrue(dao.observeAll().first().isEmpty())
    }
}
