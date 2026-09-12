package ir.talayar.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.talayar.app.BuildConfig
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.update.DataStoreUpdateCheckCache
import ir.talayar.app.data.update.UpdateCheckCache
import ir.talayar.app.data.update.UpdateRepositoryImpl
import ir.talayar.app.domain.repository.UpdateRepository
import retrofit2.Retrofit
import javax.inject.Singleton

/** Update-checker wiring (release metadata, check cache, repository). */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @Singleton
    fun provideReleaseApi(retrofit: Retrofit): ReleaseApi = retrofit.create(ReleaseApi::class.java)

    @Provides
    @Singleton
    fun provideUpdateCheckCache(@ApplicationContext context: Context): UpdateCheckCache =
        DataStoreUpdateCheckCache(context)

    @Provides
    @Singleton
    fun provideUpdateRepository(
        releaseApi: ReleaseApi,
        cache: UpdateCheckCache,
    ): UpdateRepository = UpdateRepositoryImpl(
        releaseApi = releaseApi,
        cache = cache,
        installedVersion = BuildConfig.APP_VERSION_NAME,
    )
}
