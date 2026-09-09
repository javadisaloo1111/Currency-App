package ir.talayar.app.domain.usecase

import ir.talayar.app.domain.model.AlertRule
import ir.talayar.app.domain.model.TriggeredAlert
import ir.talayar.app.domain.repository.MarketRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** Stream alert rules (all or per symbol). */
class ObserveAlertsUseCase @Inject constructor(private val repository: MarketRepository) {
    operator fun invoke(): Flow<List<AlertRule>> = repository.observeAlerts()
    operator fun invoke(symbol: String): Flow<List<AlertRule>> = repository.observeAlertsFor(symbol)
}

/** Create a price alert. */
class AddAlertUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(rule: AlertRule) = repository.addAlert(rule)
}

/** Delete a price alert. */
class RemoveAlertUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(id: Long) = repository.removeAlert(id)
}

/**
 * Evaluate all enabled alerts against the latest prices; returns the rules
 * that fired so the caller can post notifications.
 */
class EvaluateAlertsUseCase @Inject constructor(private val repository: MarketRepository) {
    suspend operator fun invoke(): List<TriggeredAlert> = repository.evaluateAlerts()
}
