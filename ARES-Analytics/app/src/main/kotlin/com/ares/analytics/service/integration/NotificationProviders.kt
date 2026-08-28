package com.ares.analytics.service.integration

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.AnalysisReady
import com.ares.analytics.shared.models.CloudUploadCommitted
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventHasher
import com.ares.analytics.shared.models.IntegrationIssueSeverity
import com.ares.analytics.shared.models.IntegrationTestRequested
import com.ares.analytics.shared.models.NotebookDraftReady
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.RobotIssueOpened
import com.ares.analytics.shared.models.RobotIssueResolved
import com.ares.analytics.shared.models.SessionImported
import com.ares.analytics.shared.models.SoftwareDigestReady
import com.ares.analytics.shared.models.WebhookNotificationTarget
import com.ares.analytics.shared.models.ZulipNotificationTarget
import com.ares.analytics.shared.models.eventType
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.ContentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class NotificationCapabilities(
    val supportsConnectionTest: Boolean,
    val supportsStreamsAndTopics: Boolean,
    val maximumMessageCharacters: Int,
)

sealed interface ProviderConnectionResult {
    data class Connected(val identity: String? = null) : ProviderConnectionResult
    data class Failed(val errorKind: DeliveryErrorKind, val safeMessage: String) : ProviderConnectionResult
    data object Unsupported : ProviderConnectionResult
}

interface NotificationProvider : IntegrationDeliveryProvider {
    val capabilities: NotificationCapabilities
    suspend fun testConnection(): ProviderConnectionResult
}

internal data class NotificationMessage(
    val subject: String,
    val markdown: String,
)

internal object NotificationMessageFormatter {
    fun format(event: IntegrationEvent): NotificationMessage {
        val workspace = event.payload.workspace
        val identity = "Team ${safeInline(workspace.teamId)} · ${safeInline(workspace.seasonId)} · ${safeInline(workspace.robotId)}"
        val (subject, details) = when (val payload = event.payload) {
            is SessionImported -> "New robot log imported" to listOf(
                "Session: `${safeInline(payload.sessionId)}`",
                "Files: ${payload.sourceNames.safeInline()}",
            )
            is AnalysisReady -> "Robot log analysis ready" to listOf(
                "Session: `${safeInline(payload.sessionId)}`",
                "Analysis: `${safeInline(payload.analysisVersion)}`",
            )
            is RobotIssueOpened -> "${payload.severity.name.lowercase().replaceFirstChar(Char::uppercase)} robot issue" to listOf(
                "Issue: `${safeInline(payload.issueId)}`",
                "Rule: `${safeInline(payload.ruleKey)}`",
                safeInline(payload.summary),
            )
            is RobotIssueResolved -> "Robot issue resolved" to listOfNotNull(
                "Issue: `${safeInline(payload.issueId)}`",
                payload.resolution?.let(::safeInline),
            )
            is CloudUploadCommitted -> "Robot log uploaded" to listOfNotNull(
                "Session: `${safeInline(payload.sessionId)}`",
                "Object: `${safeInline(payload.remoteObjectId)}`",
                payload.remoteUrl?.takeIf(::isSafeHttpsUrl)?.let { "[Open uploaded log]($it)" },
            )
            is NotebookDraftReady -> "Engineering notebook draft ready" to listOf(
                "Entry: `${safeInline(payload.entryId)}`",
                "Revision: ${payload.revision}",
            )
            is SoftwareDigestReady -> "Software change summary ready" to listOf(
                "Entry: `${safeInline(payload.entryId)}`",
                "Commits: `${safeInline(payload.commitRange)}`",
            )
            is IntegrationTestRequested -> "Integration test message" to listOf(
                "Provider: `${safeInline(payload.targetProviderId)}`",
                "This durable message confirms that Studio's notification outbox can reach the configured destination.",
            )
        }
        val markdown = buildString {
            append("**ARES Studio — ").append(subject).appendLine("**")
            appendLine(identity)
            details.forEach { detail -> append("- ").appendLine(detail) }
            append("Event: `").append(safeInline(event.eventId)).append('`')
        }.take(MAX_NOTIFICATION_CHARACTERS)
        return NotificationMessage(subject, markdown)
    }

    fun passesSeverity(config: NotificationProviderConfig, event: IntegrationEvent): Boolean {
        val severity = (event.payload as? RobotIssueOpened)?.severity ?: return true
        return severity.ordinal >= config.minimumIssueSeverity.ordinal
    }

