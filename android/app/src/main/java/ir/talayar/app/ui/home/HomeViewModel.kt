package ir.talayar.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.repository.SettingsRepository
import ir.talayar.app.domain.usecase.ObserveFavoritesUseCase
import ir.talayar.app.domain.usecase.ObserveMarketUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.domain.usecase.ToggleFavoriteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val refreshPrices: RefreshPricesUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    observeMarket: ObserveMarketUseCase,
    observeFavorites: ObserveFavoritesUseCase,
    settingsRepository: SettingsRepository,
    connectivity: Connectivity,
) : ViewModel() {

    sealed interface RefreshState {
        data object Idle : RefreshState
        data object Running : RefreshState
        data class Failed(val message: String) : RefreshState
    }

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val assets: List<MarketAsset> = emptyList(),
        val featured: MarketAsset? = null,
        val overview: List<MarketAsset> = emptyList(),
        val favorites: List<MarketAsset> = emptyList(),
        val topMovers: List<MarketAsset> = emptyList(),
        val updatedAt: Long? = null,
        val isStale: Boolean = false,
        val offline: Boolean = false,
        val error: String? = null,
        val refreshInterval: RefreshInterval = RefreshInterval.S15,
    )

    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)

    val uiState: StateFlow<UiState> = combine(
        observeMarket(),
        observeFavorites(),
        settingsRepository.settings,
        connectivity.isOnline,
        refreshState,
    ) { assets, favorites, settings, online, refresh ->
        val featured = assets.firstOrNull { it.symbol == FEATURED_SYMBOL } ?: assets.firstOrNull()
        val overview = listOfNotNull(
            assets.firstOrNull { it.symbol == "GOLD_18K" },
            assets.firstOrNull { it.symbol == "USD" },
            assets.firstOrNull { it.symbol == "COIN_EMAMI" },
        )
        val updatedAt = assets.map { it.updatedAt }.filter { it > 0 }.maxOrNull()
        UiState(
            isLoading = assets.isEmpty() && refresh !is RefreshState.Failed,
            isRefreshing = refresh is RefreshState.Running && assets.isNotEmpty(),
            assets = assets,
            featured = featured,
            overview = overview,
            favorites = favorites,
            topMovers = assets
                .filter { it.changePercent != null }
                .sortedByDescending { kotlin.math.abs(it.changePercent ?: 0.0) }
                .take(3),
            updatedAt = updatedAt,
            isStale = assets.any { it.isStale },
            offline = !online,
            error = (refresh as? RefreshState.Failed)?.message,
            refreshInterval = settings.refreshInterval,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh(silent: Boolean = true) {
        if (refreshState.value is RefreshState.Running) return
        viewModelScope.launch {
            refreshState.value = RefreshState.Running
            val result = refreshPrices()
            refreshState.value = if (result.isSuccess) {
                RefreshState.Idle
            } else {
                RefreshState.Failed(message = "اتصال به سرور برقرار نشد")
            }
        }
    }

    companion object {
        const val FEATURED_SYMBOL = "GOLD_18K"
    }
}
