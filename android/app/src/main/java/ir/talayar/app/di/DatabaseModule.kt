package ir.talayar.app.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ir.talayar.app.data.local.TalayarDatabase
import ir.talayar.app.data.local.daos.AlertDao
import ir.talayar.app.data.local.daos.FavoriteDao
import ir.talayar.app.data.local.daos.PriceDao
import ir.talayar.app.domain.repository.MarketRepository
import ir.talayar.app.data.repository.MarketRepositoryImpl
import ir.talayar.app.domain.repository.SettingsRepository
import ir.talayar.app.data.settings.SettingsStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TalayarDatabase =
        Room.databaseBuilder(context, TalayarDatabase::class.java, "talayar.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun providePriceDao(db: TalayarDatabase): PriceDao = db.priceDao()

    @Provides
    fun provideFavoriteDao(db: TalayarDatabase): FavoriteDao = db.favoriteDao()

    @Provides
    fun provideAlertDao(db: TalayarDatabase): AlertDao = db.alertDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMarketRepository(impl: MarketRepositoryImpl): MarketRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: SettingsStore): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindConnectivity(impl: ir.talayar.app.core.NetworkMonitor): ir.talayar.app.core.Connectivity
}
