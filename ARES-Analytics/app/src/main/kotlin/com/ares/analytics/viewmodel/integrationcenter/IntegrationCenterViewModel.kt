package com.ares.analytics.viewmodel.integrationcenter

import com.ares.analytics.service.integration.IntegrationCenterOperations
import com.ares.analytics.service.integration.IntegrationCenterSnapshot
import com.ares.analytics.service.integration.IntegrationCredential
import com.ares.analytics.service.integration.IntegrationDeliverySummary
import com.ares.analytics.service.integration.NotebookReviewBundle
import com.ares.analytics.service.integration.ProviderConnectionResult
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationSettings
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookPublisherConfig
import com.ares.analytics.shared.models.NotificationProviderConfig
import com.ares.analytics.shared.models.PublicationReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class IntegrationCenterTab { PROVIDERS, DELIVERIES, NOTEBOOK }

data class IntegrationCenterState(
    val tab: IntegrationCenterTab = IntegrationCenterTab.PROVIDERS,
    val settings: IntegrationSettings = IntegrationSettings(),
    val deliveries: List<IntegrationDeliverySummary> = emptyList(),
    val notebooks: List<EngineeringNotebookEntry> = emptyList(),
    val selectedEntryId: String? = null,
    val selectedRevisions: List<EngineeringNotebookEntry> = emptyList(),
    val selectedReceipts: List<PublicationReceipt> = emptyList(),
    val configurationErrors: Map<String, String> = emptyMap(),
    val credentialProtectionDescription: String = "protected local credential store",
    val isLoading: Boolean = false,
    val activeOperation: String? = null,
    val message: String? = null,
    val error: String? = null,
    val connectionResult: ProviderConnectionResult? = null,
)

sealed interface IntegrationCenterIntent {
    data object Load : IntegrationCenterIntent
    data class SelectTab(val tab: IntegrationCenterTab) : IntegrationCenterIntent
    data class SaveNotification(val config: NotificationProviderConfig, val credential: IntegrationCredential?) : IntegrationCenterIntent
    data class SavePublisher(val config: NotebookPublisherConfig, val credential: IntegrationCredential?) : IntegrationCenterIntent
    data class DeleteProvider(val providerId: String) : IntegrationCenterIntent
    data class TestNotification(val config: NotificationProviderConfig, val credential: IntegrationCredential?) : IntegrationCenterIntent
    data class SendTestNotification(val providerId: String, val workspace: IntegrationWorkspaceIdentity) : IntegrationCenterIntent
    data class RetryDelivery(val eventId: String, val providerId: String) : IntegrationCenterIntent
    data class SelectNotebook(val entryId: String) : IntegrationCenterIntent
    data class Approve(val entryId: String, val revision: Int, val reviewerId: String) : IntegrationCenterIntent
    data class Submit(val entryId: String, val revision: Int, val publisherIds: Set<String>) : IntegrationCenterIntent
    data object ClearFeedback : IntegrationCenterIntent
}

class IntegrationCenterViewModel(
    private val service: IntegrationCenterOperations,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow(IntegrationCenterState())
    val state: StateFlow<IntegrationCenterState> = _state.asStateFlow()

    fun onIntent(intent: IntegrationCenterIntent) {
        when (intent) {
            IntegrationCenterIntent.Load -> load()
            is IntegrationCenterIntent.SelectTab -> _state.update { it.copy(tab = intent.tab) }
            is IntegrationCenterIntent.SaveNotification -> operation("Saving provider") {
                service.saveNotificationProvider(intent.config, intent.credential)
                refresh("Provider saved")
            }
            is IntegrationCenterIntent.SavePublisher -> operation("Saving publisher") {
                service.saveNotebookPublisher(intent.config, intent.credential)
                refresh("Publisher saved")
            }
            is IntegrationCenterIntent.DeleteProvider -> operation("Removing provider") {
                service.deleteProvider(intent.providerId)
                refresh("Provider removed; delivery history was retained")
            }
            is IntegrationCenterIntent.TestNotification -> operation("Testing connection") {
                val result = service.testNotificationProvider(intent.config, intent.credential)
                _state.update { it.copy(connectionResult = result, message = connectionMessage(result)) }
            }
            is IntegrationCenterIntent.SendTestNotification -> operation("Queuing test message") {
                check(service.sendTestNotification(intent.providerId, intent.workspace)) {
                    "The durable test event could not be recorded"
                }
                refresh("Durable test message queued; its result will appear in Deliveries")
            }
            is IntegrationCenterIntent.RetryDelivery -> operation("Scheduling retry") {
                val retried = service.retryDelivery(intent.eventId, intent.providerId)
                refresh(if (retried) "Delivery queued for retry" else "Delivered or active work cannot be retried")
            }
            is IntegrationCenterIntent.SelectNotebook -> operation("Loading notebook") {
                val bundle = service.notebook(intent.entryId)
                applyNotebook(intent.entryId, bundle)
            }
            is IntegrationCenterIntent.Approve -> operation("Approving revision") {
                service.approve(intent.entryId, intent.revision, intent.reviewerId)
                applyNotebook(intent.entryId, service.notebook(intent.entryId))
                refresh("Revision approved; submission still requires an explicit action")
            }
            is IntegrationCenterIntent.Submit -> operation("Submitting revision") {
                service.submit(intent.entryId, intent.revision, intent.publisherIds)
                refresh("Approved revision queued for selected publishers")
            }
            IntegrationCenterIntent.ClearFeedback -> _state.update {
                it.copy(message = null, error = null, connectionResult = null)
            }
        }
    }

    private fun load() = operation("Loading integrations", loading = true) { refresh(null) }

    private suspend fun refresh(message: String?) {
        val snapshot = service.snapshot()
        applySnapshot(snapshot, message)
    }

    private fun applySnapshot(snapshot: IntegrationCenterSnapshot, message: String?) {
        _state.update {
            it.copy(
                settings = snapshot.settings,
                deliveries = snapshot.deliveries,
                notebooks = snapshot.notebooks,
                configurationErrors = snapshot.configurationErrors,
                credentialProtectionDescription = snapshot.credentialProtectionDescription,
                message = message ?: it.message,
            )
        }
    }

    private fun applyNotebook(entryId: String, bundle: NotebookReviewBundle) {
        _state.update {
            it.copy(
                selectedEntryId = entryId,
                selectedRevisions = bundle.revisions,
                selectedReceipts = bundle.receipts,
            )
        }
    }

    private fun operation(label: String, loading: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            _state.update { it.copy(isLoading = loading, activeOperation = label, error = null, message = null) }
            try {
                withContext(ioDispatcher) { block() }
            } catch (failure: Exception) {
                _state.update {
                    it.copy(error = failure.message?.take(1_024) ?: "Integration operation failed")
                }
            } finally {
                _state.update { it.copy(isLoading = false, activeOperation = null) }
            }
        }
    }

    private fun connectionMessage(result: ProviderConnectionResult): String = when (result) {
        is ProviderConnectionResult.Connected -> result.identity?.let { "Connected as $it" } ?: "Connection succeeded"
        is ProviderConnectionResult.Failed -> result.safeMessage
        ProviderConnectionResult.Unsupported -> "This provider does not expose a safe connection test"
    }
}