    private fun safeInline(value: String): String = value
        .filterNot(Char::isISOControl)
        .replace('`', '\'')
        .replace('<', '[')
        .replace('>', ']')
        .take(512)

    private fun Iterable<String>.safeInline(): String = joinToString(", ") { safeInline(it) }.take(1_024)

    private fun isSafeHttpsUrl(value: String): Boolean = runCatching {
        URI(value).let { it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.userInfo == null }
    }.getOrDefault(false)

    const val MAX_NOTIFICATION_CHARACTERS = 10_000
}

class ZulipNotificationProvider(
    private val config: NotificationProviderConfig,
    private val target: ZulipNotificationTarget = requireNotNull(config.zulip),
    private val credential: IntegrationCredential,
    private val httpClient: HttpClient = notificationHttpClient(),
) : NotificationProvider {
    override val providerId: String = config.providerId
    override val capabilities = NotificationCapabilities(
        supportsConnectionTest = true,
        supportsStreamsAndTopics = true,
        maximumMessageCharacters = NotificationMessageFormatter.MAX_NOTIFICATION_CHARACTERS,
    )
    private val siteUrl = validateHttpsBaseUrl(target.siteUrl)
    private val botEmail = requireNotNull(credential.principal?.trim()?.takeIf(String::isNotEmpty)) {
        "Zulip bot email is required"
    }

    init {
        require(target.stream.isNotBlank() && target.stream.length <= 128) { "Zulip stream is invalid" }
        require(target.topic.isNotBlank() && target.topic.length <= 256) { "Zulip topic is invalid" }
    }

    override suspend fun deliver(event: IntegrationEvent): IntegrationDeliveryResult {
        if (!NotificationMessageFormatter.passesSeverity(config, event)) {
            return IntegrationDeliveryResult.Delivered("{\"filtered\":true}")
        }
        val message = NotificationMessageFormatter.format(event)
        val response = httpClient.submitForm(
            url = "$siteUrl/api/v1/messages",
            formParameters = Parameters.build {
                append("type", "stream")
                append("to", target.stream)
                append("topic", target.topic)
                append("content", message.markdown)
            },
        ) {
            basicBotAuth()
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("Idempotency-Key", event.eventId)
        }
        val responseBody = response.bodyAsText().take(MAX_RESPONSE_CHARACTERS)
        return classifyHttpResponse(response.status, response.headers[HttpHeaders.RetryAfter]) {
            val messageId = runCatching {
                AppJson.parseToJsonElement(responseBody).jsonObject["id"]?.jsonPrimitive?.content
            }.getOrNull()
            AppJson.encodeToString(NotificationHttpReceipt(response.status.value, messageId))
        }
    }

    override suspend fun testConnection(): ProviderConnectionResult = try {
        val response = httpClient.get("$siteUrl/api/v1/users/me") {
            basicBotAuth()
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status.value in 200..299) ProviderConnectionResult.Connected(botEmail)
        else responseToConnectionFailure(response.status)
    } catch (failure: Exception) {
        ProviderConnectionResult.Failed(
            DeliveryErrorKind.TRANSIENT,
            failure.message?.take(512) ?: failure::class.java.simpleName,
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.basicBotAuth() {
        val encoded = Base64.getEncoder().encodeToString(
            "$botEmail:${credential.secret}".toByteArray(Charsets.UTF_8)
        )
        header(HttpHeaders.Authorization, "Basic $encoded")
    }
}

@Serializable
private data class WebhookEventEnvelope(
    val schemaVersion: Int,
    val eventId: String,
    val eventType: String,
    val occurredAtMs: Long,
    val contentHash: String,
    val event: IntegrationEvent,
)

@Serializable
private data class NotificationHttpReceipt(
    val status: Int,
    val remoteId: String? = null,
)

class WebhookNotificationProvider(
    private val config: NotificationProviderConfig,
    target: WebhookNotificationTarget = requireNotNull(config.webhook),
    private val credential: IntegrationCredential,
    private val httpClient: HttpClient = notificationHttpClient(),
) : NotificationProvider {
    override val providerId: String = config.providerId
    override val capabilities = NotificationCapabilities(
        supportsConnectionTest = false,
        supportsStreamsAndTopics = false,
        maximumMessageCharacters = NotificationMessageFormatter.MAX_NOTIFICATION_CHARACTERS,
    )
    private val url = validateHttpsEndpoint(target.url)

    override suspend fun deliver(event: IntegrationEvent): IntegrationDeliveryResult {
        if (!NotificationMessageFormatter.passesSeverity(config, event)) {
            return IntegrationDeliveryResult.Delivered("{\"filtered\":true}")
        }
        val body = AppJson.encodeToString(
            WebhookEventEnvelope(
                schemaVersion = 1,
                eventId = event.eventId,
                eventType = event.payload.eventType().name,
                occurredAtMs = event.occurredAtMs,
                contentHash = IntegrationEventHasher.sha256(event),
                event = event,
            )
        )
        val signature = hmacSha256(credential.secret, body)
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, USER_AGENT)
            header("Idempotency-Key", event.eventId)
            header("X-ARES-Event-Id", event.eventId)
            header("X-ARES-Signature", "v1=$signature")
            setBody(body)
        }
        response.bodyAsText().take(MAX_RESPONSE_CHARACTERS)
        return classifyHttpResponse(response.status, response.headers[HttpHeaders.RetryAfter]) {
            AppJson.encodeToString(NotificationHttpReceipt(response.status.value))
        }
    }

    override suspend fun testConnection(): ProviderConnectionResult = ProviderConnectionResult.Unsupported
}

