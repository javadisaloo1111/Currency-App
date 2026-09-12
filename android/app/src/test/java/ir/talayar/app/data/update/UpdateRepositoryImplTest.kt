package ir.talayar.app.data.update

import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.domain.model.AppUpdate
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import ir.talayar.app.domain.repository.UpdateCheckResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Contract of the update checker, scenario by scenario.
 *
 * The rule that matters most: «آخرین نسخه را دارید» may only ever follow a release
 * that was really read. A timeout, a 403, an unreachable host, a skipped periodic
 * check or a release without a package are all *distinct* results — never a
 * false "you are up to date" and never one blanket failure.
 */
class UpdateRepositoryImplTest {

    private lateinit var primary: StubReleaseSource
    private lateinit var mirror: StubReleaseSource
    private lateinit var cache: StubUpdateCheckCache
    private val sleeps = mutableListOf<Long>()
    private var now = 1_700_000_000_000L
    private val installed = "1.0.1"

    @Before
    fun setUp() {
        primary = StubReleaseSource("github-api")
        mirror = StubReleaseSource("mirror-1")
        cache = StubUpdateCheckCache()
        sleeps.clear()
        now = 1_700_000_000_000L
    }

    private fun repository(
        installedVersion: String = installed,
        online: Boolean = true,
        sources: List<ReleaseSource> = listOf(primary, mirror),
        budgetMs: Long = UpdateRepositoryImpl.DEFAULT_TOTAL_BUDGET_MS,
    ) = UpdateRepositoryImpl(
        sources = sources,
        cache = cache,
        installedVersion = installedVersion,
        connectivity = StubConnectivity(online),
        clock = { now },
        sleeper = { sleeps += it },
        totalBudgetMs = budgetMs,
    )

    private fun errorOf(result: UpdateCheckResult): UpdateError {
        assertTrue("expected an Error result, got $result", result is UpdateCheckResult.Error)
        return (result as UpdateCheckResult.Error).error
    }

    private fun updateOf(result: UpdateCheckResult): AppUpdate {
        assertTrue("expected an Available result, got $result", result is UpdateCheckResult.Available)
        return (result as UpdateCheckResult.Available).update
    }

    // ---------------------------------------------------------------
    // T1 — version comparison
    // ---------------------------------------------------------------

    @Test
    fun `same version as installed reports NoUpdate and never offers a dialog`() = runTest {
        primary.dto = releaseDto("1.0.1")
        val repository = repository()

        val result = repository.checkForUpdate(force = true)

        assertTrue("expected NoUpdate, got $result", result is UpdateCheckResult.NoUpdate)
        assertNull(repository.availableUpdate.value)
        assertEquals("a verified answer is worth caching", 1, cache.writes)
    }

    @Test
    fun `newer release is offered with the official download and checksum urls`() = runTest {
        primary.dto = releaseDto("1.0.2")
        val repository = repository()

        val update = updateOf(repository.checkForUpdate(force = true))

        assertEquals("1.0.2", update.latestVersion)
        assertEquals("$TEST_ASSET_BASE/v1.0.2/app-release-v1.0.2.apk", update.apkUrl)
        assertEquals(8_700_000L, update.apkSize)
        assertEquals("$TEST_ASSET_BASE/v1.0.2/app-release-v1.0.2.apk.sha256", update.sha256Url)
        assertFalse("only releases declaring a minimum version may be forced", update.forced)
        assertEquals(update, repository.availableUpdate.value)
    }

