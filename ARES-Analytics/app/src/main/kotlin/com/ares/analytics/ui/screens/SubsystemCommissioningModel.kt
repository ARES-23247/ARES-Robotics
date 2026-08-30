package com.ares.analytics.service.commissioning

import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemUnits
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sqrt

/** Hardware-free mechanism models used only for authoring education and review. */
internal enum class SubsystemCommissioningPlant(val displayName: String) {
    ROTARY_ARM("Pivoting arm"),
    ELEVATOR("Vertical elevator"),
    FLYWHEEL("Velocity flywheel"),
    POSITIONAL_SERVO("Positional servo"),
}

/** Explicit fault and boundary cases a student can inject into the commissioning preview. */
internal enum class SubsystemCommissioningScenario(val displayName: String) {
    NOMINAL("Normal step"),
    LOAD_DISTURBANCE("Add a load"),
    STALE_FEEDBACK("Freeze feedback"),
    FROZEN_HEARTBEAT("Frozen sensor heartbeat"),
    INVALID_FEEDBACK("Invalidate feedback"),
    FAILED_WRITE_RECOVERY("Failed write + neutral recovery"),
    BROWNOUT_RECOVERY("Brownout + voltage recovery"),
    EXCESS_CURRENT_RECOVERY("Excess current + neutral recovery"),
    UNCONFIGURED("Device not configured"),
    UNHOMED("Mechanism not homed"),
    ANGLE_BOUNDARY("Cross ±π boundary"),
}

internal data class SubsystemCommissioningSample(
    val timeSeconds: Double,
    val reference: Double,
    val measurement: Double,
    val command: Double,
    val feedbackUsable: Boolean,
    val safetyPermit: Boolean,
    val faultActive: Boolean,
    val faultLatched: Boolean,
)

internal data class SubsystemCommissioningMetrics(
    val bounded: Boolean,
    val finalError: Double?,
    val peakAbsoluteCommand: Double,
    val saturationPercent: Double,
    val neutralizedOnFault: Boolean?,
    val faultLatched: Boolean?,
    val neutralRecoverySucceeded: Boolean?,
    val enteredTolerance: Boolean,
    val statusMessage: String,
)

internal data class SubsystemCommissioningResult(
    val samples: List<SubsystemCommissioningSample>,
    val metrics: SubsystemCommissioningMetrics,
    val referenceLabel: String,
    val measurementLabel: String,
)

internal fun defaultCommissioningPlant(
    loop: SubsystemControlLoopDocument,
    targetUnit: String?,
): SubsystemCommissioningPlant = when {
    loop.strategy == SubsystemControlStrategy.SERVO_POSITION -> SubsystemCommissioningPlant.POSITIONAL_SERVO
    loop.strategy == SubsystemControlStrategy.VELOCITY_PID || SubsystemUnits.canRepresentVelocity(targetUnit) ->
        SubsystemCommissioningPlant.FLYWHEEL
    loop.feedforward.kind == SubsystemFeedforwardKind.ELEVATOR -> SubsystemCommissioningPlant.ELEVATOR
    loop.feedforward.kind in setOf(
        SubsystemFeedforwardKind.ARM,
        SubsystemFeedforwardKind.TWO_DOF_ARM,
        SubsystemFeedforwardKind.FOUR_BAR_LINKAGE,
    ) -> SubsystemCommissioningPlant.ROTARY_ARM
    SubsystemUnits.isCanonicalAngle(targetUnit) -> SubsystemCommissioningPlant.ROTARY_ARM
    else -> SubsystemCommissioningPlant.ELEVATOR
}

internal fun commissioningScenariosFor(loop: SubsystemControlLoopDocument): List<SubsystemCommissioningScenario> = buildList {
    add(SubsystemCommissioningScenario.NOMINAL)
    if (loop.strategy != SubsystemControlStrategy.SERVO_POSITION) add(SubsystemCommissioningScenario.LOAD_DISTURBANCE)
    if (loop.strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID,
            SubsystemControlStrategy.BANG_BANG,
        )
    ) {
        add(SubsystemCommissioningScenario.STALE_FEEDBACK)
        add(SubsystemCommissioningScenario.FROZEN_HEARTBEAT)
        add(SubsystemCommissioningScenario.INVALID_FEEDBACK)
    }
    add(SubsystemCommissioningScenario.FAILED_WRITE_RECOVERY)
    add(SubsystemCommissioningScenario.BROWNOUT_RECOVERY)
    add(SubsystemCommissioningScenario.EXCESS_CURRENT_RECOVERY)
    add(SubsystemCommissioningScenario.UNCONFIGURED)
    add(SubsystemCommissioningScenario.UNHOMED)
    if (loop.continuousInput.enabled && loop.strategy in setOf(
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
        )
    ) add(SubsystemCommissioningScenario.ANGLE_BOUNDARY)
}

