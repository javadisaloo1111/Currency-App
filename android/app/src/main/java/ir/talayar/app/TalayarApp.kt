package ir.talayar.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Hilt owns dependency injection; WorkManager is initialized on demand with the
 * Hilt-aware [HiltWorkerFactory] so background workers can receive dependencies.
 */
@HiltAndroidApp
class TalayarApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
