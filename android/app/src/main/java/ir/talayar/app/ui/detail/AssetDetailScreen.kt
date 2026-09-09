package ir.talayar.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.core.Formatters
import ir.talayar.app.domain.model.AlertKind
import ir.talayar.app.ui.components.ChangeBadge
import ir.talayar.app.ui.components.CurrencyAvatarHeader
import ir.talayar.app.ui.components.MarketChart
import ir.talayar.app.ui.components.PriceText
import ir.talayar.app.ui.components.RangeChips
import ir.talayar.app.ui.components.SectionHeader
import ir.talayar.app.ui.components.SkeletonBox
import ir.talayar.app.ui.components.currencyLabel
import ir.talayar.app.ui.theme.Dimens

@Composable
fun AssetDetailRoute(
    onBack: () -> Unit,
    viewModel: AssetDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    AssetDetailScreen(
        state = state,
        onBack = onBack,
        onToggleFavorite = viewModel::toggleFavorite,
        onRangeChange = viewModel::setRange,
        onRefresh = viewModel::refresh,
        onAddAlert = { showSheet = true },
        onDeleteAlert = viewModel::deleteAlert,
    )

    if (showSheet) {
        AlertSheet(
            assetName = state.asset?.name ?: "",
            unitLabel = state.asset?.let { currencyLabel(it.currency) } ?: "تومان",
            onDismiss = { showSheet = false },
            onConfirm = { kind, threshold ->
                val error = viewModel.createAlert(kind, threshold)
                if (error == null) showSheet = false
                error
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssetDetailScreen(
    state: AssetDetailViewModel.UiState,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRangeChange: (ir.talayar.app.domain.model.HistoryRange) -> Unit,
    onRefresh: () -> Unit,
    onAddAlert: () -> Unit,
    onDeleteAlert: (Long) -> Unit,
) {
    val asset = state.asset

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(asset?.name ?: "جزئیات دارایی") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "بازگشت",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            imageVector = if (state.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = if (state.isFavorite) "حذف از علاقه‌مندی‌ها" else "افزودن به علاقه‌مندی‌ها",
                            tint = if (state.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (asset == null) {
            DetailSkeleton(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Dimens.SpaceXXL),
        ) {
            // header
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.ScreenPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CurrencyAvatarHeader(asset = asset)
                    Text(
                        text = asset.name,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = Dimens.SpaceM),
                    )
                    if (asset.isStale) {
                        Text(
                            text = "آخرین قیمت معتبر (سرور موقتاً به‌روز نیست)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(top = Dimens.SpaceS),
                    ) {
                        PriceText(
                            price = asset.price,
                            currency = asset.currency,
                            style = MaterialTheme.typography.displayMedium,
                        )
                    }
                    Text(
                        text = "${currencyLabel(asset.currency)}${if (asset.unit != null) " / ${asset.unit}" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = Dimens.SpaceS, bottom = Dimens.SpaceM),
                        horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM),
                    ) {
                        ChangeBadge(
                            direction = asset.direction,
                            changePercent = asset.changePercent,
                            emphasized = true,
                        )
                        Text(
                            text = Formatters.changeAmount(asset.change),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // chart
            item(key = "chart") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    RangeChips(
                        selected = state.range,
                        onSelect = onRangeChange,
                        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                    )
                    if (state.historyLoading) {
                        SkeletonBox(
                            Modifier
                                .fillMaxWidth()
                                .padding(Dimens.SpaceL)
                                .height(Dimens.ChartHeight),
                            shape = MaterialTheme.shapes.large,
                        )
                    } else {
                        val points = state.history?.ranges?.get(state.range.id).orEmpty()
                        MarketChart(
                            points = points,
                            useDateLabels = state.range.windowMs > 24 * 60 * 60 * 1000,
                        )
                        if (!state.historyFromNetwork) {
                            Text(
                                text = "نمودار بر اساس آخرین داده‌های ذخیره‌شده روی دستگاه",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            // stats
            item(key = "stats") {
                StatsCard(asset = asset, onRefresh = onRefresh)
            }

            // alerts
            item(key = "alerts-header") {
                SectionHeader(
                    title = "هشدارهای قیمت",
                    modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                )
            }
            if (state.alerts.isEmpty()) {
                item(key = "alerts-empty") {
                    Text(
                        text = "هیچ هشداری برای این دارایی ثبت نشده است.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Dimens.ScreenPadding),
                    )
                }
            } else {
                items(state.alerts.size) { index ->
                    AlertRow(
                        alert = state.alerts[index],
                        unitLabel = currencyLabel(asset.currency),
                        onDelete = { onDeleteAlert(state.alerts[index].id) },
                    )
                }
            }
            item(key = "add-alert") {
                Button(
                    onClick = onAddAlert,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimens.ScreenPadding,
                            end = Dimens.ScreenPadding,
                            top = Dimens.SpaceL,
                        ),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.height(Dimens.SpaceS))
                    Text("افزودن هشدار قیمت")
                }
            }
        }
    }
}

@Composable
private fun StatsCard(asset: ir.talayar.app.domain.model.MarketAsset, onRefresh: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceL),
    ) {
        Column(modifier = Modifier.padding(Dimens.CardPadding)) {
            SectionHeader(title = "آمار بازار")
            StatRow("بالاترین قیمت امروز", Formatters.price(asset.dayHigh ?: asset.price) + " ${currencyLabel(asset.currency)}")
            StatRow("پایین‌ترین قیمت امروز", Formatters.price(asset.dayLow ?: asset.price) + " ${currencyLabel(asset.currency)}")
            StatRow("قیمت قبلی", Formatters.price(asset.prevPrice ?: asset.price) + " ${currencyLabel(asset.currency)}")
            StatRow("آخرین بروزرسانی", if (asset.updatedAt > 0) "${Formatters.time(asset.updatedAt)} (${Formatters.relative(asset.updatedAt)})" else "—")
            StatRow("منبع قیمت", if (asset.source.isBlank()) "—" else asset.source)
            OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(top = Dimens.SpaceS)) {
                Text("بروزرسانی")
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AlertRow(
    alert: ir.talayar.app.domain.model.AlertRule,
    unitLabel: String,
    onDelete: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding, vertical = Dimens.SpaceXS),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(alert.kind.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (alert.kind == AlertKind.PERCENT) {
                        "${Formatters.percent(alert.threshold)} تغییر"
                    } else {
                        "${Formatters.price(alert.threshold)} $unitLabel"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = "حذف هشدار",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DetailSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(120.dp),
            shape = MaterialTheme.shapes.large,
        )
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .padding(top = Dimens.SpaceL)
                .height(Dimens.ChartHeight),
            shape = MaterialTheme.shapes.large,
        )
    }
}
