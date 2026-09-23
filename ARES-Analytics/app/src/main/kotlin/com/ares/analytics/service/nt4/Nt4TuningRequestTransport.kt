package com.ares.analytics.service.nt4

import com.ares.analytics.service.TelemetryStore
import com.ares.analytics.service.tuning.TuningTransport
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the live-context checks around a suspended tuning publication and its local observations.
 * True means enqueued, not robot-applied; a later context change cannot recall the wire frame.
 */
internal suspend fun publishLiveTuningRequest(
    publisher: Nt4OutboundPublisher,
    telemetryStore: TelemetryStore,
    isReplayActive: StateFlow<Boolean>,
    currentSessionId: () -> String,
    declaration: TuningParameterDeclaration,
    value: TuningValue,
    nonce: Long,
    expectedConnection: Long,
): Boolean {
    require(when (declaration.type) {
        TuningParameterType.DOUBLE -> value.doubleValue != null
        TuningParameterType.INT -> value.intValue != null
        TuningParameterType.BOOLEAN -> value.booleanValue != null
        TuningParameterType.TEXT, TuningParameterType.ENUM -> value.textValue != null
    }) { "Tuning value does not match its declared type" }
    if (isReplayActive.value) return false
    val initialEpoch = telemetryStore.currentTargetEpoch()
    val requested = TuningTransport.requested(declaration)
    val commit = TuningTransport.requestNonce(declaration)
    val isLiveValid = { !isReplayActive.value && telemetryStore.currentTargetEpoch() == initialEpoch }
    if (!isLiveValid()) return false
    if (!publisher.publishTuningRequest(
        requestedKey = requested,
        nonceKey = commit,
        value = value,
        nonce = nonce,
        expectedConnection = expectedConnection,
        isLiveValid = isLiveValid,
    )) return false

    val now = System.currentTimeMillis()
    val sessionId = currentSessionId()
    val numeric = value.doubleValue ?: value.intValue?.toDouble() ?: if (value.booleanValue == true) 1.0 else 0.0

    if (isLiveValid()) {
        telemetryStore.accept(TelemetryFrame(now, sessionId, requested, numeric, stringValue = value.textValue))
    }
    if (isLiveValid()) {
        telemetryStore.accept(TelemetryFrame(now, sessionId, commit, nonce.toDouble()))
    }
    return true
}
