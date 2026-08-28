package com.ares.analytics.shared.models

import com.ares.analytics.shared.AppJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.MessageDigest

const val INTEGRATION_EVENT_SCHEMA_VERSION: Int = 1
const val NOTEBOOK_ENTRY_SCHEMA_VERSION: Int = 1

@Serializable
data class IntegrationWorkspaceIdentity(
    val teamId: String,
    val seasonId: String,
    val robotId: String,
)

@Serializable
sealed interface IntegrationEventPayload {
    val workspace: IntegrationWorkspaceIdentity
}

@Serializable
@SerialName("session_imported")
data class SessionImported(
    override val workspace: IntegrationWorkspaceIdentity,
    val sessionId: String,
    val sourceNames: List<String>,
    val sourceSha256: List<String>,
) : IntegrationEventPayload

@Serializable
@SerialName("analysis_ready")
data class AnalysisReady(
    override val workspace: IntegrationWorkspaceIdentity,
    val sessionId: String,
    val analysisVersion: String,
) : IntegrationEventPayload

@Serializable
@SerialName("robot_issue_opened")
data class RobotIssueOpened(
    override val workspace: IntegrationWorkspaceIdentity,
    val issueId: String,
    val sessionId: String? = null,
    val ruleKey: String,
    val severity: IntegrationIssueSeverity,
    val summary: String,
) : IntegrationEventPayload

@Serializable
@SerialName("robot_issue_resolved")
data class RobotIssueResolved(
    override val workspace: IntegrationWorkspaceIdentity,
    val issueId: String,
    val sessionId: String? = null,
    val resolution: String? = null,
) : IntegrationEventPayload

@Serializable
@SerialName("cloud_upload_committed")
data class CloudUploadCommitted(
    override val workspace: IntegrationWorkspaceIdentity,
    val sessionId: String,
    val remoteObjectId: String,
    val manifestRevision: String,
    val remoteUrl: String? = null,
) : IntegrationEventPayload

@Serializable
@SerialName("notebook_draft_ready")
data class NotebookDraftReady(
    override val workspace: IntegrationWorkspaceIdentity,
    val entryId: String,
    val revision: Int,
    val contentHash: String,
) : IntegrationEventPayload

@Serializable
@SerialName("software_digest_ready")
data class SoftwareDigestReady(
    override val workspace: IntegrationWorkspaceIdentity,
    val entryId: String,
    val revision: Int,
    val contentHash: String,
    val commitRange: String,
) : IntegrationEventPayload

@Serializable
@SerialName("integration_test_requested")
data class IntegrationTestRequested(
    override val workspace: IntegrationWorkspaceIdentity,
    val testId: String,
    val targetProviderId: String,
) : IntegrationEventPayload

@Serializable
enum class IntegrationIssueSeverity {
    INFO,
    WARNING,
    ERROR,
    CRITICAL,
}

@Serializable
enum class IntegrationEventType {
    SESSION_IMPORTED,
    ANALYSIS_READY,
    ROBOT_ISSUE_OPENED,
    ROBOT_ISSUE_RESOLVED,
    CLOUD_UPLOAD_COMMITTED,
    NOTEBOOK_DRAFT_READY,
    SOFTWARE_DIGEST_READY,
    INTEGRATION_TEST_REQUESTED,
}

fun IntegrationEventPayload.eventType(): IntegrationEventType = when (this) {
    is SessionImported -> IntegrationEventType.SESSION_IMPORTED
    is AnalysisReady -> IntegrationEventType.ANALYSIS_READY
    is RobotIssueOpened -> IntegrationEventType.ROBOT_ISSUE_OPENED
    is RobotIssueResolved -> IntegrationEventType.ROBOT_ISSUE_RESOLVED
    is CloudUploadCommitted -> IntegrationEventType.CLOUD_UPLOAD_COMMITTED
    is NotebookDraftReady -> IntegrationEventType.NOTEBOOK_DRAFT_READY
    is SoftwareDigestReady -> IntegrationEventType.SOFTWARE_DIGEST_READY
    is IntegrationTestRequested -> IntegrationEventType.INTEGRATION_TEST_REQUESTED
}

fun IntegrationEventPayload.aggregateId(): String = when (this) {
    is SessionImported -> sessionId
    is AnalysisReady -> sessionId
    is RobotIssueOpened -> issueId
    is RobotIssueResolved -> issueId
    is CloudUploadCommitted -> sessionId
    is NotebookDraftReady -> entryId
    is SoftwareDigestReady -> entryId
    is IntegrationTestRequested -> testId
}

