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
    /**
     * Declares whether the running robot has a compiled consumer for this parameter.
     *
     * The default preserves compatibility with existing robots. Generated/runtime composition
     * roots should pass their real capability predicate so authoring tools can hide impossible
     * live experiments before a student sends a request.
     */
    private val isConsumerSupported: (parameterUid: String) -> Boolean = { true },
    private val localProjectRoot: Path? = null,
    private val localOverlayFile: Path? = null,
) {
    private var lastUpdateTimestamp = 0L
    private var overlayDirty = false
    private val lastRequestNonce = LongArray(runtime.metadata.declarations.size) { -1L }

    init {
        require((localProjectRoot == null) == (localOverlayFile == null)) {
            "Local overlay project root and file must be supplied together"
        }
        publishMetadataAndValues()
    }

    fun publishMetadataAndValues() {
        telemetry.putNumber(TuningTopics.SCHEMA_VERSION_TOPIC, TuningTopics.SCHEMA_VERSION.toDouble())
        telemetry.putString("${TuningTopics.ROOT}/ProjectUid", runtime.metadata.projectUid)
        telemetry.putString("${TuningTopics.ROOT}/DrivebaseUid", runtime.metadata.drivebaseUid.orEmpty())
        telemetry.putString("${TuningTopics.ROOT}/CanonicalProfileUid", runtime.metadata.canonicalProfileUid)
        runtime.metadata.declarations.forEach { declaration ->
            val root = parameterRoot(declaration.uid)
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
            publishValue("$root/Current", current)
            publishValue("$root/Requested", current)
            telemetry.putNumber("$root/RequestNonce", -1.0)
            telemetry.putNumber("$root/ProcessedNonce", -1.0)
            telemetry.putString("$root/LastResult", "IDLE")
        }
    }

    /** Polls declared values only. Unknown topics are never enumerated or dispatched. */
    fun update(timestampMs: Long = RobotClock.currentTimeMillis()) {
        if (timestampMs - lastUpdateTimestamp < 500L) return
        lastUpdateTimestamp = timestampMs
        val context = contextProvider()
        runtime.metadata.declarations.forEachIndexed { index, declaration ->
            val current = requireNotNull(runtime.value(declaration.uid))
            val root = parameterRoot(declaration.uid)
            val nonceValue = telemetry.getNumber("$root/RequestNonce", lastRequestNonce[index].toDouble())
            // NT4 carries numbers as doubles. Restrict nonces to the exactly representable integer
            // range so reconnect/replay ordering can never alias two distinct Long values.
            val nonce = nonceValue.takeIf {
                it.isFinite() && it % 1.0 == 0.0 && it in 0.0..MAX_SAFE_DOUBLE_INTEGER
            }?.toLong()
            if (nonce != null && nonce > lastRequestNonce[index]) {
                lastRequestNonce[index] = nonce
                val candidate = readValue("$root/Requested", declaration.type, current)
                val consumerSupported = isConsumerSupported(declaration.uid)
                val result = if (consumerSupported) {
                    runtime.apply(declaration.uid, candidate, context)
                } else {
                    TuningUpdateResult.CONSUMER_REJECTED
                }
                if (result == TuningUpdateResult.APPLIED) {
                    try {
                        if (onApplied(declaration.uid, candidate)) {
                            overlayDirty = true
                            telemetry.putString("$root/LastResult", result.name)
                        } else {
                            runtime.restoreAfterFailedApply(declaration.uid, current)
                            telemetry.putString("$root/LastResult", TuningUpdateResult.CONSUMER_REJECTED.name)
                        }
                    } catch (failure: Exception) {
                        runtime.restoreAfterFailedApply(declaration.uid, current)
                        telemetry.putString("$root/LastResult", TuningUpdateResult.APPLY_CALLBACK_FAILED.name)
                        publishValue("$root/Current", current)
                        telemetry.putNumber("$root/ProcessedNonce", nonce.toDouble())
                        throw failure
                    }
                } else {
                    telemetry.putString("$root/LastResult", result.name)
                }
                publishValue("$root/Current", requireNotNull(runtime.value(declaration.uid)))
                // Publish this last: dashboards treat the matching processed nonce as the atomic
                // acknowledgement that Current and LastResult belong to their request.
                telemetry.putNumber("$root/ProcessedNonce", nonce.toDouble())
            }
        }
        if (overlayDirty) persistLocalOverlay()
    }

    private fun persistLocalOverlay() {
        val projectRoot = localProjectRoot ?: return
        val output = localOverlayFile ?: return
        val overlay = runtime.localOverlay(
            uid = "local.${runtime.metadata.projectUid}.runtime",
            profileId = "runtime-experiment",
            displayName = "Runtime experiment",
        )
        LocalTuningOverlayStore.writeAtomically(projectRoot, output, overlay)
        overlayDirty = false
    }

    private fun readValue(topic: String, type: TuningParameterType, current: TuningValue): TuningValue = when (type) {
        TuningParameterType.DOUBLE -> TuningValue(doubleValue = telemetry.getNumber(topic, requireNotNull(current.doubleValue)))
        TuningParameterType.INT -> {
            val currentValue = requireNotNull(current.intValue)
            val value = telemetry.getNumber(topic, currentValue.toDouble())
            if (!value.isFinite() || value % 1.0 != 0.0 || value !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) current
            else TuningValue(intValue = value.toInt())
        }
        TuningParameterType.BOOLEAN -> TuningValue(booleanValue = telemetry.getBoolean(topic, requireNotNull(current.booleanValue)))
        TuningParameterType.TEXT, TuningParameterType.ENUM -> TuningValue(textValue = telemetry.getString(topic, requireNotNull(current.textValue)))
    }

    private fun publishValue(topic: String, value: TuningValue) {
        when {
            value.doubleValue != null -> telemetry.putNumber(topic, requireNotNull(value.doubleValue))
            value.intValue != null -> telemetry.putNumber(topic, requireNotNull(value.intValue).toDouble())
            value.booleanValue != null -> telemetry.putBoolean(topic, requireNotNull(value.booleanValue))
            value.textValue != null -> telemetry.putString(topic, requireNotNull(value.textValue))
        }
    }

    private fun parameterRoot(uid: String): String = "${TuningTopics.ROOT}/Parameters/$uid"

    private companion object {
        const val MAX_SAFE_DOUBLE_INTEGER: Double = 9_007_199_254_740_991.0
    }
}
