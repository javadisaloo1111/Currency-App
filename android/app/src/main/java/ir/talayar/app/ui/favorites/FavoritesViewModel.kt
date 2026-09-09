package ir.talayar.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.core.Connectivity
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.usecase.ObserveFavoritesUseCase
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
class FavoritesViewModel @Inject constructor(
    private val refreshPrices: RefreshPricesUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    observeFavorites: ObserveFavoritesUseCase,
    connectivity: Connectivity,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val favorites: List<MarketAsset> = emptyList(),
        val offline: Boolean = false,
        val isStale: Boolean = false,
        val updatedAt: Long? = null,
    )

    private val refreshState = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<UiState> = combine(
        observeFavorites(),
        connectivity.isOnline,
        refreshState,
    ) { favorites, online, refreshing ->
        UiState(
            isLoading = favorites.isEmpty() && refreshing == null,
            isRefreshing = refreshing == true && favorites.isNotEmpty(),
            favorites = favorites,
            offline = !online,
            isStale = favorites.any { it.isStale },
            updatedAt = favorites.map { it.updatedAt }.filter { it > 0 }.maxOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshState.value == true) return
        viewModelScope.launch {
            refreshState.value = true
            refreshState.value = refreshPrices().isSuccess
        }
    }

    fun removeFavorite(symbol: String) {
        viewModelScope.launch { toggleFavoriteUseCase(symbol) }
    }
}
