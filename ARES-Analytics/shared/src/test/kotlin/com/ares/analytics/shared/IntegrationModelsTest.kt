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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IntegrationModelsTest {
    private val workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin")

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
