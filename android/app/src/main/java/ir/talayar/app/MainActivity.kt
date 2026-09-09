package ir.talayar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Root UI is wired in ui/TalayarRoot.kt once the design system lands.
            PlaceholderRoot()
        }
    }
}

@androidx.compose.runtime.Composable
private fun PlaceholderRoot() {
    androidx.compose.material3.Surface {
        androidx.compose.material3.Text(text = "طلایار")
    }
}
