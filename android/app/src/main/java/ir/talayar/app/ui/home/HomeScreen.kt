package ir.talayar.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.ui.components.AssetRowCard
import ir.talayar.app.ui.components.ChangeBadge
import ir.talayar.app.ui.components.ConnectionBanner
import ir.talayar.app.ui.components.EmptyState
import ir.talayar.app.ui.components.ErrorState
import ir.talayar.app.ui.components.FeaturedPriceCard
import ir.talayar.app.ui.components.HomeSkeleton
import ir.talayar.app.ui.components.LastUpdatedCaption
import ir.talayar.app.ui.components.PriceText
import ir.talayar.app.ui.components.SectionHeader
import ir.talayar.app.ui.theme.Dimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun HomeRoute(
    onAssetClick: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Foreground auto refresh (15/30/60s) — stops when the screen leaves composition.
    LaunchedEffect(state.refreshInterval) {
        val seconds = state.refreshInterval.seconds
        while (isActive && seconds > 0) {
            delay(seconds * 1000L)
            viewModel.refresh(silent = true)
        }
    }

    HomeScreen(
        state = state,
        onRefresh = { viewModel.refresh(silent = true) },
        onToggleFavorite = viewModel::toggleFavorite,
        onAssetClick = onAssetClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.UiState,
    onRefresh: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onAssetClick: (String) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.isLoading -> HomeSkeleton()

            state.assets.isEmpty() -> ErrorState(
                title = "قیمت‌ها در دسترس نیست",
                message = state.error ?: "اتصال به سرور برقرار نشد. اتصال اینترنت خود را بررسی کنید و دوباره تلاش کنید.",
                onRetry = onRefresh,
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    top = Dimens.SpaceL,
                    bottom = Dimens.SpaceXXL,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.SpaceS),
            ) {
                item(key = "header") { HomeHeader(state) }
                item(key = "banner") {
                    ConnectionBanner(offline = state.offline, stale = state.isStale, updatedAt = state.updatedAt)
                }
                state.featured?.let { featured ->
                    item(key = "featured") {
                        FeaturedPriceCard(asset = featured, onClick = { onAssetClick(featured.symbol) })
                    }
                }
                if (state.overview.isNotEmpty()) {
                    item(key = "overview") { OverviewRow(state.overview, onAssetClick) }
                }
                if (state.favorites.isNotEmpty()) {
                    item(key = "fav-header") { SectionHeader(title = "علاقه‌مندی‌های شما") }
                    items(state.favorites.take(3), key = { "fav-${it.symbol}" }) { asset ->
                        AssetRowCard(
                            asset = asset,
                            onClick = { onAssetClick(asset.symbol) },
                            onToggleFavorite = { onToggleFavorite(asset.symbol) },
                        )
                    }
                }
                if (state.topMovers.isNotEmpty()) {
                    item(key = "movers-header") { SectionHeader(title = "بیشترین تغییر امروز") }
                    items(state.topMovers, key = { "mover-${it.symbol}" }) { asset ->
                        AssetRowCard(
                            asset = asset,
                            onClick = { onAssetClick(asset.symbol) },
                            onToggleFavorite = { onToggleFavorite(asset.symbol) },
                        )
                    }
                }
                item(key = "footer") { Spacer(Modifier.height(Dimens.SpaceM)) }
            }
        }
    }
}

@Composable
private fun HomeHeader(state: HomeViewModel.UiState) {
    Column(modifier = Modifier.padding(vertical = Dimens.SpaceS)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("سلام 👋", style = MaterialTheme.typography.headlineSmall)
                Text("بازار امروز", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LastUpdatedCaption(updatedAt = state.updatedAt, isRefreshing = state.isRefreshing)
        }
    }
}

@Composable
private fun OverviewRow(assets: List<MarketAsset>, onAssetClick: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.SpaceS),
    ) {
        assets.take(3).forEach { asset ->
            MiniStatCard(asset = asset, onClick = { onAssetClick(asset.symbol) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStatCard(asset: MarketAsset, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = asset.name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            PriceText(
                price = asset.price,
                currency = asset.currency,
                style = MaterialTheme.typography.titleSmall,
            )
            ChangeBadge(direction = asset.direction, changePercent = asset.changePercent)
        }
    }
}

/** Empty favorites entry point reused by tests. */
@Composable
fun HomeEmptyFavoritesHint() {
    EmptyState(
        icon = androidx.compose.material.icons.Icons.Rounded.FavoriteBorder,
        title = "هنوز دارایی مورد علاقه‌ای ندارید",
        message = "با لمس آیکن قلب، دارایی‌های مهم خود را به علاقه‌مندی‌ها اضافه کنید.",
    )
}
