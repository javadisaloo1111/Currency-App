package ir.talayar.app.domain.usecase

import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.repository.MarketRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Stream all assets with favorite flags attached. */
class ObserveMarketUseCase @Inject constructor(private val repository: MarketRepository) {
    operator fun invoke(): Flow<List<MarketAsset>> = repository.observeAssets()
}

/** Refresh prices from the gateway (Room remains the UI source of truth). */
class RefreshPricesUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(): Result<Unit> = repository.refresh()
}

/** Stream favorites only. */
class ObserveFavoritesUseCase @Inject constructor(private val repository: MarketRepository) {
    operator fun invoke(): Flow<List<MarketAsset>> = repository.observeFavorites()
}

/** Toggle the favorite state of an asset. */
class ToggleFavoriteUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(symbol: String) = repository.toggleFavorite(symbol)
}

/** Load chart history (network + local merge, offline-safe). */
class GetHistoryUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(symbol: String) = repository.getHistory(symbol)
}
