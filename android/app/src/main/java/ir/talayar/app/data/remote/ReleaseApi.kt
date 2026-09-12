package ir.talayar.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url

/** Release metadata used by the in-app update checker. */
@Serializable
data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    val assets: List<ReleaseAssetDto> = emptyList(),
)

@Serializable
data class ReleaseAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
)

/** Read-only access to the repository's public release metadata. */
interface ReleaseApi {

    @GET
    @Headers("Accept: application/vnd.github+json")
    suspend fun latestRelease(@Url url: String): ReleaseDto
}
