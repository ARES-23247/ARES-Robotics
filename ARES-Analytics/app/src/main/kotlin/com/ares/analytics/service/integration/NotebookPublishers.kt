package com.ares.analytics.service.integration

import com.ares.analytics.service.GoogleDriveService
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.NotebookDraftReady
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.SoftwareDigestReady
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI

data class NotebookPublisherCapabilities(
    val requiresApproval: Boolean,
    val supportsRemoteStatus: Boolean,
    val supportsAttachments: Boolean,
)

sealed interface NotebookPublishResult {
    data class Published(val receipt: PublicationReceipt) : NotebookPublishResult
    data class Retry(
        val errorKind: DeliveryErrorKind,
        val safeMessage: String,
        val retryAfterMs: Long? = null,
    ) : NotebookPublishResult
    data class Rejected(val errorKind: DeliveryErrorKind, val safeMessage: String) : NotebookPublishResult
}

interface NotebookPublisher {
    val publisherId: String
    val capabilities: NotebookPublisherCapabilities
    suspend fun publish(entry: EngineeringNotebookEntry): NotebookPublishResult
}

class NotebookPublisherDeliveryAdapter(
    private val store: IntegrationStore,
    private val publisher: NotebookPublisher,
) : IntegrationDeliveryProvider {
    override val providerId: String = publisher.publisherId

    override suspend fun deliver(event: IntegrationEvent): IntegrationDeliveryResult {
        val reference = when (val payload = event.payload) {
            is NotebookDraftReady -> payload.entryId to payload.revision
            is SoftwareDigestReady -> payload.entryId to payload.revision
            else -> return IntegrationDeliveryResult.Rejected(
                DeliveryErrorKind.PAYLOAD,
                "Notebook publisher received an unsupported event type",
            )
        }
        val entry = store.getNotebookRevision(reference.first, reference.second)
            ?: return IntegrationDeliveryResult.Rejected(
                DeliveryErrorKind.PAYLOAD,
                "Notebook revision is no longer available",
            )
        if (publisher.capabilities.requiresApproval && entry.reviewState != NotebookReviewState.APPROVED) {
            return IntegrationDeliveryResult.Rejected(
                DeliveryErrorKind.PAYLOAD,
                "Notebook revision requires human approval before submission",
            )
        }
        return when (val result = publisher.publish(entry)) {
            is NotebookPublishResult.Published -> {
                store.recordPublicationReceipt(entry.entryId, result.receipt)
                IntegrationDeliveryResult.Delivered(AppJson.encodeToString(result.receipt))
            }
            is NotebookPublishResult.Retry -> IntegrationDeliveryResult.Retry(
                result.errorKind,
                result.safeMessage,
                result.retryAfterMs,
            )
            is NotebookPublishResult.Rejected -> IntegrationDeliveryResult.Rejected(
                result.errorKind,
                result.safeMessage,
            )
        }
    }
}

