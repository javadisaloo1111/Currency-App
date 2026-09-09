package ir.talayar.app.ui.market

import ir.talayar.app.core.Connectivity
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.usecase.ObserveMarketUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.domain.usecase.ToggleFavoriteUseCase
import ir.talayar.app.util.FakeConnectivity
import ir.talayar.app.util.FakeMarketRepository
import ir.talayar.app.util.FakeSettingsRepository
import ir.talayar.app.util.MainDispatcherRule
import ir.talayar.app.util.marketAsset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = FakeMarketRepository()
    private val connectivity: Connectivity = FakeConnectivity(initial = true)

    private fun viewModel(): MarketViewModel = MarketViewModel(
        refreshPrices = RefreshPricesUseCase(repository),
        toggleFavoriteUseCase = ToggleFavoriteUseCase(repository),
        observeMarket = ObserveMarketUseCase(repository),
        settingsRepository = FakeSettingsRepository(),
        connectivity = connectivity,
    )

    @Test
    fun `normalize handles arabic variants`() {
        assertEquals("طلا", MarketViewModel.normalize("طلا"))
        assertEquals("سکه", MarketViewModel.normalize("سكه")) // arabic kaf
        assertEquals("دلار", MarketViewModel.normalize("دلار "))
    }

    @Test
    fun `search filters by persian name`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K", name = "طلای ۱۸ عیار", category = AssetCategory.GOLD),
            marketAsset(symbol = "USD", name = "دلار آمریکا", category = AssetCategory.CURRENCY),
            marketAsset(symbol = "EUR", name = "یورو", category = AssetCategory.CURRENCY),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setQuery("دلار")
        advanceUntilIdle()
        assertEquals(listOf("USD"), vm.uiState.value.filtered.map { it.symbol })

        vm.setQuery("طلا")
        advanceUntilIdle()
        assertEquals(listOf("GOLD_18K"), vm.uiState.value.filtered.map { it.symbol })
    }

    @Test
    fun `category filter narrows the list`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K", name = "طلای ۱۸ عیار", category = AssetCategory.GOLD),
            marketAsset(symbol = "COIN_EMAMI", name = "سکه امامی", category = AssetCategory.COIN),
            marketAsset(symbol = "USD", name = "دلار آمریکا", category = AssetCategory.CURRENCY),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setCategory(AssetCategory.COIN)
        advanceUntilIdle()
        assertEquals(listOf("COIN_EMAMI"), vm.uiState.value.filtered.map { it.symbol })

        vm.setCategory(null)
        advanceUntilIdle()
        assertEquals(3, vm.uiState.value.filtered.size)
    }

    @Test
    fun `combined search and category returns empty for mismatches`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K", name = "طلای ۱۸ عیار", category = AssetCategory.GOLD),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setCategory(AssetCategory.CURRENCY)
        vm.setQuery("طلا")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.filtered.isEmpty())
    }
}
