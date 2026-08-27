package com.ares.analytics.ui.help

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Failure behavior students may compare in the hardware-free autonomous planning lab. */
enum class TeachingAutoFailurePolicy(val label: String) {
    STOP_AND_REPORT("Stop the routine and report the failure"),
    CONTINUE_OPTIONAL("Continue only when the failed action is optional"),
}

/** Simplified condition state for the optional mechanism action in the teaching routine. */
enum class TeachingAutoCondition(val label: String) {
    READY("Condition is true"),
    NOT_READY("Condition is false; skip the action"),
    MISSING("Condition source is missing"),
}

/**
 * Immutable input for a two-step autonomous teaching plan.
 *
 * The model never writes a routine, starts a simulator, publishes NT4, or commands hardware. It
 * exists only to teach the validation questions that precede work in the real routine builder.
 */
data class AutonomousSafetyTeachingInput(
    val fieldLengthMeters: Double = 4.0,
    val fieldWidthMeters: Double = 4.0,
    val robotLengthMeters: Double = 0.46,
    val robotWidthMeters: Double = 0.46,
    val startXMeters: Double = 1.0,
    val startYMeters: Double = 1.0,
    val startHeadingDegrees: Double = 0.0,
    val targetXMeters: Double = 2.5,
    val targetYMeters: Double = 1.0,
    val targetHeadingDegrees: Double = 0.0,
    val maxSpeedMetersPerSecond: Double = 1.0,
    val timeoutSeconds: Double = 3.0,
    val mechanismActionAvailable: Boolean = true,
    val mechanismActionOptional: Boolean = false,
    val mechanismClaimsDrivebase: Boolean = false,
    val condition: TeachingAutoCondition = TeachingAutoCondition.READY,
    val failurePolicy: TeachingAutoFailurePolicy = TeachingAutoFailurePolicy.STOP_AND_REPORT,
)

data class AutonomousSafetyTeachingResult(
    val startPoseInBounds: Boolean,
    val targetPoseInBounds: Boolean,
    val resourcesCompatible: Boolean,
    val conditionUsable: Boolean,
    val failureBehaviorValid: Boolean,
    val estimatedDriveSeconds: Double?,
    val timeoutHasMargin: Boolean,
    val previewReady: Boolean,
    val reasons: List<String>,
    val planSummary: List<String>,
)

