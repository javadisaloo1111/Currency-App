package ir.talayar.app.data.update

import android.util.Log
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.VersionComparator
import ir.talayar.app.domain.repository.UpdateCheckResult
import ir.talayar.app.domain.repository.UpdateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Update channel backed by this repository's official GitHub releases.
 *
 * Rules:
 *  - The APK is only ever accepted from the official release assets of this
 *    repository (unknown URLs are rejected).
 *  - Version comparison is strictly numeric-semver (1.0.10 > 1.0.9) and never
 *    downgrades (an older "latest" is ignored).
 *  - Checks are cached: automatic checks run at most once every 12 hours.
 *  - Any failure is swallowed (logged) — the app keeps working normally.
 */
class UpdateRepositoryImpl(
    private val releaseApi: ReleaseApi,
    private val cache: UpdateCheckCache,
    private val installedVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : UpdateRepository {

    private val _availableUpdate = MutableStateFlow<AppUpdate?>(null)
    override val availableUpdate: StateFlow<AppUpdate?> = _availableUpdate

    override suspend fun checkForUpdate(force: Boolean): UpdateCheckResult {
        if (!force) {
            val elapsed = clock() - cache.lastCheckAt()
            if (elapsed in 0 until CHECK_INTERVAL_MS) return UpdateCheckResult.NoUpdate
        }
        val dto = try {
            releaseApi.latestRelease(LATEST_RELEASE_URL)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "update check failed: ${t.message}")
            return UpdateCheckResult.Failed
        }

        cache.setLastCheckAt(clock())

        val latest = dto.tagName
        // Downgrade protection + same-version: only strictly newer releases count.
        if (VersionComparator.compare(latest, installedVersion) <= 0) {
            _availableUpdate.value = null
            return UpdateCheckResult.NoUpdate
        }

        val apkAsset = findApkAsset(dto.assets)
        if (apkAsset == null || !apkAsset.browserDownloadUrl.startsWith(OFFICIAL_ASSET_PREFIX)) {
            Log.w(TAG, "no official APK asset on release $latest — ignoring")
            _availableUpdate.value = null
            return UpdateCheckResult.NoUpdate
        }

        val update = AppUpdate(
            latestVersion = latest.removePrefix("v"),
            apkUrl = apkAsset.browserDownloadUrl,
            apkSize = apkAsset.size,
            sha256Url = dto.assets.firstOrNull { it.name == "${apkAsset.name}.sha256" }
                ?.browserDownloadUrl
                ?.takeIf { it.startsWith(OFFICIAL_ASSET_PREFIX) },
            forced = isBelowSupportedMinimum(dto.body),
        )
        _availableUpdate.value = update
        return UpdateCheckResult.Available(update)
    }

    private fun findApkAsset(assets: List<ReleaseAssetDto>): ReleaseAssetDto? =
        assets.firstOrNull { it.name.startsWith("app-release") && it.name.endsWith(".apk") }
            ?: assets.firstOrNull { it.name.endsWith(".apk") }

    /**
     * Force-update support: release notes may carry a line like
     * "minimum_supported_version: 1.0.0". When the installed version is below
     * that minimum, the update dialog is non-dismissable. Inactive for releases
     * that don't declare a minimum.
     */
    private fun isBelowSupportedMinimum(releaseBody: String?): Boolean {
        val match = MINIMUM_VERSION.find(releaseBody ?: return false) ?: return false
        val minimum = match.groupValues[1]
        return VersionComparator.compare(installedVersion, minimum) < 0
    }

    companion object {
        private const val TAG = "UpdateRepository"

        /** Automatic checks run at most once every 12 hours. */
        const val CHECK_INTERVAL_MS: Long = 12L * 60 * 60 * 1000

        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/javadisaloo1111/Currency-App/releases/latest"

        /** Only assets served from this repository's releases may be installed. */
        private const val OFFICIAL_ASSET_PREFIX =
            "https://github.com/javadisaloo1111/Currency-App/releases/download/"

        private val MINIMUM_VERSION = Regex(
            """minimum_supported_version\s*[:=]\s*v?(\d+\.\d+\.\d+)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