    @Test
    fun `installed 1_0_2 with latest v1_0_2 reports NoUpdate`() = runTest {
        primary.dto = releaseDto("1.0.2")

        val result = repository(installedVersion = "1.0.2").checkForUpdate(force = true)

        assertTrue("expected NoUpdate, got $result", result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `an older latest release is ignored (no downgrade prompt)`() = runTest {
        primary.dto = releaseDto("1.0.0")

        val result = repository(installedVersion = "1.0.2").checkForUpdate(force = true)

        assertTrue("expected NoUpdate, got $result", result is UpdateCheckResult.NoUpdate)
    }

    @Test
    fun `comparison is numeric so 1_0_10 is newer than 1_0_9`() = runTest {
        primary.dto = releaseDto("1.0.10")

        val update = updateOf(repository(installedVersion = "1.0.9").checkForUpdate(force = true))

        assertEquals("1.0.10", update.latestVersion)
    }

    @Test
    fun `repeated checks for the same version never loop into an update offer`() = runTest {
        primary.dto = releaseDto("1.0.1")
        val repository = repository()

        repeat(5) {
            assertTrue(repository.checkForUpdate(force = true) is UpdateCheckResult.NoUpdate)
            assertNull(repository.availableUpdate.value)
        }
        assertEquals("one fetch per check, no retry storm", 5, primary.calls)
    }

    @Test
    fun `a previously offered update is cleared once the device catches up`() = runTest {
        val repository = repository()
        primary.dto = releaseDto("1.0.2")
        assertTrue(repository.checkForUpdate(force = true) is UpdateCheckResult.Available)

        // The user installed 1.0.2; the running build is now 1.0.2 as well.
        val upgraded = repository(installedVersion = "1.0.2")
        assertTrue(upgraded.checkForUpdate(force = true) is UpdateCheckResult.NoUpdate)
        assertNull(upgraded.availableUpdate.value)
    }

    // ---------------------------------------------------------------
    // T6-T10 — transport / HTTP / payload failures are NOT "no update"
    // ---------------------------------------------------------------

    @Test
    fun `a timeout is reported as a timeout and never as NoUpdate`() = runTest {
        primary.error = SocketTimeoutException("connect timed out")
        mirror.error = SocketTimeoutException("read timed out")

        val result = repository().checkForUpdate(force = true)

        assertEquals(UpdateErrorKind.TIMEOUT, errorOf(result).kind)
        assertFalse("a timeout must not be cached as a completed check", cache.writes > 0)
        assertEquals("bounded retry per source", 2, primary.calls)
        assertEquals("then failover to the next source", 2, mirror.calls)
        assertEquals("linear backoff between the two attempts of each source", listOf(700L, 700L), sleeps)
    }

    @Test
    fun `http 403 is reported as rate limited, not as no internet`() = runTest {
        primary.error = httpException(403)

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.RATE_LIMITED, error.kind)
        assertEquals(2, primary.calls)
    }

    @Test
    fun `http 429 is reported as rate limited`() = runTest {
        primary.error = httpException(429)

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.RATE_LIMITED, error.kind)
    }

