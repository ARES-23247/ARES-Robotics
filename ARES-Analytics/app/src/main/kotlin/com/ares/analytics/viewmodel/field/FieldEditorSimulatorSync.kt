package com.ares.analytics.viewmodel.field

import com.ares.analytics.service.Nt4ClientService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal object FieldEditorSimulatorSync {
    const val FIELD_APPLY_CONFIRMATION_TIMEOUT_MS = 3_000L

    fun currentSimulatorReceipt(
        receiptProvider: (() -> SimulatorFieldApplyReceipt?)?,
        nt4ClientService: Nt4ClientService?,
    ): SimulatorFieldApplyReceipt? =
        receiptProvider?.invoke() ?: nt4ClientService
            ?.latestValues
            ?.get(SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC)
            ?.stringValue
            .let(::parseSimulatorFieldApplyReceipt)

    fun currentSimulatorFailure(
        failureProvider: (() -> SimulatorFieldApplyFailure?)?,
        nt4ClientService: Nt4ClientService?,
    ): SimulatorFieldApplyFailure? =
        failureProvider?.invoke() ?: nt4ClientService
            ?.latestValues
            ?.get(SIMULATOR_FIELD_APPLY_ERROR_TOPIC)
            ?.takeIf { !it.stringValue.isNullOrBlank() }
            ?.let { frame ->
                SimulatorFieldApplyFailure(
                    eventId = "${frame.sessionId}:${frame.timestampUs}:${frame.sampleOrder}",
                    message = frame.stringValue.orEmpty(),
                )
            }

    suspend fun awaitSimulatorReceipt(
        client: Nt4ClientService,
        expected: ExpectedSimulatorField,
        previousReceipt: SimulatorFieldApplyReceipt?,
    ): SimulatorFieldApplyReceipt? = withTimeoutOrNull(FIELD_APPLY_CONFIRMATION_TIMEOUT_MS) {
        client.telemetryFlow.first { frame ->
            if (frame.key.trimStart('/') != SIMULATOR_FIELD_APPLIED_RECEIPT_TOPIC) return@first false
            val receipt = parseSimulatorFieldApplyReceipt(frame.stringValue) ?: return@first false
            receipt.eventId != previousReceipt?.eventId && receipt.matches(expected)
        }.stringValue.let(::parseSimulatorFieldApplyReceipt)
    }
}