/**
 * Executes the selected generated-controller semantics against a small deterministic teaching plant.
 *
 * This is deliberately not a digital twin and cannot establish robot-safe gains. It does, however,
 * preserve the important authoring contracts: output bounds, profiled targets, derivative filtering,
 * integral anti-windup, continuous-angle error, hysteretic on/off state, and fail-closed feedback.
 */
internal fun simulateSubsystemCommissioning(
    loop: SubsystemControlLoopDocument,
    plant: SubsystemCommissioningPlant,
    scenario: SubsystemCommissioningScenario,
    durationSeconds: Double = 3.0,
    dtSeconds: Double = 0.02,
): SubsystemCommissioningResult {
    require(durationSeconds.isFinite() && durationSeconds in 0.2..10.0) {
        "Commissioning duration must be between 0.2 and 10 seconds"
    }
    require(dtSeconds.isFinite() && dtSeconds in 0.005..0.05) {
        "Commissioning step must be between 5 and 50 milliseconds"
    }
    require(loop.minimumOutput.isFinite() && loop.maximumOutput.isFinite() && loop.minimumOutput < loop.maximumOutput) {
        "Commissioning output bounds must be finite and ordered"
    }

    val feedbackRequired = loop.strategy in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
        SubsystemControlStrategy.BANG_BANG,
    )
    val effectiveScenario = when {
        scenario == SubsystemCommissioningScenario.ANGLE_BOUNDARY &&
            (!loop.continuousInput.enabled || loop.strategy !in setOf(
                SubsystemControlStrategy.POSITION_PID,
                SubsystemControlStrategy.PROFILED_POSITION_PID,
            )) -> SubsystemCommissioningScenario.NOMINAL
        scenario in setOf(
            SubsystemCommissioningScenario.STALE_FEEDBACK,
            SubsystemCommissioningScenario.FROZEN_HEARTBEAT,
            SubsystemCommissioningScenario.INVALID_FEEDBACK,
        ) && !feedbackRequired -> SubsystemCommissioningScenario.NOMINAL
        else -> scenario
    }

    val totalSteps = (durationSeconds / dtSeconds).toInt()
    require(totalSteps in 4..2_000) { "Commissioning sample count must be between 4 and 2,000" }
    val samples = ArrayList<SubsystemCommissioningSample>(totalSteps + 1)

    val angleBoundary = effectiveScenario == SubsystemCommissioningScenario.ANGLE_BOUNDARY
    var position = if (angleBoundary) Math.toRadians(179.0) else 0.0
    var velocity = 0.0
    val requestedReference = when (loop.strategy) {
        SubsystemControlStrategy.DIRECT -> loop.maximumOutput.coerceAtMost(6.0)
        SubsystemControlStrategy.SERVO_POSITION -> 0.8
        SubsystemControlStrategy.VELOCITY_PID -> 1.0
        else -> if (angleBoundary) Math.toRadians(-179.0) else 1.0
    }

    var observedMeasurement = plantMeasurement(plant, position, velocity)
    var integral = 0.0
    var derivative = 0.0
    var previousError = 0.0
    var hasPreviousError = false
    var profilePosition = observedMeasurement
    var profileVelocity = 0.0
    var profileInitialized = false
    var bangBangOutput = 0.0
    var saturatedSamples = 0
    var peakCommand = 0.0
    var bounded = true
    var neutralOnFault: Boolean? = null
    var faultLatchObserved = false
    var neutralRecoverySucceeded: Boolean? = null
    var outputFaultLatched = false
    var enteredTolerance = false
    val faultAtSeconds = durationSeconds * 0.45
    val recoveryAtSeconds = durationSeconds * 0.70
    val loadAtSeconds = durationSeconds * 0.45
    val period = loop.continuousInput.maximumInput - loop.continuousInput.minimumInput

    for (step in 0..totalSteps) {
        val now = step * dtSeconds
        val feedbackUsable = when (effectiveScenario) {
            SubsystemCommissioningScenario.STALE_FEEDBACK,
            SubsystemCommissioningScenario.FROZEN_HEARTBEAT,
            SubsystemCommissioningScenario.INVALID_FEEDBACK -> now < faultAtSeconds
            else -> true
        }
        val controllerMeasurement = when {
            effectiveScenario == SubsystemCommissioningScenario.INVALID_FEEDBACK && !feedbackUsable -> Double.NaN
            else -> observedMeasurement
        }

        val controllerCommand = when (loop.strategy) {
            SubsystemControlStrategy.DIRECT -> requestedReference.coerceIn(loop.minimumOutput, loop.maximumOutput)
            SubsystemControlStrategy.SERVO_POSITION -> requestedReference.coerceIn(0.0, 1.0)
            SubsystemControlStrategy.BANG_BANG -> {
                if (!feedbackUsable || !controllerMeasurement.isFinite()) {
                    bangBangOutput = 0.0
                } else {
                    val error = requestedReference - controllerMeasurement
                    bangBangOutput = when {
                        bangBangOutput > 0.0 && error <= loop.tolerance -> 0.0
                        bangBangOutput < 0.0 && error >= -loop.tolerance -> 0.0
                        bangBangOutput == 0.0 && error > loop.tolerance + loop.hysteresis -> loop.maximumOutput
                        bangBangOutput == 0.0 && error < -(loop.tolerance + loop.hysteresis) -> loop.minimumOutput
                        else -> bangBangOutput
                    }
                }
                bangBangOutput
            }
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID -> {
                if (!feedbackUsable || !controllerMeasurement.isFinite()) {
                    integral = 0.0
                    derivative = 0.0
                    hasPreviousError = false
                    0.0
                } else {
                    var controllerTarget = requestedReference
                    var desiredVelocity = if (loop.strategy == SubsystemControlStrategy.VELOCITY_PID) requestedReference else 0.0
                    var desiredAcceleration = 0.0
                    if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                        if (!profileInitialized) {
                            profilePosition = controllerMeasurement
                            profileVelocity = 0.0
                            profileInitialized = true
                        }
                        val remaining = if (loop.continuousInput.enabled) {
                            wrapCommissioningDelta(requestedReference - profilePosition, period)
                        } else {
                            requestedReference - profilePosition
                        }
                        val previousProfileVelocity = profileVelocity
                        val stoppingVelocity = sqrt(2.0 * loop.motionProfile.maximumAcceleration * abs(remaining))
                        val desiredProfileVelocity = sign(remaining) * minOf(loop.motionProfile.maximumVelocity, stoppingVelocity)
                        val velocityStep = loop.motionProfile.maximumAcceleration * dtSeconds
                        profileVelocity += (desiredProfileVelocity - profileVelocity).coerceIn(-velocityStep, velocityStep)
                        val positionStep = profileVelocity * dtSeconds
                        if (abs(positionStep) >= abs(remaining)) {
                            profilePosition = requestedReference
                            profileVelocity = 0.0
                        } else {
                            profilePosition += positionStep
                        }
                        desiredVelocity = profileVelocity
                        desiredAcceleration = (profileVelocity - previousProfileVelocity) / dtSeconds
                        controllerTarget = profilePosition
                    }
                    val error = if (loop.continuousInput.enabled &&
                        loop.strategy != SubsystemControlStrategy.VELOCITY_PID
                    ) {
                        wrapCommissioningDelta(controllerTarget - controllerMeasurement, period)
                    } else {
                        controllerTarget - controllerMeasurement
                    }
                    val rawDerivative = if (hasPreviousError) {
                        val delta = if (loop.continuousInput.enabled &&
                            loop.strategy != SubsystemControlStrategy.VELOCITY_PID
                        ) {
                            wrapCommissioningDelta(error - previousError, period)
                        } else {
                            error - previousError
                        }
                        delta / dtSeconds
                    } else {
                        0.0
                    }
                    val derivativeAlpha = dtSeconds / (loop.derivativeFilterTimeConstantSeconds + dtSeconds)
                    derivative += derivativeAlpha * (rawDerivative - derivative)
                    previousError = error
                    hasPreviousError = true
                    val candidateIntegral = integral + error * dtSeconds
                    val feedforward = commissioningFeedforward(
                        loop = loop,
                        desiredVelocity = desiredVelocity,
                        desiredAcceleration = desiredAcceleration,
                        position = position,
                    )
                    val unclamped = loop.kP * error + loop.kI * candidateIntegral + loop.kD * derivative + feedforward
                    val clamped = unclamped.coerceIn(loop.minimumOutput, loop.maximumOutput)
                    if (unclamped == clamped || sign(error) != sign(unclamped - clamped)) integral = candidateIntegral
                    clamped
                }
            }
        }

        val timedFaultActive = now >= faultAtSeconds && now < recoveryAtSeconds
        val faultActive = when (effectiveScenario) {
            SubsystemCommissioningScenario.STALE_FEEDBACK,
            SubsystemCommissioningScenario.FROZEN_HEARTBEAT,
            SubsystemCommissioningScenario.INVALID_FEEDBACK -> now >= faultAtSeconds
            SubsystemCommissioningScenario.FAILED_WRITE_RECOVERY,
            SubsystemCommissioningScenario.BROWNOUT_RECOVERY,
            SubsystemCommissioningScenario.EXCESS_CURRENT_RECOVERY -> timedFaultActive
            SubsystemCommissioningScenario.UNCONFIGURED,
            SubsystemCommissioningScenario.UNHOMED -> true
            else -> false
        }
        if (effectiveScenario in setOf(
                SubsystemCommissioningScenario.FAILED_WRITE_RECOVERY,
                SubsystemCommissioningScenario.EXCESS_CURRENT_RECOVERY,
            ) && timedFaultActive
        ) {
            outputFaultLatched = true
            faultLatchObserved = true
        }
        if (outputFaultLatched && now >= recoveryAtSeconds) {
            // This scenario explicitly models a successful neutral write after the fault clears.
            outputFaultLatched = false
            neutralRecoverySucceeded = true
        }
        val safetyPermit = !faultActive && !outputFaultLatched
        val command = if (safetyPermit) controllerCommand else 0.0
        if (faultActive || outputFaultLatched) {
            neutralOnFault = (neutralOnFault ?: true) && command == 0.0
        }
        if (command == loop.minimumOutput || command == loop.maximumOutput) saturatedSamples++
        peakCommand = maxOf(peakCommand, abs(command))
        val errorForTolerance = if (feedbackRequired && observedMeasurement.isFinite()) {
            if (loop.continuousInput.enabled && loop.strategy != SubsystemControlStrategy.VELOCITY_PID) {
                abs(wrapCommissioningDelta(requestedReference - observedMeasurement, period))
            } else {
                abs(requestedReference - observedMeasurement)
            }
        } else null
        if (errorForTolerance != null && errorForTolerance <= loop.tolerance.coerceAtLeast(0.02)) {
            enteredTolerance = true
        }
        samples += SubsystemCommissioningSample(
            timeSeconds = now,
            reference = requestedReference,
            measurement = observedMeasurement,
            command = command,
            feedbackUsable = feedbackUsable,
            safetyPermit = safetyPermit,
            faultActive = faultActive,
            faultLatched = outputFaultLatched,
        )
        if (step == totalSteps) break

        val load = if (effectiveScenario == SubsystemCommissioningScenario.LOAD_DISTURBANCE && now >= loadAtSeconds) 1.0 else 0.0
        val next = advanceCommissioningPlant(plant, position, velocity, command, load, dtSeconds)
        position = next.first
        velocity = next.second
        val actualMeasurement = plantMeasurement(plant, position, velocity)
        if (!(effectiveScenario in setOf(
                SubsystemCommissioningScenario.STALE_FEEDBACK,
                SubsystemCommissioningScenario.FROZEN_HEARTBEAT,
            ) && now >= faultAtSeconds)
        ) {
            observedMeasurement = actualMeasurement
        }
        if (!position.isFinite() || !velocity.isFinite() || abs(position) > 100.0 || abs(velocity) > 250.0) {
            bounded = false
            break
        }
    }

    val finalMeasurement = samples.lastOrNull()?.measurement
    val finalError = when {
        !feedbackRequired || finalMeasurement == null || !finalMeasurement.isFinite() -> null
        loop.continuousInput.enabled && loop.strategy != SubsystemControlStrategy.VELOCITY_PID ->
            abs(wrapCommissioningDelta(requestedReference - finalMeasurement, period))
        else -> abs(requestedReference - finalMeasurement)
    }
    val saturationPercent = if (samples.isEmpty()) 0.0 else saturatedSamples * 100.0 / samples.size
    val status = when {
        !bounded -> "Preview stopped because the teaching plant exceeded its safe display bounds."
        neutralOnFault == false -> "Unsafe preview: feedback failed without a neutral command."
        neutralRecoverySucceeded == true ->
            "The fault latched, output stayed neutral, and motion resumed only after an explicit successful neutral recovery."
        neutralOnFault == true -> "The injected safety condition blocked motion and the selected controller stayed neutral."
        loop.strategy == SubsystemControlStrategy.DIRECT ->
            "Open-loop output is bounded, but it cannot guarantee a measured mechanism target."
        loop.strategy == SubsystemControlStrategy.SERVO_POSITION ->
            "The normalized servo model followed the bounded 0–1 command."
        else -> "Controller remained bounded in this educational model; physical commissioning is still required."
    }

    return SubsystemCommissioningResult(
        samples = samples,
        metrics = SubsystemCommissioningMetrics(
            bounded = bounded,
            finalError = finalError,
            peakAbsoluteCommand = peakCommand,
            saturationPercent = saturationPercent,
            neutralizedOnFault = neutralOnFault,
            faultLatched = faultLatchObserved.takeIf { effectiveScenario in setOf(
                SubsystemCommissioningScenario.FAILED_WRITE_RECOVERY,
                SubsystemCommissioningScenario.EXCESS_CURRENT_RECOVERY,
            ) },
            neutralRecoverySucceeded = neutralRecoverySucceeded,
            enteredTolerance = enteredTolerance,
            statusMessage = status,
        ),
        referenceLabel = when (loop.strategy) {
            SubsystemControlStrategy.DIRECT -> "Requested output"
            SubsystemControlStrategy.VELOCITY_PID -> "Target velocity"
            else -> "Target"
        },
        measurementLabel = when (plant) {
            SubsystemCommissioningPlant.FLYWHEEL -> "Measured velocity"
            SubsystemCommissioningPlant.POSITIONAL_SERVO -> "Servo position"
            else -> "Measured position"
        },
    )
}

