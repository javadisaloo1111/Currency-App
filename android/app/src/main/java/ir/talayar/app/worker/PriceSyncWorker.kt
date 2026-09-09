package ir.talayar.app.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ir.talayar.app.domain.usecase.EvaluateAlertsUseCase
import ir.talayar.app.domain.usecase.RefreshPricesUseCase
import ir.talayar.app.notifications.PriceAlertNotifier

/**
 * Background market sync: refreshes prices into Room (offline cache stays
 * warm) and evaluates price alerts. Runs every ~15 minutes while the device
 * has network — keeping alerts working even when the app is closed.
 */
@HiltWorker
class PriceSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val refreshPrices: RefreshPricesUseCase,
    private val evaluateAlerts: EvaluateAlertsUseCase,
    private val notifier: PriceAlertNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val refresh = refreshPrices()
        if (refresh.isFailure) {
            // Offline gateway: keep the last cached prices, retry later.
            if (runAttemptCount >= MAX_ATTEMPTS) return Result.success() // don't loop forever
            return Result.retry()
        }

        val triggered = evaluateAlerts()
        for (alert in triggered) {
            notifier.notifyAlert(alert)
        }
        return Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
