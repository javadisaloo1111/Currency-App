package ir.talayar.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/**
 * Release metadata used by the in-app update checker.
 *
 * The same shape is served by the GitHub REST API (`/releases/latest`) and by the
 * small static `update.json` mirrors the release workflow publishes, so one DTO
 * covers every source (see `data/update/UpdateSources.kt`).
 *
 * Unknown keys are ignored and nulls are coerced to the defaults below
 * (see `di/NetworkModule.provideJson`), so extra GitHub fields never break parsing.
 */
@Serializable
data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    /** Drafts and prereleases must never be offered as an update. */
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<ReleaseAssetDto> = emptyList(),
)

@Serializable
data class ReleaseAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    /**
     * Content digest GitHub publishes for the uploaded bytes, e.g.
     * `sha256:9759bc35…`. Used as the expected checksum when the `.sha256`
     * sidecar asset is missing or unreadable. Mirrors may carry it too.
     */
    @SerialName("digest") val digest: String? = null,
)

/** Read-only access to the repository's public release metadata. */
interface ReleaseApi {

    /**
     * Fetches release metadata from an absolute URL (GitHub API or a mirror).
     * OkHttp follows the redirects GitHub uses for release downloads.
     */
    @GET
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28",
    )
    suspend fun latestRelease(@Url url: String): ReleaseDto
}