/** Pure, fail-closed evaluator shared by the Academy card and focused tests. */
fun evaluateAutonomousSafetyTeaching(input: AutonomousSafetyTeachingInput): AutonomousSafetyTeachingResult {
    val fieldValid = input.fieldLengthMeters.isFinite() && input.fieldLengthMeters > 0.0 &&
        input.fieldWidthMeters.isFinite() && input.fieldWidthMeters > 0.0
    val robotValid = input.robotLengthMeters.isFinite() && input.robotLengthMeters > 0.0 &&
        input.robotWidthMeters.isFinite() && input.robotWidthMeters > 0.0
    val startInBounds = fieldValid && robotValid && poseFits(
        input.startXMeters,
        input.startYMeters,
        input.robotLengthMeters,
        input.robotWidthMeters,
        input.startHeadingDegrees,
        input.fieldLengthMeters,
        input.fieldWidthMeters,
    )
    val targetInBounds = fieldValid && robotValid && poseFits(
        input.targetXMeters,
        input.targetYMeters,
        input.robotLengthMeters,
        input.robotWidthMeters,
        input.targetHeadingDegrees,
        input.fieldLengthMeters,
        input.fieldWidthMeters,
    )
    val speedValid = input.maxSpeedMetersPerSecond.isFinite() && input.maxSpeedMetersPerSecond > 0.0
    val distance = if (
        input.startXMeters.isFinite() && input.startYMeters.isFinite() &&
        input.targetXMeters.isFinite() && input.targetYMeters.isFinite()
    ) {
        hypot(input.targetXMeters - input.startXMeters, input.targetYMeters - input.startYMeters)
    } else {
        Double.NaN
    }
    val estimatedSeconds = if (speedValid && distance.isFinite()) distance / input.maxSpeedMetersPerSecond else null
    val timeoutValid = input.timeoutSeconds.isFinite() && input.timeoutSeconds > 0.0
    // The margin is deliberately visible: an ideal distance/speed estimate omits acceleration and settling.
    val timeoutHasMargin = timeoutValid && estimatedSeconds != null && input.timeoutSeconds >= estimatedSeconds * 1.25
    val resourcesCompatible = !input.mechanismClaimsDrivebase
    val conditionUsable = input.condition != TeachingAutoCondition.MISSING
    val failureBehaviorValid = when (input.failurePolicy) {
        TeachingAutoFailurePolicy.STOP_AND_REPORT -> true
        TeachingAutoFailurePolicy.CONTINUE_OPTIONAL -> input.mechanismActionOptional
    }

    val reasons = buildList {
        if (!fieldValid) add("Field dimensions must be finite and greater than zero.")
        if (!robotValid) add("Robot dimensions must be finite and greater than zero.")
        if (!startInBounds) add("The full robot footprint does not fit at the starting pose.")
        if (!targetInBounds) add("The full robot footprint does not fit at the target pose.")
        if (!speedValid) add("Maximum speed must be finite and greater than zero meters per second.")
        if (!timeoutHasMargin) add("The timeout needs at least 25% margin beyond the ideal drive-time estimate.")
        if (!input.mechanismActionAvailable) add("The named mechanism action is not in the project's action catalog.")
        if (!resourcesCompatible) add("Both parallel branches claim the drivebase resource.")
        if (!conditionUsable) add("The action condition has no available source.")
        if (!failureBehaviorValid) add("Continue-on-failure is allowed only for an action explicitly marked optional.")
    }
    val previewReady = reasons.isEmpty()
    val conditionSummary = when (input.condition) {
        TeachingAutoCondition.READY -> "condition true; action is eligible to run"
        TeachingAutoCondition.NOT_READY -> "condition false; action is skipped"
        TeachingAutoCondition.MISSING -> "condition source missing; validation fails"
    }
    return AutonomousSafetyTeachingResult(
        startPoseInBounds = startInBounds,
        targetPoseInBounds = targetInBounds,
        resourcesCompatible = resourcesCompatible,
        conditionUsable = conditionUsable,
        failureBehaviorValid = failureBehaviorValid,
        estimatedDriveSeconds = estimatedSeconds,
        timeoutHasMargin = timeoutHasMargin,
        previewReady = previewReady,
        reasons = reasons,
        planSummary = listOf(
            "Start at (${format(input.startXMeters)}, ${format(input.startYMeters)}) m and ${format(input.startHeadingDegrees)}° with the rotated robot footprint inside the field.",
            "Drive ${format(distance)} m to (${format(input.targetXMeters)}, ${format(input.targetYMeters)}) m and ${format(input.targetHeadingDegrees)}° at no more than ${format(input.maxSpeedMetersPerSecond)} m/s; timeout ${format(input.timeoutSeconds)} s.",
            "Run the named mechanism action in parallel: $conditionSummary.",
            "On failure: ${input.failurePolicy.label.lowercase()}.",
        ),
    )
}

private fun poseFits(
    x: Double,
    y: Double,
    robotLength: Double,
    robotWidth: Double,
    headingDegrees: Double,
    fieldLength: Double,
    fieldWidth: Double,
): Boolean {
    if (!x.isFinite() || !y.isFinite() || !headingDegrees.isFinite()) return false
    val headingRadians = Math.toRadians(headingDegrees)
    val halfExtentX = abs(cos(headingRadians)) * robotLength / 2.0 +
        abs(sin(headingRadians)) * robotWidth / 2.0
    val halfExtentY = abs(sin(headingRadians)) * robotLength / 2.0 +
        abs(cos(headingRadians)) * robotWidth / 2.0
    return x - halfExtentX >= 0.0 && x + halfExtentX <= fieldLength &&
        y - halfExtentY >= 0.0 && y + halfExtentY <= fieldWidth
}

private fun format(value: Double): String = if (value.isFinite()) String.format(Locale.ROOT, "%.2f", value) else "invalid"
