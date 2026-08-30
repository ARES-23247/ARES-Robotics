package com.areslib.codegen

import com.areslib.subsystem.FaultRecoveryActionKind
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemStateFieldDocument

/** Renders the allocation-free controller and its stateful safety/control policies. */
internal object SubsystemControllerRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val tuningBindings = document.controllerTuningBindings()
        val tuningStateFields = tuningBindings.joinToString("\n") { binding ->
            "    private var ${binding.variableName} = ${binding.initialValue.kotlinDouble()}"
        }
        val pidStateFields = document.controlLoops.filter { it.strategy in PID_STRATEGIES }.joinToString("\n") { loop ->
            "    private var ${loop.loopId}Integral = 0.0\n" +
                "    private var ${loop.loopId}PreviousError = 0.0\n" +
                "    private var ${loop.loopId}Derivative = 0.0\n" +
                "    private var ${loop.loopId}HasPreviousError = false"
        }
        val profileStateFields = document.controlLoops
            .filter { it.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID }
            .joinToString("\n") { loop ->
                "    private var ${loop.loopId}ProfilePosition = 0.0\n" +
                    "    private var ${loop.loopId}ProfileVelocity = 0.0\n" +
                    "    private var ${loop.loopId}ProfileInitialized = false"
            }
        val bangBangStateFields = document.controlLoops
            .filter { it.strategy == SubsystemControlStrategy.BANG_BANG }
            .joinToString("\n") { loop -> "    private var ${loop.loopId}BangBangOutput = 0.0" }
        val stateFields = listOf(tuningStateFields, pidStateFields, profileStateFields, bangBangStateFields)
            .filter(String::isNotBlank)
            .joinToString("\n")
        val loopBodies = document.controlLoops.joinToString("\n\n") { loop -> controllerLoop(document, loop) }
        val pidReset = document.controlLoops.filter { it.strategy in PID_STRATEGIES }.joinToString("\n") { loop ->
            "        ${loop.loopId}Integral = 0.0\n" +
                "        ${loop.loopId}PreviousError = 0.0\n" +
                "        ${loop.loopId}Derivative = 0.0\n" +
                "        ${loop.loopId}HasPreviousError = false"
        }
        val profileReset = document.controlLoops
            .filter { it.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID }
            .joinToString("\n") { loop ->
                "        ${loop.loopId}ProfilePosition = 0.0\n" +
                    "        ${loop.loopId}ProfileVelocity = 0.0\n" +
                    "        ${loop.loopId}ProfileInitialized = false"
            }
        val bangBangReset = document.controlLoops
            .filter { it.strategy == SubsystemControlStrategy.BANG_BANG }
            .joinToString("\n") { loop -> "        ${loop.loopId}BangBangOutput = 0.0" }
        val reset = listOf(pidReset, profileReset, bangBangReset).filter(String::isNotBlank).joinToString("\n")
            .ifBlank { "        // This subsystem has no stateful PID loops." }
        val continuousInputHelper = if (document.controlLoops.any { it.continuousInput.enabled }) {
            """

                /** Wraps a periodic delta to [-period/2, period/2) without allocating. */
                private fun wrapDelta(delta: Double, period: Double): Double {
                    if (!delta.isFinite() || !period.isFinite() || period <= 0.0) return Double.NaN
                    var wrapped = delta % period
                    val halfPeriod = period * 0.5
                    if (wrapped >= halfPeriod) wrapped -= period
                    if (wrapped < -halfPeriod) wrapped += period
                    return wrapped
                }
            """.trimEnd()
        } else ""
        val requestState = buildString {
            if (document.hasSafetyRequestHandshake()) {
                append("    private var neutralHoldCommandSequence = Long.MIN_VALUE\n")
            }
            if (document.safety.requiresExplicitNeutralRecovery) {
                append("    private var handledNeutralRecoveryRequestSequence = 0L\n")
            }
            if (document.safety.requiresCalibration) {
                append("    private var handledCalibrationConfirmationRequestSequence = 0L\n")
            }
            if (document.safety.faultRecovery.enabled) {
                append("    private var jamEvidenceSinceMs = Long.MIN_VALUE\n")
                append("    private var automaticRecoveryStartedAtMs = Long.MIN_VALUE\n")
                append("    private var automaticRecoveryRetries = 0\n")
            }
        }.trimEnd()
        val requestHandling = buildString {
            if (document.safety.requiresExplicitNeutralRecovery) {
                append(
                    """
                    if (state.neutralRecoveryRequestSequence > 0L &&
                        state.neutralRecoveryRequestSequence != handledNeutralRecoveryRequestSequence
                    ) {
                        handledNeutralRecoveryRequestSequence = state.neutralRecoveryRequestSequence
                        reset()
                        if (!safetyRequestPermitted(state, now)) {
                            io.safe()
                            return
                        }
                        // IO owns the latch: a failed neutral must never clear it.
                        if (io.recoverWithNeutral()) {
                            neutralHoldCommandSequence = state.commandSequence
${if (document.safety.faultRecovery.enabled) "                            resetAutomaticRecovery()" else ""}
                        }
                        return
                    }

                    """.trimIndent().prependIndent("        ")
                )
            }
            if (document.safety.requiresCalibration) {
                append(
                    """
                    if (state.calibrationConfirmationRequestSequence > 0L &&
                        state.calibrationConfirmationRequestSequence != handledCalibrationConfirmationRequestSequence
                    ) {
                        handledCalibrationConfirmationRequestSequence = state.calibrationConfirmationRequestSequence
                        reset()
                        val mayCalibrate = safetyRequestPermitted(state, now) && !state.outputFaultLatched
                        if (!mayCalibrate || !io.recoverWithNeutral()) {
                            io.safe()
                            return
                        }
                        io.establishCalibration()
                        neutralHoldCommandSequence = state.commandSequence
                        return
                    }

                    """.trimIndent().prependIndent("        ")
                )
            }
        }.trimEnd()
        val neutralHoldHandling = if (document.hasSafetyRequestHandshake()) {
            """
                    if (neutralHoldCommandSequence != Long.MIN_VALUE) {
                        if (state.commandSequence == neutralHoldCommandSequence) {
                            reset()
                            io.safe()
                            return
                        }
                        neutralHoldCommandSequence = Long.MIN_VALUE
                    }
            """.trimIndent()
        } else ""
        val automaticRecoveryHandling = if (document.safety.faultRecovery.enabled) {
            "        if (handleAutomaticRecovery(state, scale, interlocksPermitted, now)) return"
        } else ""
        val automaticRecoveryHelper = automaticRecoveryControllerHelper(document)
        val safetyRequestHelper = if (
            document.hasSafetyRequestHandshake()
        ) {
            val feedbackTimeoutMs = document.safety.feedbackTimeoutMs ?: Long.MAX_VALUE
            """

                private fun safetyRequestPermitted(
                    state: ${document.kotlinTypeName}State,
                    now: Long,
                ): Boolean {
                    val feedbackAgeMs = if (now >= state.feedbackTimestampMs) {
                        now - state.feedbackTimestampMs
                    } else {
                        Long.MAX_VALUE
                    }
                    return state.feedbackValid && feedbackAgeMs <= ${feedbackTimeoutMs}L &&
                        state.configurationHealthy && state.currentReadingValid
                }
            """.trimEnd()
        } else ""
        val tuningSupportBody = tuningBindings.joinToString("\n") { binding ->
            "        ${binding.parameterUid.quoted()} -> true"
        }
        val tuningSupport = if (tuningBindings.isEmpty()) {
            "    override fun supportsTuningParameter(parameterUid: String): Boolean = false"
        } else {
            """    override fun supportsTuningParameter(parameterUid: String): Boolean = when (parameterUid) {
$tuningSupportBody
        else -> false
    }"""
        }
        val tuningApplyCases = tuningBindings.joinToString("\n") { binding ->
            """            ${binding.parameterUid.quoted()} -> {
                ${binding.variableName} = candidate
                reset()
                true
            }"""
        }
        val tuningApply = if (tuningBindings.isEmpty()) {
            "    override fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean = false"
        } else {
            """    override fun applyTuningParameter(parameterUid: String, value: TuningValue): Boolean {
        val candidate = value.doubleValue?.takeIf(Double::isFinite) ?: return false
        return when (parameterUid) {
$tuningApplyCases
            else -> false
        }
    }"""
        }
        return """
            package $pkg

            import com.areslib.tuning.TuningValue
            import com.areslib.tuning.TypedTuningConsumer
            import com.areslib.util.RobotClock
            import kotlin.math.abs
            import kotlin.math.sign

            /** Allocation-free controller generated from the visual/hand-authored subsystem DSL. */
            class ${document.kotlinTypeName}Controller(private val io: ${document.kotlinTypeName}IO) : TypedTuningConsumer {
                private var lastTimestampMs = 0L
                private var homingStartedAtMs = Long.MIN_VALUE
                private var homingEvidenceSinceMs = Long.MIN_VALUE
            $requestState
            $stateFields

                /**
                 * Applies one allocation-free control step from immutable [state]. [scale] is the
                 * current brownout/current-budget multiplier; invalid or unsafe input commands neutral.
                 */
                fun update(
                    state: ${document.kotlinTypeName}State,
                    scale: Double,
                    interlocksPermitted: Boolean = true,
                ) {
                    val now = RobotClock.currentTimeMillis()
            $requestHandling
            $neutralHoldHandling
                    if (${document.requiresHoming()} && !state.homed) {
                        updateHoming(state, scale, now)
                        return
                    }
                    resetHomingAttempt()
$automaticRecoveryHandling
                    val safetyPermit = interlocksPermitted && state.feedbackValid && state.configurationHealthy && state.homed &&
                        state.calibrated && state.currentReadingValid && !state.outputFaultLatched
                    if (!scale.isFinite() || scale <= 0.0 || !safetyPermit) {
                        reset()
                        io.safe()
                        return
                    }
                    val dtSeconds = if (lastTimestampMs == 0L) 0.02 else ((now - lastTimestampMs) / 1000.0).coerceIn(0.001, 0.1)
                    lastTimestampMs = now

            $loopBodies
                }

                /** Clears controller history; callers must still command IO neutral. */
                fun reset() {
                    lastTimestampMs = 0L
                    resetHomingAttempt()
${if (document.safety.faultRecovery.enabled) "                    resetAutomaticRecovery()" else ""}
            $reset
                }

            $tuningSupport

            $tuningApply
$continuousInputHelper

                private fun updateHoming(state: ${document.kotlinTypeName}State, scale: Double, now: Long) {
                    val permitted = state.homingRequested && !state.homingFaultLatched &&
                        state.feedbackValid && state.configurationHealthy && state.currentReadingValid &&
                        !state.outputFaultLatched && scale.isFinite() && scale > 0.0
                    if (!permitted) {
                        if (!state.homingRequested && state.homingFaultLatched) io.cancelHoming() else io.safe()
                        resetHomingAttempt()
                        return
                    }
                    if (homingStartedAtMs == Long.MIN_VALUE) homingStartedAtMs = now
                    if (now < homingStartedAtMs || now - homingStartedAtMs > ${document.safety.homing.timeoutMs}L) {
                        io.failHoming()
                        resetHomingAttempt()
                        return
                    }
                    if (!io.commandHoming()) {
                        io.failHoming()
                        resetHomingAttempt()
                        return
                    }
                    if (!io.homingConditionMet) {
                        homingEvidenceSinceMs = Long.MIN_VALUE
                        return
                    }
                    if (homingEvidenceSinceMs == Long.MIN_VALUE) homingEvidenceSinceMs = now
                    if (now >= homingEvidenceSinceMs && now - homingEvidenceSinceMs >= ${document.safety.homing.dwellMs}L) {
                        if (!io.establishHome()) io.failHoming()
                        resetHomingAttempt()
                    }
                }

                private fun resetHomingAttempt() {
                    homingStartedAtMs = Long.MIN_VALUE
                    homingEvidenceSinceMs = Long.MIN_VALUE
                }
            $safetyRequestHelper
            $automaticRecoveryHelper
            }
        """.trimIndent() + "\n"
    }

    private fun controllerLoop(document: SubsystemDocument, loop: SubsystemControlLoopDocument): String {
        val actuator = document.hardware.first { it.hardwareId == loop.actuatorId }
        val targetField = document.stateFields.first { it.fieldId == loop.targetFieldId }
        val rawTarget = targetField.numericExpression("state")
        val target = targetField.clampedExpression(rawTarget)
        val command = "io.${actuator.commandName()}"
        return when (loop.strategy) {
            SubsystemControlStrategy.DIRECT -> {
                val neutral = requireNotNull(actuator.safeOutput).kotlinDouble()
                val bounded = "(($target).takeIf(Double::isFinite) ?: $neutral).coerceIn(${loop.minimumOutput.kotlinDouble()}, ${loop.maximumOutput.kotlinDouble()})"
                val applied = if (actuator.kind == SubsystemHardwareKind.MOTOR ||
                    actuator.kind == SubsystemHardwareKind.CONTINUOUS_SERVO
                ) "$bounded * scale" else bounded
                "        $command($applied)"
            }
            SubsystemControlStrategy.SERVO_POSITION ->
                "        $command((($target).takeIf(Double::isFinite) ?: 0.0).coerceIn(0.0, 1.0))"
            SubsystemControlStrategy.BANG_BANG -> {
                val measurement = document.numericStateExpression(requireNotNull(loop.measurementFieldId))
                """        val ${loop.loopId}Target = $target
        val ${loop.loopId}Measurement = $measurement
        val ${loop.loopId}Error = ${loop.loopId}Target - ${loop.loopId}Measurement
        ${loop.loopId}BangBangOutput = when {
            !${loop.loopId}Target.isFinite() || !${loop.loopId}Measurement.isFinite() -> 0.0
            ${loop.loopId}BangBangOutput > 0.0 && ${loop.loopId}Error <= ${loop.tolerance.kotlinDouble()} -> 0.0
            ${loop.loopId}BangBangOutput < 0.0 && ${loop.loopId}Error >= -${loop.tolerance.kotlinDouble()} -> 0.0
            ${loop.loopId}BangBangOutput == 0.0 && ${loop.loopId}Error > ${(loop.tolerance + loop.hysteresis).kotlinDouble()} -> ${loop.maximumOutput.kotlinDouble()}
            ${loop.loopId}BangBangOutput == 0.0 && ${loop.loopId}Error < -${(loop.tolerance + loop.hysteresis).kotlinDouble()} -> ${loop.minimumOutput.kotlinDouble()}
            else -> ${loop.loopId}BangBangOutput
        }
        $command(${loop.loopId}BangBangOutput * scale)"""
            }
            SubsystemControlStrategy.POSITION_PID,
            SubsystemControlStrategy.PROFILED_POSITION_PID,
            SubsystemControlStrategy.VELOCITY_PID -> {
                val measurement = document.numericStateExpression(requireNotNull(loop.measurementFieldId))
                val feedforward = feedforwardExpression(document, loop)
                val continuousPeriod = loop.continuousInput.maximumInput - loop.continuousInput.minimumInput
                val errorExpression = if (loop.continuousInput.enabled) {
                    "wrapDelta(${loop.loopId}Target - ${loop.loopId}Measurement, ${continuousPeriod.kotlinDouble()})"
                } else {
                    "${loop.loopId}Target - ${loop.loopId}Measurement"
                }
                val derivativeDeltaExpression = if (loop.continuousInput.enabled) {
                    "wrapDelta(${loop.loopId}Error - ${loop.loopId}PreviousError, ${continuousPeriod.kotlinDouble()})"
                } else {
                    "${loop.loopId}Error - ${loop.loopId}PreviousError"
                }
                val targetPreparation = if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                    val remainingExpression = if (loop.continuousInput.enabled) {
                        "wrapDelta(${loop.loopId}Goal - ${loop.loopId}ProfilePosition, ${continuousPeriod.kotlinDouble()})"
                    } else {
                        "${loop.loopId}Goal - ${loop.loopId}ProfilePosition"
                    }
                    """        val ${loop.loopId}Goal = $target
        val ${loop.loopId}Measurement = $measurement
        if (!${loop.loopId}ProfileInitialized && ${loop.loopId}Measurement.isFinite()) {
            ${loop.loopId}ProfilePosition = ${loop.loopId}Measurement
            ${loop.loopId}ProfileVelocity = 0.0
            ${loop.loopId}ProfileInitialized = true
        }
        val ${loop.loopId}PreviousProfileVelocity = ${loop.loopId}ProfileVelocity
        if (${loop.loopId}Goal.isFinite() && ${loop.loopId}Measurement.isFinite()) {
            val ${loop.loopId}Remaining = $remainingExpression
            val ${loop.loopId}StoppingVelocity = kotlin.math.sqrt(2.0 * ${document.controllerTuningExpression(loop, "maxacceleration", loop.motionProfile.maximumAcceleration)} * abs(${loop.loopId}Remaining))
            val ${loop.loopId}DesiredVelocity = sign(${loop.loopId}Remaining) * minOf(${document.controllerTuningExpression(loop, "maxvelocity", loop.motionProfile.maximumVelocity)}, ${loop.loopId}StoppingVelocity)
            val ${loop.loopId}VelocityStep = ${document.controllerTuningExpression(loop, "maxacceleration", loop.motionProfile.maximumAcceleration)} * dtSeconds
            ${loop.loopId}ProfileVelocity += (${loop.loopId}DesiredVelocity - ${loop.loopId}ProfileVelocity).coerceIn(-${loop.loopId}VelocityStep, ${loop.loopId}VelocityStep)
            val ${loop.loopId}PositionStep = ${loop.loopId}ProfileVelocity * dtSeconds
            if (abs(${loop.loopId}PositionStep) >= abs(${loop.loopId}Remaining)) {
                ${loop.loopId}ProfilePosition = ${loop.loopId}Goal
                ${loop.loopId}ProfileVelocity = 0.0
            } else {
                ${loop.loopId}ProfilePosition += ${loop.loopId}PositionStep
            }
        }
        val ${loop.loopId}ProfileAcceleration = (${loop.loopId}ProfileVelocity - ${loop.loopId}PreviousProfileVelocity) / dtSeconds
        val ${loop.loopId}Target = if (${loop.loopId}Goal.isFinite()) ${loop.loopId}ProfilePosition else Double.NaN"""
                } else {
                    """        val ${loop.loopId}Target = $target
        val ${loop.loopId}Measurement = $measurement
        val ${loop.loopId}ProfileAcceleration = 0.0"""
                }
                """$targetPreparation
        if (!${loop.loopId}Target.isFinite() || !${loop.loopId}Measurement.isFinite()) {
            ${loop.loopId}Integral = 0.0
            ${loop.loopId}Derivative = 0.0
            ${loop.loopId}HasPreviousError = false
            $command(0.0)
        } else {
            val ${loop.loopId}Error = $errorExpression
            val ${loop.loopId}RawDerivative = if (${loop.loopId}HasPreviousError) {
                ($derivativeDeltaExpression) / dtSeconds
            } else {
                0.0
            }
            val ${loop.loopId}DerivativeAlpha = dtSeconds / (${loop.derivativeFilterTimeConstantSeconds.kotlinDouble()} + dtSeconds)
            ${loop.loopId}Derivative += ${loop.loopId}DerivativeAlpha * (${loop.loopId}RawDerivative - ${loop.loopId}Derivative)
            ${loop.loopId}PreviousError = ${loop.loopId}Error
            ${loop.loopId}HasPreviousError = true
            val ${loop.loopId}CandidateIntegral = ${loop.loopId}Integral + ${loop.loopId}Error * dtSeconds
$feedforward
            val ${loop.loopId}Unclamped = ${document.controllerTuningExpression(loop, "kp", loop.kP)} * ${loop.loopId}Error + ${document.controllerTuningExpression(loop, "ki", loop.kI)} * ${loop.loopId}CandidateIntegral + ${document.controllerTuningExpression(loop, "kd", loop.kD)} * ${loop.loopId}Derivative + ${loop.loopId}Feedforward
            val ${loop.loopId}Output = ${loop.loopId}Unclamped.coerceIn(${loop.minimumOutput.kotlinDouble()}, ${loop.maximumOutput.kotlinDouble()})
            if (${loop.loopId}Unclamped == ${loop.loopId}Output || sign(${loop.loopId}Error) != sign(${loop.loopId}Unclamped - ${loop.loopId}Output)) {
                ${loop.loopId}Integral = ${loop.loopId}CandidateIntegral
            }
            $command(${loop.loopId}Output * scale)
        }"""
            }
        }
    }

    private fun automaticRecoveryControllerHelper(document: SubsystemDocument): String {
        val recovery = document.safety.faultRecovery
        if (!recovery.enabled) return ""
        val actuator = document.hardware.single { it.hardwareId == recovery.actuatorId }
        val outputScale = if (actuator.kind == SubsystemHardwareKind.MOTOR) 12.0 else 1.0
        val recoveryOutput = (recovery.reverseDutyCycle * outputScale).kotlinDouble()
        val evidence = document.numericStateExpression(requireNotNull(recovery.currentFieldId))
        val action = when (recovery.recoveryAction) {
            FaultRecoveryActionKind.REVERSE_BRIEFLY -> """
        if (automaticRecoveryRetries >= ${recovery.maxRetries}) {
            io.latchOutputFault()
            resetAutomaticRecovery()
            return true
        }
        automaticRecoveryRetries++
        automaticRecoveryStartedAtMs = now
        jamEvidenceSinceMs = Long.MIN_VALUE
        if (!io.commandAutomaticRecovery($recoveryOutput)) {
            io.latchOutputFault()
            resetAutomaticRecovery()
        }
        return true"""
            FaultRecoveryActionKind.NEUTRAL_STOP -> """
        io.latchOutputFault()
        resetAutomaticRecovery()
        return true"""
            FaultRecoveryActionKind.NONE,
            FaultRecoveryActionKind.HOLD_POSITION -> error("Unsupported generated automatic recovery action")
        }
        return """

    private fun handleAutomaticRecovery(
        state: ${document.kotlinTypeName}State,
        scale: Double,
        interlocksPermitted: Boolean,
        now: Long,
    ): Boolean {
        val healthy = interlocksPermitted && scale.isFinite() && scale > 0.0 &&
            state.feedbackValid && state.configurationHealthy && state.homed && state.calibrated &&
            state.currentReadingValid && !state.outputFaultLatched
        if (!healthy) {
            resetAutomaticRecovery()
            return false
        }
        if (automaticRecoveryStartedAtMs != Long.MIN_VALUE) {
            if (now < automaticRecoveryStartedAtMs) {
                io.safe()
                resetAutomaticRecovery()
                return true
            }
            if (now - automaticRecoveryStartedAtMs < ${recovery.reverseDurationMs}L) {
                if (!io.commandAutomaticRecovery($recoveryOutput)) {
                    io.latchOutputFault()
                    resetAutomaticRecovery()
                }
                return true
            }
            io.safe()
            automaticRecoveryStartedAtMs = Long.MIN_VALUE
            jamEvidenceSinceMs = Long.MIN_VALUE
            return true
        }

        val recoveryCurrentAmps = $evidence
        if (!recoveryCurrentAmps.isFinite() || recoveryCurrentAmps < ${recovery.currentThresholdAmps.kotlinDouble()}) {
            resetAutomaticRecovery()
            return false
        }
        if (jamEvidenceSinceMs == Long.MIN_VALUE || now < jamEvidenceSinceMs) {
            jamEvidenceSinceMs = now
            return false
        }
        if (now - jamEvidenceSinceMs < ${recovery.currentDurationMs}L) return false
$action
    }

    private fun resetAutomaticRecovery() {
        jamEvidenceSinceMs = Long.MIN_VALUE
        automaticRecoveryStartedAtMs = Long.MIN_VALUE
        automaticRecoveryRetries = 0
    }
        """.trimEnd().prependIndent("            ")
    }

