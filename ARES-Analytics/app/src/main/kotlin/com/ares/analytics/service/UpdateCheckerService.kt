
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
    val body: String? = null
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

        data class UpdateAvailable(val latestVersion: String, val downloadUrl: String, val releaseNotes: String?) : UpdateState()

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
                        if (isNewerVersion(BuildConfig.VERSION, release.tagName)) {
                            _updateState.value = UpdateState.UpdateAvailable(
                                latestVersion = release.tagName,
                                downloadUrl = release.htmlUrl,
                                releaseNotes = release.body
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

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.removePrefix("v").trim()
        val cleanLatest = latest.removePrefix("v").trim()
        if (cleanCurrent == cleanLatest) return false
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLength) {
            val currVal = currentParts.getOrElse(i) { 0 }
            val latVal = latestParts.getOrElse(i) { 0 }
            if (latVal > currVal) return true
            if (currVal > latVal) return false
        }
        return false
    }
}
