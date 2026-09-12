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
 * user's digit settings (LocalPriceFormat). All prices are Toman (canonical).
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
        text = Formatters.price(price, currency = currency, persianDigits = format.persianDigits),
        modifier = modifier,
        style = style,
        textAlign = textAlign,
        maxLines = 1,
    )
}

/** Currency label for a quote ("تومان"; defensive USD fallback). */
@Composable
fun currencyLabel(currency: String): String {
    return if (currency.equals("USD", ignoreCase = true)) "دلار" else "تومان"
}
