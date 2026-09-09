package ir.talayar.app.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.ui.components.AssetRowCard
import ir.talayar.app.ui.components.ConnectionBanner
import ir.talayar.app.ui.components.EmptyState
import ir.talayar.app.ui.components.ListSkeleton
import ir.talayar.app.ui.theme.Dimens

@Composable
fun FavoritesRoute(
    onAssetClick: (String) -> Unit,
    onBrowseMarket: () -> Unit,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    FavoritesScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onRemove = viewModel::removeFavorite,
        onAssetClick = onAssetClick,
        onBrowseMarket = onBrowseMarket,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    state: FavoritesViewModel.UiState,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
    onAssetClick: (String) -> Unit,
    onBrowseMarket: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "علاقه‌مندی‌ها",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(
                start = Dimens.ScreenPadding,
                end = Dimens.ScreenPadding,
                top = Dimens.SpaceL,
                bottom = Dimens.SpaceS,
            ),
        )
        ConnectionBanner(offline = state.offline, stale = state.isStale, updatedAt = state.updatedAt)

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            when {
                state.isLoading -> ListSkeleton(items = 4)

                state.favorites.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.FavoriteBorder,
                    title = "هنوز دارایی مورد علاقه‌ای ندارید",
                    message = "در صفحه «بازار» با لمس آیکن قلب، دارایی‌های مهم خود را ذخیره کنید تا قیمتشان همیشه دم دست باشد.",
                    actionLabel = "مشاهده بازار",
                    onAction = onBrowseMarket,
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
                    items(state.favorites, key = { it.symbol }) { asset ->
                        AssetRowCard(
                            asset = asset,
                            onClick = { onAssetClick(asset.symbol) },
                            onToggleFavorite = { onRemove(asset.symbol) },
                        )
                    }
                }
            }
        }
    }
}
