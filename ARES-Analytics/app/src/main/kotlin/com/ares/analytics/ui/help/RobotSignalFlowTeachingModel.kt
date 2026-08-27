package com.ares.analytics.ui.help

import kotlin.math.abs
import java.util.Locale

/** One simplified, hardware-free path through the ARES state architecture. */
enum class TeachingSignalPath(
    val label: String,
    val inputLabel: String,
    val inputUnit: String,
    val inputRange: ClosedFloatingPointRange<Double>,
    val stateField: String,
    val telemetryTopic: String,
) {
    MOTOR(
        label = "Motor command + encoder",
        inputLabel = "Gamepad axis",
        inputUnit = "ratio",
        inputRange = -1.0..1.0,
        stateField = "requestedDutyCycle",
        telemetryTopic = "Hardware/Motors/teaching/Velocity",
    ),
    POSITIONAL_SERVO(
        label = "Positional servo",
        inputLabel = "Gamepad axis",
        inputUnit = "ratio",
        inputRange = -1.0..1.0,
        stateField = "requestedPosition",
        telemetryTopic = "Hardware/Servos/teaching/Position",
    ),
    DISTANCE_SENSOR(
        label = "Distance sensor",
        inputLabel = "Cached distance",
        inputUnit = "cm",
        inputRange = 0.0..200.0,
        stateField = "cachedDistanceCm",
        telemetryTopic = "Hardware/Sensors/teaching/DistanceCm",
    ),
}

/** Immutable teaching snapshot retained before and after one modeled loop. */
data class RobotSignalTeachingSnapshot(
    val eventSequence: Long = 0L,
    val requestedValue: Double = 0.0,
    val cachedMeasurement: Double = 0.0,
    val cachedMeasurementValid: Boolean = true,
    val cachedMeasurementAgeMs: Long = 20L,
    val outputFaultLatched: Boolean = false,
)

data class RobotSignalTeachingInput(
    val path: TeachingSignalPath = TeachingSignalPath.MOTOR,
    val rawInput: Double = 0.5,
    val inverted: Boolean = false,
    val deadband: Double = 0.05,
    val cachedMeasurement: Double = 1.5,
    val measurementValid: Boolean = true,
    val measurementAgeMs: Long = 20L,
    val feedbackTimeoutMs: Long = 100L,
    val configurationHealthy: Boolean = true,
    val outputWriteSucceeds: Boolean = true,
)

data class TeachingTelemetrySample(
    val topic: String,
    val value: Double?,
    val unit: String,
    val valid: Boolean,
    val freshnessText: String,
)

data class RobotSignalTeachingResult(
    val previousState: RobotSignalTeachingSnapshot,
    val actionDescriptions: List<String>,
    val reducerState: RobotSignalTeachingSnapshot,
    val controllerOutput: Double?,
    val controllerDecision: String,
    val ioResult: String,
    val finalState: RobotSignalTeachingSnapshot,
    val telemetry: TeachingTelemetrySample,
)

/**
 * Models one loop without using the production Redux store, NT4, files, simulator, or hardware.
 * The returned snapshots never share mutable state.
 */
