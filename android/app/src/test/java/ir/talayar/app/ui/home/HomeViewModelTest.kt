package ir.talayar.app.ui.home

import ir.talayar.app.core.Connectivity
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.usecase.ObserveFavoritesUseCase
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = FakeMarketRepository()
    private val settings = FakeSettingsRepository()
    private val connectivity: Connectivity = FakeConnectivity(initial = true)

    private fun viewModel(): HomeViewModel = HomeViewModel(
        refreshPrices = RefreshPricesUseCase(repository),
        toggleFavoriteUseCase = ToggleFavoriteUseCase(repository),
        observeMarket = ObserveMarketUseCase(repository),
        observeFavorites = ObserveFavoritesUseCase(repository),
        settingsRepository = settings,
        networkMonitor = connectivity,
    )

    @Test
    fun `loads market data with featured asset and overview`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K", name = "طلای ۱۸ عیار", category = AssetCategory.GOLD, price = 6_703_000.0),
            marketAsset(symbol = "USD", name = "دلار آمریکا", category = AssetCategory.CURRENCY, price = 104_850.0, changePercent = -0.21),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.featured)
        assertEquals("GOLD_18K", state.featured?.symbol)
        assertEquals(listOf("GOLD_18K", "USD"), state.overview.map { it.symbol })
        assertTrue(state.topMovers.isNotEmpty())
        assertTrue(state.error == null)
    }

    @Test
    fun `refresh failure with empty data shows error`() = runTest(dispatcherRule.dispatcher) {
        repository.refreshResult = Result.failure(java.io.IOException("offline"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertNull(state.featured)
        assertTrue(state.error != null || state.isLoading)
    }

    @Test
    fun `refresh failure keeps last data`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(marketAsset(symbol = "GOLD_18K"))
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        repository.refreshResult = Result.failure(java.io.IOException("offline"))
        vm.refresh()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state.assets.isNotEmpty())
        assertNotNull(state.featured)
    }

    @Test
    fun `toggleFavorite delegates to repository`() = runTest(dispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.toggleFavorite("USD")
        advanceUntilIdle()
        assertEquals(listOf("USD"), repository.toggledFavorites)
    }

    @Test
    fun `favorites section only contains favorites`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K"),
            marketAsset(symbol = "USD"),
        )
        repository.favorites.value = setOf("USD")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertEquals(listOf("USD"), vm.uiState.value.favorites.map { it.symbol })
    }
}
