package ir.talayar.app.ui.favorites

import ir.talayar.app.core.Connectivity
import ir.talayar.app.domain.usecase.ObserveFavoritesUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.domain.usecase.ToggleFavoriteUseCase
import ir.talayar.app.util.FakeConnectivity
import ir.talayar.app.util.FakeMarketRepository
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
class FavoritesViewModelTest {

    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private val repository = FakeMarketRepository()
    private val connectivity: Connectivity = FakeConnectivity(initial = true)

    private fun viewModel(): FavoritesViewModel = FavoritesViewModel(
        refreshPrices = RefreshPricesUseCase(repository),
        toggleFavoriteUseCase = ToggleFavoriteUseCase(repository),
        observeFavorites = ObserveFavoritesUseCase(repository),
        connectivity = connectivity,
    )

    @Test
    fun `favorites stream reflects repository favorites`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(
            marketAsset(symbol = "GOLD_18K"),
            marketAsset(symbol = "USD"),
        )
        repository.favorites.value = setOf("USD")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("USD"), state.favorites.map { it.symbol })
        assertTrue(!state.isLoading)
    }

    @Test
    fun `empty favorites state is not loading after refresh`() = runTest(dispatcherRule.dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertTrue(vm.uiState.value.favorites.isEmpty())
        assertTrue(!vm.uiState.value.isLoading)
    }

    @Test
    fun `removeFavorite toggles the symbol off`() = runTest(dispatcherRule.dispatcher) {
        repository.assets.value = listOf(marketAsset(symbol = "GOLD_18K"))
        repository.favorites.value = setOf("GOLD_18K")
        val vm = viewModel()
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.removeFavorite("GOLD_18K")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.favorites.isEmpty())
        assertEquals(listOf("GOLD_18K"), repository.toggledFavorites)
    }
}
