package ir.talayar.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.HistoryRange
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.domain.model.PriceHistory
import ir.talayar.app.domain.repository.MarketRepository
import ir.talayar.app.domain.usecase.AddAlertUseCase
import ir.talayar.app.domain.usecase.GetHistoryUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.domain.usecase.RemoveAlertUseCase
import ir.talayar.app.domain.usecase.ToggleFavoriteUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AssetDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val refreshPrices: RefreshPricesUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val getHistory: GetHistoryUseCase,
    private val addAlertUseCase: AddAlertUseCase,
    private val removeAlertUseCase: RemoveAlertUseCase,
    private val repository: MarketRepository,
) : ViewModel() {

    val symbol: String = savedStateHandle.get<String>("symbol") ?: ""

    data class UiState(
        val asset: MarketAsset? = null,
        val isFavorite: Boolean = false,
        val range: HistoryRange = HistoryRange.D1,
        val historyLoading: Boolean = true,
        val history: PriceHistory? = null,
        val historyFromNetwork: Boolean = false,
        val alerts: List<AlertRule> = emptyList(),
    )

    private val range = MutableStateFlow(HistoryRange.D1)
    private val historyState = MutableStateFlow<HistoryState>(HistoryState.Loading)

    sealed interface HistoryState {
        data object Loading : HistoryState
        data class Ready(val history: PriceHistory) : HistoryState
    }

    val uiState: StateFlow<UiState> = combine(
        repository.observeAsset(symbol),
        range,
        historyState,
        repository.observeAlertsFor(symbol),
    ) { asset, selectedRange, history, alerts ->
        UiState(
            asset = asset,
            isFavorite = asset?.isFavorite == true,
            range = selectedRange,
            historyLoading = history is HistoryState.Loading,
            history = (history as? HistoryState.Ready)?.history,
            historyFromNetwork = (history as? HistoryState.Ready)?.history?.fromNetwork == true,
            alerts = alerts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshPrices()
            loadHistory()
        }
    }

    fun setRange(newRange: HistoryRange) {
        range.value = newRange
        // history is fetched once with all ranges; no refetch needed
    }

    fun toggleFavorite() {
        viewModelScope.launch { toggleFavoriteUseCase(symbol) }
    }

    private suspend fun loadHistory() {
        historyState.value = HistoryState.Loading
        val result = getHistory(symbol)
        result.onSuccess { historyState.value = HistoryState.Ready(it) }
        result.onFailure {
            // The repository already falls back to cached Room points.
            historyState.value = HistoryState.Ready(
                PriceHistory(symbol = symbol, ranges = emptyMap(), updatedAt = 0),
            )
        }
    }

    /** Returns an error message, or null on success. */
    fun createAlert(kind: AlertKind, threshold: Double): String? {
        val asset = uiState.value.asset ?: return "اطلاعات دارایی در دسترس نیست."
        if (threshold <= 0.0) return "مقدار واردشده معتبر نیست."
        if (kind == AlertKind.PERCENT && threshold > 50.0) return "درصد تغییر باید حداکثر ۵۰ باشد."
        viewModelScope.launch {
            addAlertUseCase(AlertRule(symbol = asset.symbol, kind = kind, threshold = threshold))
        }
        return null
    }

    fun deleteAlert(id: Long) {
        viewModelScope.launch { removeAlertUseCase(id) }
    }
}
