package ir.talayar.app.domain.repository

import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import kotlinx.coroutines.flow.StateFlow

/** Outcome of a single update check. Every outcome is distinct — no umbrella "failed". */
sealed interface UpdateCheckResult {

    /**
     * A release was successfully read from an authoritative source and it is NOT
     * newer than the installed version (same version, or an older one).
     * This is the only result that may be rendered as «آخرین نسخه را دارید».
     */
    data object NoUpdate : UpdateCheckResult

    /** A newer official release exists and is installable. */
    data class Available(val update: AppUpdate) : UpdateCheckResult

    /**
     * The periodic check was skipped because the last *successful* check is still
     * inside the cache window. Deliberately not [NoUpdate]: nothing was verified,
     * so the UI must never claim "you are up to date" for it.
     */
    data object NotDueYet : UpdateCheckResult

    /** The check could not be completed. Carries the precise reason. */
    data class Error(val error: UpdateError) : UpdateCheckResult
}

/**
 * In-app update channel backed by this repository's official releases.
 *
 * Metadata is read from an ordered list of sources (GitHub REST API first, then
 * static mirrors of the same payload), so a single unreachable or rate-limited
 * host cannot take updates down. Periodic checks are rate limited; manual checks
 * always hit the network.
 */
interface UpdateRepository {

    /** Newest update found by the last successful check (null = none). */
    val availableUpdate: StateFlow<AppUpdate?>

    /**
     * Checks the official release channel for a newer version.
     *
     * @param force bypass the periodic-check cache (manual «بررسی بروزرسانی»).
     */
    suspend fun checkForUpdate(force: Boolean = false): UpdateCheckResult
}
