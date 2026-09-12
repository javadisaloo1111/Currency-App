package ir.talayar.app.data.update

import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.data.remote.ReleaseDto
import ir.talayar.app.domain.repository.UpdateCheckResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class UpdateRepositoryImplTest {

    private lateinit var api: FakeReleaseApi
    private lateinit var cache: FakeUpdateCheckCache
    private var now: Long = 1_000_000L
    private lateinit var repository: UpdateRepositoryImpl

    private val installed = "1.0.1"

    @Before
    fun setUp() {
        api = FakeReleaseApi()
        cache = FakeUpdateCheckCache()
        repository = UpdateRepositoryImpl(api, cache, installed) { now }
    }

    private fun release(
        tag: String,
        body: String? = null,
        assets: List<ReleaseAssetDto> = defaultAssets(tag.removePrefix("v")),
    ) = ReleaseDto(tagName = tag, name = "Gold Market Android $tag", body = body, assets = assets)

    private fun defaultAssets(version: String) = listOf(
        ReleaseAssetDto(
            name = "app-release-v$version.apk",
            browserDownloadUrl = "https://github.com/javadisaloo1111/Currency-App/releases/download/v$version/app-release-v$version.apk",
            size = 8_645_881,
        ),
        ReleaseAssetDto(
            name = "app-release-v$version.apk.sha256",
            browserDownloadUrl = "https://github.com/javadisaloo1111/Currency-App/releases/download/v$version/app-release-v$version.apk.sha256",
            size = 99,
        ),
    )

    // -- scenario: same version -> no update, no dialog ----------------------

    @Test
    fun `same version returns NoUpdate and clears any pending update`() = runTest {
        api.dto = release("v1.0.1")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertNull(repository.availableUpdate.value)
        assertEquals(1, api.calls)
    }

    // -- scenario: newer version -> update available -------------------------

    @Test
    fun `newer release returns Available with the official apk and checksum urls`() = runTest {
        api.dto = release("v1.0.2")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Available)
        val update = (result as UpdateCheckResult.Available).update
        assertEquals("1.0.2", update.latestVersion)
        assertEquals(
            "https://github.com/javadisaloo1111/Currency-App/releases/download/v1.0.2/app-release-v1.0.2.apk",
            update.apkUrl,
        )
        assertEquals(8_645_881, update.apkSize)
        assertTrue(update.sha256Url!!.endsWith("app-release-v1.0.2.apk.sha256"))
        assertEquals(update, repository.availableUpdate.value)
        assertTrue(!update.forced)
    }

    // -- scenario: 1.0.10 must beat 1.0.9 (numeric semver) --------------------

    @Test
    fun `version 1_0_10 is newer than installed 1_0_9`() = runTest {
        repository = UpdateRepositoryImpl(api, cache, "1.0.9") { now }
        api.dto = release("v1.0.10")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.0.10", (result as UpdateCheckResult.Available).update.latestVersion)
    }

    // -- scenario: downgrade protection ---------------------------------------

    @Test
    fun `older latest release is ignored (no downgrade)`() = runTest {
        repository = UpdateRepositoryImpl(api, cache, "1.0.10") { now }
        api.dto = release("v1.0.9")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertNull(repository.availableUpdate.value)
    }

    // -- scenario: release service unreachable -> app keeps working -----------

    @Test
    fun `network failure returns Failed without throwing`() = runTest {
        api.error = IOException("api.github.com unreachable")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Failed)
        assertNull(repository.availableUpdate.value)
    }

    // -- scenario: periodic cache (12h) limits automatic checks ---------------

    @Test
    fun `automatic check inside the cache window skips the api call`() = runTest {
        api.dto = release("v1.0.2")
        repository.checkForUpdate(force = true)
        assertEquals(1, api.calls)

        // 1 hour later: no new call, no dialog re-show.
        now += 60L * 60 * 1000
        val result = repository.checkForUpdate(force = false)

        assertEquals(1, api.calls)
        assertTrue(result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `automatic check after 12 hours hits the api again`() = runTest {
        api.dto = release("v1.0.2")
        repository.checkForUpdate(force = true)
        assertEquals(1, api.calls)

        now += 12L * 60 * 60 * 1000 + 1
        val result = repository.checkForUpdate(force = false)

        assertEquals(2, api.calls)
        assertTrue(result is UpdateCheckResult.Available)
    }

    @Test
    fun `manual check always bypasses the cache`() = runTest {
        api.dto = release("v1.0.2")
        repository.checkForUpdate(force = true)
        now += 1_000L

        val result = repository.checkForUpdate(force = true)

        assertEquals(2, api.calls)
        assertTrue(result is UpdateCheckResult.Available)
    }

    // -- scenario: force-update metadata --------------------------------------

    @Test
    fun `release below the declared minimum supported version is forced`() = runTest {
        api.dto = release(
            "v1.1.0",
            body = "minimum_supported_version: 1.0.2\nسایر توضیحات انتشار",
        )

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Available)
        assertTrue((result as UpdateCheckResult.Available).update.forced)
    }

    @Test
    fun `release without a declared minimum is not forced`() = runTest {
        api.dto = release("v1.1.0", body = "توضیحات انتشار بدون حداقل نسخه")

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Available)
        assertTrue(!(result as UpdateCheckResult.Available).update.forced)
    }

    // -- scenario: only official repository assets are accepted ---------------

    @Test
    fun `apk asset from an unknown host is rejected`() = runTest {
        api.dto = release(
            "v1.0.2",
            assets = listOf(
                ReleaseAssetDto(
                    name = "app-release-v1.0.2.apk",
                    browserDownloadUrl = "https://evil.example.com/app-release-v1.0.2.apk",
                    size = 8_645_881,
                ),
            ),
        )

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.NoUpdate)
        assertNull(repository.availableUpdate.value)
    }

    @Test
    fun `release without any apk asset is ignored`() = runTest {
        api.dto = release("v1.0.2", assets = emptyList())

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `checksum asset from an unknown host is dropped`() = runTest {
        api.dto = release(
            "v1.0.2",
            assets = listOf(
                ReleaseAssetDto(
                    name = "app-release-v1.0.2.apk",
                    browserDownloadUrl = "https://github.com/javadisaloo1111/Currency-App/releases/download/v1.0.2/app-release-v1.0.2.apk",
                    size = 8_645_881,
                ),
                ReleaseAssetDto(
                    name = "app-release-v1.0.2.apk.sha256",
                    browserDownloadUrl = "https://evil.example.com/app-release-v1.0.2.apk.sha256",
                    size = 99,
                ),
            ),
        )

        val result = repository.checkForUpdate(force = true)

        assertTrue(result is UpdateCheckResult.Available)
        assertNull((result as UpdateCheckResult.Available).update.sha256Url)
    }
}

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
    var last: Long = 0L

    override suspend fun lastCheckAt(): Long = last

    override suspend fun setLastCheckAt(nowMs: Long) {
        last = nowMs
    }
}
