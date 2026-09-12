package ir.talayar.app.data.update

import android.util.Log
import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.data.remote.ReleaseDto
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import ir.talayar.app.domain.model.VersionComparator
import ir.talayar.app.domain.repository.UpdateCheckResult
import ir.talayar.app.domain.repository.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Update channel backed by this repository's official GitHub releases.
 *
 * Flow (each step produces a distinct result — never a blanket "failed"):
 * ```
 * CHECKING → FETCH RELEASE (source list with bounded retry + failover)
 *          → PARSE RELEASE (draft/prerelease/tag sanity)
 *          → COMPARE VERSION (numeric semver)
 *               equal or older  → NoUpdate        («آخرین نسخه را دارید»)
 *               newer           → SELECT APK ASSET → Available (dialog)
 *          → any failure        → Error(kind)     (precise Persian copy)
 * ```
 *
 * Rules:
 *  - The APK is only ever accepted from the official release assets of this
 *    repository (unknown hosts are rejected — see [ReleaseAssetSelector]).
 *  - Version comparison is strictly numeric semver (1.0.10 > 1.0.9); an equal
 *    version never re-offers itself (no update loop) and an older "latest" is
 *    ignored (no downgrade).
 *  - «You are up to date» is only reported after a release was really read from a
 *    source. A skipped periodic check returns [UpdateCheckResult.NotDueYet] and a
 *    failure returns [UpdateCheckResult.Error]; neither claims freshness.
 *  - Automatic checks are rate limited (12 h); manual checks always hit the network.
 *  - Nothing here ever throws at the caller (except cancellation) and nothing here
 *    can disturb the price cache: failures stay inside the update channel.
 */
