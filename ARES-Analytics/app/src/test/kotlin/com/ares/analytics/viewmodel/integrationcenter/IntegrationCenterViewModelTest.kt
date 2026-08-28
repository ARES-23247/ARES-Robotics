package com.ares.analytics.viewmodel.integrationcenter

import com.ares.analytics.service.integration.IntegrationCenterOperations
import com.ares.analytics.service.integration.IntegrationCenterSnapshot
import com.ares.analytics.service.integration.IntegrationCredential
import com.ares.analytics.service.integration.NotebookReviewBundle
import com.ares.analytics.service.integration.ProviderConnectionResult
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationSettings
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookPublisherConfig
import com.ares.analytics.shared.models.NotebookPublisherKind
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.ZulipNotificationTarget
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationCenterViewModelTest {
    @Test
    fun `saving provider never retains its credential in view model state`() = runTest {
        val fake = FakeOperations()
        val viewModel = IntegrationCenterViewModel(fake, this, StandardTestDispatcher(testScheduler))
        val config = zulipConfig()

        viewModel.onIntent(
            IntegrationCenterIntent.SaveNotification(
                config,
                IntegrationCredential("bot@example.com", "super-secret-api-key"),
            )
        )
        advanceUntilIdle()

        assertEquals("super-secret-api-key", fake.savedCredential?.secret)
        assertEquals(listOf(config), viewModel.state.value.settings.notificationProviders)
        assertFalse(viewModel.state.value.toString().contains("super-secret-api-key"))
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `approval and submission remain separate explicit operations`() = runTest {
        val fake = FakeOperations().apply {
            selectedBundle = NotebookReviewBundle(listOf(notebook()), emptyList())
        }
        val viewModel = IntegrationCenterViewModel(fake, this, StandardTestDispatcher(testScheduler))

        viewModel.onIntent(IntegrationCenterIntent.Approve("entry-1", 1, "mentor-1"))
        advanceUntilIdle()
        assertEquals(1, fake.approvals)
        assertEquals(0, fake.submissions)
        assertTrue(viewModel.state.value.message.orEmpty().contains("still requires"))

        viewModel.onIntent(IntegrationCenterIntent.Submit("entry-1", 1, setOf("markdown.local")))
        advanceUntilIdle()
        assertEquals(1, fake.submissions)
        assertEquals(setOf("markdown.local"), fake.submittedPublishers)
    }

    @Test
    fun `retry reports when immutable completed delivery cannot be queued`() = runTest {
        val fake = FakeOperations().apply { retryResult = false }
        val viewModel = IntegrationCenterViewModel(fake, this, StandardTestDispatcher(testScheduler))

        viewModel.onIntent(IntegrationCenterIntent.RetryDelivery("event-1", "zulip.primary"))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.message.orEmpty().contains("cannot be retried"))
    }

    @Test
    fun `durable test notification is explicit and refreshes delivery history`() = runTest {
        val fake = FakeOperations()
        val viewModel = IntegrationCenterViewModel(fake, this, StandardTestDispatcher(testScheduler))
        val workspace = IntegrationWorkspaceIdentity("23247", "2026", "robot")

        viewModel.onIntent(IntegrationCenterIntent.SendTestNotification("zulip.primary", workspace))
        advanceUntilIdle()

        assertEquals("zulip.primary", fake.testProviderId)
        assertEquals(workspace, fake.testWorkspace)
        assertTrue(viewModel.state.value.message.orEmpty().contains("queued"))
    }

    private fun zulipConfig() = NotificationProviderConfig(
        providerId = "zulip.primary",
        displayName = "Team Zulip",
        kind = NotificationProviderKind.ZULIP,
        eventTypes = emptySet(),
        zulip = ZulipNotificationTarget("https://example.zulipchat.com", "robot", "ARES"),
    )

    private fun notebook(reviewState: NotebookReviewState = NotebookReviewState.DRAFT) = EngineeringNotebookEntry(
        entryId = "entry-1",
        revision = 1,
        entryType = NotebookEntryType.ENGINEERING_NOTE,
        workspace = IntegrationWorkspaceIdentity("23247", "2026", "robot"),
        markdownBody = "# Entry",
        visibility = NotebookVisibility.TEAM,
        reviewState = reviewState,
        contentHash = "a".repeat(64),
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
    )

    private inner class FakeOperations : IntegrationCenterOperations {
        var settings = IntegrationSettings()
        var savedCredential: IntegrationCredential? = null
        var selectedBundle = NotebookReviewBundle(emptyList(), emptyList())
        var approvals = 0
        var submissions = 0
        var submittedPublishers = emptySet<String>()
        var retryResult = true
        var testProviderId: String? = null
        var testWorkspace: IntegrationWorkspaceIdentity? = null

        override suspend fun snapshot() = IntegrationCenterSnapshot(
            settings,
            deliveries = emptyList(),
            notebooks = selectedBundle.revisions.takeLast(1),
            configurationErrors = emptyMap(),
            credentialProtectionDescription = "test protection",
        )

        override suspend fun notebook(entryId: String) = selectedBundle

        override suspend fun saveNotificationProvider(
            config: NotificationProviderConfig,
            credential: IntegrationCredential?,
        ) {
            savedCredential = credential
            settings = settings.copy(notificationProviders = listOf(config))
        }

        override suspend fun saveNotebookPublisher(config: NotebookPublisherConfig, credential: IntegrationCredential?) {
            savedCredential = credential
            settings = settings.copy(notebookPublishers = listOf(config))
        }

        override suspend fun deleteProvider(providerId: String) = Unit

        override suspend fun testNotificationProvider(
            config: NotificationProviderConfig,
            unsavedCredential: IntegrationCredential?,
        ): ProviderConnectionResult = ProviderConnectionResult.Connected("test")

        override suspend fun sendTestNotification(
            providerId: String,
            workspace: IntegrationWorkspaceIdentity,
        ): Boolean {
            testProviderId = providerId
            testWorkspace = workspace
            return true
        }

        override suspend fun retryDelivery(eventId: String, providerId: String): Boolean = retryResult

        override suspend fun approve(
            entryId: String,
            revision: Int,
            reviewerId: String,
        ): EngineeringNotebookEntry {
            approvals++
            val approved = selectedBundle.revisions.first().copy(
                reviewState = NotebookReviewState.APPROVED,
                humanReviewerId = reviewerId,
            )
            selectedBundle = selectedBundle.copy(revisions = listOf(approved))
            return approved
        }

        override suspend fun submit(entryId: String, revision: Int, publisherIds: Set<String>): Boolean {
            submissions++
            submittedPublishers = publisherIds
            return true
        }
    }
}
