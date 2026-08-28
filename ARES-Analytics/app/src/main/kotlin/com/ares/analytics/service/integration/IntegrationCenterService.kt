package com.ares.analytics.service.integration

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationSettings
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookPublisherConfig
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.NotificationProviderKind
import com.ares.analytics.shared.models.PublicationReceipt
import io.ktor.client.HttpClient
import java.time.Clock

data class IntegrationCenterSnapshot(
    val settings: IntegrationSettings,
    val deliveries: List<IntegrationDeliverySummary>,
    val notebooks: List<EngineeringNotebookEntry>,
    val configurationErrors: Map<String, String>,
    val credentialProtectionDescription: String,
)

data class NotebookReviewBundle(
    val revisions: List<EngineeringNotebookEntry>,
    val receipts: List<PublicationReceipt>,
)

interface IntegrationCenterOperations {
    suspend fun snapshot(): IntegrationCenterSnapshot
    suspend fun notebook(entryId: String): NotebookReviewBundle
    suspend fun saveNotificationProvider(config: NotificationProviderConfig, credential: IntegrationCredential?)
    suspend fun saveNotebookPublisher(config: NotebookPublisherConfig, credential: IntegrationCredential?)
    suspend fun deleteProvider(providerId: String)
    suspend fun testNotificationProvider(
        config: NotificationProviderConfig,
        unsavedCredential: IntegrationCredential? = null,
    ): ProviderConnectionResult
    suspend fun sendTestNotification(providerId: String, workspace: IntegrationWorkspaceIdentity): Boolean
    suspend fun retryDelivery(eventId: String, providerId: String): Boolean
    suspend fun approve(entryId: String, revision: Int, reviewerId: String): EngineeringNotebookEntry
    suspend fun submit(entryId: String, revision: Int, publisherIds: Set<String>): Boolean
}

/** Application service used by the UI; all blocking work is invoked from view-model IO coroutines. */
class IntegrationCenterService(
    private val settingsService: IntegrationSettingsService,
    private val store: IntegrationStore,
    private val eventRecorder: IntegrationEventRecorder,
    private val reloadIntegrations: suspend () -> Unit,
    private val configurationErrors: () -> Map<String, String>,
    private val httpClient: HttpClient = notificationHttpClient(),
    private val clock: Clock = Clock.systemUTC(),
) : IntegrationCenterOperations, AutoCloseable {
    override suspend fun snapshot(): IntegrationCenterSnapshot = IntegrationCenterSnapshot(
        settings = settingsService.load(),
        deliveries = store.listRecentDeliveries(),
        notebooks = store.listLatestNotebookEntries(),
        configurationErrors = configurationErrors(),
        credentialProtectionDescription = settingsService.credentialProtectionDescription,
    )

    override suspend fun notebook(entryId: String): NotebookReviewBundle = NotebookReviewBundle(
        revisions = store.listNotebookRevisions(entryId),
        receipts = store.listPublicationReceipts(entryId),
    )

    override suspend fun saveNotificationProvider(
        config: NotificationProviderConfig,
        credential: IntegrationCredential?,
    ) {
        val current = settingsService.load()
        require(current.notebookPublishers.none { it.publisherId == config.providerId }) {
            "Provider ID is already used by a notebook publisher"
        }
        settingsService.save(
            current.copy(
                notificationProviders = current.notificationProviders
                    .filterNot { it.providerId == config.providerId } + config,
            )
        )
        if (credential != null) settingsService.saveCredential(config.providerId, credential)
        reloadIntegrations()
    }

    override suspend fun saveNotebookPublisher(
        config: NotebookPublisherConfig,
        credential: IntegrationCredential?,
    ) {
        val current = settingsService.load()
        require(current.notificationProviders.none { it.providerId == config.publisherId }) {
            "Provider ID is already used by a notification provider"
        }
        settingsService.save(
            current.copy(
                notebookPublishers = current.notebookPublishers
                    .filterNot { it.publisherId == config.publisherId } + config,
            )
        )
        if (credential != null) settingsService.saveCredential(config.publisherId, credential)
        reloadIntegrations()
    }

    override suspend fun deleteProvider(providerId: String) {
        val current = settingsService.load()
        settingsService.save(
            current.copy(
                notificationProviders = current.notificationProviders.filterNot { it.providerId == providerId },
                notebookPublishers = current.notebookPublishers.filterNot { it.publisherId == providerId },
            )
        )
        settingsService.deleteCredential(providerId)
        reloadIntegrations()
    }

    override suspend fun testNotificationProvider(
        config: NotificationProviderConfig,
        unsavedCredential: IntegrationCredential?,
    ): ProviderConnectionResult {
        val credential = unsavedCredential ?: settingsService.credential(config.providerId)
            ?: return ProviderConnectionResult.Failed(
                DeliveryErrorKind.CONFIGURATION,
                "Credentials are not configured",
            )
        val provider = when (config.kind) {
            NotificationProviderKind.ZULIP -> ZulipNotificationProvider(config, credential = credential, httpClient = httpClient)
            NotificationProviderKind.WEBHOOK -> WebhookNotificationProvider(config, credential = credential, httpClient = httpClient)
        }
        return provider.testConnection()
    }

    override suspend fun sendTestNotification(
        providerId: String,
        workspace: IntegrationWorkspaceIdentity,
    ): Boolean {
        val provider = settingsService.load().notificationProviders
            .firstOrNull { it.providerId == providerId && it.enabled }
            ?: throw IllegalArgumentException("Select an enabled notification provider")
        require(provider.eventTypes.contains(IntegrationEventType.INTEGRATION_TEST_REQUESTED)) {
            "Enable integration test events for this provider before sending a test message"
        }
        return eventRecorder.integrationTestRequested(workspace, providerId, clock.millis())
    }

    override suspend fun retryDelivery(eventId: String, providerId: String): Boolean =
        store.retryDelivery(eventId, providerId, clock.millis())

    override suspend fun approve(entryId: String, revision: Int, reviewerId: String): EngineeringNotebookEntry {
        require(reviewerId.isNotBlank() && reviewerId.length <= 256) { "Reviewer identity is required" }
        val current = requireNotNull(store.getNotebookRevision(entryId, revision)) { "Notebook revision was not found" }
        require(current.reviewState in setOf(NotebookReviewState.DRAFT, NotebookReviewState.REVIEWED)) {
            "Notebook revision cannot be approved from ${current.reviewState.name.lowercase()}"
        }
        val approved = current.copy(
            reviewState = NotebookReviewState.APPROVED,
            humanReviewerId = reviewerId.trim(),
            updatedAtMs = clock.millis().coerceAtLeast(current.updatedAtMs),
        )
        store.saveNotebookRevision(approved)
        return approved
    }

    override suspend fun submit(entryId: String, revision: Int, publisherIds: Set<String>): Boolean {
        val entry = requireNotNull(store.getNotebookRevision(entryId, revision)) { "Notebook revision was not found" }
        require(entry.reviewState == NotebookReviewState.APPROVED) { "Approve this exact revision before submitting it" }
        val enabledPublishers = settingsService.load().notebookPublishers
            .filter { it.enabled }
            .mapTo(hashSetOf()) { it.publisherId }
        require(publisherIds.isNotEmpty() && publisherIds.all(enabledPublishers::contains)) {
            "Select at least one enabled notebook publisher"
        }
        return eventRecorder.submitNotebookRevision(entry, publisherIds)
    }

    override fun close() = httpClient.close()
}