class UpdateRepositoryImpl(
    private val sources: List<ReleaseSource>,
    private val cache: UpdateCheckCache,
    private val installedVersion: String,
    private val connectivity: Connectivity = AlwaysOnlineConnectivity,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val totalBudgetMs: Long = DEFAULT_TOTAL_BUDGET_MS,
) : UpdateRepository {

    /** Convenience constructor: default source list (GitHub API + static mirrors). */
    constructor(
        releaseApi: ReleaseApi,
        cache: UpdateCheckCache,
        installedVersion: String,
        clock: () -> Long = System::currentTimeMillis,
    ) : this(
        defaultReleaseSources(releaseApi),
        cache,
        installedVersion,
        AlwaysOnlineConnectivity,
        clock,
    )

    private val _availableUpdate = MutableStateFlow<AppUpdate?>(null)
    override val availableUpdate: StateFlow<AppUpdate?> = _availableUpdate

    override suspend fun checkForUpdate(force: Boolean): UpdateCheckResult {
        if (!force) {
            val elapsed = clock() - cache.lastCheckAt()
            if (elapsed in 0 until CHECK_INTERVAL_MS) {
                // Honest "not checked" — the UI must not turn this into "you are up to date".
                return UpdateCheckResult.NotDueYet
            }
        }

        val online = isDeviceOnline()
        if (!online) {
            val error = UpdateError(
                UpdateErrorKind.NO_INTERNET,
                "connectivity monitor reports no network",
            )
            Log.w(TAG, "update check not attempted: $error")
            return UpdateCheckResult.Error(error)
        }

        // A manual check must re-notify the dialog even when it finds the very same
        // release again (StateFlow would otherwise de-duplicate the identical value
        // and the dialog would stay hidden after the user postponed it once).
        if (force) _availableUpdate.value = null

        val deadlineAt = clock() + totalBudgetMs
        // The FIRST failure is the one reported when no source succeeds: the list is
        // ordered by authority, so the GitHub API's diagnosis ("rate limited") is more
        // truthful than a mirror's ("not found" — the mirror may simply be stale).
        var firstError: UpdateError? = null

        for (source in sources) {
            if (clock() >= deadlineAt) {
                firstError = firstError ?: UpdateError(
                    UpdateErrorKind.TIMEOUT,
                    "time budget of ${totalBudgetMs}ms exhausted before trying '${source.id}'",
                )
                break
            }
            when (val fetch = fetchWithRetry(source, online, deadlineAt)) {
                is Fetch.Bad -> {
                    // Fail over: the next source may be reachable even when this one is not.
                    firstError = firstError ?: fetch.error
                }

                is Fetch.Ok -> when (val decision = evaluate(fetch.dto, source.id)) {
                    is Decision.Unusable -> firstError = firstError ?: decision.error
                    is Decision.Final -> {
                        cache.setLastCheckAt(clock())
                        Log.i(TAG, "update check via '${source.id}' -> ${decision.result}")
                        return decision.result
                    }
                }
            }
        }

        // Every source failed. The cache is deliberately NOT touched, so the next
        // automatic check retries, and no stale update is presented as fresh.
        val error = firstError ?: UpdateError(
            UpdateErrorKind.CONNECTION_FAILED,
            "no release source answered",
        )
        Log.w(TAG, "update check failed on all ${sources.size} source(s): $error")
        return UpdateCheckResult.Error(error)
    }

    // ---------------------------------------------------------------------
    // fetch
    // ---------------------------------------------------------------------

    private sealed interface Fetch {
        data class Ok(val dto: ReleaseDto) : Fetch
        data class Bad(val error: UpdateError) : Fetch
    }

    /**
     * Fetches from one source with a bounded retry (linear backoff) for transient
     * failures only. A permanent answer (404, unparsable body, offline) moves on
     * immediately instead of burning the time budget.
     */
    private suspend fun fetchWithRetry(
        source: ReleaseSource,
        online: Boolean,
        deadlineAt: Long,
    ): Fetch {
        var lastError: UpdateError = UpdateError(UpdateErrorKind.UNKNOWN, "no attempt made")
        for (attempt in 1..MAX_ATTEMPTS_PER_SOURCE) {
            if (attempt > 1) {
                val backoff = BACKOFF_BASE_MS * (attempt - 1)
                if (clock() + backoff >= deadlineAt) break
                sleeper(backoff)
            }
            try {
                return Fetch.Ok(source.latest())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                lastError = classifyUpdateFailure(t, online)
                Log.w(
                    TAG,
                    "release source '${source.id}' attempt $attempt/$MAX_ATTEMPTS_PER_SOURCE failed: $lastError",
                )
                if (!lastError.retryable) break
            }
        }
        return Fetch.Bad(lastError)
    }

    // ---------------------------------------------------------------------
    // parse + decide
    // ---------------------------------------------------------------------

    private sealed interface Decision {
        /** A definitive answer: cache the check time and return it to the caller. */
        data class Final(val result: UpdateCheckResult) : Decision

        /** This payload was unusable; the next source should be tried. */
        data class Unusable(val error: UpdateError) : Decision
    }

    private fun evaluate(dto: ReleaseDto, sourceId: String): Decision {
        if (dto.draft || dto.prerelease) {
            return Decision.Unusable(
                UpdateError(
                    UpdateErrorKind.RELEASE_NOT_FOUND,
                    "source '$sourceId' returned a draft/prerelease",
                ),
            )
        }

        val tag = dto.tagName.trim()
        val latest = VersionComparator.canonical(tag)
        if (tag.isBlank() || latest == null) {
            return Decision.Unusable(
                UpdateError(
                    UpdateErrorKind.RELEASE_NOT_FOUND,
                    "source '$sourceId' returned an unparsable tag '$tag'",
                ),
            )
        }

        // Equal version -> no update loop. Older version -> no downgrade.
        if (!VersionComparator.isNewer(tag, installedVersion)) {
            _availableUpdate.value = null
            return Decision.Final(UpdateCheckResult.NoUpdate)
        }

        val asset = ReleaseAssetSelector.select(tag, dto.assets)
        if (asset == null) {
            return Decision.Final(
                UpdateCheckResult.Error(
                    UpdateError(
                        UpdateErrorKind.APK_NOT_FOUND,
                        "release $tag has ${dto.assets.size} asset(s) and none is an installable " +
                            "APK from ${UpdateEndpoints.OFFICIAL_ASSET_PREFIX}",
                    ),
                ),
            )
        }

        val update = AppUpdate(
            latestVersion = latest,
            apkUrl = asset.browserDownloadUrl,
            apkSize = asset.size,
            sha256Url = checksumUrlFor(asset, dto),
            sha256 = digestSha256Of(asset),
            forced = isBelowSupportedMinimum(dto.body),
        )
        Log.i(
            TAG,
            "update available: $tag (installed $installedVersion) asset='${asset.name}' " +
                "size=${asset.size} sha256=${update.sha256Url != null}",
        )
        _availableUpdate.value = update
        return Decision.Final(UpdateCheckResult.Available(update))
    }

    /** URL of the published ".sha256" sidecar, when it exists and comes from the official host. */
    private fun checksumUrlFor(apk: ReleaseAssetDto, dto: ReleaseDto): String? {
        val expected = "${apk.name.trim()}.sha256"
        return dto.assets.firstOrNull { candidate ->
            expected.equals(candidate.name.trim(), ignoreCase = true) &&
                candidate.browserDownloadUrl.startsWith(UpdateEndpoints.OFFICIAL_ASSET_PREFIX)
        }?.browserDownloadUrl
    }

    /**
     * SHA-256 hex from the digest the release service publishes for the asset
     * (`sha256:<hex>`). Anything that is not exactly 64 hex characters is ignored
     * rather than trusted: a wrong expectation would block a good download.
     */
    private fun digestSha256Of(asset: ReleaseAssetDto): String? {
        val digest = asset.digest?.trim()?.lowercase() ?: return null
        val hex = digest.removePrefix("sha256:")
        return if (SHA256_HEX.matches(hex)) hex else null
    }

    /**
     * Force-update support: release notes may carry a line like
     * "minimum_supported_version: 1.0.0". When the installed version is below that
     * minimum the dialog is non-dismissable. Inactive for releases without it.
     */
    private fun isBelowSupportedMinimum(releaseBody: String?): Boolean {
        val match = MINIMUM_VERSION.find(releaseBody ?: return false) ?: return false
        val minimum = match.groupValues[1]
        return VersionComparator.compare(installedVersion, minimum) < 0
    }

    /**
     * Connectivity snapshot. A monitor that never answers must not block the check,
     * so the probe is time-boxed and optimistic.
     */
    private suspend fun isDeviceOnline(): Boolean = try {
        withTimeoutOrNull(CONNECTIVITY_PROBE_MS) { connectivity.isOnline.first() } ?: true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (t: Throwable) {
        Log.w(TAG, "connectivity probe failed, assuming online: ${t.javaClass.simpleName}")
        true
    }

    companion object {
        private const val TAG = "UpdateRepository"

        /** Automatic checks run at most once every 12 hours. */
        const val CHECK_INTERVAL_MS: Long = 12L * 60 * 60 * 1000

        /** Bounded retries per source before failing over (never infinite). */
        private const val MAX_ATTEMPTS_PER_SOURCE = 2
        private const val BACKOFF_BASE_MS = 700L

        /** Hard ceiling for one whole check, so a filtered network can never hang the UI. */
        const val DEFAULT_TOTAL_BUDGET_MS: Long = 45_000L

        /** How long the connectivity monitor gets before we optimistically assume online. */
        private const val CONNECTIVITY_PROBE_MS: Long = 1_500L

        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        private val MINIMUM_VERSION = Regex(
            """minimum_supported_version\s*[:=]\s*v?(\d+\.\d+\.\d+)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
