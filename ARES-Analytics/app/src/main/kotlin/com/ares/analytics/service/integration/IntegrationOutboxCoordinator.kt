package com.ares.analytics.service.integration

import com.ares.analytics.shared.models.IntegrationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID
import kotlin.math.min

interface IntegrationDeliveryProvider {
    val providerId: String
    suspend fun deliver(event: IntegrationEvent): IntegrationDeliveryResult
}
sealed interface IntegrationDeliveryResult {
    data class Delivered(val receiptJson: String? = null) : IntegrationDeliveryResult

    data class Retry(
        val errorKind: DeliveryErrorKind,
        val safeMessage: String,
        val retryAfterMs: Long? = null,
    ) : IntegrationDeliveryResult

    data class Rejected(
        val errorKind: DeliveryErrorKind,
        val safeMessage: String,
    ) : IntegrationDeliveryResult
}

class IntegrationOutboxCoordinator(
    private val store: IntegrationStore,
    providers: Collection<IntegrationDeliveryProvider>,
    private val clock: Clock = Clock.systemUTC(),
    private val workerId: String = "integration-${UUID.randomUUID()}",
    private val leaseDurationMs: Long = 60_000L,
    private val pollIntervalMs: Long = 2_000L,
    private val maximumAttempts: Int = 8,
) {
    private val providersById = providers.associateBy(IntegrationDeliveryProvider::providerId)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null

    init {
        require(workerId.isNotBlank()) { "Integration worker ID cannot be blank" }
        require(leaseDurationMs > 0L) { "Integration lease duration must be positive" }
        require(pollIntervalMs > 0L) { "Integration poll interval must be positive" }
        require(maximumAttempts > 0) { "Integration maximum attempts must be positive" }
        require(providersById.size == providers.size) { "Integration provider IDs must be unique" }
    }

    fun start() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (isActive) {
                runCatching { drainOnce() }
                    .onFailure { failure ->
                        System.err.println(
                            "[ARES-Analytics] Integration outbox pass failed: " +
                                (failure.message ?: failure::class.java.simpleName)
                        )
                    }
                delay(pollIntervalMs)
            }
        }
    }

    suspend fun drainOnce(limit: Int = 32): Int {
        require(limit in 1..256) { "Integration delivery claim limit must be between 1 and 256" }
        val nowMs = clock.millis()
        val claimed = store.claimDeliveries(workerId, nowMs, leaseDurationMs, limit)
        claimed.forEach { claim -> deliver(claim) }
        return claimed.size
    }

    private suspend fun deliver(claim: ClaimedIntegrationDelivery) {
        val nowMs = clock.millis()
        val provider = providersById[claim.delivery.providerId]
        if (provider == null) {
            store.markFailed(
                eventId = claim.event.eventId,
                providerId = claim.delivery.providerId,
                workerId = workerId,
                errorKind = DeliveryErrorKind.CONFIGURATION,
                safeMessage = "Configured integration provider is unavailable",
                retryAtMs = null,
                completedAtMs = nowMs,
            )
            return
        }

        val result = try {
            provider.deliver(claim.event)
        } catch (failure: Exception) {
            IntegrationDeliveryResult.Retry(
                errorKind = DeliveryErrorKind.UNKNOWN,
                safeMessage = failure.message?.take(MAX_SAFE_ERROR_LENGTH)
                    ?: failure::class.java.simpleName,
            )
        }

        when (result) {
            is IntegrationDeliveryResult.Delivered -> store.markDelivered(
                eventId = claim.event.eventId,
                providerId = claim.delivery.providerId,
                workerId = workerId,
                receiptJson = result.receiptJson?.take(MAX_RECEIPT_LENGTH),
                completedAtMs = clock.millis(),
            )

            is IntegrationDeliveryResult.Rejected -> store.markFailed(
                eventId = claim.event.eventId,
                providerId = claim.delivery.providerId,
                workerId = workerId,
                errorKind = result.errorKind,
                safeMessage = result.safeMessage.take(MAX_SAFE_ERROR_LENGTH),
                retryAtMs = null,
                completedAtMs = clock.millis(),
            )

            is IntegrationDeliveryResult.Retry -> {
                val retryAtMs = if (claim.delivery.attemptCount >= maximumAttempts) {
                    null
                } else {
                    clock.millis() + (result.retryAfterMs ?: retryDelayMs(claim.delivery.attemptCount))
                }
                store.markFailed(
                    eventId = claim.event.eventId,
                    providerId = claim.delivery.providerId,
                    workerId = workerId,
                    errorKind = result.errorKind,
                    safeMessage = result.safeMessage.take(MAX_SAFE_ERROR_LENGTH),
                    retryAtMs = retryAtMs,
                    completedAtMs = clock.millis(),
                )
            }
        }
    }

    suspend fun closeAndJoin() {
        scope.cancel()
        workerJob?.cancelAndJoin()
    }

    private fun retryDelayMs(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, 20)
        return min(BASE_RETRY_MS shl exponent, MAX_RETRY_MS)
    }

    private companion object {
        const val BASE_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15 * 60 * 1_000L
        const val MAX_SAFE_ERROR_LENGTH = 1_024
        const val MAX_RECEIPT_LENGTH = 16_384
    }
}