internal fun notificationHttpClient(): HttpClient = HttpClient(CIO) {
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000L
        requestTimeoutMillis = 20_000L
        socketTimeoutMillis = 20_000L
    }
}

private fun classifyHttpResponse(
    status: HttpStatusCode,
    retryAfterHeader: String?,
    receipt: () -> String,
): IntegrationDeliveryResult = when (status.value) {
    in 200..299 -> IntegrationDeliveryResult.Delivered(receipt())
    401, 403 -> IntegrationDeliveryResult.Rejected(
        DeliveryErrorKind.AUTHENTICATION,
        "Notification provider rejected its credentials",
    )
    404 -> IntegrationDeliveryResult.Rejected(
        DeliveryErrorKind.CONFIGURATION,
        "Notification target was not found",
    )
    408, 425 -> IntegrationDeliveryResult.Retry(
        DeliveryErrorKind.TRANSIENT,
        "Notification provider timed out",
    )
    429 -> IntegrationDeliveryResult.Retry(
        DeliveryErrorKind.RATE_LIMITED,
        "Notification provider rate limit was reached",
        retryAfterMs = retryAfterHeader?.toLongOrNull()?.coerceIn(1L, 86_400L)?.times(1_000L),
    )
    in 500..599 -> IntegrationDeliveryResult.Retry(
        DeliveryErrorKind.TRANSIENT,
        "Notification provider is temporarily unavailable (${status.value})",
    )
    else -> IntegrationDeliveryResult.Rejected(
        DeliveryErrorKind.PAYLOAD,
        "Notification provider rejected the request (${status.value})",
    )
}

private fun responseToConnectionFailure(status: HttpStatusCode): ProviderConnectionResult.Failed = when (status.value) {
    401, 403 -> ProviderConnectionResult.Failed(
        DeliveryErrorKind.AUTHENTICATION,
        "Notification provider rejected its credentials",
    )
    404 -> ProviderConnectionResult.Failed(
        DeliveryErrorKind.CONFIGURATION,
        "Notification provider endpoint was not found",
    )
    else -> ProviderConnectionResult.Failed(
        DeliveryErrorKind.TRANSIENT,
        "Notification provider returned HTTP ${status.value}",
    )
}

private fun validateHttpsBaseUrl(value: String): String = validateHttpsEndpoint(value).trimEnd('/')

private fun validateHttpsEndpoint(value: String): String {
    val uri = runCatching { URI(value.trim()) }
        .getOrElse { throw IllegalArgumentException("Integration URL is invalid") }
    require(uri.scheme.equals("https", ignoreCase = true)) { "Integration URL must use HTTPS" }
    require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
        "Integration URL must have a host and cannot contain credentials or a fragment"
    }
    return uri.toASCIIString()
}

private fun hmacSha256(secret: String, body: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private const val USER_AGENT = "ares-robotics-studio-integrations/1"
private const val MAX_RESPONSE_CHARACTERS = 16_384
