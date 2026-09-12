package ir.talayar.app.data.update

import ir.talayar.app.core.Connectivity
import ir.talayar.app.data.remote.ReleaseApi
import ir.talayar.app.data.remote.ReleaseAssetDto
import ir.talayar.app.data.remote.ReleaseDto
import ir.talayar.app.domain.model.UpdateError
import ir.talayar.app.domain.model.UpdateErrorKind
import ir.talayar.app.domain.model.VersionComparator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Where release metadata comes from.
 *
 * `api.github.com` is authoritative, but it is a single host that is frequently
 * unreachable on restricted networks and is rate limited to 60 unauthenticated
 * requests per hour **per IP** — which carrier-grade NAT makes very easy to hit
 * (the API then answers 403). The very same metadata is therefore also published
 * as a small static `update.json` beside the app's price API and served by three
 * independent hosts. This is the same failover strategy the market repository
 * already uses for prices (`MarketRepositoryImpl.STATIC_ENDPOINTS`).
 *
 * Adding a gateway or a company mirror later means adding one URL here — the
 * checker, the asset selection, the downloader and the UI do not change.
 */
object UpdateEndpoints {

    private const val OWNER = "javadisaloo1111"
    private const val REPO = "Currency-App"

    /** Authoritative source. GitHub excludes drafts and prereleases from it. */
    const val GITHUB_API_LATEST: String =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Static mirrors of the same payload, published by the release workflow. */
    val MIRRORS: List<String> = listOf(
        "https://$OWNER.github.io/$REPO/update.json",
        "https://raw.githubusercontent.com/$OWNER/$REPO/gh-pages/update.json",
        "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@gh-pages/update.json",
    )

    /** Only assets served from this repository's official release download path are installable. */
    const val OFFICIAL_ASSET_PREFIX: String =
        "https://github.com/$OWNER/$REPO/releases/download/"

    /** Human-readable release page (used in logs only). */
    const val RELEASES_PAGE: String = "https://github.com/$OWNER/$REPO/releases"
}

/** One place release metadata can be fetched from. */
interface ReleaseSource {

    /** Stable identifier for logs — never shown to the user. */
    val id: String

    /** Fetches the newest published release. Throws on any transport or parse failure. */
    suspend fun latest(): ReleaseDto
}

/** [ReleaseSource] backed by the shared Retrofit instance and an absolute URL. */
class HttpReleaseSource(
    override val id: String,
    private val api: ReleaseApi,
    private val url: String,
) : ReleaseSource {

    override suspend fun latest(): ReleaseDto = api.latestRelease(url)

    override fun toString(): String = "HttpReleaseSource($id)"
}

/** Default ordered source list: authoritative API first, then the static mirrors. */
fun defaultReleaseSources(api: ReleaseApi): List<ReleaseSource> =
    listOf(HttpReleaseSource("github-api", api, UpdateEndpoints.GITHUB_API_LATEST)) +
        UpdateEndpoints.MIRRORS.mapIndexed { index, url ->
            HttpReleaseSource("mirror-${index + 1}", api, url)
        }

/**
 * Connectivity stub for callers without a monitor (and for unit tests): assumes
 * the device is online and lets the transport errors speak for themselves.
 */
object AlwaysOnlineConnectivity : Connectivity {
    override val isOnline: Flow<Boolean> = flowOf(true)
}

/**
 * Maps any failure of the update flow onto a distinct [UpdateErrorKind].
 *
 * @param online the connectivity monitor's answer. It is what keeps a filtered or
 *   unreachable release service from being reported as «no internet»: an
 *   [IOException] while the device demonstrably has a network is a *connection*
 *   problem with the server, not an offline device.
 */
