package ir.talayar.app.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.talayar.app.ui.detail.AssetDetailRoute
import ir.talayar.app.ui.favorites.FavoritesRoute
import ir.talayar.app.ui.home.HomeRoute
import ir.talayar.app.ui.market.MarketRoute
import ir.talayar.app.ui.settings.SettingsRoute

object Routes {
    const val HOME = "home"
    const val MARKET = "market"
    const val FAVORITES = "favorites"
    const val SETTINGS = "settings"
    const val ASSET_DETAIL = "asset/{symbol}"

    fun assetDetail(symbol: String): String = "asset/$symbol"
}

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.HOME, "خانه", Icons.Rounded.Home),
    TopLevelDestination(Routes.MARKET, "بازار", Icons.Rounded.Search),
    TopLevelDestination(Routes.FAVORITES, "علاقه‌مندی‌ها", Icons.Rounded.Favorite),
    TopLevelDestination(Routes.SETTINGS, "تنظیمات", Icons.Rounded.Settings),
)

/** Root scaffold: bottom navigation (4 tabs) + nav graph. */
@Composable
fun TalayarApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topLevelDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
            ) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                HomeRoute(onAssetClick = { symbol -> navController.navigate(Routes.assetDetail(symbol)) })
            }
            composable(Routes.MARKET) {
                MarketRoute(onAssetClick = { symbol -> navController.navigate(Routes.assetDetail(symbol)) })
            }
            composable(Routes.FAVORITES) {
                FavoritesRoute(
                    onAssetClick = { symbol -> navController.navigate(Routes.assetDetail(symbol)) },
                    onBrowseMarket = {
                        navController.navigate(Routes.MARKET) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsRoute()
            }
            composable(Routes.ASSET_DETAIL) {
                AssetDetailRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}
