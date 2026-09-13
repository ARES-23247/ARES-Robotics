package com.areslib.tuning

import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import java.nio.file.Path

/**
 * Declaration-driven tuning transport.
 *
 * It publishes only known typed parameters, applies policy through [TypedTuningRuntime], and may
 * persist only an explicitly robot-local experimental overlay. It never reflects over Redux state,
 * writes canonical project profiles, or accepts unknown topic paths.
 */
class TuningManager(
    private val runtime: TypedTuningRuntime,
    private val telemetry: ITelemetry,
    private val contextProvider: () -> TuningApplyContext,
    /** Returns true only after the robot consumer committed the value to its Redux/control boundary. */
    private val onApplied: (parameterUid: String, value: TuningValue) -> Boolean,
    /** Declares whether the running robot has a compiled consumer for this parameter. */
    private val isConsumerSupported: (parameterUid: String) -> Boolean,
    private val localProjectRoot: Path? = null,
    private val localOverlayFile: Path? = null,
) {
    private var lastUpdateTimestamp = 0L
    private var hasUpdateTimestamp = false
    private var metadataPublished = false
    private var overlayDirty = false
    private val topics = Array(runtime.metadata.declarations.size) { ParameterTopics(runtime.metadata.declarations[it]) }
    private val lastRequestNonce = LongArray(runtime.metadata.declarations.size) { -1L }

    init {
        require((localProjectRoot == null) == (localOverlayFile == null)) {
            "Local overlay project root and file must be supplied together"
        }
        publishMetadataAndValues()
    }

    fun publishMetadataAndValues() {
        telemetry.putNumber(TuningTopics.SCHEMA_VERSION_TOPIC, TuningTopics.SCHEMA_VERSION.toDouble())
        telemetry.putString("${TuningTopics.ROOT}/ProjectId", runtime.metadata.projectId)
        telemetry.putString("${TuningTopics.ROOT}/DrivebaseUid", runtime.metadata.drivebaseUid.orEmpty())
        telemetry.putString("${TuningTopics.ROOT}/CanonicalProfileUid", runtime.metadata.canonicalProfileUid)
        topics.forEach { topic ->
            val declaration = topic.declaration
            val root = topic.root
            telemetry.putString("$root/Key", declaration.key)
            telemetry.putString("$root/ComponentUid", declaration.componentUid)
            telemetry.putString("$root/DisplayName", declaration.displayName)
            telemetry.putString("$root/Description", declaration.description)
            telemetry.putString("$root/Type", declaration.type.name)
            telemetry.putString("$root/Unit", declaration.unit.orEmpty())
            telemetry.putString("$root/ApplyPolicy", declaration.applyPolicy.name)
            telemetry.putBoolean("$root/ConsumerSupported", isConsumerSupported(declaration.uid))
            telemetry.putNumber("$root/Minimum", declaration.minimum ?: Double.NaN)
            telemetry.putNumber("$root/Maximum", declaration.maximum ?: Double.NaN)
            telemetry.putString("$root/EnumOptions", declaration.enumOptions.joinToString("\u001f"))
            publishValue("$root/Default", declaration.defaultValue)
            publishValue("$root/Canonical", requireNotNull(runtime.canonicalValue(declaration.uid)))
            val current = requireNotNull(runtime.value(declaration.uid))
            publishValue(topic.current, current)
            // Metadata refresh must not overwrite a dashboard proposal or erase its acknowledgement.
            if (!metadataPublished) {
                publishValue(topic.requested, current)
                telemetry.putNumber(topic.requestNonce, -1.0)
                telemetry.putNumber(topic.processedNonce, -1.0)
                telemetry.putString(topic.lastResult, "IDLE")
            }
        }
        metadataPublished = true
    }

    /**
     * Polls declared nonces at most every 500 ms. The first call may poll immediately; a clock
     * rewind rebases the interval without applying requests. Idle polls allocate no topic strings
     * and do not query apply context. Every proposal checks fresh arm/disable state separately.
     */
    fun update(timestampMs: Long = RobotClock.currentTimeMillis()) {
        if (hasUpdateTimestamp) {
            if (timestampMs < lastUpdateTimestamp) {
                lastUpdateTimestamp = timestampMs
                return
            }
            val elapsed = timestampMs - lastUpdateTimestamp
            // Ordered subtraction overflow represents a forward interval longer than the throttle.
            if (elapsed >= 0L && elapsed < 500L) return
        }
        lastUpdateTimestamp = timestampMs
        hasUpdateTimestamp = true
        for (index in topics.indices) {
            val topic = topics[index]
            val declaration = topic.declaration
            val nonceValue = telemetry.getNumber(topic.requestNonce, lastRequestNonce[index].toDouble())
            // NT4 carries numbers as doubles. Restrict nonces to the exactly representable integer
            // range so reconnect/replay ordering can never alias two distinct Long values.
            val nonce = nonceValue.takeIf {
                it.isFinite() && it % 1.0 == 0.0 && it in 0.0..MAX_SAFE_DOUBLE_INTEGER
            }?.toLong()
            if (nonce != null && nonce > lastRequestNonce[index]) {
                lastRequestNonce[index] = nonce
                val current = requireNotNull(runtime.value(declaration.uid))
                val candidate = readValue(topic.requested, declaration.type)
                val consumerSupported = isConsumerSupported(declaration.uid)
                var result = when {
                    !consumerSupported -> TuningUpdateResult.CONSUMER_REJECTED
                    candidate == null -> TuningUpdateResult.INVALID_VALUE
                    else -> runtime.apply(declaration.uid, candidate, contextProvider())
                }
                if (result == TuningUpdateResult.APPLIED) {
                    val accepted = try {
                        onApplied(declaration.uid, requireNotNull(candidate))
                    } catch (failure: Exception) {
                        runtime.restoreAfterFailedApply(declaration.uid, current)
                        try {
                            acknowledge(topic, TuningUpdateResult.APPLY_CALLBACK_FAILED, current, nonce)
                        } catch (diagnosticFailure: Exception) {
                            if (diagnosticFailure !== failure) failure.addSuppressed(diagnosticFailure)
                        }
                        throw failure
                    }
                    if (accepted) overlayDirty = true else {
                        runtime.restoreAfterFailedApply(declaration.uid, current)
                        result = TuningUpdateResult.CONSUMER_REJECTED
                    }
                }
                // A transport failure after a successful consumer commit must not roll back only
                // the tuning store and leave it inconsistent with the controller's actual value.
                acknowledge(topic, result, requireNotNull(runtime.value(declaration.uid)), nonce)
            }
        }
        if (overlayDirty) persistLocalOverlay()
    }

    private fun acknowledge(topic: ParameterTopics, result: TuningUpdateResult, current: TuningValue, nonce: Long) {
        telemetry.putString(topic.lastResult, result.name)
        publishValue(topic.current, current)
        // Publish last: matching ProcessedNonce commits Current and LastResult for the dashboard.
        telemetry.putNumber(topic.processedNonce, nonce.toDouble())
    }

    private fun persistLocalOverlay() {
        val projectRoot = localProjectRoot ?: return
        val output = localOverlayFile ?: return
        val overlay = runtime.localOverlay(
            uid = "local.${runtime.metadata.projectId}.runtime",
            profileId = "runtime-experiment",
            displayName = "Runtime experiment",
        )
        LocalTuningOverlayStore.writeAtomically(projectRoot, output, overlay)
        overlayDirty = false
    }

    private fun readValue(topic: String, type: TuningParameterType): TuningValue? = when (type) {
        TuningParameterType.DOUBLE -> TuningValue(doubleValue = telemetry.getNumber(topic, Double.NaN))
        TuningParameterType.INT -> {
            val value = telemetry.getNumber(topic, Double.NaN)
            if (!value.isFinite() || value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) null
            else TuningValue(intValue = value.toInt())
        }
        // Two different defaults distinguish missing/incompatible data from a legitimate false
        // or sentinel-valued string without extending the platform-neutral telemetry API.
        TuningParameterType.BOOLEAN -> when {
            telemetry.getBoolean(topic, false) -> TuningValue(booleanValue = true)
            !telemetry.getBoolean(topic, true) -> TuningValue(booleanValue = false)
            else -> null
        }
        TuningParameterType.TEXT, TuningParameterType.ENUM -> {
            val value = telemetry.getString(topic, ABSENT_TEXT)
            if (value != ABSENT_TEXT || telemetry.getString(topic, SECOND_ABSENT_TEXT) == ABSENT_TEXT) {
                TuningValue(textValue = value)
            } else null
        }
    }

    private fun publishValue(topic: String, value: TuningValue) {
        when {
            value.doubleValue != null -> telemetry.putNumber(topic, requireNotNull(value.doubleValue))
            value.intValue != null -> telemetry.putNumber(topic, requireNotNull(value.intValue).toDouble())
            value.booleanValue != null -> telemetry.putBoolean(topic, requireNotNull(value.booleanValue))
            value.textValue != null -> telemetry.putString(topic, requireNotNull(value.textValue))
        }
    }

    private class ParameterTopics(val declaration: TuningParameterDeclaration) {
        val root = "${TuningTopics.ROOT}/Parameters/${declaration.uid}"
        val current = "$root/Current"
        val requested = "$root/Requested"
        val requestNonce = "$root/RequestNonce"
        val processedNonce = "$root/ProcessedNonce"
        val lastResult = "$root/LastResult"
    }

    private companion object {
        const val MAX_SAFE_DOUBLE_INTEGER: Double = 9_007_199_254_740_991.0
        private const val ABSENT_TEXT: String = "\u0000"
        private const val SECOND_ABSENT_TEXT: String = "\u0001"
    }
}