@Serializable
data class IntegrationEvent(
    val eventId: String,
    val occurredAtMs: Long,
    val payload: IntegrationEventPayload,
    val schemaVersion: Int = INTEGRATION_EVENT_SCHEMA_VERSION,
)

object IntegrationEventHasher {
    fun sha256(event: IntegrationEvent): String = sha256Hex(AppJson.encodeToString(event))
}

@Serializable
enum class NotebookEntryType {
    SESSION_SUMMARY,
    ROBOT_ISSUE,
    SOFTWARE_CHANGE,
    ENGINEERING_NOTE,
}

@Serializable
enum class NotebookVisibility {
    PRIVATE,
    TEAM,
    PUBLIC_CANDIDATE,
}

@Serializable
enum class NotebookReviewState {
    DRAFT,
    REVIEWED,
    APPROVED,
    SUBMITTED,
    PUBLISHED,
    REJECTED,
    SUPERSEDED,
}

@Serializable
data class NotebookEvidenceReference(
    val kind: String,
    val referenceId: String,
    val sha256: String? = null,
    val label: String? = null,
    val uri: String? = null,
)

@Serializable
data class NotebookAiProvenance(
    val provider: String,
    val model: String,
    val promptSchemaVersion: Int,
    val generatedAtMs: Long,
    val evidenceHashes: List<String> = emptyList(),
)

@Serializable
data class EngineeringNotebookEntry(
    val entryId: String,
    val revision: Int,
    val entryType: NotebookEntryType,
    val workspace: IntegrationWorkspaceIdentity,
    val markdownBody: String,
    val evidence: List<NotebookEvidenceReference> = emptyList(),
    val visibility: NotebookVisibility = NotebookVisibility.PRIVATE,
    val reviewState: NotebookReviewState = NotebookReviewState.DRAFT,
    val humanAuthorId: String? = null,
    val humanReviewerId: String? = null,
    val aiProvenance: NotebookAiProvenance? = null,
    val contentHash: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val schemaVersion: Int = NOTEBOOK_ENTRY_SCHEMA_VERSION,
)

@Serializable
private data class NotebookHashMaterial(
    val schemaVersion: Int,
    val entryId: String,
    val revision: Int,
    val entryType: NotebookEntryType,
    val workspace: IntegrationWorkspaceIdentity,
    val markdownBody: String,
    val evidence: List<NotebookEvidenceReference>,
    val visibility: NotebookVisibility,
    val humanAuthorId: String?,
    val aiProvenance: NotebookAiProvenance?,
)

object EngineeringNotebookHasher {
    fun sha256(entry: EngineeringNotebookEntry): String = sha256(
        entryId = entry.entryId,
        revision = entry.revision,
        entryType = entry.entryType,
        workspace = entry.workspace,
        markdownBody = entry.markdownBody,
        evidence = entry.evidence,
        visibility = entry.visibility,
        humanAuthorId = entry.humanAuthorId,
        aiProvenance = entry.aiProvenance,
        schemaVersion = entry.schemaVersion,
    )

    fun sha256(
        entryId: String,
        revision: Int,
        entryType: NotebookEntryType,
        workspace: IntegrationWorkspaceIdentity,
        markdownBody: String,
        evidence: List<NotebookEvidenceReference> = emptyList(),
        visibility: NotebookVisibility = NotebookVisibility.PRIVATE,
        humanAuthorId: String? = null,
        aiProvenance: NotebookAiProvenance? = null,
        schemaVersion: Int = NOTEBOOK_ENTRY_SCHEMA_VERSION,
    ): String = sha256Hex(
        AppJson.encodeToString(
            NotebookHashMaterial(
                schemaVersion = schemaVersion,
                entryId = entryId,
                revision = revision,
                entryType = entryType,
                workspace = workspace,
                markdownBody = markdownBody,
                evidence = evidence,
                visibility = visibility,
                humanAuthorId = humanAuthorId,
                aiProvenance = aiProvenance,
            )
        )
    )
}

@Serializable
data class PublicationReceipt(
    val publisherId: String,
    val remoteId: String,
    val remoteUrl: String? = null,
    val submittedRevision: Int,
    val submittedContentHash: String,
    val acceptedAtMs: Long,
    val metadata: Map<String, String> = emptyMap(),
)

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
