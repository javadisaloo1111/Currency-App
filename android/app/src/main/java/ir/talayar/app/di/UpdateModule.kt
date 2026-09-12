package ir.talayar.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.talayar.app.BuildConfig
import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.update.DataStoreUpdateCheckCache
import ir.talayar.app.data.update.ReleaseSource
import ir.talayar.app.data.update.UpdateApi
import ir.talayar.app.data.update.UpdateCheckCache
import ir.talayar.app.data.update.UpdateRepositoryImpl
import ir.talayar.app.data.update.defaultReleaseSources
import ir.talayar.app.domain.repository.UpdateRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Update-checker wiring (release sources, check cache, repository). */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    /** Release-metadata API on the dedicated, tightly time-boxed client. */
    @Provides
    @Singleton
    @UpdateApi
    fun provideReleaseApi(@UpdateApi retrofit: Retrofit): ReleaseApi =
        retrofit.create(ReleaseApi::class.java)

    /**
     * Ordered release-metadata sources: the GitHub REST API first (authoritative,
     * excludes drafts/prereleases), then the static mirrors of the same payload for
     * networks where that single host is filtered or rate limited.
     *
     * A private gateway or a company mirror is added by appending one URL to
     * `UpdateEndpoints.MIRRORS` — nothing else in the update flow changes.
     */
    @Provides
    @Singleton
    fun provideReleaseSources(@UpdateApi releaseApi: ReleaseApi): List<@JvmSuppressWildcards ReleaseSource> =
        defaultReleaseSources(releaseApi)

    @Provides
    @Singleton
    fun provideUpdateCheckCache(@ApplicationContext context: Context): UpdateCheckCache =
        DataStoreUpdateCheckCache(context)

    @Provides
    @Singleton
    fun provideUpdateRepository(
        sources: List<@JvmSuppressWildcards ReleaseSource>,
        cache: UpdateCheckCache,
        connectivity: Connectivity,
    ): UpdateRepository = UpdateRepositoryImpl(
        sources = sources,
        cache = cache,
        installedVersion = BuildConfig.APP_VERSION_NAME,
        connectivity = connectivity,
    )
}
