package ir.talayar.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.talayar.app.core.Formatters
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.ui.theme.Gold100
import ir.talayar.app.ui.theme.Gold500
import ir.talayar.app.ui.theme.Gold700

/**
 * The hero card of the home screen — a gold gradient card with the featured
 * asset (18k gold), its price, change and last-update time.
 */
@Composable
fun FeaturedPriceCard(
    asset: MarketAsset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val format = ir.talayar.app.ui.theme.TalayarTheme.priceFormat
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(listOf(Gold700, Gold500, Gold700)),
                    RoundedCornerShape(24.dp),
                )
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = asset.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFFFF6DE),
                    )
                    if (asset.isStale) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Black.copy(alpha = 0.25f),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(
                                text = "آخرین قیمت معتبر",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFFFF6DE),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        text = Formatters.price(
                            asset.price,
                            currency = asset.currency,
                            persianDigits = format.persianDigits,
                        ),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                    )
                    Text(
                        text = " ${currencyLabel(asset.currency)}${if (asset.unit != null) " / ${asset.unit}" else ""}",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFFF4D98B),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    ChangeBadge(direction = asset.direction, change = asset.change, emphasized = true)
                    Text(
                        text = "بروزرسانی ${Formatters.relative(asset.updatedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFEFE0B4),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
