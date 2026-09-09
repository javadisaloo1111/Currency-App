package ir.talayar.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import ir.talayar.app.core.Formatters
import ir.talayar.app.ui.theme.TalayarTheme

/**
 * Formats a price with grouping separators + Persian digits according to the
 * user's unit/digit settings (LocalPriceFormat). USD assets (global ounce)
 * ignore the Toman/Rial setting.
 */
@Composable
fun PriceText(
    price: Double,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    textAlign: TextAlign? = null,
) {
    val format = TalayarTheme.priceFormat
    Text(
        text = Formatters.price(price, unit = format.unit, currency = currency, persianDigits = format.persianDigits),
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        maxLines = 1,
    )
}

/** Currency label for a quote ("تومان", "ریال" or "$"). */
@Composable
fun currencyLabel(currency: String): String {
    val format = TalayarTheme.priceFormat
    return when {
        currency.equals("USD", ignoreCase = true) -> "دلار"
        else -> if (format.unit.tomanFactor == 10.0) "ریال" else "تومان"
    }
}
