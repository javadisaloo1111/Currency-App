package ir.talayar.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/**
 * Price Gateway API. The base URL is resolved per request from user settings
 * (see [ir.talayar.app.data.repository.MarketRepositoryImpl]) — @Url keeps
 * Retrofit out of base-URL plumbing and makes the interface trivially fakeable
 * in tests.
 */
interface MarketApi {

    @GET
    suspend fun prices(@Url url: String, @Query("ts") cacheBuster: Long): PricesEnvelopeDto

    @GET
    suspend fun history(@Url url: String, @Query("ts") cacheBuster: Long): HistoryDto

    @GET
    suspend fun health(@Url url: String, @Query("ts") cacheBuster: Long): HealthDto
}