    @Test
    fun `http 404 is reported as not found and is not retried`() = runTest {
        primary.error = httpException(404)

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.NOT_FOUND, error.kind)
        assertEquals("a permanent answer must not burn retries", 1, primary.calls)
    }

    @Test
    fun `http 5xx is reported as a server error`() = runTest {
        primary.error = httpException(503)

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.SERVER_ERROR, error.kind)
        assertEquals(2, primary.calls)
    }

    @Test
    fun `an unparseable body is reported as an invalid response`() = runTest {
        primary.error = SerializationException("Unexpected JSON token at offset 0: <")

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.INVALID_RESPONSE, error.kind)
        assertEquals(1, primary.calls)
    }

    @Test
    fun `an unreachable host is reported as a dns failure`() = runTest {
        primary.error = UnknownHostException("api.github.com")

        val error = errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.DNS_FAILURE, error.kind)
    }

    @Test
    fun `the first source diagnosis wins when every source fails`() = runTest {
        // Real world: api.github.com answers 403 (rate limit) while the mirrors,
        // which do not exist yet, answer 404. The truthful reason is the rate limit.
        primary.error = httpException(403)
        mirror.error = httpException(404)

        val error = errorOf(repository().checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.RATE_LIMITED, error.kind)
    }

    // ---------------------------------------------------------------
    // connectivity: offline vs. server unreachable
    // ---------------------------------------------------------------

    @Test
    fun `an offline device reports no internet without calling any source`() = runTest {
        primary.dto = releaseDto("1.0.2")

        val error = errorOf(repository(online = false).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.NO_INTERNET, error.kind)
        assertEquals(0, primary.calls)
        assertEquals(0, mirror.calls)
        assertEquals(0, cache.writes)
    }

    @Test
    fun `an online device with a blocked update server is NOT told it has no internet`() = runTest {
        // Exactly the reported bug: internet works (browsing the release page and
        // downloading the package manually succeeded) but api.github.com is filtered.
        primary.error = IOException("Connection reset by peer")
        mirror.error = IOException("Connection reset by peer")

        val error = errorOf(repository(online = true).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.CONNECTION_FAILED, error.kind)
        assertEquals(
            "ارتباط با سرور بروزرسانی برقرار نشد. لطفاً دوباره تلاش کنید.",
            error.userMessage,
        )
        assertTrue(error.retryable)
    }

    // ---------------------------------------------------------------
    // failover across sources
    // ---------------------------------------------------------------

    @Test
    fun `a blocked api falls over to the static mirror and still finds the update`() = runTest {
        primary.error = UnknownHostException("api.github.com")
        mirror.dto = releaseDto("1.0.2")

        val update = updateOf(repository().checkForUpdate(force = true))

        assertEquals("1.0.2", update.latestVersion)
        assertEquals(2, primary.calls)
        assertEquals(1, mirror.calls)
        assertEquals("a successful check is cached", 1, cache.writes)
    }

    @Test
    fun `an unusable payload on one source falls over to the next`() = runTest {
        primary.dto = releaseDto("1.0.2", draft = true)
        mirror.dto = releaseDto("1.0.2")

        val update = updateOf(repository().checkForUpdate(force = true))

        assertEquals("1.0.2", update.latestVersion)
        assertEquals(1, mirror.calls)
    }

    @Test
    fun `the total time budget stops the crawl instead of hanging the ui`() = runTest {
        primary.dto = releaseDto("1.0.2")

        val error = errorOf(repository(budgetMs = 0L).checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.TIMEOUT, error.kind)
        assertEquals("no source may be tried once the budget is gone", 0, primary.calls)
    }

    // ---------------------------------------------------------------
    // release sanity: drafts, prereleases, unparsable tags, missing package
    // ---------------------------------------------------------------

    @Test
    fun `a draft release is never offered`() = runTest {
        primary.dto = releaseDto("1.0.2", draft = true)
        mirror.dto = releaseDto("1.0.2", draft = true)

        val error = errorOf(repository().checkForUpdate(force = true))

        assertEquals(UpdateErrorKind.RELEASE_NOT_FOUND, error.kind)
    }

    @Test
    fun `a prerelease is never offered`() = runTest {
        primary.dto = releaseDto("1.0.2", prerelease = true)
        mirror.dto = releaseDto("1.0.2", prerelease = true)

        assertEquals(
            UpdateErrorKind.RELEASE_NOT_FOUND,
            errorOf(repository().checkForUpdate(force = true)).kind,
        )
    }

    @Test
    fun `an unparsable tag such as latest is rejected`() = runTest {
        primary.dto = releaseDto("1.0.2", tag = "latest")

        assertEquals(
            UpdateErrorKind.RELEASE_NOT_FOUND,
            errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).kind,
        )
    }

    @Test
    fun `a blank tag is rejected`() = runTest {
        primary.dto = releaseDto("1.0.2", tag = "   ")

        assertEquals(
            UpdateErrorKind.RELEASE_NOT_FOUND,
            errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).kind,
        )
    }

    @Test
    fun `a newer release without any asset reports a missing package, not NoUpdate`() = runTest {
        primary.dto = releaseDto("1.0.2", assets = emptyList())
        val repository = repository(sources = listOf(primary))

        val result = repository.checkForUpdate(force = true)

        assertEquals(UpdateErrorKind.APK_NOT_FOUND, errorOf(result).kind)
        assertEquals("a definitive answer is cached", 1, cache.writes)
        assertNull("nothing may be offered for install", repository.availableUpdate.value)
    }

    @Test
    fun `a release whose only asset is a checksum reports a missing package`() = runTest {
        primary.dto = releaseDto("1.0.2", assets = listOf(checksumAsset("1.0.2")))

        assertEquals(
            UpdateErrorKind.APK_NOT_FOUND,
            errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).kind,
        )
    }

    // ---------------------------------------------------------------
    // asset selection through the repository
    // ---------------------------------------------------------------

    @Test
    fun `the apk is chosen among mixed release assets`() = runTest {
        primary.dto = releaseDto(
            "1.0.2",
            assets = listOf(
                checksumAsset("1.0.2"),
                ReleaseAssetDto(
                    name = "Source code (zip)",
                    browserDownloadUrl = "https://github.com/javadisaloo1111/Currency-App/archive/refs/tags/v1.0.2.zip",
                    size = 250_000,
                    contentType = "application/zip",
                ),
                ReleaseAssetDto(
                    name = "mapping.txt",
                    browserDownloadUrl = "$TEST_ASSET_BASE/v1.0.2/mapping.txt",
                    size = 900_000,
                    contentType = "text/plain",
                ),
                apkAsset("1.0.2"),
            ),
        )

        val update = updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals("$TEST_ASSET_BASE/v1.0.2/app-release-v1.0.2.apk", update.apkUrl)
        assertEquals("$TEST_ASSET_BASE/v1.0.2/app-release-v1.0.2.apk.sha256", update.sha256Url)
    }

    @Test
    fun `the digest published with the asset becomes the expected checksum`() = runTest {
        primary.dto = releaseDto("1.0.2", assets = listOf(apkAsset("1.0.2", digest = TEST_DIGEST)))

        val update = updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals(TEST_DIGEST_HEX, update.sha256)
        assertNull("no sidecar asset was published", update.sha256Url)
    }

    @Test
    fun `sidecar and digest are both carried when the release publishes both`() = runTest {
        primary.dto = releaseDto(
            "1.0.2",
            assets = listOf(apkAsset("1.0.2", digest = TEST_DIGEST), checksumAsset("1.0.2")),
        )

        val update = updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertEquals("$TEST_ASSET_BASE/v1.0.2/app-release-v1.0.2.apk.sha256", update.sha256Url)
        assertEquals(TEST_DIGEST_HEX, update.sha256)
    }

    @Test
    fun `a malformed digest is ignored instead of trusted`() = runTest {
        primary.dto = releaseDto("1.0.2", assets = listOf(apkAsset("1.0.2", digest = "sha1:deadbeef")))

        assertNull(updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).sha256)
    }

    @Test
    fun `an asset outside the official release path is never installable`() = runTest {
        primary.dto = releaseDto(
            "1.0.2",
            assets = listOf(
                apkAsset("1.0.2", urlBase = "https://evil.example.com/malware"),
            ),
        )

        assertEquals(
            UpdateErrorKind.APK_NOT_FOUND,
            errorOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).kind,
        )
    }

    @Test
    fun `a checksum from a foreign host is dropped but the official apk is still offered`() = runTest {
        primary.dto = releaseDto(
            "1.0.2",
            assets = listOf(
                apkAsset("1.0.2"),
                checksumAsset("1.0.2", urlBase = "https://evil.example.com/malware"),
            ),
        )

        val update = updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertNull("an untrusted checksum must be ignored, not used", update.sha256Url)
    }

    // ---------------------------------------------------------------
    // force-update marker in the release notes
    // ---------------------------------------------------------------

    @Test
    fun `a release declaring a higher minimum version is offered as forced`() = runTest {
        primary.dto = releaseDto("1.0.3", body = "fixes\nminimum_supported_version: 1.0.3\n")

        val update = updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true))

        assertTrue("installed 1.0.1 is below the declared minimum", update.forced)
    }

    @Test
    fun `a release declaring a satisfied minimum version is not forced`() = runTest {
        primary.dto = releaseDto("1.0.3", body = "minimum_supported_version: 1.0.1")

        assertFalse(updateOf(repository(sources = listOf(primary)).checkForUpdate(force = true)).forced)
    }

    // ---------------------------------------------------------------
    // T14 — cache semantics
    // ---------------------------------------------------------------

    @Test
    fun `an automatic check inside the window is NotDueYet and never claims freshness`() = runTest {
        primary.dto = releaseDto("1.0.2")
        cache.setLastCheckAt(now - 60L * 60 * 1000) // checked one hour ago
        cache.resetWrites()

        val result = repository().checkForUpdate(force = false)

        assertTrue("expected NotDueYet, got $result", result is UpdateCheckResult.NotDueYet)
        assertEquals(0, primary.calls)
    }

    @Test
    fun `an automatic check after the window hits the network again`() = runTest {
        primary.dto = releaseDto("1.0.2")
        cache.setLastCheckAt(now - UpdateRepositoryImpl.CHECK_INTERVAL_MS - 1)

        val result = repository().checkForUpdate(force = false)

        assertTrue("expected Available, got $result", result is UpdateCheckResult.Available)
        assertTrue(primary.calls >= 1)
    }

    @Test
    fun `a manual check always bypasses the cache`() = runTest {
        primary.dto = releaseDto("1.0.2")
        cache.setLastCheckAt(now) // just checked

        val result = repository().checkForUpdate(force = true)

        assertTrue("expected Available, got $result", result is UpdateCheckResult.Available)
        assertEquals(1, primary.calls)
    }

    @Test
    fun `a failed check is not recorded so the next automatic check retries`() = runTest {
        primary.error = SocketTimeoutException("timed out")
        mirror.error = SocketTimeoutException("timed out")
        cache.setLastCheckAt(now - UpdateRepositoryImpl.CHECK_INTERVAL_MS * 2)
        cache.resetWrites()

        assertTrue(repository().checkForUpdate(force = false) is UpdateCheckResult.Error)

        assertEquals(0, cache.writes)
    }
}
