package ir.talayar.app.core

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Persian-first formatting utilities for prices, percents and timestamps.
 * All display values flow through here so typography stays consistent.
 */
object Formatters {

    val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

    private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    private val intFormat = DecimalFormat("#,###")
    private val decimalFormat = DecimalFormat("#,###.##")

    fun toPersianDigits(text: String): String = buildString(text.length) {
        for (ch in text) {
            if (ch in '0'..'9') append(PERSIAN_DIGITS[ch - '0']) else append(ch)
        }
    }

    /**
     * Format a price for display (canonical unit: Toman).
     *  - USD-denominated values keep up to 2 decimals (defensive; the gateway
     *    publishes every asset in Toman).
     */
    fun price(
        value: Double,
        currency: String = "TOMAN",
        persianDigits: Boolean = true,
    ): String {
        val converted = value
        val formatted = when {
            currency.equals("USD", ignoreCase = true) -> decimalFormat.format(converted)
            converted >= 1000.0 -> intFormat.format(converted)
            converted == converted.toLong().toDouble() -> intFormat.format(converted)
            else -> decimalFormat.format(converted)
        }
        return if (persianDigits) localizeDigits(formatted) else formatted
    }

    /** "+۱٫۰۲٪" / "−۰٫۵۳٪" / "—" */
    fun percent(value: Double?, persianDigits: Boolean = true): String {
        if (value == null || !value.isFinite()) return "—"
        val sign = when {
            value > 0.0 -> "+"
            value < 0.0 -> "−"
            else -> ""
        }
        val formatted = DecimalFormat("0.##").format(kotlin.math.abs(value))
        val text = "$sign$formatted%"
        return if (persianDigits) localizeDigits(text) else text
    }

    /** Change amount, grouped with sign (Toman). */
    fun changeAmount(value: Double?, persianDigits: Boolean = true): String {
        if (value == null || !value.isFinite()) return "—"
        val converted = value
        val sign = if (converted > 0.0) "+" else if (converted < 0.0) "−" else ""
        val formatted = intFormat.format(kotlin.math.abs(converted))
        val text = "$sign$formatted"
        return if (persianDigits) localizeDigits(text) else text
    }

    /** "۱۲:۴۲:۳۱" (Tehran time). */
    fun time(epochMs: Long, persianDigits: Boolean = true): String {
        if (epochMs <= 0L) return "—"
        val text = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(TEHRAN)
            .format(Instant.ofEpochMilli(epochMs))
        return if (persianDigits) toPersianDigits(text) else text
    }

    /** "۱۲:۴۲" (Tehran time, short). */
    fun shortTime(epochMs: Long, persianDigits: Boolean = true): String {
        if (epochMs <= 0L) return "—"
        val text = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(TEHRAN)
            .format(Instant.ofEpochMilli(epochMs))
        return if (persianDigits) toPersianDigits(text) else text
    }

    /** Jalali date like "۱۲ شهریور" (Tehran timezone, no ICU dependency). */
    fun date(epochMs: Long, persianDigits: Boolean = true): String {
        if (epochMs <= 0L) return "—"
        val zoned = Instant.ofEpochMilli(epochMs).atZone(TEHRAN)
        val (day, monthName) = Jalali.toJalaliParts(zoned.year, zoned.monthValue, zoned.dayOfMonth)
        val text = "$day $monthName"
        return if (persianDigits) toPersianDigits(text) else text
    }

    /** Relative time: "چند لحظه پیش"، "۳ دقیقه پیش"، "۲ ساعت پیش"، date fallback. */
    fun relative(epochMs: Long, persianDigits: Boolean = true, nowMs: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0L) return "—"
        val diff = nowMs - epochMs
        return when {
            diff < 0 -> time(epochMs, persianDigits)
            diff < 15_000L -> "چند لحظه پیش"
            diff < 60_000L -> "${num(diff / 1000, persianDigits)} ثانیه پیش"
            diff < 60 * 60_000L -> "${num(diff / 60_000, persianDigits)} دقیقه پیش"
            diff < 24 * 60 * 60_000L -> "${num(diff / 3_600_000, persianDigits)} ساعت پیش"
            else -> date(epochMs, persianDigits)
        }
    }

    private fun num(v: Long, persian: Boolean): String =
        if (persian) toPersianDigits(v.toString()) else v.toString()

    /** Latin grouped digits -> Persian digits with U+066C group + U+066B decimal separators. */
    private fun localizeDigits(groupedLatin: String): String {
        val sb = StringBuilder(groupedLatin.length)
        for (ch in groupedLatin) {
            when {
                ch in '0'..'9' -> sb.append(PERSIAN_DIGITS[ch - '0'])
                ch == ',' -> sb.append('٬')
                ch == '.' -> sb.append('٫')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}

/** Solar Hijri (Jalali) calendar conversion — standard jalali.c algorithm. */
object Jalali {
    private val G_D_M = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
    val MONTH_NAMES = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    /** Returns (day, monthName). */
    fun toJalaliParts(gy: Int, gm: Int, gd: Int): Pair<Int, String> {
        val gy2 = gy - 1600
        // Gregorian leap day correction — only after February, only in leap years.
        val gLeap = (gy % 4 == 0 && gy % 100 != 0) || (gy % 400 == 0)
        val gd2 = gd + (if (gm > 2 && gLeap) 1 else 0)
        var days = 365L * gy2 + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 - 80 + gd2 + G_D_M[gm - 1]
        var jy = 979L
        jy += 33L * (days / 12053); days %= 12053
        jy += 4L * (days / 1461); days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm = if (days < 186) 1 + (days / 31).toInt() else 7 + ((days - 186) / 30).toInt()
        val jd = 1 + (if (days < 186) days % 31 else (days - 186) % 30).toInt()
        return jd to MONTH_NAMES[jm - 1]
    }
}
