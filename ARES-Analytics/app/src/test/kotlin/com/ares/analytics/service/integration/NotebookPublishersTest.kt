package com.ares.analytics.service.integration

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookDraftReady
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NotebookPublishersTest {
    @Test
    fun `local markdown publisher creates stable portable file`() = runTest {
        val directory = Files.createTempDirectory("ares-notebook-markdown").toFile()
        try {
            val entry = notebookEntry(NotebookReviewState.DRAFT)
            val publisher = LocalMarkdownNotebookPublisher("markdown.local", directory) { 2_000L }

            val first = assertIs<NotebookPublishResult.Published>(publisher.publish(entry))
            val second = assertIs<NotebookPublishResult.Published>(publisher.publish(entry))

            assertEquals(first.receipt.remoteId, second.receipt.remoteId)
            val exported = directory.resolve(first.receipt.remoteId).readText()
            assertTrue(exported.contains("schema: ares.engineering-notebook/v1"))
            assertTrue(exported.contains("content_sha256: ${entry.contentHash}"))
            assertTrue(exported.contains("# Brownout investigation"))
            assertTrue(exported.contains("## Evidence"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `drive publisher reuses immutable content addressed file`() = runTest {
        val entry = notebookEntry(NotebookReviewState.REVIEWED)
        val drive = FakeDriveNotebookClient()
        val publisher = GoogleDriveNotebookPublisher("drive.team", drive, nowMs = { 2_000L })

        val first = assertIs<NotebookPublishResult.Published>(publisher.publish(entry))
        val second = assertIs<NotebookPublishResult.Published>(publisher.publish(entry))

        assertEquals("file-1", first.receipt.remoteId)
        assertEquals(first.receipt.remoteId, second.receipt.remoteId)
        assertEquals(1, drive.writeCount)
    }

    @Test
    fun `CMS publisher submits approved draft with scoped bearer and idempotency`() = runTest {
        val entry = notebookEntry(NotebookReviewState.APPROVED)
        var requestBody = ""
        val client = HttpClient(MockEngine { request ->
            requestBody = request.body.toByteArray().toString(Charsets.UTF_8)
            assertEquals("Bearer cms-installation-token", request.headers[HttpHeaders.Authorization])
            assertEquals("${entry.entryId}:${entry.contentHash}", request.headers["Idempotency-Key"])
            respond(
                content = "{\"draftId\":\"draft-42\",\"reviewUrl\":\"https://cms.example.org/review/draft-42\"}",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val publisher = CmsNotebookPublisher(
            publisherId = "cms.team",
            endpoint = "https://cms.example.org/api/integrations/robotics-studio/v1/notebook-drafts",
            credential = IntegrationCredential(secret = "cms-installation-token"),
            httpClient = client,
            nowMs = { 2_000L },
        )

        val result = assertIs<NotebookPublishResult.Published>(publisher.publish(entry))

        assertEquals("draft-42", result.receipt.remoteId)
        val bodyObject = AppJson.parseToJsonElement(requestBody).jsonObject
        assertEquals(entry.entryId, bodyObject["entryId"]?.jsonPrimitive?.content)
        assertEquals(entry.contentHash, bodyObject["contentHash"]?.jsonPrimitive?.content)
        assertEquals("1", bodyObject["schemaVersion"]?.jsonPrimitive?.content)
        assertTrue("entry" !in bodyObject)
        assertTrue("aiProvenance" !in bodyObject)
        assertTrue(requestBody.contains("Brownout investigation"))
        assertTrue(!requestBody.contains("cms-installation-token"))
    }

    @Test
    fun `delivery adapter requires approval and persists exact receipt`() = runTest {
        val tempDirectory = Files.createTempDirectory("ares-notebook-adapter").toFile()
        val database = DatabaseService(tempDirectory.resolve("telemetry.duckdb").absolutePath)
        try {
            val draft = notebookEntry(NotebookReviewState.DRAFT)
            database.integrations.saveNotebookRevision(draft)
            val publisher = object : NotebookPublisher {
                override val publisherId = "cms.team"
                override val capabilities = NotebookPublisherCapabilities(true, true, false)
                override suspend fun publish(entry: EngineeringNotebookEntry): NotebookPublishResult =
                    NotebookPublishResult.Published(
                        com.ares.analytics.shared.models.PublicationReceipt(
                            publisherId,
                            "remote-1",
                            submittedRevision = entry.revision,
                            submittedContentHash = entry.contentHash,
                            acceptedAtMs = 2_000L,
                        )
                    )
            }
            val adapter = NotebookPublisherDeliveryAdapter(database.integrations, publisher)
            val event = notebookEvent(draft)

            val rejected = assertIs<IntegrationDeliveryResult.Rejected>(adapter.deliver(event))
            assertEquals(DeliveryErrorKind.PAYLOAD, rejected.errorKind)

            val approved = draft.copy(
                reviewState = NotebookReviewState.APPROVED,
                humanReviewerId = "mentor-1",
                updatedAtMs = 1_500L,
            )
            database.integrations.saveNotebookRevision(approved)
            assertIs<IntegrationDeliveryResult.Delivered>(adapter.deliver(event))
            assertEquals(1, database.integrations.listPublicationReceipts(draft.entryId).size)
        } finally {
            database.close()
            tempDirectory.deleteRecursively()
        }
    }

    private fun notebookEntry(reviewState: NotebookReviewState): EngineeringNotebookEntry {
        val workspace = IntegrationWorkspaceIdentity("23247", "2026", "marvin")
        val evidence = listOf(NotebookEvidenceReference("session", "session-1", "a".repeat(64), "Match log"))
        val hash = EngineeringNotebookHasher.sha256(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ROBOT_ISSUE,
            workspace = workspace,
            markdownBody = "# Brownout investigation\n\nBattery voltage dipped during acceleration.",
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            humanAuthorId = "student-1",
        )
        return EngineeringNotebookEntry(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ROBOT_ISSUE,
            workspace = workspace,
            markdownBody = "# Brownout investigation\n\nBattery voltage dipped during acceleration.",
            evidence = evidence,
            visibility = NotebookVisibility.TEAM,
            reviewState = reviewState,
            humanAuthorId = "student-1",
            humanReviewerId = if (reviewState == NotebookReviewState.APPROVED) "mentor-1" else null,
            contentHash = hash,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
        )
    }

    private fun notebookEvent(entry: EngineeringNotebookEntry) = IntegrationEvent(
        eventId = "notebook-draft-ready:${entry.entryId}:${entry.contentHash}",
        occurredAtMs = entry.updatedAtMs,
        payload = NotebookDraftReady(
            workspace = entry.workspace,
            entryId = entry.entryId,
            revision = entry.revision,
            contentHash = entry.contentHash,
        ),
    )

    private class FakeDriveNotebookClient : DriveNotebookClient {
        private var fileId: String? = null
        var writeCount: Int = 0
        override suspend fun rootFolderId(): String = "root"
        override suspend fun findOrCreateFolder(name: String, parentId: String): String = "folder"
        override suspend fun findFile(name: String, parentId: String): String? = fileId
        override suspend fun writeFile(name: String, bytes: ByteArray, parentId: String, mimeType: String): String {
            writeCount += 1
            return "file-1".also { fileId = it }
        }
    }
}