private val PID_STRATEGIES = setOf(
    SubsystemControlStrategy.POSITION_PID,
    SubsystemControlStrategy.PROFILED_POSITION_PID,
    SubsystemControlStrategy.VELOCITY_PID,
)

private fun SubsystemStateFieldDocument.clampedExpression(expression: String): String {
    val lowerBound = minimum
    val upperBound = maximum
    return when {
        lowerBound != null && upperBound != null ->
            "($expression).coerceIn(${lowerBound.kotlinDouble()}, ${upperBound.kotlinDouble()})"
        lowerBound != null -> "($expression).coerceAtLeast(${lowerBound.kotlinDouble()})"
        upperBound != null -> "($expression).coerceAtMost(${upperBound.kotlinDouble()})"
        else -> expression
    }
}

private fun SubsystemDocument.numericStateExpression(fieldId: String, receiver: String = "state"): String =
    requireNotNull(field(fieldId)) { "State field '$fieldId' does not exist" }.numericExpression(receiver)

private fun feedforwardExpression(document: SubsystemDocument, loop: SubsystemControlLoopDocument): String {
        val ff = loop.feedforward
        if (ff.kind == SubsystemFeedforwardKind.NONE) return "            val ${loop.loopId}Feedforward = 0.0"
        val defaultVelocity = when (loop.strategy) {
            SubsystemControlStrategy.VELOCITY_PID -> "${loop.loopId}Target"
            SubsystemControlStrategy.PROFILED_POSITION_PID -> "${loop.loopId}ProfileVelocity"
            else -> "0.0"
        }
        val velocity = ff.velocityFieldId?.let { document.numericStateExpression(it) } ?: defaultVelocity
        val acceleration = ff.accelerationFieldId?.let { document.numericStateExpression(it) }
            ?: if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) "${loop.loopId}ProfileAcceleration" else "0.0"
        val gravity = when (ff.kind) {
            SubsystemFeedforwardKind.NONE, SubsystemFeedforwardKind.SIMPLE_MOTOR -> "0.0"
            SubsystemFeedforwardKind.ELEVATOR -> document.controllerTuningExpression(loop, "kg", ff.kG)
            SubsystemFeedforwardKind.ARM ->
                "${document.controllerTuningExpression(loop, "kg", ff.kG)} * kotlin.math.cos(${document.numericStateExpression(requireNotNull(ff.gravityAngleFieldId))})"
            SubsystemFeedforwardKind.TWO_DOF_ARM -> {
                val linkage = document.linkage
                val theta1 = document.numericStateExpression(requireNotNull(linkage.joint1AngleFieldId))
                val theta2 = document.numericStateExpression(requireNotNull(linkage.joint2AngleFieldId))
                val sharedDistal = "(${linkage.link2MassKg.kotlinDouble()} * ${linkage.link2CenterOfMassMeters.kotlinDouble()} * 9.80665 * kotlin.math.cos($theta1 + $theta2))"
                val torque = if (ff.linkageJoint == 1) {
                    "((${linkage.link1MassKg.kotlinDouble()} * ${linkage.link1CenterOfMassMeters.kotlinDouble()} + ${linkage.link2MassKg.kotlinDouble()} * ${linkage.link1LengthMeters.kotlinDouble()}) * 9.80665 * kotlin.math.cos($theta1) + $sharedDistal)"
                } else {
                    sharedDistal
                }
                "${document.controllerTuningExpression(loop, "kg", ff.kG)} * $torque"
            }
            SubsystemFeedforwardKind.FOUR_BAR_LINKAGE ->
                error("Generated four-bar feedforward requires a closed-chain model and is intentionally unsupported")
        }
        return """            val ${loop.loopId}DesiredVelocity = $velocity
            val ${loop.loopId}DesiredAcceleration = $acceleration
            val ${loop.loopId}Static = if (${loop.loopId}DesiredVelocity == 0.0) 0.0 else ${document.controllerTuningExpression(loop, "ks", ff.kS)} * sign(${loop.loopId}DesiredVelocity)
            val ${loop.loopId}Feedforward = ${loop.loopId}Static + ${document.controllerTuningExpression(loop, "kv", ff.kV)} * ${loop.loopId}DesiredVelocity +
                ${document.controllerTuningExpression(loop, "ka", ff.kA)} * ${loop.loopId}DesiredAcceleration + $gravity"""
}

