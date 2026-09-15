package com.ares.analytics.viewmodel

import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.service.tuning.TuningTransport
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

internal suspend fun recordTuningPromotionCheckpoint(
    recorder: ProjectCheckpointRecorder,
    projectPath: String,
    profileDisplayName: String,
    reviewSummary: String,
) = recorder.checkpoint(
    projectPath = projectPath,
    label = "Promoted $profileDisplayName tuning profile: $reviewSummary",
    pathScopes = setOf(".ares/tuning", ".ares/history/tuning"),
)

private const val MAX_SAFE_REQUEST_NONCE = 9_007_199_254_740_991L

internal fun nextTuningRequestNonce(local: Long, observed: Double?, processed: Double? = null): Long {
    fun floor(value: Double?): Long = value?.takeIf {
        it.isFinite() && it % 1.0 == 0.0 && it in 0.0..MAX_SAFE_REQUEST_NONCE.toDouble()
    }?.toLong() ?: -1L
    val base = maxOf(local, floor(observed), floor(processed))
    require(base < MAX_SAFE_REQUEST_NONCE) {
        "The robot tuning request nonce is exhausted. Restart both robot and dashboard before another live test; no value was requested."
    }
    return base + 1L
}

internal data class ObservedTuningTopics(val declaration: TuningParameterDeclaration) {
    val current = TuningTransport.current(declaration)
    val consumerSupported = TuningTransport.consumerSupported(declaration)
}

internal fun TelemetryFrame.tuningBoolean(): Boolean? {
    val text = stringValue
    return if (text != null) text.toBooleanStrictOrNull()
        else when (value) { 0.0 -> false; 1.0 -> true; else -> null }
}

internal fun TelemetryFrame.toTuningValue(declaration: TuningParameterDeclaration): TuningValue? = when (declaration.type) {
    TuningParameterType.DOUBLE -> value.takeIf(Double::isFinite)?.let { TuningValue(doubleValue = it) }
    TuningParameterType.INT -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble() }
        ?.let { TuningValue(intValue = it.toInt()) }
    TuningParameterType.BOOLEAN -> tuningBoolean()?.let { TuningValue(booleanValue = it) }
    TuningParameterType.TEXT, TuningParameterType.ENUM -> stringValue?.let { TuningValue(textValue = it) }
}
