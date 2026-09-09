package ir.talayar.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ir.talayar.app.data.settings.ThemeMode
import ir.talayar.app.domain.model.PriceFormat
import ir.talayar.app.ui.navigation.TalayarApp
import ir.talayar.app.ui.settings.SettingsViewModel
import ir.talayar.app.ui.theme.TalayarTheme

/**
 * App root: resolves the theme (light/dark/system) + price format from
 * settings, forces the RTL layout direction (the app is Persian-only) and
 * mounts the navigation scaffold.
 */
@Composable
fun TalayarRoot(settingsViewModel: SettingsViewModel = hiltViewModel()) {
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()

    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    TalayarTheme(
        darkTheme = darkTheme,
        priceFormat = PriceFormat(persianDigits = true, unit = settings.priceUnit),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                TalayarApp()
            }
        }
    }
}
