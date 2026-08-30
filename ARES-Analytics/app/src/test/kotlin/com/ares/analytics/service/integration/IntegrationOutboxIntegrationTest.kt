package com.ares.analytics.service.integration

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.ImportReport
import com.ares.analytics.service.ImportStatus
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.AlertRecord
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.SessionImported
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationOutboxIntegrationTest {
    private val workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin")

    @Test
    fun `queued delivery survives restart and remains idempotent`() = runTest {
        withDatabasePath { databasePath ->
            val event = importedEvent("event-restart", 1_000L)
            DatabaseService(databasePath).also { database ->
                database.integrations.enqueue(event, setOf("zulip.primary"))
                database.close()
            }

            DatabaseService(databasePath).also { reopened ->
                val claim = reopened.integrations.claimDeliveries(
                    workerId = "worker-1",
                    nowMs = 1_000L,
                    leaseDurationMs = 500L,
                    limit = 10,
                ).single()
                assertEquals(event, claim.event)
                assertEquals(1, claim.delivery.attemptCount)

                reopened.integrations.markDelivered(
                    eventId = event.eventId,
                    providerId = "zulip.primary",
                    workerId = "worker-1",
                    receiptJson = "{\"messageId\":\"42\"}",
                    completedAtMs = 1_100L,
                )
                reopened.integrations.enqueue(event, setOf("zulip.primary"))

                val delivery = reopened.integrations.getDelivery(event.eventId, "zulip.primary")
                assertEquals(DeliveryState.DELIVERED, delivery?.state)
                assertEquals(1, delivery?.attemptCount)
                assertTrue(
                    reopened.integrations.claimDeliveries("worker-2", 2_000L, 500L, 10).isEmpty()
                )
                reopened.close()
            }
        }
    }

    @Test
    fun `expired leases are recovered and stale workers cannot acknowledge them`() = runTest {
        withDatabase { database ->
            val event = importedEvent("event-lease", 100L)
            database.integrations.enqueue(event, setOf("webhook.primary"))

            database.integrations.claimDeliveries("worker-old", 100L, 10L, 1).single()
            assertTrue(database.integrations.claimDeliveries("worker-new", 109L, 10L, 1).isEmpty())

            val recovered = database.integrations.claimDeliveries("worker-new", 110L, 10L, 1).single()
            assertEquals(2, recovered.delivery.attemptCount)
            assertFailsWith<IllegalStateException> {
                database.integrations.markDelivered(
                    event.eventId,
                    "webhook.primary",
                    "worker-old",
                    null,
                    111L,
                )
            }
            database.integrations.markDelivered(
                event.eventId,
                "webhook.primary",
                "worker-new",
                null,
                112L,
            )
        }
    }

    @Test
    fun `event ID collision with different content fails closed`() = runTest {
        withDatabase { database ->
            database.integrations.enqueue(importedEvent("event-collision", 100L), emptySet())

            assertFailsWith<IllegalArgumentException> {
                database.integrations.enqueue(importedEvent("event-collision", 101L), emptySet())
            }
        }
    }

    @Test
    fun `durable import and analysis boundaries emit routed typed events`() = runTest {
        withDatabase { database ->
            database.integrationRouting.replace(
                mapOf(
                    IntegrationEventType.SESSION_IMPORTED to setOf("zulip.primary"),
                    IntegrationEventType.ANALYSIS_READY to setOf("webhook.primary"),
                )
            )
            val session = Session("session-routed", "23247", "2026", "marvin", 5_000L)
            val report = ImportReport(
                sourceName = "robot.csv.gz",
                sourceSha256 = "b".repeat(64),
                sourceSizeBytes = 123L,
                decoder = "csv",
                status = ImportStatus.SUCCESS,
                sessionId = session.sessionId,
            )

            database.insertImportSession(session)
            database.completeSessionImport(session, listOf(report))
            database.insertSessionSummary(
                SessionSummary(session.sessionId, session.teamId, session.seasonId, session.robotId, session.createdAt)
            )

            assertEquals(
                DeliveryState.PENDING,
                database.integrations.getDelivery("session-imported:${session.sessionId}", "zulip.primary")?.state,
            )
            assertEquals(
                DeliveryState.PENDING,
                database.integrations.getDelivery(
                    "analysis-ready:${session.sessionId}:summary-v1",
                    "webhook.primary",
                )?.state,
            )
        }
    }

    @Test
    fun `simulation evidence is persisted locally without external deliveries`() = runTest {
        withDatabase { database ->
            database.integrationRouting.replace(
                IntegrationEventType.entries.associateWith {
                    setOf("zulip.primary", "webhook.primary", "cms.primary")
                },
                notebookPublisherIds = setOf("cms.primary"),
            )
            val session = Session(
                sessionId = "session-simulation",
                teamId = "23247",
                seasonId = "2026",
                robotId = "lightbot",
                createdAt = 5_000L,
                tags = listOf("SIMULATION", "studio-experiment"),
            )
            val report = ImportReport(
                sourceName = "simulator.jsonl",
                sourceSha256 = "c".repeat(64),
                sourceSizeBytes = 321L,
                decoder = "jsonl",
                status = ImportStatus.SUCCESS,
                sessionId = session.sessionId,
            )

            database.insertImportSession(session)
            database.completeSessionImport(session, listOf(report))
            database.insertSessionSummary(
                SessionSummary(
                    session.sessionId,
                    session.teamId,
                    session.seasonId,
                    session.robotId,
                    session.createdAt,
                    tags = session.tags,
                )
            )
            database.insertAlert(
                AlertRecord(
                    alertId = "sim-alert",
                    sessionId = session.sessionId,
                    ruleKey = "Robot/LoopTimeMs",
                    triggerTimestampMs = 5_100L,
                )
            )
            val notebook = notebookEntry(1, "# Simulation notes", 5_200L)
            database.saveEngineeringNotebookRevision(notebook, externalUpdatesAllowed = false)
            database.integrationEvents.cloudUploadCommitted(
                workspace = workspace,
                sessionId = session.sessionId,
                remoteObjectId = "drive-object",
                manifestRevision = "d".repeat(64),
                occurredAtMs = 5_300L,
                externalUpdatesAllowed = false,
            )

            val eventIds = listOf(
                "session-imported:${session.sessionId}",
                "analysis-ready:${session.sessionId}:summary-v1",
                "robot-issue-opened:sim-alert",
                "notebook-draft-ready:${notebook.entryId}:${notebook.contentHash}",
                "cloud-upload-committed:${session.sessionId}:drive-object",
            )
            eventIds.forEach { eventId ->
                assertTrue(database.integrations.getEvent(eventId) != null, "$eventId was not recorded locally")
                listOf("zulip.primary", "webhook.primary", "cms.primary").forEach { providerId ->
                    assertNull(
                        database.integrations.getDelivery(eventId, providerId),
                        "$eventId created an external $providerId delivery",
                    )
                }
            }
        }
    }

    @Test
    fun `notebook revisions are sequential and receipts reference exact content`() = runTest {
        withDatabase { database ->
            val first = notebookEntry(1, "# Initial analysis", 1_000L)
            database.integrations.saveNotebookRevision(first)
            database.integrations.saveNotebookRevision(
                first.copy(
                    reviewState = NotebookReviewState.REVIEWED,
                    humanReviewerId = "mentor-1",
                    updatedAtMs = 1_100L,
                )
            )

            val second = notebookEntry(2, "# Revised analysis", 1_200L)
            database.integrations.saveNotebookRevision(second)
            assertEquals(listOf(1, 2), database.integrations.listNotebookRevisions("entry-1").map { it.revision })
            assertEquals(NotebookReviewState.REVIEWED, database.integrations.getNotebookRevision("entry-1", 1)?.reviewState)
            assertEquals(second, database.integrations.getLatestNotebookRevision("entry-1"))

            val receipt = PublicationReceipt(
                publisherId = "markdown.local",
                remoteId = "entry-1-r2.md",
                submittedRevision = 2,
                submittedContentHash = second.contentHash,
                acceptedAtMs = 1_300L,
            )
            database.integrations.recordPublicationReceipt("entry-1", receipt)
            database.integrations.recordPublicationReceipt("entry-1", receipt)
            assertEquals(listOf(receipt), database.integrations.listPublicationReceipts("entry-1"))

            assertFailsWith<IllegalArgumentException> {
                database.integrations.saveNotebookRevision(notebookEntry(4, "# Skipped", 1_400L))
            }
            assertFailsWith<IllegalArgumentException> {
                database.integrations.recordPublicationReceipt(
                    "entry-1",
                    receipt.copy(submittedContentHash = "0".repeat(64)),
                )
            }
        }
    }

    @Test
    fun `coordinator retries transient failure and preserves provider isolation`() = runTest {
        withDatabase { database ->
            val clock = MutableClock(1_000L)
            var attempts = 0
            val provider = object : IntegrationDeliveryProvider {
                override val providerId: String = "zulip.primary"

                override suspend fun deliver(event: IntegrationEvent): IntegrationDeliveryResult {
                    attempts += 1
                    return if (attempts == 1) {
                        IntegrationDeliveryResult.Retry(
                            DeliveryErrorKind.TRANSIENT,
                            "temporary test failure",
                        )
                    } else {
                        IntegrationDeliveryResult.Delivered("{\"messageId\":\"42\"}")
                    }
                }
            }
            val event = importedEvent("event-retry", clock.millis())
            database.integrations.enqueue(event, setOf(provider.providerId, "missing.provider"))
            val coordinator = IntegrationOutboxCoordinator(
                store = database.integrations,
                providers = listOf(provider),
                clock = clock,
                workerId = "worker",
            )

            assertEquals(2, coordinator.drainOnce())
            assertEquals(DeliveryState.RETRY, database.integrations.getDelivery(event.eventId, provider.providerId)?.state)
            assertEquals(DeliveryState.DEAD, database.integrations.getDelivery(event.eventId, "missing.provider")?.state)

            clock.nowMs = 1_999L
            assertEquals(0, coordinator.drainOnce())
            clock.nowMs = 2_000L
            assertEquals(1, coordinator.drainOnce())
            assertEquals(DeliveryState.DELIVERED, database.integrations.getDelivery(event.eventId, provider.providerId)?.state)
            assertNull(database.integrations.getDelivery(event.eventId, provider.providerId)?.lastErrorKind)
            coordinator.closeAndJoin()
        }
    }

    @Test
    fun `delivery history can retry failures but never redeliver completed work`() = runTest {
        withDatabase { database ->
            val event = importedEvent("event-history", 1_000L)
            database.integrations.enqueue(event, setOf("webhook.primary"))
            database.integrations.claimDeliveries("worker", 1_000L, 500L, 1)
            database.integrations.markFailed(
                event.eventId,
                "webhook.primary",
                "worker",
                DeliveryErrorKind.AUTHENTICATION,
                "credential rejected",
                retryAtMs = null,
                completedAtMs = 1_100L,
            )

            val history = database.integrations.listRecentDeliveries()
            assertEquals(event, history.single().event)
            assertEquals(DeliveryState.DEAD, history.single().delivery.state)
            assertTrue(database.integrations.retryDelivery(event.eventId, "webhook.primary", 1_200L))
            assertEquals(1, database.integrations.getDelivery(event.eventId, "webhook.primary")?.attemptCount)

            database.integrations.claimDeliveries("worker-2", 1_200L, 500L, 1)
            database.integrations.markDelivered(event.eventId, "webhook.primary", "worker-2", null, 1_300L)
            assertEquals(false, database.integrations.retryDelivery(event.eventId, "webhook.primary", 1_400L))
        }
    }

    @Test
    fun `notebook publishers receive only explicitly submitted approved revisions`() = runTest {
        withDatabase { database ->
            database.integrationRouting.replace(
                mapOf(IntegrationEventType.NOTEBOOK_DRAFT_READY to setOf("zulip.primary", "cms.primary")),
                notebookPublisherIds = setOf("cms.primary"),
            )
            val draft = notebookEntry(1, "# Review me", 1_000L)
            database.saveEngineeringNotebookRevision(draft)

            val eventId = "notebook-draft-ready:${draft.entryId}:${draft.contentHash}"
            assertEquals(DeliveryState.PENDING, database.integrations.getDelivery(eventId, "zulip.primary")?.state)
            assertNull(database.integrations.getDelivery(eventId, "cms.primary"))

            val approved = draft.copy(
                reviewState = NotebookReviewState.APPROVED,
                humanReviewerId = "mentor-1",
                updatedAtMs = 1_100L,
            )
            database.integrations.saveNotebookRevision(approved)
            assertTrue(database.integrationEvents.submitNotebookRevision(approved, setOf("cms.primary")))
            assertEquals(DeliveryState.PENDING, database.integrations.getDelivery(eventId, "cms.primary")?.state)
            assertEquals(listOf(approved), database.integrations.listLatestNotebookEntries())
        }
    }

    private fun importedEvent(eventId: String, occurredAtMs: Long): IntegrationEvent = IntegrationEvent(
        eventId = eventId,
        occurredAtMs = occurredAtMs,
        payload = SessionImported(
            workspace = workspace,
            sessionId = "session-1",
            sourceNames = listOf("robot.csv.gz"),
            sourceSha256 = listOf("a".repeat(64)),
        ),
    )

    private fun notebookEntry(revision: Int, body: String, nowMs: Long): EngineeringNotebookEntry {
        val hash = EngineeringNotebookHasher.sha256(
            entryId = "entry-1",
            revision = revision,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = body,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-1",
        )
        return EngineeringNotebookEntry(
            entryId = "entry-1",
            revision = revision,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = body,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-1",
            contentHash = hash,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
    }

    private suspend fun withDatabase(block: suspend (DatabaseService) -> Unit) {
        withDatabasePath { databasePath ->
            val database = DatabaseService(databasePath)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }

    private suspend fun withDatabasePath(block: suspend (String) -> Unit) {
        val tempDirectory = Files.createTempDirectory("ares-integrations").toFile()
        try {
            block(tempDirectory.resolve("telemetry.duckdb").absolutePath)
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    private class MutableClock(var nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }
}
