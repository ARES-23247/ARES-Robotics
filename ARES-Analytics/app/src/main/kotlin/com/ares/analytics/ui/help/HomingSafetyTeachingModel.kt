package com.ares.analytics.ui.help

/** Evidence source used by the hardware-free homing safety teaching model. */
enum class TeachingHomingMethod(val label: String) {
    SENSOR("Digital sensor"),
    CURRENT_STALL("Current stall"),
    VELOCITY_STALL("Velocity stall"),
    COMBINED_STALL("Current + velocity stall"),
}

/**
 * One immutable snapshot for the Academy homing safety lab.
 *
 * This is intentionally not a production controller or subsystem state. It lets students reason
 * about cached measurements, freshness, bounded homing evidence, fault latching, and neutral
 * recovery without publishing commands or changing project files.
 */
data class HomingSafetyTeachingInput(
    val method: TeachingHomingMethod = TeachingHomingMethod.SENSOR,
    val configurationHealthy: Boolean = true,
    val feedbackAgeMs: Long = 20L,
    val feedbackTimeoutMs: Long = 100L,
    val sensorActive: Boolean = false,
    val currentValid: Boolean = true,
    val currentAmps: Double = 1.0,
    val currentThresholdAmps: Double = 4.0,
    val velocityValid: Boolean = true,
    val velocityRps: Double = 1.0,
    val velocityThresholdRps: Double = 0.20,
    val evidenceDwellMs: Long = 0L,
    val requiredDwellMs: Long = 200L,
    val homed: Boolean = false,
    val faultLatched: Boolean = false,
)

data class HomingSafetyTeachingResult(
    val feedbackFresh: Boolean,
    val requiredMeasurementsValid: Boolean,
    val homingEvidencePresent: Boolean,
    val homingEvidenceConfirmed: Boolean,
    val motionPermitted: Boolean,
    val neutralRequired: Boolean,
    val reasons: List<String>,
)

/** Pure fail-closed evaluation used by both the Academy card and focused tests. */
fun evaluateHomingSafetyTeaching(input: HomingSafetyTeachingInput): HomingSafetyTeachingResult {
    val currentThresholdValid = input.currentThresholdAmps.isFinite() && input.currentThresholdAmps >= 0.0
    val velocityThresholdValid = input.velocityThresholdRps.isFinite() && input.velocityThresholdRps >= 0.0
    val feedbackFresh = input.feedbackAgeMs >= 0L && input.feedbackTimeoutMs > 0L &&
        input.feedbackAgeMs <= input.feedbackTimeoutMs
    val currentUsable = input.currentValid && input.currentAmps.isFinite() && input.currentAmps >= 0.0
    val velocityUsable = input.velocityValid && input.velocityRps.isFinite()
    val requiredMeasurementsValid = when (input.method) {
        TeachingHomingMethod.SENSOR -> true
        TeachingHomingMethod.CURRENT_STALL -> currentThresholdValid && currentUsable
        TeachingHomingMethod.VELOCITY_STALL -> velocityThresholdValid && velocityUsable
        TeachingHomingMethod.COMBINED_STALL ->
            currentThresholdValid && currentUsable && velocityThresholdValid && velocityUsable
    }
    val evidencePresent = requiredMeasurementsValid && when (input.method) {
        TeachingHomingMethod.SENSOR -> input.sensorActive
        TeachingHomingMethod.CURRENT_STALL -> input.currentAmps >= input.currentThresholdAmps
        TeachingHomingMethod.VELOCITY_STALL -> kotlin.math.abs(input.velocityRps) <= input.velocityThresholdRps
        TeachingHomingMethod.COMBINED_STALL ->
            input.currentAmps >= input.currentThresholdAmps &&
                kotlin.math.abs(input.velocityRps) <= input.velocityThresholdRps
    }
    val evidenceConfirmed = input.requiredDwellMs > 0L && input.evidenceDwellMs >= input.requiredDwellMs &&
        feedbackFresh && evidencePresent

    val reasons = buildList {
        if (!input.configurationHealthy) add("Configuration health is not confirmed.")
        if (!feedbackFresh) add("The cached feedback is stale or has an invalid timestamp.")
        if (!requiredMeasurementsValid) add("A measurement required by this homing method is invalid.")
        if (!input.homed) add("The mechanism has not established its home reference.")
        if (input.faultLatched) add("An output fault is latched until a successful neutral write is confirmed.")
    }
    val motionPermitted = reasons.isEmpty()
    return HomingSafetyTeachingResult(
        feedbackFresh = feedbackFresh,
        requiredMeasurementsValid = requiredMeasurementsValid,
        homingEvidencePresent = evidencePresent,
        homingEvidenceConfirmed = evidenceConfirmed,
        motionPermitted = motionPermitted,
        neutralRequired = !motionPermitted,
        reasons = reasons,
    )
}

/** Advances only the modeled evidence dwell. Broken or stale evidence resets the dwell. */
fun advanceHomingEvidence(
    input: HomingSafetyTeachingInput,
    elapsedMs: Long,
): HomingSafetyTeachingInput {
    require(elapsedMs >= 0L) { "Elapsed teaching time must not be negative" }
    val present = evaluateHomingSafetyTeaching(input).let {
        it.feedbackFresh && it.requiredMeasurementsValid && it.homingEvidencePresent
    }
    val nextDwell = if (present) (input.evidenceDwellMs + elapsedMs).coerceAtMost(60_000L) else 0L
    val candidate = input.copy(evidenceDwellMs = nextDwell)
    return if (evaluateHomingSafetyTeaching(candidate).homingEvidenceConfirmed) {
        candidate.copy(homed = true)
    } else {
        candidate
    }
}

/** A fault clears only after the modeled neutral write succeeds. Homing is deliberately retained. */
fun attemptTeachingNeutralRecovery(
    input: HomingSafetyTeachingInput,
    neutralWriteSucceeded: Boolean,
): HomingSafetyTeachingInput = if (input.faultLatched && neutralWriteSucceeded) {
    input.copy(faultLatched = false)
} else {
    input
}
