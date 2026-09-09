package ir.talayar.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ir.talayar.app.domain.model.PriceFormat

// ---------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------

val TalayarShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ---------------------------------------------------------------------------
// Extended market colors (sentiment + brand) beyond the M3 scheme
// ---------------------------------------------------------------------------

@Immutable
data class MarketColors(
    val positive: Color,
    val onPositive: Color,
    val positiveContainer: Color,
    val negative: Color,
    val onNegative: Color,
    val negativeContainer: Color,
    val brand: Color,
    val brandDim: Color,
)

val LocalMarketColors = staticCompositionLocalOf {
    MarketColors(
        positive = LightPositive,
        onPositive = LightOnPositive,
        positiveContainer = LightPositiveContainer,
        negative = LightNegative,
        onNegative = LightOnNegative,
        negativeContainer = LightNegativeContainer,
        brand = Gold500,
        brandDim = Gold700,
    )
}

/** Price display format provided at the root (digits + unit from settings). */
val LocalPriceFormat = staticCompositionLocalOf { PriceFormat() }

object TalayarTheme {
    val market: MarketColors
        @Composable get() = LocalMarketColors.current

    val priceFormat: PriceFormat
        @Composable get() = LocalPriceFormat.current
}

// ---------------------------------------------------------------------------
// Color schemes
// ---------------------------------------------------------------------------

private val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightPositive,
    error = LightError,
    errorContainer = LightErrorContainer,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkPositive,
    error = DarkError,
    errorContainer = DarkErrorContainer,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
)

private val LightMarketColors = MarketColors(
    positive = LightPositive,
    onPositive = LightOnPositive,
    positiveContainer = LightPositiveContainer,
    negative = LightNegative,
    onNegative = LightOnNegative,
    negativeContainer = LightNegativeContainer,
    brand = Gold500,
    brandDim = Gold700,
)

private val DarkMarketColors = MarketColors(
    positive = DarkPositive,
    onPositive = DarkOnPositive,
    positiveContainer = DarkPositiveContainer,
    negative = DarkNegative,
    onNegative = DarkOnNegative,
    negativeContainer = DarkNegativeContainer,
    brand = Gold300,
    brandDim = Gold500,
)

/**
 * Centralized theme: light & dark, Material 3, Vazirmatn typography and the
 * extended market palette.
 */
@Composable
fun TalayarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    priceFormat: PriceFormat = PriceFormat(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val marketColors = if (darkTheme) DarkMarketColors else LightMarketColors

    CompositionLocalProvider(
        LocalMarketColors provides marketColors,
        LocalPriceFormat provides priceFormat,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TalayarTypography,
            shapes = TalayarShapes,
            content = content,
        )
    }
}
