package ir.talayar.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ir.talayar.app.core.Formatters
import ir.talayar.app.domain.model.ChangeDirection
import ir.talayar.app.ui.theme.TalayarTheme

/**
 * Market-standard change badge: green pill with ↑ for gains, red pill with ↓
 * for losses, neutral pill with — when unchanged. Numeric text stays logically
 * ordered inside the RTL layout.
 */
@Composable
fun ChangeBadge(
    direction: ChangeDirection,
    changePercent: Double?,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val market = TalayarTheme.market
    val (container, content) = when (direction) {
        ChangeDirection.UP -> market.positiveContainer to market.positive
        ChangeDirection.DOWN -> market.negativeContainer to market.negative
        ChangeDirection.FLAT -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val arrow = when (direction) {
        ChangeDirection.UP -> "↑"
        ChangeDirection.DOWN -> "↓"
        ChangeDirection.FLAT -> "—"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = if (emphasized) container else container.copy(alpha = 0.65f),
        contentColor = content,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = arrow,
                style = if (emphasized) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            )
            Text(
                text = Formatters.percent(changePercent),
                style = if (emphasized) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
