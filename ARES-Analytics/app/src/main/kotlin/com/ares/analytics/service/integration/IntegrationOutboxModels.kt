package com.ares.analytics.service.integration

import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.PublicationReceipt

enum class DeliveryState {
    PENDING,
    IN_FLIGHT,
    RETRY,
    DELIVERED,
    DEAD,
}

enum class DeliveryErrorKind {
    TRANSIENT,
    RATE_LIMITED,
    AUTHENTICATION,
    CONFIGURATION,
    PAYLOAD,
    UNKNOWN,
}

data class IntegrationDelivery(
    val eventId: String,
    val providerId: String,
    val state: DeliveryState,
    val attemptCount: Int,
    val nextAttemptAtMs: Long,
    val leaseOwner: String? = null,
    val leaseExpiresAtMs: Long? = null,
    val lastErrorKind: DeliveryErrorKind? = null,
    val lastErrorMessage: String? = null,
    val receiptJson: String? = null,
    val updatedAtMs: Long,
)

data class ClaimedIntegrationDelivery(
    val event: IntegrationEvent,
    val delivery: IntegrationDelivery,
)

data class IntegrationDeliverySummary(
    val event: IntegrationEvent,
    val delivery: IntegrationDelivery,
)

interface IntegrationStore {
    suspend fun enqueue(event: IntegrationEvent, providerIds: Set<String>)

    suspend fun claimDeliveries(
        workerId: String,
        nowMs: Long,
        leaseDurationMs: Long,
        limit: Int,
    ): List<ClaimedIntegrationDelivery>

    suspend fun markDelivered(
        eventId: String,
        providerId: String,
        workerId: String,
        receiptJson: String?,
        completedAtMs: Long,
    )

    suspend fun markFailed(
        eventId: String,
        providerId: String,
        workerId: String,
        errorKind: DeliveryErrorKind,
        safeMessage: String,
        retryAtMs: Long?,
        completedAtMs: Long,
    )

    suspend fun getEvent(eventId: String): IntegrationEvent?
    suspend fun getDelivery(eventId: String, providerId: String): IntegrationDelivery?
    suspend fun listRecentDeliveries(limit: Int = 200): List<IntegrationDeliverySummary>
    suspend fun retryDelivery(eventId: String, providerId: String, requestedAtMs: Long): Boolean

    suspend fun saveNotebookRevision(entry: EngineeringNotebookEntry)
    suspend fun getNotebookRevision(entryId: String, revision: Int): EngineeringNotebookEntry?
    suspend fun getLatestNotebookRevision(entryId: String): EngineeringNotebookEntry?
    suspend fun listNotebookRevisions(entryId: String): List<EngineeringNotebookEntry>
    suspend fun listLatestNotebookEntries(limit: Int = 200): List<EngineeringNotebookEntry>
    suspend fun recordPublicationReceipt(entryId: String, receipt: PublicationReceipt)
    suspend fun listPublicationReceipts(entryId: String): List<PublicationReceipt>
}
