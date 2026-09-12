package ir.talayar.app.data.update

import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.data.remote.ReleaseDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

/**
 * Shared test doubles for the update channel.
 *
 * They live in one file (with unique, non-file-private names) so every update test
 * can reuse them without redeclaring top-level classes — two files declaring the
 * same class name in one package is a "Duplicate JVM class name" compile error.
 */

/** Official release download prefix used by the published assets. */
const val TEST_ASSET_BASE = "https://github.com/javadisaloo1111/Currency-App/releases/download"

/** A release source whose payload or failure is scripted per test. */
class StubReleaseSource(
    override val id: String,
    var dto: ReleaseDto? = null,
    var error: Throwable? = null,
) : ReleaseSource {

    var calls = 0
        private set

    override suspend fun latest(): ReleaseDto {
        calls++
        error?.let { throw it }
        return checkNotNull(dto) { "StubReleaseSource($id) was given neither a payload nor an error" }
    }
}

/** In-memory replacement for the DataStore-backed periodic-check cache. */
class StubUpdateCheckCache(initialLastCheckAt: Long = 0L) : UpdateCheckCache {

    private var last: Long = initialLastCheckAt

    /** How often a check was recorded as successful (a failure must never record one). */
    var writes = 0
        private set

    /** Forgets earlier writes (tests that seed the cache first). */
    fun resetWrites() {
        writes = 0
    }

    override suspend fun lastCheckAt(): Long = last

    override suspend fun setLastCheckAt(nowMs: Long) {
        last = nowMs
        writes++
    }
}

/** Connectivity monitor a test can flip between online and offline. */
class StubConnectivity(online: Boolean = true) : Connectivity {

    private val state = MutableStateFlow(online)

    override val isOnline: Flow<Boolean> = state

    fun setOnline(value: Boolean) {
        state.value = value
    }
}

/** Builds an APK asset exactly like the release workflow publishes it. */
fun apkAsset(
    version: String,
    name: String = "app-release-v$version.apk",
    size: Long = 8_700_000L,
    contentType: String? = "application/vnd.android.package-archive",
    urlBase: String = TEST_ASSET_BASE,
    /** Digest the release service publishes for the asset, e.g. "sha256:<64 hex>". */
    digest: String? = null,
) = ReleaseAssetDto(
    name = name,
    browserDownloadUrl = "$urlBase/v$version/$name",
    size = size,
    contentType = contentType,
    digest = digest,
)

/** A realistic published digest (64 lowercase hex characters, "sha256:" prefixed). */
const val TEST_DIGEST_HEX = "9759bc350d1780b1f2d79a4d5ccf2ca3b9c4f6e4cea2e510306322dbd40fbb43"
const val TEST_DIGEST = "sha256:$TEST_DIGEST_HEX"

/** Builds the published ".sha256" sidecar asset. */
fun checksumAsset(version: String, urlBase: String = TEST_ASSET_BASE) = ReleaseAssetDto(
    name = "app-release-v$version.apk.sha256",
    browserDownloadUrl = "$urlBase/v$version/app-release-v$version.apk.sha256",
    size = 89,
    contentType = "application/octet-stream",
)

/** Builds a release payload in the shape both the API and the static mirror serve. */
fun releaseDto(
    version: String,
    tag: String = "v$version",
    body: String? = null,
    draft: Boolean = false,
    prerelease: Boolean = false,
    assets: List<ReleaseAssetDto> = listOf(apkAsset(version), checksumAsset(version)),
) = ReleaseDto(
    tagName = tag,
    name = "Gold Market Android $tag",
    body = body,
    draft = draft,
    prerelease = prerelease,
    publishedAt = "2026-09-12T10:00:00Z",
    assets = assets,
)

/** A real Retrofit [HttpException] for a given status code. */
fun httpException(code: Int): HttpException = HttpException(
    Response.error<ReleaseDto>(code, "{}".toResponseBody("application/json".toMediaType())),
)
