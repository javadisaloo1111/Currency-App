package ir.talayar.app.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.settings.RefreshInterval
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.repository.SettingsRepository
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
class MarketViewModel @Inject constructor(
    private val refreshPrices: RefreshPricesUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    observeMarket: ObserveMarketUseCase,
    settingsRepository: SettingsRepository,
    connectivity: Connectivity,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val query: String = "",
        val category: AssetCategory? = null,
        val filtered: List<MarketAsset> = emptyList(),
        val totalCount: Int = 0,
        val offline: Boolean = false,
        val isStale: Boolean = false,
        val updatedAt: Long? = null,
        val error: String? = null,
        val refreshInterval: RefreshInterval = RefreshInterval.S15,
    )

    private data class Filters(val query: String, val category: AssetCategory?)

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<AssetCategory?>(null)

    /** null = idle, true = running, false = failed */
    private val refreshState = MutableStateFlow<Boolean?>(null)
    private val lastError = MutableStateFlow<String?>(null)

    private val filters = combine(query, category) { q, c -> Filters(q, c) }

    // refreshState + lastError merged so combine stays within the 5-flow overload.
    private val refreshOutcome = combine(refreshState, lastError) { state, error -> state to error }

    val uiState: StateFlow<UiState> = combine(
        observeMarket(),
        filters,
        settingsRepository.settings,
        connectivity.isOnline,
        refreshOutcome,
    ) { assets, f, settings, online, outcome ->
        val refreshing = outcome.first
        val error = outcome.second
        val normalizedQuery = normalize(f.query)
        val filtered = assets.filter { asset ->
            (f.category == null || asset.category == f.category) &&
                (normalizedQuery.isEmpty() ||
                    normalize(asset.name).contains(normalizedQuery) ||
                    asset.symbol.contains(normalizedQuery, ignoreCase = true))
        }
        UiState(
            isLoading = assets.isEmpty() && refreshing == null,
            isRefreshing = refreshing == true && assets.isNotEmpty(),
            query = f.query,
            category = f.category,
            filtered = filtered,
            totalCount = assets.size,
            offline = !online,
            isStale = assets.any { it.isStale },
            updatedAt = assets.map { it.updatedAt }.filter { it > 0 }.maxOrNull(),
            error = if (refreshing == false && assets.isEmpty()) error ?: "اتصال به سرور برقرار نشد" else null,
            refreshInterval = settings.refreshInterval,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        if (refreshState.value == true) return
        viewModelScope.launch {
            refreshState.value = true
            val result = refreshPrices()
            lastError.value = (result.exceptionOrNull() as? ir.talayar.app.data.remote.GatewayException)?.userMessage
            refreshState.value = result.isSuccess
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setCategory(value: AssetCategory?) {
        category.value = value
    }

    fun toggleFavorite(symbol: String) {
        viewModelScope.launch { toggleFavoriteUseCase(symbol) }
    }

    companion object {
        /** Normalizes Arabic/Persian character variants for search. */
        fun normalize(input: String): String = input
            .trim()
            .lowercase()
            .replace('ك', 'ک')
            .replace('ي', 'ی')
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ة', 'ه')
            .replace('\u200c', ' ')
    }
}
