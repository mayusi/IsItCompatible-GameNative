package app.gamenative.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs for the GitHub Releases API response used by [AppUpdateChecker].
 * Ported from io.github.mayusi.isitcompatible.getit.GithubReleaseDto.
 */
@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
data class GhAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)
