package ir.talayar.app.domain.repository

import ir.talayar.app.domain.model.AppUpdate
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a single update check. */
sealed interface UpdateCheckResult {
    /** Latest release is not newer than the installed version (or unparsable). */
    data object NoUpdate : UpdateCheckResult

    /** A newer official release exists. */
    data class Available(val update: AppUpdate) : UpdateCheckResult

    /** The update service is unreachable — must never disturb normal app usage. */
    data object Failed : UpdateCheckResult
}

/**
 * In-app update channel backed by this repository's official releases.
 * Checks are cached (periodic auto-checks are rate limited); manual checks
 * bypass the cache.
 */
interface UpdateRepository {

    /** Newest update found by the last successful check (null = none). */
    val availableUpdate: StateFlow<AppUpdate?>

    /**
     * Checks the official release channel for a newer version.
     *
     * @param force bypass the periodic-check cache (manual "بررسی بروزرسانی").
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateCheckResult
}
