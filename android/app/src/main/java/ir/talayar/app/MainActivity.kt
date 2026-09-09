package ir.talayar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import ir.talayar.app.ui.TalayarRoot
import ir.talayar.app.worker.WorkScheduler

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the offline cache warm + alerts working while the app is closed.
        WorkScheduler.ensurePeriodicSync(this)

        setContent {
            TalayarRoot()
        }
    }
}
