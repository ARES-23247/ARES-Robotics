package com.ares.analytics.service

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UpdateCheckerServiceTest class.
 */
class UpdateCheckerServiceTest {

    @Test
    /**
     * testCheckForUpdatesAvailable fun.
     */
    fun testCheckForUpdatesAvailable() = runBlocking {
        val mockEngine = MockEngine { request ->
            assertEquals("api.github.com", request.url.host)
            assertEquals("/repos/ARES-23247/ARES-Analytics/releases/latest", request.url.encodedPath)
            respond(
                content = """
                {
                    "tag_name": "v99.0.0",
                    "html_url": "https://github.com/ARES-23247/ARES-Analytics/releases/tag/v99.0.0",
                    "body": "Bug fixes and performance improvements"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val service = UpdateCheckerService(client)

        // Trigger update check
        service.checkForUpdates()

        // Wait until update state transitions away from Checking or UpToDate
        var state = service.updateState.value
        var retries = 0
        while ((state is UpdateCheckerService.UpdateState.Checking || state is UpdateCheckerService.UpdateState.UpToDate) && retries < 50) {
            delay(50)
            state = service.updateState.value
            retries++
        }

        println("Actual update state: $state")
        assertTrue(state is UpdateCheckerService.UpdateState.UpdateAvailable)
        assertEquals("v99.0.0", state.latestVersion)
        assertEquals("https://github.com/ARES-23247/ARES-Analytics/releases/tag/v99.0.0", state.downloadUrl)
        assertEquals("Bug fixes and performance improvements", state.releaseNotes)

        service.dispose()
    }

    @Test
    /**
     * testCheckForUpdatesUpToDate fun.
     */
    fun testCheckForUpdatesUpToDate() = runBlocking {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                {
                    "tag_name": "v1.4.0",
                    "html_url": "https://github.com/ARES-23247/ARES-Analytics/releases/tag/v1.4.0",
                    "body": "Initial Release"
                }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val service = UpdateCheckerService(client)

        // Trigger update check
        service.checkForUpdates()
        var state = service.updateState.value
        var retries = 0
        while (state is UpdateCheckerService.UpdateState.Checking && retries < 50) {
            delay(50)
            state = service.updateState.value
            retries++
        }

        assertTrue(state is UpdateCheckerService.UpdateState.UpToDate)
        service.dispose()
    }
}
