package ir.talayar.app.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ir.talayar.app.BuildConfig
import ir.talayar.app.data.remote.MarketApi
import ir.talayar.app.data.update.UpdateApi
import ir.talayar.app.data.update.UpdateDownloads
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    /**
     * Identifies the app to every endpoint. The GitHub REST API *requires* a
     * User-Agent header and rejects requests without one, and intermediaries on
     * restricted networks are friendlier to a descriptive agent than to a bare
     * "okhttp/x.y.z". No credentials of any kind are attached — the update channel
     * is fully anonymous and read-only.
     */
    @Provides
    @Singleton
    fun provideUserAgentInterceptor(): Interceptor {
        val userAgent = "Talayar/" + BuildConfig.APP_VERSION_NAME +
            " (Android " + Build.VERSION.SDK_INT + "; in-app update checker; " +
            "+https://github.com/javadisaloo1111/Currency-App)"
        return Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .build()
            chain.proceed(request)
        }
    }

    /** Price Gateway client (small JSON payloads, multi-endpoint failover upstream). */
    @Provides
    @Singleton
    fun provideOkHttpClient(userAgent: Interceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(userAgent)
            .addInterceptor(logging)
            .build()
    }

    /**
     * Client for release-metadata calls (a few kilobytes of JSON).
     *
     * More generous than the gateway client because it must survive slow mobile
     * links, but still hard-bounded: a filtered or black-holed host must not hang
     * the «بررسی بروزرسانی» button. The repository additionally caps a whole check
     * (all sources, all retries) with its own time budget.
     */
    @Provides
    @Singleton
    @UpdateApi
    fun provideUpdateApiClient(client: OkHttpClient): OkHttpClient =
        client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * Client for multi-megabyte APK downloads.
     *
     * **No overall call timeout**: the shared 25 s `callTimeout` used to cancel
     * release downloads mid-stream whenever the link was slower than ~350 kB/s,
     * which is ordinary on Iranian mobile networks. Per-socket timeouts stay
     * bounded so a stalled transfer still fails instead of hanging forever.
     */
    @Provides
    @Singleton
    @UpdateDownloads
    fun provideUpdateDownloadClient(client: OkHttpClient): OkHttpClient =
        client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * Retrofit with a placeholder base URL — every call passes a full @Url
     * built from user settings (see MarketRepositoryImpl), which lets the user
     * point the app at their own self-hosted Price Gateway at runtime.
     */
    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit = Retrofit.Builder()
        .baseUrl("https://localhost/") // overridden per request via @Url
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    /**
     * Retrofit for the update channel. Same placeholder-base/@Url pattern, because
     * the release metadata is fetched from several absolute URLs (GitHub API plus
     * its static mirrors) that are chosen at call time.
     */
    @Provides
    @Singleton
    @UpdateApi
    fun provideUpdateRetrofit(@UpdateApi client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://localhost/") // overridden per request via @Url
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideMarketApi(retrofit: Retrofit): MarketApi = retrofit.create(MarketApi::class.java)

}
