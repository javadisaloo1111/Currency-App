package ir.talayar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ir.talayar.app.domain.model.AssetCategory

/**
 * Typographic category avatar (طلا / سکه / ارز / رمزارز) — image-free,
 * premium and lightweight.
 */
@Composable
fun CategoryAvatar(
    category: AssetCategory,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val (letter, tint) = when (category) {
        AssetCategory.GOLD -> "ط" to MaterialTheme.colorScheme.primaryContainer
        AssetCategory.COIN -> "س" to MaterialTheme.colorScheme.secondaryContainer
        AssetCategory.CURRENCY -> "ا" to MaterialTheme.colorScheme.surfaceVariant
        AssetCategory.CRYPTO -> "ر" to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
    }
    Box(
        modifier = modifier
            .size(size)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Larger hero avatar used on the detail screen. */
@Composable
fun CurrencyAvatarHeader(
    asset: ir.talayar.app.domain.model.MarketAsset,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = modifier.padding(top = 8.dp),
    ) {
        CategoryAvatar(category = asset.category, size = 64.dp)
        androidx.compose.material3.Text(
            text = asset.category.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