fun runRobotSignalTeachingLoop(
    previous: RobotSignalTeachingSnapshot,
    input: RobotSignalTeachingInput,
): RobotSignalTeachingResult {
    require(input.rawInput.isFinite()) { "Teaching input must be finite" }
    require(input.deadband.isFinite() && input.deadband in 0.0..0.95) { "Deadband must be between 0 and 0.95" }
    require(input.cachedMeasurement.isFinite()) { "Cached teaching measurement must be finite" }
    require(input.measurementAgeMs >= 0L) { "Measurement age must not be negative" }
    require(input.feedbackTimeoutMs > 0L) { "Feedback timeout must be positive" }

    val sequence = previous.eventSequence + 1L
    val feedbackFresh = input.measurementAgeMs <= input.feedbackTimeoutMs
    val transformedInput = when (input.path) {
        TeachingSignalPath.MOTOR -> shapeAxis(input.rawInput, input.deadband, input.inverted)
        TeachingSignalPath.POSITIONAL_SERVO -> {
            val axis = shapeAxis(input.rawInput, input.deadband, input.inverted)
            ((axis + 1.0) / 2.0).coerceIn(0.0, 1.0)
        }
        TeachingSignalPath.DISTANCE_SENSOR -> input.rawInput.coerceIn(input.path.inputRange)
    }

    val actions = when (input.path) {
        TeachingSignalPath.MOTOR -> listOf(
            "CachedVelocityReceived(${format(input.cachedMeasurement)} rot/s, valid=${input.measurementValid})",
            "SetRequestedDutyCycle(${format(transformedInput)}, sequence=$sequence)",
        )
        TeachingSignalPath.POSITIONAL_SERVO -> listOf(
            "SetRequestedPosition(${format(transformedInput)}, sequence=$sequence)",
        )
        TeachingSignalPath.DISTANCE_SENSOR -> listOf(
            "CachedDistanceReceived(${format(transformedInput)} cm, valid=${input.measurementValid})",
        )
    }

    val reduced = when (input.path) {
        TeachingSignalPath.MOTOR -> previous.copy(
            eventSequence = sequence,
            requestedValue = transformedInput,
            cachedMeasurement = input.cachedMeasurement,
            cachedMeasurementValid = input.measurementValid,
            cachedMeasurementAgeMs = input.measurementAgeMs,
        )
        TeachingSignalPath.POSITIONAL_SERVO -> previous.copy(
            eventSequence = sequence,
            requestedValue = transformedInput,
        )
        TeachingSignalPath.DISTANCE_SENSOR -> previous.copy(
            eventSequence = sequence,
            cachedMeasurement = transformedInput,
            cachedMeasurementValid = input.measurementValid,
            cachedMeasurementAgeMs = input.measurementAgeMs,
        )
    }

    val feedbackUsable = input.measurementValid && feedbackFresh
    val controllerPermitted = input.configurationHealthy && !previous.outputFaultLatched && when (input.path) {
        TeachingSignalPath.MOTOR -> feedbackUsable
        TeachingSignalPath.POSITIONAL_SERVO -> true
        TeachingSignalPath.DISTANCE_SENSOR -> false
    }
    val controllerOutput = when {
        input.path == TeachingSignalPath.DISTANCE_SENSOR -> null
        controllerPermitted -> reduced.requestedValue
        else -> 0.0
    }
    val controllerDecision = when {
        input.path == TeachingSignalPath.DISTANCE_SENSOR -> "Sensor-only path: observe cached state; do not command an output."
        !input.configurationHealthy -> "Neutral requested because configuration health is not confirmed."
        previous.outputFaultLatched -> "Neutral requested because an output fault is already latched."
        input.path == TeachingSignalPath.MOTOR && !feedbackUsable ->
            "Neutral requested because cached encoder feedback is invalid or stale."
        else -> "Requested state is permitted for this simplified mock adapter."
    }

    val attemptedActuatorWrite = input.path != TeachingSignalPath.DISTANCE_SENSOR
    val writeSucceeded = !attemptedActuatorWrite || input.outputWriteSucceeds
    val finalState = if (attemptedActuatorWrite && !writeSucceeded) {
        reduced.copy(outputFaultLatched = true)
    } else {
        reduced
    }
    val ioResult = when {
        !attemptedActuatorWrite -> "No actuator write: the sensor adapter only refreshed its cached input."
        writeSucceeded -> "Mock IO accepted ${format(requireNotNull(controllerOutput))}."
        else -> "Mock IO rejected the write; Redux receives an output-fault action and latches the fault."
    }

    val telemetry = when (input.path) {
        TeachingSignalPath.MOTOR -> TeachingTelemetrySample(
            topic = input.path.telemetryTopic,
            value = input.cachedMeasurement.takeIf { feedbackUsable },
            unit = "rot/s",
            valid = feedbackUsable,
            freshnessText = freshnessText(input.measurementAgeMs, input.feedbackTimeoutMs),
        )
        TeachingSignalPath.POSITIONAL_SERVO -> TeachingTelemetrySample(
            topic = input.path.telemetryTopic,
            value = controllerOutput.takeIf { writeSucceeded },
            unit = "normalized position",
            valid = writeSucceeded,
            freshnessText = if (writeSucceeded) "written in this modeled loop" else "no confirmed write",
        )
        TeachingSignalPath.DISTANCE_SENSOR -> TeachingTelemetrySample(
            topic = input.path.telemetryTopic,
            value = transformedInput.takeIf { feedbackUsable },
            unit = "cm",
            valid = feedbackUsable,
            freshnessText = freshnessText(input.measurementAgeMs, input.feedbackTimeoutMs),
        )
    }

    return RobotSignalTeachingResult(
        previousState = previous,
        actionDescriptions = actions,
        reducerState = reduced,
        controllerOutput = controllerOutput,
        controllerDecision = controllerDecision,
        ioResult = ioResult,
        finalState = finalState,
        telemetry = telemetry,
    )
}

private fun shapeAxis(raw: Double, deadband: Double, inverted: Boolean): Double {
    val clamped = raw.coerceIn(-1.0, 1.0)
    val shaped = if (abs(clamped) <= deadband) 0.0 else {
        val magnitude = (abs(clamped) - deadband) / (1.0 - deadband)
        if (clamped < 0.0) -magnitude else magnitude
    }
    return if (inverted) -shaped else shaped
}

private fun freshnessText(ageMs: Long, timeoutMs: Long): String =
    if (ageMs <= timeoutMs) "fresh: $ageMs ms old (limit $timeoutMs ms)"
    else "stale: $ageMs ms old (limit $timeoutMs ms)"

private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
