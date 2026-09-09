package ir.talayar.app.ui.detail

import androidx.lifecycle.SavedStateHandle
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.domain.model.HistoryRange
import ir.talayar.app.domain.model.PriceHistory
import ir.talayar.app.domain.repository.MarketRepository
import ir.talayar.app.domain.usecase.AddAlertUseCase
import ir.talayar.app.domain.usecase.GetHistoryUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.domain.usecase.RemoveAlertUseCase
import ir.talayar.app.domain.usecase.ToggleFavoriteUseCase
import ir.talayar.app.util.FakeMarketRepository
import ir.talayar.app.util.MainDispatcherRule
import ir.talayar.app.util.historyPoints
import ir.talayar.app.util.marketAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssetDetailViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository: FakeMarketRepository = FakeMarketRepository()

    private fun viewModel(symbol: String = "GOLD_18K"): AssetDetailViewModel {
        val handle = SavedStateHandle(mapOf("symbol" to symbol))
        return AssetDetailViewModel(
            savedStateHandle = handle,
            refreshPrices = RefreshPricesUseCase(repository),
            toggleFavoriteUseCase = ToggleFavoriteUseCase(repository),
            getHistory = GetHistoryUseCase(repository),
            addAlertUseCase = AddAlertUseCase(repository),
            removeAlertUseCase = RemoveAlertUseCase(repository),
            repository = repository as MarketRepository,
        )
    }

    @Test
    fun `loads asset and history ranges`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(marketAsset(symbol = "GOLD_18K"))
        repository.historyResult = Result.success(
            PriceHistory(
                symbol = "GOLD_18K",
                ranges = mapOf(
                    "1H" to historyPoints(1_000L to 100.0, 2_000L to 110.0),
                    "1D" to historyPoints(1_000L to 90.0, 2_000L to 110.0),
                ),
                updatedAt = 2L,
                fromNetwork = true,
            ),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNotNull(state.asset)
        assertEquals("GOLD_18K", state.asset?.symbol)
        assertTrue(!state.historyLoading)
        assertEquals(2, state.history?.ranges?.get("1H")?.size)
        assertTrue(state.historyFromNetwork)
    }

    @Test
    fun `range switch does not refetch history`() = runTest(dispatcherRule.dispatcher) {
        repository.historyResult = Result.success(PriceHistory("GOLD_18K", emptyMap(), 1L))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setRange(HistoryRange.Y1)
        advanceUntilIdle()

        assertEquals(HistoryRange.Y1, vm.uiState.value.range)
        assertEquals(1, repository.historyCalls) // fetched once at init; range switch must not refetch
    }

    @Test
    fun `createAlert validates thresholds`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(marketAsset(symbol = "GOLD_18K"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertNotNull(vm.createAlert(AlertKind.ABOVE, 0.0)) // invalid
        assertNotNull(vm.createAlert(AlertKind.PERCENT, 90.0)) // > 50%
        assertNull(vm.createAlert(AlertKind.ABOVE, 7_500_000.0)) // valid
        advanceUntilIdle()

        assertEquals(1, repository.addedAlerts.size)
        assertEquals(7_500_000.0, repository.addedAlerts.first().threshold, 0.0)
    }

    @Test
    fun `createAlert rejects when asset is unavailable`() = runTest(dispatcherRule.dispatcher) {
        val vm = viewModel("NOPE")
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertNotNull(vm.createAlert(AlertKind.ABOVE, 100.0))
        assertTrue(repository.addedAlerts.isEmpty())
    }

    @Test
    fun `deleteAlert delegates to repository`() = runTest(dispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.deleteAlert(42L)
        advanceUntilIdle()
        assertEquals(listOf(42L), repository.removedAlerts)
    }
}
