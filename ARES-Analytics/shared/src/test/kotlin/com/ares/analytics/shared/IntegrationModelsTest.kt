package com.ares.analytics.shared

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventHasher
import com.ares.analytics.shared.models.IntegrationEventPayload
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.RobotIssueOpened
import com.ares.analytics.shared.models.IntegrationIssueSeverity
import com.ares.analytics.shared.models.eventType
import com.ares.analytics.shared.models.aggregateId
import com.ares.analytics.shared.models.AnalysisReady
import com.ares.analytics.shared.models.CloudUploadCommitted
import com.ares.analytics.shared.models.IntegrationTestRequested
import com.ares.analytics.shared.models.NotebookDraftReady
import com.ares.analytics.shared.models.RobotIssueResolved
import com.ares.analytics.shared.models.SessionImported
import com.ares.analytics.shared.models.SoftwareDigestReady
import java.math.BigInteger
import java.security.MessageDigest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IntegrationModelsTest {
    private val workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin")

    @Test
    fun `every event retains its wire discriminator and aggregate routing`() {
        val cases = listOf(
            Triple(SessionImported(workspace, "session", listOf("日志"), listOf("hash")), IntegrationEventType.SESSION_IMPORTED, "session"),
            Triple(AnalysisReady(workspace, "session", "v1"), IntegrationEventType.ANALYSIS_READY, "session"),
            Triple(RobotIssueOpened(workspace, "issue", "session", "rule", IntegrationIssueSeverity.ERROR, "温度"), IntegrationEventType.ROBOT_ISSUE_OPENED, "issue"),
            Triple(RobotIssueResolved(workspace, "issue", "session"), IntegrationEventType.ROBOT_ISSUE_RESOLVED, "issue"),
            Triple(CloudUploadCommitted(workspace, "session", "remote", "revision"), IntegrationEventType.CLOUD_UPLOAD_COMMITTED, "session"),
            Triple(NotebookDraftReady(workspace, "entry", 1, "hash"), IntegrationEventType.NOTEBOOK_DRAFT_READY, "entry"),
            Triple(SoftwareDigestReady(workspace, "entry", 1, "hash", "a..b"), IntegrationEventType.SOFTWARE_DIGEST_READY, "entry"),
            Triple(IntegrationTestRequested(workspace, "test", "provider"), IntegrationEventType.INTEGRATION_TEST_REQUESTED, "test"),
        )
        assertEquals(IntegrationEventType.entries.toSet(), cases.map { it.second }.toSet())
        for ((payload, type, aggregate) in cases) {
            val event = IntegrationEvent("event", 1000L, payload)
            val encoded = AppJson.encodeToString(event)
            val decoded = AppJson.decodeFromString<IntegrationEvent>(encoded)
            assertEquals(event, decoded)
            assertEquals(type, decoded.payload.eventType())
            assertEquals(aggregate, decoded.payload.aggregateId())
            val expectedHash = BigInteger(1, MessageDigest.getInstance("SHA-256")
                .digest(encoded.toByteArray(Charsets.UTF_8))).toString(16).padStart(64, '0')
            assertEquals(expectedHash, IntegrationEventHasher.sha256(event))
        }
    }

    @Test
    fun `typed event payload round trips with its schema and type`() {
        val event = IntegrationEvent(
            eventId = "event-1",
            occurredAtMs = 1_000L,
            payload = RobotIssueOpened(
                workspace = workspace,
                issueId = "issue-1",
                sessionId = "session-1",
                ruleKey = "brownout-risk",
                severity = IntegrationIssueSeverity.ERROR,
                summary = "Battery sag exceeded the configured threshold",
            ),
        )

        val encoded = AppJson.encodeToString(event)
        val decoded = AppJson.decodeFromString<IntegrationEvent>(encoded)

        assertEquals(event, decoded)
        assertEquals(IntegrationEventType.ROBOT_ISSUE_OPENED, decoded.payload.eventType())
        assertEquals(64, IntegrationEventHasher.sha256(decoded).length)
    }

    @Test
    fun `notebook content hash tracks content but not review metadata`() {
        val evidence = listOf(
            NotebookEvidenceReference("session", "session-1", sha256 = "a".repeat(64))
        )
        val contentHash = EngineeringNotebookHasher.sha256(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ROBOT_ISSUE,
            workspace = workspace,
            markdownBody = "# Brownout investigation",
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-1",
        )
        val draft = EngineeringNotebookEntry(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ROBOT_ISSUE,
            workspace = workspace,
            markdownBody = "# Brownout investigation",
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-1",
            contentHash = contentHash,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
        )

        assertEquals(contentHash, EngineeringNotebookHasher.sha256(draft))
        assertEquals(
            contentHash,
            EngineeringNotebookHasher.sha256(
                draft.copy(
                    reviewState = NotebookReviewState.REVIEWED,
                    humanReviewerId = "mentor-1",
                    updatedAtMs = 2_000L,
                )
            ),
        )
        assertNotEquals(
            contentHash,
            EngineeringNotebookHasher.sha256(draft.copy(markdownBody = "# Revised investigation")),
        )
    }

    @Test
    fun `notebook hash remains compatible with the ARES website ingest contract`() {
        assertEquals(
            "93074f84203145939f8ba2c5f7d40b5699482e52385c763cd3e9b115686fc61e",
            EngineeringNotebookHasher.sha256(
                entryId = "entry-2026-001",
                revision = 1,
                entryType = NotebookEntryType.ROBOT_ISSUE,
                workspace = IntegrationWorkspaceIdentity("23247", "2026", "Lightbot"),
                markdownBody = "# Brownout investigation",
                evidence = listOf(
                    NotebookEvidenceReference("session", "session-1", sha256 = "a".repeat(64))
                ),
                visibility = NotebookVisibility.TEAM,
            ),
        )
    }
}
