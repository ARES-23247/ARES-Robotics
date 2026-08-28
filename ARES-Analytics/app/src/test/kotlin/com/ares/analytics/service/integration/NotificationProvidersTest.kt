package com.ares.analytics.service.integration

import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationSettings
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.SessionImported
import com.ares.analytics.shared.models.WebhookNotificationTarget
import com.ares.analytics.shared.models.ZulipNotificationTarget
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotificationProvidersTest {
    private val event = IntegrationEvent(
        eventId = "session-imported:session-1",
        occurredAtMs = 1_000L,
        payload = SessionImported(
            workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin"),
            sessionId = "session-1",
            sourceNames = listOf("robot.csv.gz"),
            sourceSha256 = listOf("a".repeat(64)),
        ),
    )

    @Test
    fun `zulip provider uses bot auth stream topic and idempotency key`() = runTest {
        var requestBody = ""
        val client = HttpClient(MockEngine { request ->
            requestBody = request.body.toByteArray().toString(Charsets.UTF_8)
            assertEquals("Basic Ym90QGV4YW1wbGUuY29tOnRlc3QtYXBpLWtleQ==", request.headers[HttpHeaders.Authorization])
            assertEquals(event.eventId, request.headers["Idempotency-Key"])
            respond(
                content = "{\"result\":\"success\",\"id\":42}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val provider = ZulipNotificationProvider(
            config = zulipConfig(),
            credential = IntegrationCredential("bot@example.com", "test-api-key"),
            httpClient = client,
        )

        val result = assertIs<IntegrationDeliveryResult.Delivered>(provider.deliver(event))

        assertTrue(requestBody.contains("type=stream"))
        assertTrue(requestBody.contains("to=robot-alerts"))
        assertTrue(requestBody.contains("topic=ARES+Studio"))
        assertTrue(requestBody.contains("session-1"))
        assertTrue(result.receiptJson.orEmpty().contains("42"))
}
    @Test
    fun `zulip rate limit becomes bounded retry instruction`() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = "rate limited",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.RetryAfter, "7"),
            )
        })
        val provider = ZulipNotificationProvider(
            zulipConfig(),
            credential = IntegrationCredential("bot@example.com", "test-api-key"),
            httpClient = client,
        )

        val result = assertIs<IntegrationDeliveryResult.Retry>(provider.deliver(event))

        assertEquals(DeliveryErrorKind.RATE_LIMITED, result.errorKind)
        assertEquals(7_000L, result.retryAfterMs)
    }

    @Test
    fun `webhook signs the exact body and carries event identity`() = runTest {
        var body = ""
        val secret = "webhook-secret-value"
        val client = HttpClient(MockEngine { request ->
            body = request.body.toByteArray().toString(Charsets.UTF_8)
            assertEquals(event.eventId, request.headers["Idempotency-Key"])
            assertEquals(event.eventId, request.headers["X-ARES-Event-Id"])
            assertEquals("v1=${hmac(secret, body)}", request.headers["X-ARES-Signature"])
            respond("accepted", HttpStatusCode.Accepted)
        })
        val provider = WebhookNotificationProvider(
            config = NotificationProviderConfig(
                providerId = "webhook.primary",
                displayName = "Team CMS",
                kind = NotificationProviderKind.WEBHOOK,
                eventTypes = setOf(IntegrationEventType.SESSION_IMPORTED),
                webhook = WebhookNotificationTarget("https://cms.example.org/hooks/ares"),
            ),
            credential = IntegrationCredential(secret = secret),
            httpClient = client,
        )

        assertIs<IntegrationDeliveryResult.Delivered>(provider.deliver(event))
        assertTrue(body.contains(event.eventId))
        assertTrue(body.contains("SESSION_IMPORTED"))
        assertFalse(body.contains(secret))
    }

    @Test
    fun `settings reject non TLS provider endpoints`() = runTest {
        val tempDirectory = Files.createTempDirectory("ares-integration-settings").toFile()
        try {
            val service = IntegrationSettingsService(
                settingsFile = tempDirectory.resolve("integrations.json"),
                credentialStore = FakeCredentialStore(),
            )
            assertFailsWith<IllegalArgumentException> {
                service.save(
                    IntegrationSettings(
                        notificationProviders = listOf(
                            zulipConfig().copy(
                                zulip = ZulipNotificationTarget(
                                    "http://zulip.example.org",
                                    "robot-alerts",
                                    "ARES Studio",
                                )
                            )
                        )
                    )
                )
            }
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `protected credential store never writes plaintext secret`() {
        val tempDirectory = Files.createTempDirectory("ares-integration-credentials").toFile()
        val file = tempDirectory.resolve("credentials.bin")
        try {
            val transform: (ByteArray) -> ByteArray = { bytes -> bytes.map { (it.toInt() xor 0x5A).toByte() }.toByteArray() }
            val store = ProtectedIntegrationCredentialStore(
                file = file,
                protect = transform,
                unprotect = transform,
                protectionDescription = "test transform",
            )

            store.write("zulip.primary", IntegrationCredential("bot@example.com", "secret-api-key"))

            assertFalse(file.readText(Charsets.ISO_8859_1).contains("secret-api-key"))
            assertEquals("secret-api-key", store.read("zulip.primary")?.secret)
            assertTrue(store.delete("zulip.primary"))
            assertEquals(null, store.read("zulip.primary"))
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    private fun zulipConfig() = NotificationProviderConfig(
        providerId = "zulip.primary",
        displayName = "Team Zulip",
        kind = NotificationProviderKind.ZULIP,
        eventTypes = setOf(IntegrationEventType.SESSION_IMPORTED),
        zulip = ZulipNotificationTarget(
            siteUrl = "https://zulip.example.org",
            stream = "robot-alerts",
            topic = "ARES Studio",
        ),
    )

    private fun hmac(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private class FakeCredentialStore : IntegrationCredentialStore {
        private val values = mutableMapOf<String, IntegrationCredential>()
        override fun read(providerId: String): IntegrationCredential? = values[providerId]
        override fun write(providerId: String, credential: IntegrationCredential) {
            values[providerId] = credential
        }
        override fun delete(providerId: String): Boolean = values.remove(providerId) != null
        override val protectionDescription: String = "test"
    }
}
