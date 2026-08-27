package com.ares.analytics.gateway.routes

import com.ares.analytics.shared.GoogleAuthorizationCodeExchangeRequest
import com.ares.analytics.shared.GoogleRefreshTokenExchangeRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GoogleOAuthBrokerRoutesTest {
    private val validVerifier = "a".repeat(43)

    @Test
    fun `authorization code exchange adds protected client credentials`() = runBlocking {
        val submittedForm = AtomicReference<FormDataContent>()
        val broker = brokerWith { request ->
            submittedForm.set(assertIs<FormDataContent>(request.body))
            respond(
                content = """{
                    "access_token":"access-token",
                    "expires_in":3600,
                    "refresh_token":"refresh-token",
                    "id_token":"identity-token",
                    "token_type":"Bearer"
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val result = assertIs<GoogleBrokerResult.Success>(
            broker.exchangeAuthorizationCode(
                GoogleAuthorizationCodeExchangeRequest(
                    code = "one-time-code",
                    codeVerifier = validVerifier,
                    redirectUri = ARES_GOOGLE_REDIRECT_URI,
                ),
            ),
        )

        assertEquals("access-token", result.tokens.accessToken)
        assertEquals("refresh-token", result.tokens.refreshToken)
        with(submittedForm.get().formData) {
            assertEquals(clientId, this["client_id"])
            assertEquals("protected-secret", this["client_secret"])
            assertEquals("one-time-code", this["code"])
            assertEquals(validVerifier, this["code_verifier"])
            assertEquals(ARES_GOOGLE_REDIRECT_URI, this["redirect_uri"])
            assertEquals("authorization_code", this["grant_type"])
            assertNull(this["refresh_token"])
        }
    }

    @Test
    fun `malformed verifier and redirect are rejected without contacting Google`() = runBlocking {
        val googleRequests = AtomicInteger()
        val broker = brokerWith {
            googleRequests.incrementAndGet()
            error("Google must not receive an invalid desktop exchange")
        }

        val badVerifier = assertIs<GoogleBrokerResult.Failure>(
            broker.exchangeAuthorizationCode(
                GoogleAuthorizationCodeExchangeRequest(
                    code = "code",
                    codeVerifier = "too-short",
                    redirectUri = ARES_GOOGLE_REDIRECT_URI,
                ),
            ),
        )
        val badRedirect = assertIs<GoogleBrokerResult.Failure>(
            broker.exchangeAuthorizationCode(
                GoogleAuthorizationCodeExchangeRequest(
                    code = "code",
                    codeVerifier = validVerifier,
                    redirectUri = "http://localhost:5805/callback",
                ),
            ),
        )

        assertEquals(HttpStatusCode.BadRequest, badVerifier.status)
        assertEquals("invalid_request", badVerifier.error.error)
        assertEquals(HttpStatusCode.BadRequest, badRedirect.status)
        assertEquals(0, googleRequests.get())
    }

    @Test
    fun `missing protected configuration fails closed`() = runBlocking {
        val googleRequests = AtomicInteger()
        val client = mockGoogleClient {
            googleRequests.incrementAndGet()
            error("Google must not be contacted without complete server credentials")
        }
        val broker = GoogleOAuthBroker(
            clientId = clientId,
            clientSecret = "",
            httpClient = client,
        )

        val result = assertIs<GoogleBrokerResult.Failure>(
            broker.exchangeAuthorizationCode(
                GoogleAuthorizationCodeExchangeRequest("code", validVerifier, ARES_GOOGLE_REDIRECT_URI),
            ),
        )

        assertEquals(HttpStatusCode.ServiceUnavailable, result.status)
        assertEquals("broker_unavailable", result.error.error)
        assertEquals(0, googleRequests.get())
        client.close()
    }

    @Test
    fun `Google failures return allowlisted recovery text without upstream credentials`() = runBlocking {
        val broker = brokerWith {
            respond(
                content = """{
                    "error":"invalid_grant",
                    "error_description":"protected-secret $clientId one-time-code refresh-token"
                }""".trimIndent(),
                status = HttpStatusCode.BadRequest,
                headers = jsonHeaders,
            )
        }

        val result = assertIs<GoogleBrokerResult.Failure>(
            broker.exchangeAuthorizationCode(
                GoogleAuthorizationCodeExchangeRequest("one-time-code", validVerifier, ARES_GOOGLE_REDIRECT_URI),
            ),
        )
        val rendered = "${result.error.error} ${result.error.errorDescription}"

        assertEquals(HttpStatusCode.BadRequest, result.status)
        assertEquals("invalid_grant", result.error.error)
        assertTrue("Sign in again" in rendered)
        listOf("protected-secret", clientId, "one-time-code", "refresh-token").forEach {
            assertTrue(it !in rendered)
        }
    }

    @Test
    fun `refresh exchange uses the server credential and returns refreshed tokens`() = runBlocking {
        val submittedForm = AtomicReference<FormDataContent>()
        val broker = brokerWith { request ->
            submittedForm.set(assertIs<FormDataContent>(request.body))
            respond(
                content = """{"access_token":"fresh-access","expires_in":1800}""",
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }

        val result = assertIs<GoogleBrokerResult.Success>(
            broker.refresh(GoogleRefreshTokenExchangeRequest("desktop-refresh-token")),
        )

        assertEquals("fresh-access", result.tokens.accessToken)
        assertNull(result.tokens.refreshToken)
        with(submittedForm.get().formData) {
            assertEquals(clientId, this["client_id"])
            assertEquals("protected-secret", this["client_secret"])
            assertEquals("desktop-refresh-token", this["refresh_token"])
            assertEquals("refresh_token", this["grant_type"])
            assertNull(this["code"])
            assertNull(this["redirect_uri"])
        }
    }

    @Test
    fun `proxy-aware rate limiting keeps distinct Cloud Run clients in distinct buckets`() =
        testApplication {
            application {
                install(XForwardedHeaders)
                install(RateLimit) {
                    register(RateLimitName("per-source")) {
                        requestKey { call -> call.request.origin.remoteHost }
                        rateLimiter(limit = 1, refillPeriod = 60.seconds)
                    }
                }
                routing {
                    rateLimit(RateLimitName("per-source")) {
                        get("/limited") { call.respondText("ok") }
                    }
                }
            }

            val firstClientFirstRequest = client.get("/limited") {
                header(HttpHeaders.XForwardedFor, "198.51.100.10")
            }
            val firstClientSecondRequest = client.get("/limited") {
                header(HttpHeaders.XForwardedFor, "198.51.100.10")
            }
            val secondClientFirstRequest = client.get("/limited") {
                header(HttpHeaders.XForwardedFor, "198.51.100.11")
            }

            assertEquals(HttpStatusCode.OK, firstClientFirstRequest.status)
            assertEquals(HttpStatusCode.TooManyRequests, firstClientSecondRequest.status)
            assertEquals(HttpStatusCode.OK, secondClientFirstRequest.status)
        }

    @Test
    fun `malformed desktop JSON returns a safe client error`() = testApplication {
        application {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(RateLimit) {
                register(RateLimitName("oauth-exchange-global")) {
                    rateLimiter(limit = 10, refillPeriod = 60.seconds)
                }
                register(RateLimitName("oauth-exchange")) {
                    rateLimiter(limit = 10, refillPeriod = 60.seconds)
                }
            }
            routing {
                googleOAuthBrokerRoutes(brokerWith { error("Google must not receive malformed JSON") })
            }
        }

        val response = client.post("/api/oauth/google/token") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("invalid_request" in response.bodyAsText())
    }

    private fun brokerWith(handler: io.ktor.client.engine.mock.MockRequestHandler): GoogleOAuthBroker =
        GoogleOAuthBroker(
            clientId = clientId,
            clientSecret = "protected-secret",
            httpClient = mockGoogleClient(handler),
        )

    private fun mockGoogleClient(handler: io.ktor.client.engine.mock.MockRequestHandler): HttpClient =
        HttpClient(MockEngine(handler)) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

    private companion object {
        const val clientId = "123456789012-gateway-test.apps.googleusercontent.com"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}
