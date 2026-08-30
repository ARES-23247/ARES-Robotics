package com.ares.analytics.service

import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parametersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the round-1/round-2 [OAuthService.loadPersistedAuth] rewrite: it must only restore
 * [AuthState.Authenticated] when the persisted-token refresh truly succeeds, stay
 * [AuthState.Unauthenticated] on refresh failure, and stay Unauthenticated when no client
 * config is present. Uses [OAuthService]'s injectable `httpClient` (MockEngine) and
 * `authFilePath` testability seams plus the internal [loadPersistedAuth] entry point so the
 * async init path can be awaited deterministically.
 */
class OAuthServiceTest {

    private val managedClientId = "123456789012-test-client.apps.googleusercontent.com"
    private val managedBrokerUrl = "https://oauth-broker.test"

    private fun resolver(clientId: String = managedClientId) =
        GoogleOAuthClientResolver(clientId, managedBrokerUrl)

    private lateinit var tempDir: File
    private lateinit var authFile: File
    private lateinit var envService: EnvironmentService

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "ares-oauth-test-${System.nanoTime()}").apply { mkdirs() }
        authFile = File(tempDir, "auth.json")
        envService = EnvironmentService(
            configPath = File(tempDir, "config.json").absolutePath,
            workspacesPath = File(tempDir, "workspaces.json").absolutePath
        )
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun writeAuth(refreshToken: String?, expired: Boolean, clientId: String? = managedClientId) {
        val auth = OAuthSavedAuth(
            googleClientId = clientId,
            googleAccessToken = "old-access",
            googleRefreshToken = refreshToken,
            googleTokenExpiresAt = if (expired) System.currentTimeMillis() - 60_000 else System.currentTimeMillis() + 3_600_000,
            googleIdToken = makeIdToken("sub-9", "u@x.com", "User"),
            uid = "sub-9",
            email = "u@x.com",
            displayName = "User"
        )
        authFile.writeText(Json.encodeToString(auth))
    }

    private fun writeConfig(clientId: String?) {
        File(tempDir, "TeamCode/src/main/java/TestRobot.kt").apply {
            parentFile.mkdirs()
            writeText("class TestRobot")
        }
        val workspaces = AppWorkspaces(
            activeWorkspaceId = "ws",
            workspaces = listOf(
                WorkspaceConfig(
                    id = "ws",
                    teamId = "23247",
                    seasonId = "2526",
                    robotId = "r1",
                    projectPath = tempDir.absolutePath,
                    league = League.FTC,
                    googleClientId = clientId,
                    googleClientSecret = "secret"
                )
            )
        )
        File(tempDir, "workspaces.json").writeText(Json.encodeToString(workspaces))
    }

    /** Builds an unsigned JWT whose payload carries the fields [OAuthService] decodes for identity. */
    private fun makeIdToken(sub: String, email: String, name: String): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val payload = """{"sub":"$sub","email":"$email","name":"$name"}"""
        return "header." + encoder.encodeToString(payload.toByteArray()) + ".signature"
    }

    private fun mockClient(refreshSucceeds: Boolean, includeIdToken: Boolean = true): HttpClient = HttpClient(MockEngine { _ ->
        if (refreshSucceeds) {
            val idTokenField = if (includeIdToken) {
                "\"idToken\":\"${makeIdToken("sub-9", "u@x.com", "Refreshed")}\","
            } else {
                ""
            }
            val body = """{"accessToken":"new-access",$idTokenField"expiresIn":3600,"refreshToken":"rt"}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        } else {
            respond("invalid_grant", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
        }
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun delayedSuccessClient(
        requestStarted: CompletableDeferred<Unit>,
        releaseResponse: CompletableDeferred<Unit>,
        requestCount: AtomicInteger = AtomicInteger()
    ): HttpClient = HttpClient(MockEngine { _ ->
        requestCount.incrementAndGet()
        requestStarted.complete(Unit)
        releaseResponse.await()
        val body = """{"accessToken":"new-access","idToken":"${makeIdToken("sub-9", "u@x.com", "Refreshed")}","expiresIn":3600,"refreshToken":"rt"}"""
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun pickerClient(pickerEmail: String): HttpClient {
        val tokenRequests = AtomicInteger()
        return HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/token") -> {
                    val ordinal = tokenRequests.incrementAndGet()
                    val identity = if (ordinal == 1) {
                        "\"idToken\":\"${makeIdToken("sub-9", "u@x.com", "User")}\","
                    } else ""
                    respond(
                        """{"accessToken":"access-$ordinal",${identity}"expiresIn":3600,"refreshToken":"refresh-$ordinal"}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
                request.url.encodedPath.endsWith("/about") -> respond(
                    """{"user":{"emailAddress":"$pickerEmail"}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("Unexpected OAuth picker request ${request.url.encodedPath}")
            }
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    @Test
    fun `refresh success restores Authenticated`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        writeConfig(clientId = "client-123")
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = true),
            loadPersistedAuthOnInit = false
        )
        try {
            service.loadPersistedAuth()
            val state = service.authState.value
            assertTrue(state is AuthState.Authenticated, "Expected Authenticated after successful refresh, got $state")
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `refresh may omit optional id token and retains established identity`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        writeConfig(clientId = "client-123")
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = true, includeIdToken = false),
            loadPersistedAuthOnInit = false
        )
        try {
            service.loadPersistedAuth()
            val state = service.authState.value as AuthState.Authenticated
            assertEquals("User", state.displayName)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `refresh failure clears session and reports recovery`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        writeConfig(clientId = "client-123")
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = false),
            loadPersistedAuthOnInit = false
        )
        try {
            service.loadPersistedAuth()
            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("revoked or expired"))
            assertFalse(authFile.exists())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `build without a managed client leaves Unauthenticated`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        writeConfig(clientId = null)
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(""),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = true), // must never be called
            loadPersistedAuthOnInit = false
        )
        try {
            service.loadPersistedAuth()
            assertEquals(AuthState.Unauthenticated, service.authState.value)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `deleted custom client clears unusable session with administrator recovery`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        val client = HttpClient(MockEngine {
            respond(
                """{"error":"deleted_client","error_description":"The OAuth client was deleted."}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = client,
            loadPersistedAuthOnInit = false,
        )
        try {
            assertNull(service.refreshGoogleAccessTokenForTest(managedClientId))
            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("custom Google OAuth client was deleted"))
            assertFalse(authFile.exists())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `offline startup keeps persisted credentials but does not claim authenticated`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        val client = HttpClient(MockEngine { throw IOException("offline") })
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = client,
            loadPersistedAuthOnInit = false,
        )
        try {
            service.loadPersistedAuth()
            assertEquals(AuthState.Unauthenticated, service.authState.value)
            assertTrue(authFile.isFile, "A temporary network outage must not revoke a reusable refresh token")
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `drive picker returns one folder only for the signed in Google account`() = runBlocking {
        val picked = mutableListOf<String>()
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = pickerClient("u@x.com"),
            loadPersistedAuthOnInit = false,
        )
        try {
            val loginState = service.beginGoogleLoginForTest(managedClientId)
            withTimeout(5_000) {
                assertNotNull(service.dispatchOAuthCallbackForTest(loginState, "login-code")).join()
            }
            assertIs<AuthState.Authenticated>(service.authState.value)

            val pickerState = service.beginGoogleDriveFolderPickerForTest(managedClientId) { picked += it }
            withTimeout(5_000) {
                assertNotNull(
                    service.dispatchOAuthCallbackForTest(
                        pickerState,
                        "picker-code",
                        parametersOf("picked_file_ids", "shared-folder-01"),
                    ),
                ).join()
            }

            assertEquals(listOf("shared-folder-01"), picked)
            assertEquals(DrivePickerState.Selected("shared-folder-01"), service.drivePickerState.value)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `drive picker rejects an account switch before saving a destination`() = runBlocking {
        val picked = mutableListOf<String>()
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = pickerClient("mentor@another-team.example"),
            loadPersistedAuthOnInit = false,
        )
        try {
            val loginState = service.beginGoogleLoginForTest(managedClientId)
            withTimeout(5_000) {
                assertNotNull(service.dispatchOAuthCallbackForTest(loginState, "login-code")).join()
            }
            val pickerState = service.beginGoogleDriveFolderPickerForTest(managedClientId) { picked += it }
            withTimeout(5_000) {
                assertNotNull(
                    service.dispatchOAuthCallbackForTest(
                        pickerState,
                        "picker-code",
                        parametersOf("picked_file_ids", "shared-folder-01"),
                    ),
                ).join()
            }

            assertTrue(picked.isEmpty())
            val error = assertIs<DrivePickerState.Error>(service.drivePickerState.value)
            assertTrue(error.message.contains("ARES is signed in as u@x.com"))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `authorization exchange sends only one-time PKCE material to configured broker`() = runBlocking {
        var observedPath: String? = null
        var observedBody: String? = null
        val client = HttpClient(MockEngine { request ->
            observedPath = request.url.encodedPath
            observedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                """{"accessToken":"access","idToken":"${makeIdToken("sub-9", "u@x.com", "User")}","expiresIn":3600,"refreshToken":"refresh"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = client,
            loadPersistedAuthOnInit = false,
        )
        try {
            val state = service.beginGoogleLoginForTest(managedClientId)
            withTimeout(5_000) {
                assertNotNull(service.dispatchOAuthCallbackForTest(state, "authorization-code")).join()
            }

            assertEquals("/api/oauth/google/token", observedPath)
            val json = Json.parseToJsonElement(assertNotNull(observedBody)).jsonObject
            assertEquals(setOf("code", "codeVerifier", "redirectUri"), json.keys)
            assertEquals("authorization-code", json.getValue("code").jsonPrimitive.content)
            assertEquals(GOOGLE_DESKTOP_REDIRECT_URI, json.getValue("redirectUri").jsonPrimitive.content)
            assertTrue(json.getValue("codeVerifier").jsonPrimitive.content.length >= 43)
            assertTrue(observedBody?.contains(managedClientId) == false)
            assertTrue(observedBody?.contains("client_secret", ignoreCase = true) == false)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `refresh exchange sends the desktop-owned refresh token only to configured broker`() = runBlocking {
        writeAuth(refreshToken = "sensitive-refresh", expired = true)
        var observedPath: String? = null
        var observedBody: String? = null
        val client = HttpClient(MockEngine { request ->
            observedPath = request.url.encodedPath
            observedBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                """{"accessToken":"new-access","expiresIn":3600}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = client,
            loadPersistedAuthOnInit = false,
        )
        try {
            assertEquals("new-access", service.refreshGoogleAccessTokenForTest(managedClientId))

            assertEquals("/api/oauth/google/refresh", observedPath)
            val json = Json.parseToJsonElement(assertNotNull(observedBody)).jsonObject
            assertEquals(setOf("refreshToken"), json.keys)
            assertEquals("sensitive-refresh", json.getValue("refreshToken").jsonPrimitive.content)
            assertTrue(observedBody?.contains(managedClientId) == false)
            assertTrue(observedBody?.contains("client_secret", ignoreCase = true) == false)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `legacy token without issuing client is cleared with recovery guidance`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true, clientId = null)
        writeConfig(clientId = "deleted-legacy.apps.googleusercontent.com")
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = true),
            loadPersistedAuthOnInit = false,
        )
        try {
            service.loadPersistedAuth()

            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("previous Google sign-in"))
            assertFalse(authFile.exists())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `revoked refresh token clears unusable persisted auth`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        writeConfig(clientId = null)
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = false),
            loadPersistedAuthOnInit = false,
        )
        try {
            assertNull(service.refreshGoogleAccessToken())

            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("revoked or expired"))
            assertFalse(authFile.exists())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `deleted managed client clears unusable persisted auth with recovery guidance`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        val client = HttpClient(MockEngine {
            respond(
                """{"error":"deleted_client","errorDescription":"ARES Google sign-in is temporarily unavailable."}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = client,
            loadPersistedAuthOnInit = false,
        )
        try {
            assertNull(service.refreshGoogleAccessToken())

            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("ARES Google sign-in client is unavailable"))
            assertFalse(authFile.exists())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `logout generation rejects a delayed refresh commit`() = runBlocking {
        writeAuth(refreshToken = "rt", expired = true)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = delayedSuccessClient(requestStarted, releaseResponse),
            loadPersistedAuthOnInit = false
        )
        try {
            val refresh = async(Dispatchers.Default) {
                service.refreshGoogleAccessTokenForTest(managedClientId)
            }
            withTimeout(5_000) { requestStarted.await() }

            service.logout()
            releaseResponse.complete(Unit)

            assertNull(withTimeout(5_000) { refresh.await() })
            assertEquals(AuthState.Unauthenticated, service.authState.value)
            assertFalse(authFile.exists(), "A stale refresh must not recreate auth.json after logout")
        } finally {
            releaseResponse.complete(Unit)
            service.dispose()
        }
    }

    @Test
    fun `logout cancels a delayed authorization-code exchange`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = delayedSuccessClient(requestStarted, releaseResponse),
            loadPersistedAuthOnInit = false
        )
        try {
            val state = service.beginGoogleLoginForTest(managedClientId)
            val exchangeJob = assertNotNull(service.dispatchOAuthCallbackForTest(state, "authorization-code"))
            withTimeout(5_000) { requestStarted.await() }

            service.logout()
            releaseResponse.complete(Unit)
            withTimeout(5_000) { exchangeJob.join() }

            assertTrue(exchangeJob.isCancelled, "Logout must cancel service-owned token exchanges")
            assertEquals(AuthState.Unauthenticated, service.authState.value)
            assertFalse(authFile.exists(), "A canceled exchange must not recreate auth.json after logout")
        } finally {
            releaseResponse.complete(Unit)
            service.dispose()
        }
    }

    @Test
    fun `oauth state is consumed exactly once across duplicate callbacks`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>().apply { complete(Unit) }
        val requestCount = AtomicInteger()
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = delayedSuccessClient(requestStarted, releaseResponse, requestCount),
            loadPersistedAuthOnInit = false
        )
        try {
            val state = service.beginGoogleLoginForTest(managedClientId)
            val dispatched = listOf("code-a", "code-b").map { code ->
                async(Dispatchers.Default) { service.dispatchOAuthCallbackForTest(state, code) }
            }.awaitAll()
            val accepted = dispatched.filterNotNull()

            assertEquals(1, accepted.size, "Only one callback may consume an OAuth state value")
            withTimeout(5_000) { accepted.single().join() }
            assertEquals(1, requestCount.get(), "Duplicate callbacks must not start another token exchange")
            assertTrue(service.authState.value is AuthState.Authenticated)
            assertTrue(authFile.isFile)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `credential replace failure preserves prior bytes and never publishes Authenticated`() = runBlocking {
        val previousBytes = "previous-auth-state".toByteArray()
        authFile.writeBytes(previousBytes)
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(),
            authFilePath = authFile.absolutePath,
            httpClient = mockClient(refreshSucceeds = true),
            loadPersistedAuthOnInit = false,
            secretsWriter = { file, bytes ->
                writeSecrets(file, bytes) { _, _ ->
                    throw IOException("injected auth replace failure")
                }
            },
        )
        try {
            val state = service.beginGoogleLoginForTest(managedClientId)
            val exchange = assertNotNull(service.dispatchOAuthCallbackForTest(state, "authorization-code"))
            withTimeout(5_000) { exchange.join() }

            val failure = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(failure.message.contains("could not be saved"))
            assertTrue(previousBytes.contentEquals(authFile.readBytes()))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun `interactive login without a client id fails with setup guidance`() {
        val service = OAuthService(
            environmentService = envService,
            googleClientResolver = resolver(""),
            httpClient = mockClient(refreshSucceeds = false),
            authFilePath = authFile.absolutePath,
            loadPersistedAuthOnInit = false,
        )
        try {
            service.startGoogleLogin()

            val error = assertIs<AuthState.Error>(service.authState.value)
            assertTrue(error.message.contains("unavailable in this build"))
        } finally {
            service.dispose()
        }
    }
}
