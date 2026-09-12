package ir.talayar.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FormattersTest {

    private val zone: ZoneId = Formatters.TEHRAN

    @Test
    fun `price formats with grouping separators and persian digits`() {
        val formatted = Formatters.price(6_703_000.0)
        assertEquals("۶٬۷۰۳٬۰۰۰", formatted)
    }

    @Test
    fun `price keeps two decimals for usd assets`() {
        val formatted = Formatters.price(2651.38, currency = "USD")
        assertEquals("۲٬۶۵۱٫۳۸", formatted)
    }

    @Test
    fun `price formats the canonical toman value without any unit conversion`() {
        // Prices arrive in Toman and are displayed in Toman — never in Rial.
        assertEquals("۱٬۰۴۸٬۵۰۰", Formatters.price(104_850.0))
        assertEquals("۱۲٬۴۵۰٬۰۰۰", Formatters.price(12_450_000.0))
    }

    @Test
    fun `price keeps latin digits when requested`() {
        val formatted = Formatters.price(104_850.0, persianDigits = false)
        assertEquals("104,850", formatted)
    }

    @Test
    fun `percent formats with sign`() {
        assertEquals("+۱٫۰۲", Formatters.percent(1.02).dropLast(1))
        assertEquals("−۰٫۵۳", Formatters.percent(-0.53).dropLast(1))
        assertEquals("—", Formatters.percent(null))
    }

    @Test
    fun `change amount formats with sign and grouping`() {
        assertEquals("+۱۲۵٬۰۰۰", Formatters.changeAmount(125_000.0))
        assertEquals("−۱۲۵٬۰۰۰", Formatters.changeAmount(-125_000.0))
        assertEquals("—", Formatters.changeAmount(null))
    }

    @Test
    fun `time renders tehran timezone`() {
        val epochMs = Instant.parse("2026-09-09T09:12:31Z").toEpochMilli()
        // Tehran is UTC+3:30 in September -> 12:42:31
        assertEquals("۱۲:۴۲:۳۱", Formatters.time(epochMs))
        assertEquals("12:42:31", Formatters.time(epochMs, persianDigits = false))
    }

    @Test
    fun `relative renders minutes and hours in persian`() {
        val now = 1_757_400_000_000L
        assertEquals("چند لحظه پیش", Formatters.relative(now - 5_000, nowMs = now))
        assertEquals("۳ دقیقه پیش", Formatters.relative(now - 3 * 60_000, nowMs = now))
        assertEquals("۲ ساعت پیش", Formatters.relative(now - 2 * 3_600_000, nowMs = now))
        assertEquals("—", Formatters.relative(0, nowMs = now))
    }

    @Test
    fun `date renders jalali calendar`() {
        // 2026-09-09 is 18 Shahrivar 1405
        val epochMs = Instant.parse("2026-09-09T09:12:31Z").toEpochMilli()
        val formatted = Formatters.date(epochMs)
        assertTrue("expected ۱۸ شهریور but was $formatted", formatted == "۱۸ شهریور")
    }
}
