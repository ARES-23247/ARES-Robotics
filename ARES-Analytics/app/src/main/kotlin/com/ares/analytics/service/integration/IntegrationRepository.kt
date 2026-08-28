package com.ares.analytics.service.integration

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.EngineeringNotebookHasher
import com.ares.analytics.shared.models.IntegrationEvent
import com.ares.analytics.shared.models.IntegrationEventHasher
import com.ares.analytics.shared.models.IntegrationEventPayload
import com.ares.analytics.shared.models.IntegrationWorkspaceIdentity
import com.ares.analytics.shared.models.NotebookAiProvenance
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookEvidenceReference
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.NotebookVisibility
import com.ares.analytics.shared.models.PublicationReceipt
import com.ares.analytics.shared.models.aggregateId
import com.ares.analytics.shared.models.eventType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.sql.Connection
import java.sql.ResultSet

internal class IntegrationRepository(
    private val connection: Connection,
    private val mutex: Mutex,
) : IntegrationStore {

    override suspend fun enqueue(event: IntegrationEvent, providerIds: Set<String>) {
        mutex.withLock {
            validateEvent(event)
            val normalizedProviders = providerIds.map { providerId -> validateProviderId(providerId) }.toSortedSet()
            val payloadJson = AppJson.encodeToString(IntegrationEventPayload.serializer(), event.payload)
            val contentHash = IntegrationEventHasher.sha256(event)

            inTransaction {
                val existingHash = connection.prepareStatement(
                    "SELECT content_hash FROM integration_events WHERE event_id = ?"
                ).use { statement ->
                    statement.setString(1, event.eventId)
                    statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                }
                require(existingHash == null || existingHash == contentHash) {
                    "Integration event ID already exists with different content"
                }

                if (existingHash == null) {
                    connection.prepareStatement(
                        """
                        INSERT INTO integration_events (
                            event_id, schema_version, event_type, occurred_at_ms, aggregate_id,
                            team_id, season_id, robot_id, payload_json, content_hash
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, event.eventId)
                        statement.setInt(2, event.schemaVersion)
                        statement.setString(3, event.payload.eventType().name)
                        statement.setLong(4, event.occurredAtMs)
                        statement.setString(5, event.payload.aggregateId())
                        statement.setString(6, event.payload.workspace.teamId)
                        statement.setString(7, event.payload.workspace.seasonId)
                        statement.setString(8, event.payload.workspace.robotId)
                        statement.setString(9, payloadJson)
                        statement.setString(10, contentHash)
                        statement.executeUpdate()
                    }
                }

                normalizedProviders.forEach { providerId ->
                    val deliveryExists = connection.prepareStatement(
                        "SELECT 1 FROM integration_deliveries WHERE event_id = ? AND provider_id = ? LIMIT 1"
                    ).use { statement ->
                        statement.setString(1, event.eventId)
                        statement.setString(2, providerId)
                        statement.executeQuery().use { rows -> rows.next() }
                    }
                    if (deliveryExists) return@forEach
                    connection.prepareStatement(
                        """
                        INSERT INTO integration_deliveries (
                            event_id, provider_id, state, attempt_count, next_attempt_at_ms,
                            lease_owner, lease_expires_at_ms, last_error_kind, last_error_message,
                            receipt_json, updated_at_ms
                        ) VALUES (?, ?, 'PENDING', 0, ?, NULL, NULL, NULL, NULL, NULL, ?)
                        """.trimIndent()
                    ).use { statement ->
                        statement.setString(1, event.eventId)
                        statement.setString(2, providerId)
                        statement.setLong(3, event.occurredAtMs)
                        statement.setLong(4, event.occurredAtMs)
                        statement.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun claimDeliveries(
        workerId: String,
        nowMs: Long,
        leaseDurationMs: Long,
        limit: Int,
    ): List<ClaimedIntegrationDelivery> = mutex.withLock {
        require(workerId.isNotBlank()) { "Integration worker ID cannot be blank" }
        require(leaseDurationMs > 0L) { "Integration lease duration must be positive" }
        require(limit in 1..256) { "Integration delivery claim limit must be between 1 and 256" }

        inTransaction {
            val candidates = connection.prepareStatement(
                """
                SELECT event_id, provider_id
                FROM integration_deliveries
                WHERE state IN ('PENDING', 'RETRY', 'IN_FLIGHT')
                  AND next_attempt_at_ms <= ?
                  AND (lease_owner IS NULL OR lease_expires_at_ms <= ?)
                ORDER BY next_attempt_at_ms, event_id, provider_id
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, nowMs)
                statement.setLong(2, nowMs)
                statement.setInt(3, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1) to rows.getString(2))
                    }
                }
            }

            val leaseExpiresAtMs = nowMs + leaseDurationMs
            candidates.map { (eventId, providerId) ->
                connection.prepareStatement(
                    """
                    UPDATE integration_deliveries
                    SET state = 'IN_FLIGHT', attempt_count = attempt_count + 1,
                        lease_owner = ?, lease_expires_at_ms = ?, updated_at_ms = ?
                    WHERE event_id = ? AND provider_id = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, workerId)
                    statement.setLong(2, leaseExpiresAtMs)
                    statement.setLong(3, nowMs)
                    statement.setString(4, eventId)
                    statement.setString(5, providerId)
                    check(statement.executeUpdate() == 1) { "Integration delivery claim was lost" }
                }
                ClaimedIntegrationDelivery(
                    event = requireNotNull(getEventSync(eventId)),
                    delivery = requireNotNull(getDeliverySync(eventId, providerId)),
                )
            }
        }
    }

    override suspend fun markDelivered(
        eventId: String,
        providerId: String,
        workerId: String,
        receiptJson: String?,
        completedAtMs: Long,
    ) = mutex.withLock {
        updateClaimedDelivery(
            eventId = eventId,
            providerId = providerId,
            workerId = workerId,
            state = DeliveryState.DELIVERED,
            errorKind = null,
            safeMessage = null,
            receiptJson = receiptJson,
            nextAttemptAtMs = null,
            completedAtMs = completedAtMs,
        )
    }

    override suspend fun markFailed(
        eventId: String,
        providerId: String,
        workerId: String,
        errorKind: DeliveryErrorKind,
        safeMessage: String,
        retryAtMs: Long?,
        completedAtMs: Long,
    ) = mutex.withLock {
        require(safeMessage.length <= MAX_ERROR_LENGTH) { "Integration error message exceeds storage limit" }
        val state = if (retryAtMs == null) DeliveryState.DEAD else DeliveryState.RETRY
        updateClaimedDelivery(
            eventId = eventId,
            providerId = providerId,
            workerId = workerId,
            state = state,
            errorKind = errorKind,
            safeMessage = safeMessage,
            receiptJson = null,
            nextAttemptAtMs = retryAtMs ?: completedAtMs,
            completedAtMs = completedAtMs,
        )
    }

    override suspend fun getEvent(eventId: String): IntegrationEvent? = mutex.withLock {
        getEventSync(eventId)
    }

    override suspend fun getDelivery(eventId: String, providerId: String): IntegrationDelivery? = mutex.withLock {
        getDeliverySync(eventId, providerId)
    }

    override suspend fun listRecentDeliveries(limit: Int): List<IntegrationDeliverySummary> = mutex.withLock {
        require(limit in 1..1_000) { "Integration delivery history limit must be between 1 and 1000" }
        connection.prepareStatement(
            """
            SELECT event_id, provider_id
            FROM integration_deliveries
            ORDER BY updated_at_ms DESC, event_id, provider_id
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val eventId = rows.getString(1)
                        val providerId = rows.getString(2)
                        add(
                            IntegrationDeliverySummary(
                                event = requireNotNull(getEventSync(eventId)),
                                delivery = requireNotNull(getDeliverySync(eventId, providerId)),
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun retryDelivery(eventId: String, providerId: String, requestedAtMs: Long): Boolean =
        mutex.withLock {
            require(requestedAtMs >= 0L) { "Integration retry time cannot be negative" }
            val existing = getDeliverySync(eventId, providerId) ?: return@withLock false
            if (existing.state == DeliveryState.DELIVERED || existing.state == DeliveryState.IN_FLIGHT) {
                return@withLock false
            }
            connection.prepareStatement(
                """
                UPDATE integration_deliveries
                SET state = 'PENDING', next_attempt_at_ms = ?, lease_owner = NULL,
                    lease_expires_at_ms = NULL, updated_at_ms = ?
                WHERE event_id = ? AND provider_id = ? AND state IN ('PENDING', 'RETRY', 'DEAD')
                """.trimIndent()
            ).use { statement ->
                statement.setLong(1, requestedAtMs)
                statement.setLong(2, requestedAtMs)
                statement.setString(3, eventId)
                statement.setString(4, providerId)
                statement.executeUpdate() == 1
            }
        }

    override suspend fun saveNotebookRevision(entry: EngineeringNotebookEntry) {
        mutex.withLock {
            validateNotebookEntry(entry)
            inTransaction {
            val existingHash = connection.prepareStatement(
                "SELECT content_hash FROM engineering_notebook_entries WHERE entry_id = ? AND revision = ?"
            ).use { statement ->
                statement.setString(1, entry.entryId)
                statement.setInt(2, entry.revision)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
            if (existingHash != null) {
                require(existingHash == entry.contentHash) {
                    "Notebook revision already exists with different content"
                }
                connection.prepareStatement(
                    """
                    UPDATE engineering_notebook_entries
                    SET review_state = ?, human_reviewer_id = ?, updated_at_ms = ?
                    WHERE entry_id = ? AND revision = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, entry.reviewState.name)
                    statement.setString(2, entry.humanReviewerId)
                    statement.setLong(3, entry.updatedAtMs)
                    statement.setString(4, entry.entryId)
                    statement.setInt(5, entry.revision)
                    statement.executeUpdate()
                }
                return@inTransaction
            }

            val latestRevision = connection.prepareStatement(
                "SELECT MAX(revision) FROM engineering_notebook_entries WHERE entry_id = ?"
            ).use { statement ->
                statement.setString(1, entry.entryId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1).takeUnless { rows.wasNull() }
                }
            }
            require(entry.revision == (latestRevision?.plus(1) ?: 1)) {
                "Notebook revisions must be inserted sequentially"
            }

            connection.prepareStatement(
                """
                INSERT INTO engineering_notebook_entries (
                    entry_id, revision, schema_version, entry_type, team_id, season_id, robot_id,
                    markdown_body, evidence_json, visibility, review_state, human_author_id,
                    human_reviewer_id, ai_provenance_json, content_hash, created_at_ms, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, entry.entryId)
                statement.setInt(2, entry.revision)
                statement.setInt(3, entry.schemaVersion)
                statement.setString(4, entry.entryType.name)
                statement.setString(5, entry.workspace.teamId)
                statement.setString(6, entry.workspace.seasonId)
                statement.setString(7, entry.workspace.robotId)
                statement.setString(8, entry.markdownBody)
                statement.setString(9, AppJson.encodeToString(entry.evidence))
                statement.setString(10, entry.visibility.name)
                statement.setString(11, entry.reviewState.name)
                statement.setString(12, entry.humanAuthorId)
                statement.setString(13, entry.humanReviewerId)
                statement.setString(14, entry.aiProvenance?.let { AppJson.encodeToString(it) })
                statement.setString(15, entry.contentHash)
                statement.setLong(16, entry.createdAtMs)
                statement.setLong(17, entry.updatedAtMs)
                statement.executeUpdate()
            }
            }
        }
    }

    override suspend fun getNotebookRevision(entryId: String, revision: Int): EngineeringNotebookEntry? =
        mutex.withLock { getNotebookRevisionSync(entryId, revision) }

    override suspend fun getLatestNotebookRevision(entryId: String): EngineeringNotebookEntry? = mutex.withLock {
        connection.prepareStatement(
            """
            SELECT * FROM engineering_notebook_entries
            WHERE entry_id = ? ORDER BY revision DESC LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entryId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toNotebookEntry() else null }
        }
    }

    override suspend fun listNotebookRevisions(entryId: String): List<EngineeringNotebookEntry> = mutex.withLock {
        connection.prepareStatement(
            "SELECT * FROM engineering_notebook_entries WHERE entry_id = ? ORDER BY revision"
        ).use { statement ->
            statement.setString(1, entryId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toNotebookEntry()) }
            }
        }
    }

    override suspend fun listLatestNotebookEntries(limit: Int): List<EngineeringNotebookEntry> = mutex.withLock {
        require(limit in 1..1_000) { "Notebook history limit must be between 1 and 1000" }
        connection.prepareStatement(
            """
            SELECT entries.*
            FROM engineering_notebook_entries entries
            INNER JOIN (
                SELECT entry_id, MAX(revision) AS revision
                FROM engineering_notebook_entries
                GROUP BY entry_id
            ) latest ON entries.entry_id = latest.entry_id AND entries.revision = latest.revision
            ORDER BY entries.updated_at_ms DESC, entries.entry_id
            LIMIT ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.toNotebookEntry()) }
            }
        }
    }

    override suspend fun recordPublicationReceipt(entryId: String, receipt: PublicationReceipt) {
        mutex.withLock {
            validateProviderId(receipt.publisherId)
            val entry = getNotebookRevisionSync(entryId, receipt.submittedRevision)
                ?: throw IllegalArgumentException("Publication receipt references an unknown notebook revision")
            require(entry.contentHash == receipt.submittedContentHash) {
                "Publication receipt content hash does not match the notebook revision"
            }
            val receiptJson = AppJson.encodeToString(receipt)
            connection.prepareStatement(
                """
                INSERT OR IGNORE INTO notebook_publication_receipts (
                    entry_id, revision, publisher_id, content_hash, remote_id, receipt_json, accepted_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, entryId)
                statement.setInt(2, receipt.submittedRevision)
                statement.setString(3, receipt.publisherId)
                statement.setString(4, receipt.submittedContentHash)
                statement.setString(5, receipt.remoteId)
                statement.setString(6, receiptJson)
                statement.setLong(7, receipt.acceptedAtMs)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun listPublicationReceipts(entryId: String): List<PublicationReceipt> = mutex.withLock {
        connection.prepareStatement(
            """
            SELECT receipt_json FROM notebook_publication_receipts
            WHERE entry_id = ? ORDER BY accepted_at_ms, publisher_id, remote_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, entryId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(AppJson.decodeFromString<PublicationReceipt>(rows.getString(1)))
                }
            }
        }
    }

    private fun updateClaimedDelivery(
        eventId: String,
        providerId: String,
        workerId: String,
        state: DeliveryState,
        errorKind: DeliveryErrorKind?,
        safeMessage: String?,
        receiptJson: String?,
        nextAttemptAtMs: Long?,
        completedAtMs: Long,
    ) {
        val sql = """
            UPDATE integration_deliveries
            SET state = ?, last_error_kind = ?, last_error_message = ?, receipt_json = ?,
                updated_at_ms = ?, next_attempt_at_ms = COALESCE(?, next_attempt_at_ms),
                lease_owner = NULL, lease_expires_at_ms = NULL
            WHERE event_id = ? AND provider_id = ? AND state = 'IN_FLIGHT' AND lease_owner = ?
        """.trimIndent()
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, errorKind?.name)
            statement.setString(3, safeMessage)
            statement.setString(4, receiptJson)
            statement.setLong(5, completedAtMs)
            if (nextAttemptAtMs == null) statement.setNull(6, java.sql.Types.BIGINT)
            else statement.setLong(6, nextAttemptAtMs)
            statement.setString(7, eventId)
            statement.setString(8, providerId)
            statement.setString(9, workerId)
            check(statement.executeUpdate() == 1) { "Integration delivery lease is no longer owned by this worker" }
        }
    }

    private fun getEventSync(eventId: String): IntegrationEvent? = connection.prepareStatement(
        "SELECT event_id, schema_version, occurred_at_ms, payload_json FROM integration_events WHERE event_id = ?"
    ).use { statement ->
        statement.setString(1, eventId)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            IntegrationEvent(
                eventId = rows.getString("event_id"),
                occurredAtMs = rows.getLong("occurred_at_ms"),
                payload = AppJson.decodeFromString(
                    IntegrationEventPayload.serializer(),
                    rows.getString("payload_json"),
                ),
                schemaVersion = rows.getInt("schema_version"),
            )
        }
    }

    private fun getDeliverySync(eventId: String, providerId: String): IntegrationDelivery? =
        connection.prepareStatement(
            "SELECT * FROM integration_deliveries WHERE event_id = ? AND provider_id = ?"
        ).use { statement ->
            statement.setString(1, eventId)
            statement.setString(2, providerId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toDelivery() else null }
        }

    private fun getNotebookRevisionSync(entryId: String, revision: Int): EngineeringNotebookEntry? =
        connection.prepareStatement(
            "SELECT * FROM engineering_notebook_entries WHERE entry_id = ? AND revision = ?"
        ).use { statement ->
            statement.setString(1, entryId)
            statement.setInt(2, revision)
            statement.executeQuery().use { rows -> if (rows.next()) rows.toNotebookEntry() else null }
        }

    private fun ResultSet.toDelivery(): IntegrationDelivery = IntegrationDelivery(
        eventId = getString("event_id"),
        providerId = getString("provider_id"),
        state = DeliveryState.valueOf(getString("state")),
        attemptCount = getInt("attempt_count"),
        nextAttemptAtMs = getLong("next_attempt_at_ms"),
        leaseOwner = getString("lease_owner"),
        leaseExpiresAtMs = getLong("lease_expires_at_ms").takeUnless { wasNull() },
        lastErrorKind = getString("last_error_kind")?.let(DeliveryErrorKind::valueOf),
        lastErrorMessage = getString("last_error_message"),
        receiptJson = getString("receipt_json"),
        updatedAtMs = getLong("updated_at_ms"),
    )

    private fun ResultSet.toNotebookEntry(): EngineeringNotebookEntry = EngineeringNotebookEntry(
        entryId = getString("entry_id"),
        revision = getInt("revision"),
        entryType = NotebookEntryType.valueOf(getString("entry_type")),
        workspace = IntegrationWorkspaceIdentity(
            teamId = getString("team_id"),
            seasonId = getString("season_id"),
            robotId = getString("robot_id"),
        ),
        markdownBody = getString("markdown_body"),
        evidence = AppJson.decodeFromString(
            ListSerializer(NotebookEvidenceReference.serializer()),
            getString("evidence_json"),
        ),
        visibility = NotebookVisibility.valueOf(getString("visibility")),
        reviewState = NotebookReviewState.valueOf(getString("review_state")),
        humanAuthorId = getString("human_author_id"),
        humanReviewerId = getString("human_reviewer_id"),
        aiProvenance = getString("ai_provenance_json")?.let {
            AppJson.decodeFromString<NotebookAiProvenance>(it)
        },
        contentHash = getString("content_hash"),
        createdAtMs = getLong("created_at_ms"),
        updatedAtMs = getLong("updated_at_ms"),
        schemaVersion = getInt("schema_version"),
    )

    private fun validateEvent(event: IntegrationEvent) {
        require(event.schemaVersion > 0) { "Integration event schema version must be positive" }
        require(event.eventId.isNotBlank() && event.eventId.length <= MAX_ID_LENGTH) {
            "Integration event ID must contain 1 to $MAX_ID_LENGTH characters"
        }
        require(event.occurredAtMs >= 0L) { "Integration event time cannot be negative" }
        validateWorkspace(event.payload.workspace)
        require(event.payload.aggregateId().isNotBlank()) { "Integration event aggregate ID cannot be blank" }
    }

    private fun validateNotebookEntry(entry: EngineeringNotebookEntry) {
        require(entry.schemaVersion > 0) { "Notebook schema version must be positive" }
        require(entry.entryId.isNotBlank() && entry.entryId.length <= MAX_ID_LENGTH) {
            "Notebook entry ID must contain 1 to $MAX_ID_LENGTH characters"
        }
        require(entry.revision > 0) { "Notebook revision must be positive" }
        require(entry.markdownBody.length <= MAX_NOTEBOOK_LENGTH) { "Notebook body exceeds storage limit" }
        require(entry.updatedAtMs >= entry.createdAtMs) { "Notebook update time cannot precede creation" }
        validateWorkspace(entry.workspace)
        require(EngineeringNotebookHasher.sha256(entry) == entry.contentHash) {
            "Notebook content hash does not match its canonical content"
        }
    }

    private fun validateWorkspace(workspace: IntegrationWorkspaceIdentity) {
        require(workspace.teamId.isNotBlank()) { "Integration team ID cannot be blank" }
        require(workspace.seasonId.isNotBlank()) { "Integration season ID cannot be blank" }
        require(workspace.robotId.isNotBlank()) { "Integration robot ID cannot be blank" }
    }

    private fun validateProviderId(providerId: String): String {
        val normalized = providerId.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_PROVIDER_ID_LENGTH) {
            "Integration provider ID must contain 1 to $MAX_PROVIDER_ID_LENGTH characters"
        }
        require(normalized.matches(PROVIDER_ID_PATTERN)) {
            "Integration provider ID may contain lowercase letters, digits, dots, underscores, and hyphens"
        }
        return normalized
    }

    private inline fun <T> inTransaction(block: () -> T): T {
        connection.createStatement().use { it.execute("BEGIN TRANSACTION") }
        return try {
            block().also { connection.createStatement().use { statement -> statement.execute("COMMIT") } }
        } catch (failure: Throwable) {
            runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
            throw failure
        }
    }

    private companion object {
        const val MAX_ID_LENGTH = 256
        const val MAX_PROVIDER_ID_LENGTH = 128
        const val MAX_ERROR_LENGTH = 1_024
        const val MAX_NOTEBOOK_LENGTH = 1_000_000
        val PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
