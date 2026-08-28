package com.ares.analytics.service.integration

import com.ares.analytics.service.ImportReport
import com.ares.analytics.shared.AlertRecord
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.models.AnalysisReady
import com.ares.analytics.shared.models.CloudUploadCommitted
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventType
import com.ares.analytics.shared.models.IntegrationIssueSeverity
import com.ares.analytics.shared.models.IntegrationTestRequested
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.NotebookDraftReady
import com.ares.analytics.shared.models.RobotIssueOpened
import com.ares.analytics.shared.models.RobotIssueResolved
import com.ares.analytics.shared.models.SessionImported
import com.ares.analytics.shared.models.SoftwareDigestReady
import com.ares.analytics.shared.models.eventType
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

/** Runtime routing is mutable so provider settings can change without rebuilding import services. */
class IntegrationRoutingPolicy {
    private val routes = AtomicReference<Map<IntegrationEventType, Set<String>>>(emptyMap())
    private val notebookPublishers = AtomicReference<Set<String>>(emptySet())

    fun replace(newRoutes: Map<IntegrationEventType, Set<String>>, notebookPublisherIds: Set<String> = emptySet()) {
        routes.set(newRoutes.mapValues { (_, providers) -> providers.toSet() })
        notebookPublishers.set(notebookPublisherIds.toSet())
    }

    fun providersFor(type: IntegrationEventType): Set<String> = routes.get()[type].orEmpty()

    fun notificationProvidersFor(type: IntegrationEventType): Set<String> =
        providersFor(type) - notebookPublishers.get()
}

/** Records typed events after their owning transaction has committed. */
class IntegrationEventRecorder(
    private val store: IntegrationStore,
    private val routingPolicy: IntegrationRoutingPolicy,
) {
    suspend fun sessionImported(session: Session, reports: List<ImportReport>) = recordSafely(
        IntegrationEvent(
            eventId = "session-imported:${session.sessionId}",
            occurredAtMs = session.createdAt,
            payload = SessionImported(
                workspace = session.workspaceIdentity(),
                sessionId = session.sessionId,
                sourceNames = reports.map(ImportReport::sourceName).distinct().sorted(),
                sourceSha256 = reports.map(ImportReport::sourceSha256).distinct().sorted(),
            ),
        )
    )

    suspend fun analysisReady(summary: SessionSummary, analysisVersion: String = "summary-v1") = recordSafely(
        IntegrationEvent(
            eventId = "analysis-ready:${summary.sessionId}:$analysisVersion",
            occurredAtMs = summary.createdAt,
            payload = AnalysisReady(
                workspace = summary.workspaceIdentity(),
                sessionId = summary.sessionId,
                analysisVersion = analysisVersion,
            ),
        )
    )

    suspend fun alertPersisted(alert: AlertRecord, workspace: IntegrationWorkspaceIdentity): Boolean {
        val resolvedAtMs = alert.resolveTimestampMs
        return recordSafely(
            if (resolvedAtMs == null) {
            IntegrationEvent(
                eventId = "robot-issue-opened:${alert.alertId}",
                occurredAtMs = alert.triggerTimestampMs,
                payload = RobotIssueOpened(
                    workspace = workspace,
                    issueId = alert.alertId,
                    sessionId = alert.sessionId,
                    ruleKey = alert.ruleKey,
                    severity = IntegrationIssueSeverity.WARNING,
                    summary = "Alert rule ${alert.ruleKey} opened",
                ),
            )
            } else {
                IntegrationEvent(
                    eventId = "robot-issue-resolved:${alert.alertId}",
                    occurredAtMs = resolvedAtMs,
                    payload = RobotIssueResolved(
                        workspace = workspace,
                        issueId = alert.alertId,
                        sessionId = alert.sessionId,
                        resolution = "Alert rule ${alert.ruleKey} resolved",
                    ),
                )
            }
        )
    }

    suspend fun cloudUploadCommitted(
        workspace: IntegrationWorkspaceIdentity,
        sessionId: String,
        remoteObjectId: String,
        manifestRevision: String,
        occurredAtMs: Long,
        remoteUrl: String? = null,
    ) = recordSafely(
        IntegrationEvent(
            eventId = "cloud-upload-committed:$sessionId:$remoteObjectId",
            occurredAtMs = occurredAtMs,
            payload = CloudUploadCommitted(
                workspace = workspace,
                sessionId = sessionId,
                remoteObjectId = remoteObjectId,
                manifestRevision = manifestRevision,
                remoteUrl = remoteUrl,
            ),
        )
    )

    suspend fun notebookDraftReady(entry: EngineeringNotebookEntry) = recordSafely(
        notebookEvent(entry),
        routingPolicy.notificationProvidersFor(IntegrationEventType.NOTEBOOK_DRAFT_READY),
    )

    suspend fun submitNotebookRevision(entry: EngineeringNotebookEntry, publisherIds: Set<String>): Boolean {
        require(entry.reviewState == com.ares.analytics.shared.models.NotebookReviewState.APPROVED) {
            "Only an approved notebook revision can be submitted"
        }
        require(publisherIds.isNotEmpty()) { "At least one notebook publisher must be selected" }
        return recordSafely(notebookEvent(entry), publisherIds)
    }

    private fun notebookEvent(entry: EngineeringNotebookEntry) = IntegrationEvent(
            eventId = "notebook-draft-ready:${entry.entryId}:${entry.contentHash}",
            // Review-state changes do not alter contentHash or event identity. Keep event content
            // stable so an explicit post-approval submission can idempotently add destinations.
            occurredAtMs = entry.createdAtMs,
            payload = NotebookDraftReady(
                workspace = entry.workspace,
                entryId = entry.entryId,
                revision = entry.revision,
                contentHash = entry.contentHash,
            ),
        )

    suspend fun softwareDigestReady(entry: EngineeringNotebookEntry, commitRange: String) = recordSafely(
        IntegrationEvent(
            eventId = "software-digest-ready:${entry.entryId}:${entry.contentHash}",
            occurredAtMs = entry.updatedAtMs,
            payload = SoftwareDigestReady(
                workspace = entry.workspace,
                entryId = entry.entryId,
                revision = entry.revision,
                contentHash = entry.contentHash,
                commitRange = commitRange,
            ),
        ),
        routingPolicy.notificationProvidersFor(IntegrationEventType.SOFTWARE_DIGEST_READY),
    )

    suspend fun integrationTestRequested(
        workspace: IntegrationWorkspaceIdentity,
        providerId: String,
        occurredAtMs: Long,
    ): Boolean {
        val testId = UUID.randomUUID().toString()
        return recordSafely(
            IntegrationEvent(
                eventId = "integration-test:$testId",
                occurredAtMs = occurredAtMs,
                payload = IntegrationTestRequested(
                    workspace = workspace,
                    testId = testId,
                    targetProviderId = providerId,
                ),
            ),
            setOf(providerId),
        )
    }

    suspend fun recordSafely(
        event: IntegrationEvent,
        providerIds: Set<String> = routingPolicy.providersFor(event.payload.eventType()),
    ): Boolean = runCatching {
        store.enqueue(event, providerIds)
        true
    }.getOrElse { failure ->
        System.err.println(
            "[ARES-Analytics] Durable integration event ${event.eventId} could not be recorded; " +
                "the owning operation remains committed: " +
                (failure.message ?: failure::class.java.simpleName)
        )
        false
    }

    private fun Session.workspaceIdentity() = IntegrationWorkspaceIdentity(teamId, seasonId, robotId)

    private fun SessionSummary.workspaceIdentity() = IntegrationWorkspaceIdentity(teamId, seasonId, robotId)
}
