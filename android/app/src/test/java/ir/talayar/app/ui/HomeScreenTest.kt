package ir.talayar.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import ir.talayar.app.domain.model.AssetCategory
import ir.talayar.app.domain.model.MarketAsset
import ir.talayar.app.ui.home.HomeScreen
import ir.talayar.app.ui.home.HomeViewModel
import ir.talayar.app.ui.theme.TalayarTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI tests running on the JVM via Robolectric:
 *  - renders prices & change badges from a loaded state
 *  - dark mode smoke test
 *  - error state with retry callback
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "fa-rIR-w411dp-h900dp")
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun asset(
        symbol: String,
        name: String,
        category: AssetCategory,
        price: Double,
        changePercent: Double?,
    ) = MarketAsset(
        symbol = symbol,
        name = name,
        category = category,
        price = price,
        change = 100_000.0,
        changePercent = changePercent,
        updatedAt = 1_757_400_751_000,
        isStale = false,
    )

    private fun loadedState() = HomeViewModel.UiState(
        isLoading = false,
        assets = listOf(
            asset("GOLD_18K", "طلای ۱۸ عیار", AssetCategory.GOLD, 6_703_000.0, 1.75),
            asset("USD", "دلار آمریکا", AssetCategory.CURRENCY, 104_850.0, -0.21),
            asset("COIN_EMAMI", "سکه امامی", AssetCategory.COIN, 60_150_000.0, 0.59),
        ),
        // Featured asset is intentionally NOT repeated in overview/topMovers so
        // text nodes stay unique for semantics lookups.
        featured = asset("GOLD_18K", "طلای ۱۸ عیار", AssetCategory.GOLD, 6_703_000.0, 1.75),
        overview = listOf(
            asset("USD", "دلار آمریکا", AssetCategory.CURRENCY, 104_850.0, -0.21),
            asset("COIN_EMAMI", "سکه امامی", AssetCategory.COIN, 60_150_000.0, 0.59),
        ),
        topMovers = listOf(
            asset("COIN_EMAMI", "سکه امامی", AssetCategory.COIN, 60_150_000.0, 0.59),
        ),
        updatedAt = 1_757_400_751_000,
    )

    @Test
    fun `home screen renders greeting featured card and sections`() {
        composeRule.setContent {
            TalayarTheme(darkTheme = false) {
                HomeScreen(
                    state = loadedState(),
                    onRefresh = {},
                    onToggleFavorite = {},
                    onAssetClick = {},
                )
            }
        }

        composeRule.onNodeWithText("سلام جواد عیسی لو").assertIsDisplayed()
        composeRule.onNodeWithText("بازار امروز").assertIsDisplayed()
        composeRule.onNodeWithText("طلای ۱۸ عیار").assertIsDisplayed()
        composeRule.onNodeWithText("بیشترین تغییر امروز").assertIsDisplayed()
        composeRule.onNodeWithText("آخرین بروزرسانی", substring = true).assertIsDisplayed()
    }

    @Test
    fun `home screen renders in dark mode`() {
        composeRule.setContent {
            TalayarTheme(darkTheme = true) {
                HomeScreen(
                    state = loadedState(),
                    onRefresh = {},
                    onToggleFavorite = {},
                    onAssetClick = {},
                )
            }
        }
        composeRule.onNodeWithText("طلای ۱۸ عیار").assertIsDisplayed()
    }

    @Test
    fun `error state shows message and retry invokes callback`() {
        var retryClicked = false
        composeRule.setContent {
            TalayarTheme(darkTheme = false) {
                HomeScreen(
                    state = HomeViewModel.UiState(
                        isLoading = false,
                        assets = emptyList(),
                        error = "اتصال به سرور برقرار نشد",
                    ),
                    onRefresh = { retryClicked = true },
                    onToggleFavorite = {},
                    onAssetClick = {},
                )
            }
        }

        composeRule.onNodeWithText("قیمت‌ها در دسترس نیست").assertIsDisplayed()
        composeRule.onNodeWithText("تلاش مجدد").performClick()
        composeRule.waitForIdle()
        assertTrue(retryClicked)
    }
}
