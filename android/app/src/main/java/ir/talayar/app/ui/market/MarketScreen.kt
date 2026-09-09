package ir.talayar.app.ui.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.ui.components.AssetRowCard
import ir.talayar.app.ui.components.CategoryChips
import ir.talayar.app.ui.components.ConnectionBanner
import ir.talayar.app.ui.components.EmptyState
import ir.talayar.app.ui.components.ErrorState
import ir.talayar.app.ui.components.ListSkeleton
import ir.talayar.app.ui.components.SearchField
import ir.talayar.app.ui.components.SectionHeader
import ir.talayar.app.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun MarketRoute(
    onAssetClick: (String) -> Unit,
    viewModel: MarketViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.refreshInterval) {
        val seconds = state.refreshInterval.seconds
        while (isActive && seconds > 0) {
            delay(seconds * 1000L)
            viewModel.refresh()
        }
    }

    MarketScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onQueryChange = viewModel::setQuery,
        onCategoryChange = viewModel::setCategory,
        onToggleFavorite = viewModel::toggleFavorite,
        onAssetClick = onAssetClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    state: MarketViewModel.UiState,
    onRefresh: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCategoryChange: (ir.talayar.app.domain.model.AssetCategory?) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAssetClick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = Dimens.ScreenPadding)) {
            Text(
                text = "بازار",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(vertical = Dimens.SpaceS),
            )
            SearchField(value = state.query, onValueChange = onQueryChange)
            CategoryChips(selected = state.category, onSelect = onCategoryChange, modifier = Modifier.padding(vertical = Dimens.SpaceS))
        }

        ConnectionBanner(offline = state.offline, stale = state.isStale, updatedAt = state.updatedAt)

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.isLoading -> ListSkeleton(items = 9)

                state.filtered.isEmpty() && state.query.isNotBlank() -> EmptyState(
                    icon = Icons.Rounded.Search,
                    title = "دارایی یافت نشد",
                    message = "عبارت «${state.query}» با هیچ دارایی‌ای مطابقت ندارد.",
                )

                state.filtered.isEmpty() -> ErrorState(
                    title = "قیمت‌ها در دسترس نیست",
                    message = state.error ?: "اتصال به سرور برقرار نشد.",
                    onRetry = onRefresh,
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Dimens.ScreenPadding,
                        end = Dimens.ScreenPadding,
                        top = Dimens.SpaceS,
                        bottom = Dimens.SpaceXXL,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
                ) {
                    item(key = "header-count") {
                        SectionHeader(title = "${state.filtered.size} دارایی")
                    }
                    items(state.filtered, key = { it.symbol }) { asset ->
                        AssetRowCard(
                            asset = asset,
                            onClick = { onAssetClick(asset.symbol) },
                            onToggleFavorite = { onToggleFavorite(asset.symbol) },
                        )
                    }
                }
            }
        }
    }
}
