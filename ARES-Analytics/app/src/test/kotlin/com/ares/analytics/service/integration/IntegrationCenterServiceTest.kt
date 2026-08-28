package com.ares.analytics.service.integration

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookPublisherConfig
import com.ares.analytics.shared.models.NotebookPublisherKind
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.ZulipNotificationTarget
import com.ares.analytics.shared.models.eventType
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntegrationCenterServiceTest {
    @Test
    fun `approval does not submit until exact publishers are explicitly selected`() = runTest {
        val directory = Files.createTempDirectory("ares-integration-center").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        val credentials = MemoryCredentialStore()
        val settings = IntegrationSettingsService(directory.resolve("integrations.json"), credentials)
        var reloads = 0
        val service = IntegrationCenterService(
            settingsService = settings,
            store = database.integrations,
            eventRecorder = database.integrationEvents,
            reloadIntegrations = { reloads++ },
            configurationErrors = { emptyMap() },
            clock = FixedClock(2_000L),
        )
        try {
            settings.save(
                com.ares.analytics.shared.models.IntegrationSettings(
                    notebookPublishers = listOf(
                        NotebookPublisherConfig(
                            publisherId = "markdown.local",
                            displayName = "Local notebook",
                            kind = NotebookPublisherKind.LOCAL_MARKDOWN,
                            localDirectory = directory.resolve("notebook").absolutePath,
                        )
                    )
                )
            )
            val draft = notebookEntry()
            database.integrations.saveNotebookRevision(draft)

            val approved = service.approve(draft.entryId, draft.revision, "mentor-1")
            assertEquals(NotebookReviewState.APPROVED, approved.reviewState)
            val eventId = "notebook-draft-ready:${draft.entryId}:${draft.contentHash}"
            assertNull(database.integrations.getDelivery(eventId, "markdown.local"))

            assertTrue(service.submit(draft.entryId, draft.revision, setOf("markdown.local")))
            assertNotNull(database.integrations.getDelivery(eventId, "markdown.local"))
            assertFailsWith<IllegalArgumentException> {
                service.submit(draft.entryId, draft.revision, setOf("unknown.publisher"))
            }
            assertEquals(0, reloads)
        } finally {
            service.close()
            database.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `test notification is recorded durably for only the selected enabled provider`() = runTest {
        val directory = Files.createTempDirectory("ares-integration-test-message").toFile()
        val database = DatabaseService(directory.resolve("telemetry.duckdb").absolutePath)
        val settings = IntegrationSettingsService(directory.resolve("integrations.json"), MemoryCredentialStore())
        val service = IntegrationCenterService(
            settingsService = settings,
            store = database.integrations,
            eventRecorder = database.integrationEvents,
            reloadIntegrations = {},
            configurationErrors = { emptyMap() },
            clock = FixedClock(3_000L),
        )
        val workspace = IntegrationWorkspaceIdentity("23247", "2026", "robot")
        try {
            settings.save(
                com.ares.analytics.shared.models.IntegrationSettings(
                    notificationProviders = listOf(
                        NotificationProviderConfig(
                            providerId = "zulip.primary",
                            displayName = "Team Zulip",
                            kind = NotificationProviderKind.ZULIP,
                            eventTypes = setOf(IntegrationEventType.INTEGRATION_TEST_REQUESTED),
                            zulip = ZulipNotificationTarget(
                                siteUrl = "https://example.zulipchat.com",
                                stream = "robot",
                                topic = "ARES Studio",
                            ),
                        )
                    )
                )
            )

            assertTrue(service.sendTestNotification("zulip.primary", workspace))
            val delivery = service.snapshot().deliveries.single()
            assertEquals("zulip.primary", delivery.delivery.providerId)
            assertEquals(IntegrationEventType.INTEGRATION_TEST_REQUESTED, delivery.event.payload.eventType())
            assertEquals(3_000L, delivery.event.occurredAtMs)
        } finally {
            service.close()
            database.close()
            directory.deleteRecursively()
        }
    }

    private fun notebookEntry(): EngineeringNotebookEntry {
        val workspace = IntegrationWorkspaceIdentity("23247", "2026", "robot")
        val hash = EngineeringNotebookHasher.sha256(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = "# Evidence",
            visibility = NotebookVisibility.TEAM,
        )
        return EngineeringNotebookEntry(
            entryId = "entry-1",
            revision = 1,
            entryType = NotebookEntryType.ENGINEERING_NOTE,
            workspace = workspace,
            markdownBody = "# Evidence",
            visibility = NotebookVisibility.TEAM,
            contentHash = hash,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
        )
    }

    private class MemoryCredentialStore : IntegrationCredentialStore {
        private val values = mutableMapOf<String, IntegrationCredential>()
        override fun read(providerId: String) = values[providerId]
        override fun write(providerId: String, credential: IntegrationCredential) { values[providerId] = credential }
        override fun delete(providerId: String): Boolean = values.remove(providerId) != null
        override val protectionDescription: String = "test memory"
    }

    private class FixedClock(private val nowMs: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }
}