fun classifyUpdateFailure(t: Throwable?, online: Boolean): UpdateError {
    if (t is UpdateDownloadException) return t.error
    val detail = t?.let { throwable ->
        val message = throwable.message?.take(160).orEmpty()
        if (message.isEmpty()) throwable.javaClass.simpleName else "${throwable.javaClass.simpleName}: $message"
    }
    return when (t) {
        // Non-2xx: GitHub answers 403 both for auth problems and for rate limits,
        // and 429 when a proxy throttles. Neither means "you are offline".
        is HttpException -> when (val code = t.code()) {
            403, 429 -> UpdateError(UpdateErrorKind.RATE_LIMITED, "HTTP $code", t)
            404 -> UpdateError(UpdateErrorKind.NOT_FOUND, "HTTP 404", t)
            in 500..599 -> UpdateError(UpdateErrorKind.SERVER_ERROR, "HTTP $code", t)
            else -> UpdateError(UpdateErrorKind.HTTP_ERROR, "HTTP $code", t)
        }

        // Unparseable / unexpected payload.
        is SerializationException -> UpdateError(UpdateErrorKind.INVALID_RESPONSE, detail, t)

        // Transport, most specific first (SSLException and SocketTimeoutException
        // are both IOExceptions, so they must be matched before the generic one).
        is UnknownHostException -> UpdateError(UpdateErrorKind.DNS_FAILURE, detail, t)
        is SSLException -> UpdateError(UpdateErrorKind.TLS_FAILURE, detail, t)
        is SocketTimeoutException -> UpdateError(UpdateErrorKind.TIMEOUT, detail, t)
        is InterruptedIOException -> UpdateError(UpdateErrorKind.TIMEOUT, detail, t)
        is IOException ->
            if (online) {
                UpdateError(UpdateErrorKind.CONNECTION_FAILED, detail, t)
            } else {
                UpdateError(UpdateErrorKind.NO_INTERNET, detail, t)
            }

        is IllegalArgumentException, is IllegalStateException ->
            UpdateError(UpdateErrorKind.INVALID_RESPONSE, detail, t)

        null -> UpdateError(UpdateErrorKind.UNKNOWN, "no failure information", null)
        else -> UpdateError(UpdateErrorKind.UNKNOWN, detail, t)
    }
}

/**
 * Chooses the installable APK among a release's assets.
 *
 * Deliberately **not** tied to one fixed file name: the published asset is
 * `app-release-<tag>.apk` today, but the selection also survives renamed or
 * additional assets. Assets are scored and the best candidate wins; checksum
 * sidecars, source archives, mapping files and debug/unsigned builds can never be
 * selected, and a download URL outside the repository's official release path is
 * rejected outright.
 */
object ReleaseAssetSelector {

    /** Anything below this cannot be a real release APK (see [ApkDownloader.MIN_APK_BYTES]). */
    const val MIN_PLAUSIBLE_APK_BYTES: Long = 500_000L

    /**
     * Whole-name tokens that mark a non-installable build. Matched as tokens, not
     * substrings, so a legitimate name such as "latest.apk" is never rejected for
     * containing "test".
     */
    private val REJECTED_TOKENS = setOf(
        "debug", "unsigned", "test", "tests", "sample", "source", "sources",
        "mapping", "symbols", "instant", "wear",
    )

    private val TOKEN_SEPARATOR = Regex("[^a-z0-9]+")

    private val REJECTED_SUFFIXES = listOf(
        ".sha256", ".sha512", ".sha1", ".md5", ".asc", ".sig", ".txt", ".json", ".xml",
        ".zip", ".tar", ".gz", ".tgz", ".md", ".apk.meta",
    )

    /**
     * @param tag the release tag the assets belong to (e.g. "v1.0.3").
     * @return the best installable APK asset, or null when the release has none.
     */
    fun select(tag: String, assets: List<ReleaseAssetDto>): ReleaseAssetDto? {
        val version = VersionComparator.canonical(tag)?.lowercase()
        val scored = assets.mapNotNull { asset -> score(asset, tag, version)?.let { asset to it } }
        // Highest score wins; ties break on the larger file, then on the name so
        // the choice is deterministic across runs and sources.
        val best: Comparator<Pair<ReleaseAssetDto, Int>> =
            compareBy({ it.second }, { it.first.size }, { it.first.name })
        return scored.maxWithOrNull(best)?.first
    }

    /** Positive score for an installable release APK; null when the asset must be ignored. */
    private fun score(asset: ReleaseAssetDto, tag: String, version: String?): Int? {
        val name = asset.name.trim()
        val lower = name.lowercase()
        if (lower.isEmpty()) return null
        if (!lower.endsWith(".apk")) return null
        if (REJECTED_SUFFIXES.any { lower.endsWith(it) }) return null
        val tokens = lower.split(TOKEN_SEPARATOR).filter { it.isNotEmpty() }
        if (tokens.any { it in REJECTED_TOKENS }) return null
        // Security: only this repository's official release download path is installable.
        if (!asset.browserDownloadUrl.startsWith(UpdateEndpoints.OFFICIAL_ASSET_PREFIX)) return null

        var score = 1
        if (asset.contentType?.contains("android.package-archive", ignoreCase = true) == true) score += 8
        if (lower == "app-release-${tag.trim().lowercase()}.apk") score += 6
        if (lower.startsWith("app-release")) score += 4
        if (version != null && lower.contains(version)) score += 3
        if (lower.endsWith("-release.apk") || lower == "release.apk") score += 1
        if (asset.size >= MIN_PLAUSIBLE_APK_BYTES) score += 2
        return score
    }
}