private fun commissioningFeedforward(
    loop: SubsystemControlLoopDocument,
    desiredVelocity: Double,
    desiredAcceleration: Double,
    position: Double,
): Double {
    val feedforward = loop.feedforward
    if (feedforward.kind == SubsystemFeedforwardKind.NONE) return 0.0
    val static = if (desiredVelocity == 0.0) 0.0 else feedforward.kS * sign(desiredVelocity)
    val gravity = when (feedforward.kind) {
        SubsystemFeedforwardKind.NONE,
        SubsystemFeedforwardKind.SIMPLE_MOTOR -> 0.0
        SubsystemFeedforwardKind.ELEVATOR -> feedforward.kG
        SubsystemFeedforwardKind.ARM,
        SubsystemFeedforwardKind.TWO_DOF_ARM,
        SubsystemFeedforwardKind.FOUR_BAR_LINKAGE -> feedforward.kG * cos(position)
    }
    return static + feedforward.kV * desiredVelocity + feedforward.kA * desiredAcceleration + gravity
}

private fun advanceCommissioningPlant(
    plant: SubsystemCommissioningPlant,
    position: Double,
    velocity: Double,
    command: Double,
    load: Double,
    dtSeconds: Double,
): Pair<Double, Double> = when (plant) {
    SubsystemCommissioningPlant.FLYWHEEL -> {
        val acceleration = (-velocity + 0.40 * command - 0.30 * load) / 0.25
        val nextVelocity = velocity + acceleration * dtSeconds
        (position + nextVelocity * dtSeconds) to nextVelocity
    }
    SubsystemCommissioningPlant.ROTARY_ARM -> {
        val acceleration = (1.8 * command - 0.40 * velocity - 2.5 * cos(position) - load) / 0.15
        val nextVelocity = velocity + acceleration * dtSeconds
        (position + nextVelocity * dtSeconds) to nextVelocity
    }
    SubsystemCommissioningPlant.ELEVATOR -> {
        val acceleration = (3.5 * command - velocity - 3.924 - load) / 2.0
        val nextVelocity = velocity + acceleration * dtSeconds
        (position + nextVelocity * dtSeconds) to nextVelocity
    }
    SubsystemCommissioningPlant.POSITIONAL_SERVO -> {
        val nextPosition = position + (command.coerceIn(0.0, 1.0) - position) * (dtSeconds / 0.15).coerceAtMost(1.0)
        nextPosition to ((nextPosition - position) / dtSeconds)
    }
}

private fun plantMeasurement(plant: SubsystemCommissioningPlant, position: Double, velocity: Double): Double =
    if (plant == SubsystemCommissioningPlant.FLYWHEEL) velocity else position

private fun wrapCommissioningDelta(delta: Double, period: Double): Double {
    if (!delta.isFinite() || !period.isFinite() || period <= 0.0) return Double.NaN
    var wrapped = delta % period
    val halfPeriod = period * 0.5
    if (wrapped >= halfPeriod) wrapped -= period
    if (wrapped < -halfPeriod) wrapped += period
    return wrapped
}
