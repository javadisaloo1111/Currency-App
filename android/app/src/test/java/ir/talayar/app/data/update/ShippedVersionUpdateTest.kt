package ir.talayar.app.data.update

import ir.talayar.app.BuildConfig
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.data.remote.ReleaseDto
import ir.talayar.app.domain.model.VersionComparator
import ir.talayar.app.domain.repository.UpdateCheckResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the version THIS build ships.
 *
 * Whatever `APP_VERSION_NAME` the build carries, the update channel must:
 *  - offer the next release, resolving the exact asset name/URL the release
 *    workflow publishes (`app-release-v<x.y.z>.apk` + its `.sha256` sidecar),
 *  - stay silent for the very same version (no update loop after upgrading),
 *  - stay silent for older releases (no downgrade),
 *  - answer «not due yet» (never «you are up to date») for a skipped periodic check.
 *
 * The expectations are derived from [BuildConfig.APP_VERSION_NAME], so the test
 * keeps protecting future releases without being edited on every version bump.
 */
class ShippedVersionUpdateTest {

    private val shipped: String = BuildConfig.APP_VERSION_NAME
    private val parts: IntArray = requireNotNull(VersionComparator.parse(shipped)) {
        "APP_VERSION_NAME must be a parsable x.y.z version, was '$shipped'"
    }
    private val nextVersion: String = "${parts[0]}.${parts[1]}.${parts[2] + 1}"
    private val previousVersion: String = "${parts[0]}.${parts[1]}.${parts[2] - 1}"

    private var now: Long = 1_700_000_000_000L
    private val api = FakeReleaseApi()
    private val cache = FakeUpdateCheckCache()

    private fun repository() = UpdateRepositoryImpl(api, cache, shipped) { now }

    /** Mirrors the assets `.github/workflows/android-release.yml` publishes. */
    private fun publishedRelease(version: String) = ReleaseDto(
        tagName = "v$version",
        name = "Gold Market Android v$version",
        body = "# طلایار\nفایل `app-release-v$version.apk` را دانلود و نصب کنید.",
        assets = listOf(
            ReleaseAssetDto(
                name = "app-release-v$version.apk",
                browserDownloadUrl = "$ASSET_BASE/v$version/app-release-v$version.apk",
                size = 8_700_000,
                contentType = "application/vnd.android.package-archive",
                // GitHub publishes a digest for every uploaded release asset; the
                // updater uses it as the expected checksum when there is no sidecar.
                digest = "sha256:$DIGEST_HEX",
            ),
            ReleaseAssetDto(
                name = "app-release-v$version.apk.sha256",
                browserDownloadUrl = "$ASSET_BASE/v$version/app-release-v$version.apk.sha256",
                size = 89,
                contentType = "application/octet-stream",
            ),
        ),
    )

    @Test
    fun `shipped version is a parsable semver the comparator understands`() {
        assertNotNull(VersionComparator.parse(shipped))
        assertEquals(0, VersionComparator.compare(shipped, "v$shipped"))
    }

    @Test
    fun `the next release is detected as an update with the official apk url`() = runTest {
        api.dto = publishedRelease(nextVersion)

        val result = repository().checkForUpdate(force = true)

        assertTrue("expected Available, got $result", result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals(nextVersion, update.latestVersion)
        assertEquals("$ASSET_BASE/v$nextVersion/app-release-v$nextVersion.apk", update.apkUrl)
        assertEquals(8_700_000L, update.apkSize)
        assertEquals("$ASSET_BASE/v$nextVersion/app-release-v$nextVersion.apk.sha256", update.sha256Url)
        assertEquals(DIGEST_HEX, update.sha256)
        assertTrue(!update.forced)
        assertTrue(VersionComparator.compare("v$nextVersion", shipped) > 0)
    }

    @Test
    fun `the shipped version itself never offers an update (no update loop)`() = runTest {
        api.dto = publishedRelease(shipped)
        val repository = repository()

        repeat(3) {
            val result = repository.checkForUpdate(force = true)
            assertTrue("expected NoUpdate, got $result", result is UpdateCheckResult.NoUpdate)
            assertNull(repository.availableUpdate.value)
        }
    }

    @Test
    fun `an older release is never offered (no downgrade)`() = runTest {
        api.dto = publishedRelease(previousVersion)
        val repository = repository()

        val result = repository.checkForUpdate(force = true)

        assertTrue("expected NoUpdate, got $result", result is UpdateCheckResult.NoUpdate)
        assertNull(repository.availableUpdate.value)
        assertTrue(VersionComparator.compare("v$previousVersion", shipped) < 0)
    }

    @Test
    fun `after upgrading, the offered release stops being offered`() = runTest {
        // Installed = shipped, latest = next -> update offered.
        api.dto = publishedRelease(nextVersion)
        assertTrue(repository().checkForUpdate(force = true) is UpdateCheckResult.Available)

        // Same device, now running the release it just installed -> silence.
        val upgraded = UpdateRepositoryImpl(api, cache, nextVersion) { now }
        val result = upgraded.checkForUpdate(force = true)
        assertTrue("expected NoUpdate after upgrading, got $result", result is UpdateCheckResult.NoUpdate)
        assertNull(upgraded.availableUpdate.value)
    }

    @Test
    fun `automatic re-checks inside the 12h window do not re-hit the release api`() = runTest {
        api.dto = publishedRelease(nextVersion)
        val repository = repository()

        assertTrue(repository.checkForUpdate(force = false) is UpdateCheckResult.Available)
        assertEquals(1, api.calls)

        now += 60L * 60 * 1000 // one hour later, app re-opened
        // NotDueYet — NOT NoUpdate: nothing was verified, so the UI must not claim
        // «آخرین نسخه را دارید» for a check that never happened.
        val second = repository.checkForUpdate(force = false)
        assertTrue("expected NotDueYet, got $second", second is UpdateCheckResult.NotDueYet)
        assertEquals("the release api must not be polled again", 1, api.calls)

        // A manual check always goes to the network and answers truthfully.
        assertTrue(repository.checkForUpdate(force = true) is UpdateCheckResult.Available)
        assertEquals(2, api.calls)
    }

    // Nested (not file-level) on purpose: UpdateRepositoryImplTest.kt already
    // declares file-private fakes with these names, and two top-level classes
    // with the same name in one package are a "Duplicate JVM class name" error.
    private class FakeReleaseApi : ReleaseApi {
        var dto: ReleaseDto? = null
        var error: Throwable? = null
        var calls = 0

        override suspend fun latestRelease(url: String): ReleaseDto {
            calls++
            error?.let { throw it }
            return requireNotNull(dto)
        }
    }

    private class FakeUpdateCheckCache : UpdateCheckCache {
        private var last: Long = 0L

        override suspend fun lastCheckAt(): Long = last

        override suspend fun setLastCheckAt(nowMs: Long) {
            last = nowMs
        }
    }

    private companion object {
        const val ASSET_BASE = "https://github.com/javadisaloo1111/Currency-App/releases/download"
        const val DIGEST_HEX = "9759bc350d1780b1f2d79a4d5ccf2ca3b9c4f6e4cea2e510306322dbd40fbb43"
    }
}
