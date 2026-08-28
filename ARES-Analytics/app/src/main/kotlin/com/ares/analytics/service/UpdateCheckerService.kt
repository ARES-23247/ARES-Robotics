
package com.ares.analytics.service

import com.ares.analytics.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * GitHub Release payload record returned by GitHub REST API (`/releases/latest`).
 *
 * @property tagName Release version tag string (e.g. `"v2.4.0"`).
 * @property htmlUrl Direct browser URL to release release release page.
 * @property body Optional markdown release notes text.
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long,
    @SerialName("content_type") val contentType: String? = null,
)

data class WindowsUpdateCandidate(
    val version: String,
    val installerName: String,
    val installerUrl: String,
    val checksumUrl: String,
    val sizeBytes: Long,
    val releasePageUrl: String,
    val releaseNotes: String?,
)

/**
 * Service checking for application software updates via GitHub Releases API.
 *
 * Compares current application version ([BuildConfig.VERSION]) against the latest release published to
 * `https://api.github.com/repos/ARES-23247/ARES-Analytics/releases/latest`.
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes HTTP requests asynchronously within [serviceScope] on `Dispatchers.IO`. Updates UI state via [StateFlow].
 *
 * @param httpClient Ktor HTTP client configured with JSON serialization.
 * @param serviceScope Coroutine scope for update checking tasks.
 *
 * @see GitHubRelease
 */
class UpdateCheckerService(
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    sealed class UpdateState {

        object Checking : UpdateState()

        object UpToDate : UpdateState()

        data class UpdateAvailable(
            val latestVersion: String,
            val downloadUrl: String,
            val releaseNotes: String?,
            val windowsCandidate: WindowsUpdateCandidate? = null,
        ) : UpdateState()

        data class Error(val message: String) : UpdateState()
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.UpToDate)
    val updateState: StateFlow<UpdateState> = _updateState

    fun checkForUpdates() {
        serviceScope.launch {
            _updateState.value = UpdateState.Checking
            try {
                httpClient.prepareGet("https://api.github.com/repos/ARES-23247/ARES-Analytics/releases/latest") {
                    header(HttpHeaders.UserAgent, "ares-analytics-app")
                }.execute { response ->
                    if (response.status == HttpStatusCode.OK) {
                        val release = response.body<GitHubRelease>()
                        if (!release.draft && !release.prerelease && isSemanticVersionNewer(BuildConfig.VERSION, release.tagName)) {
                            _updateState.value = UpdateState.UpdateAvailable(
                                latestVersion = release.tagName,
                                downloadUrl = release.htmlUrl,
                                releaseNotes = release.body,
                                windowsCandidate = release.windowsUpdateCandidate(),
                            )
                        } else {
                            _updateState.value = UpdateState.UpToDate
                        }
                    } else {
                        _updateState.value = UpdateState.Error("API returned status ${response.status}")
                    }
                }
            } catch (e: Exception) {
                _updateState.value = UpdateState.Error(e.message ?: "Unknown error checking updates")
            }
        }
    }

    fun dispose() {
        serviceScope.coroutineContext.cancelChildren()
    }

}

internal fun isSemanticVersionNewer(current: String, latest: String): Boolean {
    fun parse(value: String): List<Int>? {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim()) ?: return null
        return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
    }
    val currentParts = parse(current) ?: return false
    val latestParts = parse(latest) ?: return false
    return latestParts.zip(currentParts).firstOrNull { (next, installed) -> next != installed }
        ?.let { (next, installed) -> next > installed }
        ?: false
}

private fun GitHubRelease.windowsUpdateCandidate(): WindowsUpdateCandidate? {
    val installers = assets.filter { it.name.endsWith(".msi", ignoreCase = true) }
    if (installers.size != 1) return null
    val installer = installers.single()
    val checksum = assets.singleOrNull { it.name == "${installer.name}.sha256" } ?: return null
    if (installer.size <= 0L) return null
    return WindowsUpdateCandidate(
        version = tagName.removePrefix("v"),
        installerName = installer.name,
        installerUrl = installer.browserDownloadUrl,
        checksumUrl = checksum.browserDownloadUrl,
        sizeBytes = installer.size,
        releasePageUrl = htmlUrl,
        releaseNotes = body,
    )
}
