package ir.talayar.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gateway DTOs — field names match the Price Gateway contract
 * (both the self-hosted backend and the static GitHub-Pages API).
 * Every field is optional/defensive so a malformed payload never crashes the app.
 */

@Serializable
data class PricesEnvelopeDto(
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("data") val data: List<AssetDto> = emptyList(),
)

@Serializable
data class AssetDto(
    @SerialName("symbol") val symbol: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("category") val category: String = "currency",
    @SerialName("price") val price: Double = 0.0,
    @SerialName("currency") val currency: String = "TOMAN",
    @SerialName("unit") val unit: String? = null,
    @SerialName("change") val change: Double? = null,
    @SerialName("change_percent") val changePercent: Double? = null,
    @SerialName("day_high") val dayHigh: Double? = null,
    @SerialName("day_low") val dayLow: Double? = null,
    @SerialName("prev_price") val prevPrice: Double? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("source") val source: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
)

@Serializable
data class HistoryPointDto(
    @SerialName("t") val t: Long = 0L,
    @SerialName("p") val p: Double = 0.0,
)

/** Supports both response shapes: {ranges:{...}} and {points:[...], range:"1H"}. */
@Serializable
data class HistoryDto(
    @SerialName("symbol") val symbol: String? = null,
    @SerialName("range") val range: String? = null,
    @SerialName("points") val points: List<HistoryPointDto>? = null,
    @SerialName("ranges") val ranges: Map<String, List<HistoryPointDto>>? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
)

@Serializable
data class HealthDto(
    @SerialName("status") val status: String = "unknown",
    @SerialName("version") val version: String? = null,
    @SerialName("time") val time: String? = null,
    @SerialName("is_stale") val isStale: Boolean = false,
    @SerialName("sources") val sources: List<HealthSourceDto> = emptyList(),
)

@Serializable
data class HealthSourceDto(
    @SerialName("name") val name: String = "",
    @SerialName("status") val status: String = "unknown",
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("error_count") val errorCount: Int = 0,
    @SerialName("avg_response_ms") val avgResponseMs: Long? = null,
)