class LocalMarkdownNotebookPublisher(
    override val publisherId: String,
    private val directory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NotebookPublisher {
    override val capabilities = NotebookPublisherCapabilities(
        requiresApproval = false,
        supportsRemoteStatus = false,
        supportsAttachments = true,
    )

    override suspend fun publish(entry: EngineeringNotebookEntry): NotebookPublishResult {
        val markdown = renderNotebookMarkdown(entry)
        val filename = notebookFilename(entry)
        val destination = File(directory, filename)
        return try {
            directory.mkdirs()
            require(directory.isDirectory) { "Notebook export directory could not be created" }
            if (destination.isFile) {
                if (destination.readText() != markdown) {
                    return NotebookPublishResult.Rejected(
                        DeliveryErrorKind.PAYLOAD,
                        "Existing notebook export does not match its content hash",
                    )
                }
            } else {
                writeFileAtomically(destination) { temporary -> temporary.writeText(markdown) }
            }
            NotebookPublishResult.Published(
                PublicationReceipt(
                    publisherId = publisherId,
                    remoteId = filename,
                    remoteUrl = destination.toURI().toString(),
                    submittedRevision = entry.revision,
                    submittedContentHash = entry.contentHash,
                    acceptedAtMs = nowMs(),
                )
            )
        } catch (failure: Exception) {
            NotebookPublishResult.Rejected(
                DeliveryErrorKind.CONFIGURATION,
                failure.message?.take(1_024) ?: "Notebook export failed",
            )
        }
    }
}

interface DriveNotebookClient {
    suspend fun rootFolderId(): String
    suspend fun findOrCreateFolder(name: String, parentId: String): String
    suspend fun findFile(name: String, parentId: String): String?
    suspend fun writeFile(name: String, bytes: ByteArray, parentId: String, mimeType: String): String
}

class GoogleDriveNotebookClient(private val drive: GoogleDriveService) : DriveNotebookClient {
    override suspend fun rootFolderId(): String = drive.workspaceRootId()
    override suspend fun findOrCreateFolder(name: String, parentId: String): String =
        drive.findOrCreateFolder(name, parentId)
    override suspend fun findFile(name: String, parentId: String): String? = drive.findFile(name, parentId)
    override suspend fun writeFile(name: String, bytes: ByteArray, parentId: String, mimeType: String): String =
        drive.writeFile(name, bytes, parentId, mimeType)
}

class GoogleDriveNotebookPublisher(
    override val publisherId: String,
    private val drive: DriveNotebookClient,
    private val folderName: String = "engineering-notebook",
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NotebookPublisher {
    override val capabilities = NotebookPublisherCapabilities(
        requiresApproval = false,
        supportsRemoteStatus = false,
        supportsAttachments = true,
    )

    init {
        require(folderName.isNotBlank() && folderName.length <= 128) { "Drive notebook folder name is invalid" }
    }

    override suspend fun publish(entry: EngineeringNotebookEntry): NotebookPublishResult = try {
        val rootId = drive.rootFolderId()
        val folderId = drive.findOrCreateFolder(folderName, rootId)
        val filename = notebookFilename(entry)
        val existingId = drive.findFile(filename, folderId)
        val fileId = existingId ?: drive.writeFile(
            filename,
            renderNotebookMarkdown(entry).toByteArray(Charsets.UTF_8),
            folderId,
            "text/markdown; charset=utf-8",
        )
        NotebookPublishResult.Published(
            PublicationReceipt(
                publisherId = publisherId,
                remoteId = fileId,
                remoteUrl = "https://drive.google.com/open?id=$fileId",
                submittedRevision = entry.revision,
                submittedContentHash = entry.contentHash,
                acceptedAtMs = nowMs(),
            )
        )
    } catch (failure: Exception) {
        NotebookPublishResult.Retry(
            DeliveryErrorKind.TRANSIENT,
            failure.message?.take(1_024) ?: "Google Drive notebook export failed",
        )
    }
}

class CmsNotebookPublisher(
    override val publisherId: String,
    endpoint: String,
    private val credential: IntegrationCredential,
    private val httpClient: HttpClient = notificationHttpClient(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NotebookPublisher {
    override val capabilities = NotebookPublisherCapabilities(
        requiresApproval = true,
        supportsRemoteStatus = true,
        supportsAttachments = false,
    )
    private val endpoint = validateCmsEndpoint(endpoint)
    private val transportJson = Json(AppJson) { encodeDefaults = true }

    override suspend fun publish(entry: EngineeringNotebookEntry): NotebookPublishResult {
        val response = try {
            httpClient.post(endpoint) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${credential.secret}")
                header("Idempotency-Key", "${entry.entryId}:${entry.contentHash}")
                header(HttpHeaders.UserAgent, "ares-robotics-studio-integrations/1")
                // The CMS contract requires schemaVersion even when it equals the model default.
                // Keep nulls omitted so the strict endpoint receives only fields it understands.
                setBody(transportJson.encodeToString(entry))
            }
        } catch (failure: Exception) {
            return NotebookPublishResult.Retry(
                DeliveryErrorKind.TRANSIENT,
                failure.message?.take(1_024) ?: "CMS submission failed",
            )
        }
        val responseBody = response.bodyAsText().take(16_384)
        return when (response.status.value) {
            in 200..299 -> {
                val responseObject = runCatching { AppJson.parseToJsonElement(responseBody).jsonObject }.getOrNull()
                val remoteId = responseObject?.get("draftId")?.jsonPrimitive?.content
                    ?: responseObject?.get("id")?.jsonPrimitive?.content
                    ?: return NotebookPublishResult.Rejected(
                        DeliveryErrorKind.PAYLOAD,
                        "CMS accepted the draft without returning its ID",
                    )
                val reviewUrl = responseObject?.get("reviewUrl")?.jsonPrimitive?.content
                    ?.takeIf { isHttpsUrl(it) }
                NotebookPublishResult.Published(
                    PublicationReceipt(
                        publisherId = publisherId,
                        remoteId = remoteId,
                        remoteUrl = reviewUrl,
                        submittedRevision = entry.revision,
                        submittedContentHash = entry.contentHash,
                        acceptedAtMs = nowMs(),
                    )
                )
            }
            401, 403 -> NotebookPublishResult.Rejected(
                DeliveryErrorKind.AUTHENTICATION,
                "CMS rejected its installation credential",
            )
            404 -> NotebookPublishResult.Rejected(
                DeliveryErrorKind.CONFIGURATION,
                "CMS draft endpoint was not found",
            )
            409, 422 -> NotebookPublishResult.Rejected(
                DeliveryErrorKind.PAYLOAD,
                "CMS rejected the notebook revision (${response.status.value})",
            )
            429 -> NotebookPublishResult.Retry(
                DeliveryErrorKind.RATE_LIMITED,
                "CMS rate limit was reached",
                response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.times(1_000L),
            )
            in 500..599 -> NotebookPublishResult.Retry(
                DeliveryErrorKind.TRANSIENT,
                "CMS is temporarily unavailable (${response.status.value})",
            )
            else -> NotebookPublishResult.Rejected(
                DeliveryErrorKind.PAYLOAD,
                "CMS rejected the notebook revision (${response.status.value})",
            )
        }
    }
}

internal fun renderNotebookMarkdown(entry: EngineeringNotebookEntry): String = buildString {
    appendLine("---")
    appendLine("schema: ares.engineering-notebook/v${entry.schemaVersion}")
    appendLine("entry_id: ${yamlQuote(entry.entryId)}")
    appendLine("revision: ${entry.revision}")
    appendLine("type: ${entry.entryType.name.lowercase()}")
    appendLine("team_id: ${yamlQuote(entry.workspace.teamId)}")
    appendLine("season_id: ${yamlQuote(entry.workspace.seasonId)}")
    appendLine("robot_id: ${yamlQuote(entry.workspace.robotId)}")
    appendLine("visibility: ${entry.visibility.name.lowercase()}")
    appendLine("review_state: ${entry.reviewState.name.lowercase()}")
    appendLine("content_sha256: ${entry.contentHash}")
    appendLine("created_at_ms: ${entry.createdAtMs}")
    appendLine("updated_at_ms: ${entry.updatedAtMs}")
    appendLine("---")
    appendLine()
    appendLine(entry.markdownBody.trim())
    if (entry.evidence.isNotEmpty()) {
        appendLine()
        appendLine("## Evidence")
        appendLine()
        entry.evidence.forEach { evidence ->
            append("- ").append(evidence.label ?: evidence.kind)
                .append(": `").append(evidence.referenceId.replace("`", "'")).append('`')
            evidence.sha256?.let { append(" (SHA-256 `").append(it).append("`)") }
            appendLine()
        }
    }
}

private fun notebookFilename(entry: EngineeringNotebookEntry): String {
    val safeId = entry.entryId.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(80)
        .ifBlank { "notebook-entry" }
    return "$safeId-r${entry.revision}-${entry.contentHash.take(12)}.md"
}

private fun yamlQuote(value: String): String = "\"" + value
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\r", " ")
    .replace("\n", " ") + "\""

private fun validateCmsEndpoint(value: String): String {
    require(isHttpsUrl(value)) { "CMS endpoint must be an HTTPS URL without embedded credentials" }
    return URI(value).toASCIIString()
}

private fun isHttpsUrl(value: String): Boolean = runCatching {
    URI(value).let { uri ->
        uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }
}.getOrDefault(false)
