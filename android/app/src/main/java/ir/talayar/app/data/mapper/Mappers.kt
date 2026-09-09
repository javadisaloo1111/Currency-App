package ir.talayar.app.data.mapper

import ir.talayar.app.data.local.entities.AlertEntity
import ir.talayar.app.data.local.entities.LatestPriceEntity
import ir.talayar.app.data.remote.AssetDto
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.model.MarketAsset

/** Parse an ISO-8601 timestamp defensively; null/invalid -> 0. */
fun parseIsoMillis(iso: String?): Long {
    if (iso.isNullOrBlank()) return 0L
    return try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

fun AssetDto.toEntity(): LatestPriceEntity? {
    if (symbol.isBlank() || price <= 0.0) return null
    return LatestPriceEntity(
        symbol = symbol,
        name = name.ifBlank { symbol },
        category = category,
        currency = currency,
        unit = unit,
        price = price,
        change = change,
        changePercent = changePercent,
        dayHigh = dayHigh,
        dayLow = dayLow,
        prevPrice = prevPrice,
        updatedAt = parseIsoMillis(updatedAt),
        source = source.orEmpty(),
        isStale = isStale,
    )
}

fun LatestPriceEntity.toDomain(isFavorite: Boolean = false): MarketAsset = MarketAsset(
    symbol = symbol,
    name = name,
    category = AssetCategory.fromId(category),
    price = price,
    currency = currency,
    unit = unit,
    change = change,
    changePercent = changePercent,
    dayHigh = dayHigh,
    dayLow = dayLow,
    prevPrice = prevPrice,
    updatedAt = updatedAt,
    source = source,
    isStale = isStale,
    isFavorite = isFavorite,
)

fun AlertEntity.toDomain(): AlertRule? {
    val kind = AlertKind.fromId(kind) ?: return null
    return AlertRule(
        id = id,
        symbol = symbol,
        kind = kind,
        threshold = threshold,
        createdAt = createdAt,
        triggeredAt = triggeredAt,
        enabled = enabled,
    )
}

fun AlertRule.toEntity(): AlertEntity = AlertEntity(
    id = id,
    symbol = symbol,
    kind = kind.id,
    threshold = threshold,
    createdAt = createdAt,
    triggeredAt = triggeredAt,
    enabled = enabled,
)