private data class GeneratedControllerTuningBinding(
    val parameterUid: String,
    val variableName: String,
    val initialValue: Double,
)

private fun SubsystemDocument.controllerTuningBindings(): List<GeneratedControllerTuningBinding> {
    val bindings = controlLoops.flatMap { loop ->
        val supportedDefaults = buildMap {
            if (loop.strategy in PID_STRATEGIES) {
                put("kp", loop.kP)
                put("ki", loop.kI)
                put("kd", loop.kD)
            }
            if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                put("maxvelocity", loop.motionProfile.maximumVelocity)
                put("maxacceleration", loop.motionProfile.maximumAcceleration)
            }
            if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                put("ks", loop.feedforward.kS)
                put("kv", loop.feedforward.kV)
                put("ka", loop.feedforward.kA)
                if (loop.feedforward.kind != SubsystemFeedforwardKind.SIMPLE_MOTOR) {
                    put("kg", loop.feedforward.kG)
                }
            }
        }
        tuningParameters.mapNotNull { declaration ->
            if (declaration.componentUid != loop.uid ||
                declaration.type != com.areslib.tuning.TuningParameterType.DOUBLE
            ) return@mapNotNull null
            val suffix = declaration.key.substringAfterLast('.').lowercase()
            val fallback = supportedDefaults[suffix] ?: return@mapNotNull null
            GeneratedControllerTuningBinding(
                parameterUid = declaration.uid,
                variableName = "${loop.loopId}${suffix.replaceFirstChar(Char::uppercaseChar)}",
                initialValue = declaration.defaultValue.doubleValue ?: fallback,
            )
        }
    }.distinctBy { it.parameterUid }.sortedBy { it.parameterUid }

    val duplicateRuntimeBinding = bindings.groupBy(GeneratedControllerTuningBinding::variableName)
        .entries
        .firstOrNull { (_, declarations) -> declarations.size > 1 }
    require(duplicateRuntimeBinding == null) {
        val (variableName, declarations) = requireNotNull(duplicateRuntimeBinding)
        "Subsystem '$documentId' declares multiple tuning parameters for generated controller binding " +
            "'$variableName': ${declarations.joinToString { it.parameterUid }}"
    }
    return bindings
}

private fun SubsystemDocument.controllerTuningExpression(
    loop: SubsystemControlLoopDocument,
    suffix: String,
    fallback: Double,
): String = controllerTuningBindings()
    .firstOrNull {
        it.variableName == "${loop.loopId}${suffix.replaceFirstChar(Char::uppercaseChar)}"
    }
    ?.variableName
    ?: fallback.kotlinDouble()
}
