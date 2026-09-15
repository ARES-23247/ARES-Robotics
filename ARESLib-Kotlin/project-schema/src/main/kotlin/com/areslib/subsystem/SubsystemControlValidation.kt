package com.areslib.subsystem

/** Validates loop bindings, units, and numerical limits in their original diagnostic order. */
internal object SubsystemControlValidation {
    fun validate(
        document: SubsystemDocument,
        hardwareById: Map<String, SubsystemHardwareDocument>,
        fieldsById: Map<String, SubsystemStateFieldDocument>,
        issue: (String, String) -> Unit,
    ) {
        document.controlLoops.forEachIndexed { index, loop ->
            val path = "controlLoops[$index]"
            if (!loop.loopId.isUsableSubsystemKotlinIdentifier()) issue("$path.loopId", "Control loop ID must be a Kotlin identifier, not a keyword")
            if (loop.uid.isBlank()) issue("$path.uid", "Control loop UID is required")
            if (loop.displayName.isBlank()) issue("$path.displayName", "Control loop display name is required")
            val actuator = hardwareById[loop.actuatorId]
            if (actuator == null) {
                issue("$path.actuatorId", "Unknown actuator '${loop.actuatorId}'")
            } else if (actuator.kind !in SUBSYSTEM_ACTUATOR_KINDS) {
                issue("$path.actuatorId", "Selected hardware is a sensor, not an actuator")
            } else if (actuator.following != null) {
                issue("$path.actuatorId", "A follower cannot own a controller; control its leader instead")
            }
            val target = fieldsById[loop.targetFieldId]
            if (target == null) {
                issue("$path.targetFieldId", "Unknown target field '${loop.targetFieldId}'")
            } else {
                if (target.role != SubsystemFieldRole.TARGET && target.role != SubsystemFieldRole.CONFIGURATION) {
                    issue("$path.targetFieldId", "Control targets must use a target or configuration field")
                }
                if (target.type !in SUBSYSTEM_NUMERIC_TYPES) issue("$path.targetFieldId", "Control targets must be numeric")
            }
            val needsMeasurement = loop.strategy in SUBSYSTEM_CLOSED_LOOP_STRATEGIES
            val measurement = loop.measurementFieldId?.let(fieldsById::get)
            if (needsMeasurement && measurement == null) issue("$path.measurementFieldId", "This strategy requires a measurement field")
            if (measurement != null && measurement.type !in SUBSYSTEM_NUMERIC_TYPES) issue("$path.measurementFieldId", "Control measurements must be numeric")
            if (needsMeasurement && target != null && measurement != null &&
                !SubsystemUnits.controlUnitsCompatible(target.unit, measurement.unit)
            ) {
                issue(
                    "$path.measurementFieldId",
                    "Target '${target.fieldId}' uses ${target.unit} but feedback '${measurement.fieldId}' uses ${measurement.unit}. Convert both to the same unit before control.",
                )
            }
            if (loop.strategy == SubsystemControlStrategy.SERVO_POSITION && actuator?.kind != SubsystemHardwareKind.POSITIONAL_SERVO) {
                issue("$path.strategy", "Servo-position control requires a positional servo")
            }
            if (loop.strategy != SubsystemControlStrategy.SERVO_POSITION && actuator?.kind == SubsystemHardwareKind.POSITIONAL_SERVO) {
                issue("$path.strategy", "Positional servos require servo-position control")
            }
            listOf(
                loop.kP,
                loop.kI,
                loop.kD,
                loop.feedforward.kS,
                loop.feedforward.kV,
                loop.feedforward.kA,
                loop.feedforward.kG,
                loop.derivativeFilterTimeConstantSeconds,
                loop.continuousInput.minimumInput,
                loop.continuousInput.maximumInput,
                loop.tolerance,
                loop.hysteresis,
                loop.minimumOutput,
                loop.maximumOutput,
            )
                .forEach { value -> if (!value.isFinite()) issue(path, "Controller values must be finite") }
            if (loop.derivativeFilterTimeConstantSeconds < 0.0) {
                issue("$path.derivativeFilterTimeConstantSeconds", "Derivative filter time cannot be negative")
            }
            if (loop.tolerance < 0.0) issue("$path.tolerance", "Tolerance cannot be negative")
            if (loop.hysteresis < 0.0) issue("$path.hysteresis", "Hysteresis cannot be negative")
            if (loop.strategy != SubsystemControlStrategy.BANG_BANG && loop.hysteresis != 0.0) {
                issue("$path.hysteresis", "Restart hysteresis is available only for bang-bang control")
            }
            if (loop.continuousInput.enabled) {
                if (loop.strategy !in SUBSYSTEM_CONTINUOUS_POSITION_STRATEGIES) {
                    issue("$path.continuousInput.enabled", "Continuous input is available only for position PID control")
                }
                if (target != null && !SubsystemUnits.isCanonicalAngle(target.unit)) {
                    issue("$path.targetFieldId", "Continuous position targets must use canonical radians (rad)")
                }
                if (measurement != null && !SubsystemUnits.isCanonicalAngle(measurement.unit)) {
                    issue("$path.measurementFieldId", "Continuous position feedback must use canonical radians (rad)")
                }
                val period = loop.continuousInput.maximumInput - loop.continuousInput.minimumInput
                if (loop.continuousInput.minimumInput >= loop.continuousInput.maximumInput) {
                    issue("$path.continuousInput", "Continuous input minimum must be below maximum")
                } else if (kotlin.math.abs(period - 2.0 * Math.PI) > 1e-4) {
                    issue("$path.continuousInput", "Continuous angle range must span one full turn (2π radians)")
                }
            }
            if (loop.minimumOutput >= loop.maximumOutput) issue(path, "Minimum output must be below maximum output")
            val profile = loop.motionProfile
            if (!profile.maximumVelocity.isFinite() || profile.maximumVelocity <= 0.0) {
                issue("$path.motionProfile.maximumVelocity", "Profile maximum velocity must be finite and positive")
            }
            if (!profile.maximumAcceleration.isFinite() || profile.maximumAcceleration <= 0.0) {
                issue("$path.motionProfile.maximumAcceleration", "Profile maximum acceleration must be finite and positive")
            }
            if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID && actuator?.kind != SubsystemHardwareKind.MOTOR) {
                issue("$path.strategy", "Profiled position control currently requires a motor actuator")
            }
            SubsystemSafetyValidation.validateFeedforward(document, loop, fieldsById, path, issue)
        }

    }

    val SUBSYSTEM_ACTUATOR_KINDS = setOf(
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.POSITIONAL_SERVO,
        SubsystemHardwareKind.CONTINUOUS_SERVO,
        SubsystemHardwareKind.DIGITAL_OUTPUT,
        SubsystemHardwareKind.PWM_OUTPUT,
        SubsystemHardwareKind.INDICATOR_LIGHT,
        SubsystemHardwareKind.BUZZER,
        SubsystemHardwareKind.PRISM_DRIVER,
        SubsystemHardwareKind.SOLENOID,
    )
    private val SUBSYSTEM_NUMERIC_TYPES = setOf(SubsystemValueType.DOUBLE, SubsystemValueType.INT)
    val SUBSYSTEM_CLOSED_LOOP_STRATEGIES = setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
        SubsystemControlStrategy.BANG_BANG,
    )
    private val SUBSYSTEM_CONTINUOUS_POSITION_STRATEGIES = setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
    )
}
