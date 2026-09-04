package com.areslib.subsystem

import com.areslib.tuning.TuningApplyPolicy
import com.areslib.tuning.TuningParameterDeclaration
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

/**
 * Documented, capability-oriented subsystem starters.
 *
 * Templates deliberately preserve domain, controller, IO, hardware, simulation, lifecycle, and
 * verification boundaries. New Builder documents default to fully declarative ownership; callers
 * may explicitly request an editable generated starter when teaching Kotlin is the goal.
 */
object SubsystemTemplates {
    fun create(
        template: SubsystemTemplate,
        documentId: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        displayName: String = kotlinTypeName.toDisplayWords(),
    ): SubsystemDocument = createWithOwnership(
        template = template,
        documentId = documentId,
        kotlinTypeName = kotlinTypeName,
        platform = platform,
        displayName = displayName,
        implementationKind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
    )

    /**
     * Creates a template with an explicit source-ownership model.
     *
     * Most Robot Builder callers should use [create], which produces deterministic generated
     * runtime and tests. This entry point exists for the documented Kotlin teaching workflow that
     * deliberately creates an editable generated starter.
     */
    fun createWithOwnership(
        template: SubsystemTemplate,
        documentId: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        displayName: String = kotlinTypeName.toDisplayWords(),
        implementationKind: SubsystemImplementationKind,
    ): SubsystemDocument = when (template) {
        SubsystemTemplate.SIMPLE_ACTUATOR -> actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.DIRECT)
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM ->
            actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM ->
            actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.VELOCITY_PID)
        SubsystemTemplate.ELEVATOR_LIFT -> elevator(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.ARM_PIVOT -> armPivot(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.FLYWHEEL_SHOOTER -> flywheel(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.INTAKE_CONVEYOR -> intakeConveyor(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.DUAL_MOTOR_FOLLOWER -> dualMotorFollower(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.POSITIONAL_SERVO -> positionalServo(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.CONTINUOUS_SERVO -> continuousServo(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM -> sensorOnly(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.LIMIT_SWITCH_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.DIGITAL_INPUT, "Limit switch", "Cached limit-switch state for homing, interlocks, or diagnostics.")
        SubsystemTemplate.BEAM_BREAK_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.DIGITAL_INPUT, "Beam-break sensor", "Cached beam-break state for game-piece detection without hidden hardware reads.")
        SubsystemTemplate.POTENTIOMETER_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.ANALOG_INPUT, "Potentiometer", "Cached potentiometer voltage with explicit scale and offset for physical units.")
        SubsystemTemplate.ABSOLUTE_ENCODER_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.ABSOLUTE_ENCODER, "Absolute encoder", "Standalone absolute encoder converted to canonical radians.")
        SubsystemTemplate.QUADRATURE_ENCODER_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.QUADRATURE_ENCODER, "Quadrature encoder", "Incremental encoder with reviewed counts per revolution and canonical radian outputs.")
        SubsystemTemplate.DISTANCE_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.DISTANCE_SENSOR, "Distance sensor", "Cached distance measurement in meters with validity and freshness checks.")
        SubsystemTemplate.IMU_SENSOR -> hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.IMU, "IMU", "Cached CCW-positive yaw and yaw rate in radians.")
        SubsystemTemplate.PNEUMATIC_ACTUATOR -> {
            require(platform == SubsystemPlatform.FRC) { "Generated pneumatic actuator templates are available only for FRC projects" }
            hardwareTemplate(documentId, displayName, kotlinTypeName, platform, template, SubsystemHardwareKind.SOLENOID, "Solenoid", "Binary pneumatic output with a declared neutral and fail-closed write handling.")
        }
        SubsystemTemplate.HOMED_MECHANISM -> homed(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.CURRENT_HOMED_MECHANISM -> currentHomed(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.VELOCITY_HOMED_MECHANISM -> velocityHomed(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.COMPOSITE_MECHANISM -> composite(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.TWO_DOF_ARM -> twoDofArm(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.INDICATOR_LIGHT_PWM -> indicatorLight(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.PRISM_LED_DRIVER -> prismDriver(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.ADVANCED_CUSTOM -> advanced(documentId, displayName, kotlinTypeName, platform)
    }.withRecommendedTuningParameters().withTemplateOwnership(implementationKind)

    private fun SubsystemDocument.withTemplateOwnership(kind: SubsystemImplementationKind): SubsystemDocument = when (kind) {
        SubsystemImplementationKind.DECLARATIVE_GENERATED -> copy(
            generateMockIo = true,
            generateTest = true,
            implementation = SubsystemImplementationDocument(
                kind = kind,
                ownership = SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT,
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.GENERATED_MOCK),
            ),
        )
        SubsystemImplementationKind.GENERATED_STARTER -> copy(
            implementation = SubsystemImplementationDocument(
                kind = kind,
                ownership = SubsystemSourceOwnership.GENERATED_STARTER,
                simulation = SubsystemSimulationDocument(
                    if (generateMockIo) SubsystemSimulationSupport.GENERATED_MOCK else SubsystemSimulationSupport.UNAVAILABLE,
                ),
            ),
        )
        SubsystemImplementationKind.HAND_AUTHORED -> error(
            "Hand-authored subsystem metadata must explicitly declare module, source, runtime types, and simulation support",
        )
    }

    private fun motorConnection(platform: SubsystemPlatform, name: String, canId: Int = 1) =
        when (platform) {
            SubsystemPlatform.FTC -> SubsystemHardwareConnection(hardwareMapName = name)
            SubsystemPlatform.FRC -> SubsystemHardwareConnection(canId = canId)
            SubsystemPlatform.XRP -> SubsystemHardwareConnection(channel = 3)
        }

    private fun digitalConnection(platform: SubsystemPlatform, name: String, channel: Int = 0) =
        if (platform == SubsystemPlatform.FTC || platform == SubsystemPlatform.XRP) SubsystemHardwareConnection(hardwareMapName = name)
        else SubsystemHardwareConnection(channel = channel)

    private fun hardwareTemplate(
        id: String,
        displayName: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        template: SubsystemTemplate,
        kind: SubsystemHardwareKind,
        hardwareName: String,
        description: String,
    ): SubsystemDocument {
        val scaffold = SubsystemHardwareScaffolding.create(
            kind = kind,
            hardwareId = "sensor".takeIf { kind !in setOf(SubsystemHardwareKind.SOLENOID) } ?: "solenoid",
            displayName = hardwareName,
            platform = platform,
            hardwareMapName = when (kind) {
                SubsystemHardwareKind.IMU -> "imu"
                SubsystemHardwareKind.SOLENOID -> "solenoid"
                else -> "sensor"
            },
        )
        val actuator = kind == SubsystemHardwareKind.SOLENOID
        return SubsystemDocument(
            documentId = id,
            displayName = displayName,
            kotlinTypeName = kotlinTypeName,
            description = description,
            platform = platform,
            template = template,
            hardware = listOf(scaffold.hardware),
            stateFields = scaffold.stateFields,
            controlLoops = scaffold.controlLoops,
            safety = SubsystemSafetyDocument(
                feedbackTimeoutMs = if (actuator) null else 250L,
                requiresConfigurationHealth = true,
                requiresCurrentMonitoring = false,
                latchOutputFaults = actuator,
                requiresExplicitNeutralRecovery = actuator,
            ),
            autonomousResourceKey = id.takeIf { actuator },
        )
    }

    private fun actuator(
        id: String,
        displayName: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        strategy: SubsystemControlStrategy,
    ): SubsystemDocument {
        val closedLoop = strategy == SubsystemControlStrategy.POSITION_PID ||
            strategy == SubsystemControlStrategy.PROFILED_POSITION_PID ||
            strategy == SubsystemControlStrategy.VELOCITY_PID
        val measurementId = if (strategy == SubsystemControlStrategy.VELOCITY_PID) "velocity" else "position"
        return SubsystemDocument(
            documentId = id,
            displayName = displayName,
            kotlinTypeName = kotlinTypeName,
            description = "${templateLabel(strategy)} with cached inputs and fail-closed output handling.",
            platform = platform,
            template = when (strategy) {
                SubsystemControlStrategy.DIRECT -> SubsystemTemplate.SIMPLE_ACTUATOR
                SubsystemControlStrategy.POSITION_PID,
                SubsystemControlStrategy.PROFILED_POSITION_PID -> SubsystemTemplate.POSITION_CONTROLLED_MECHANISM
                else -> SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM
            },
            hardware = listOf(
                SubsystemHardwareDocument(
                    "motor", "Motor", SubsystemHardwareKind.MOTOR, motorConnection(platform, "motor"),
                    measurements = buildList {
                        add(SubsystemMeasurementDocument("position", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE))
                        add(SubsystemMeasurementDocument("velocity", SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND))
                        if (platform != SubsystemPlatform.XRP) add(
                            SubsystemMeasurementDocument(
                                "currentAmps",
                                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
                                validMinimum = 0.0,
                            ),
                        )
                    },
                    safeOutput = 0.0,
                )
            ),
            stateFields = buildList {
                add(SubsystemStateFieldDocument("target", "Target", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, defaultNumber = 0.0))
                add(SubsystemStateFieldDocument("position", "Position", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0))
                add(SubsystemStateFieldDocument("velocity", "Velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0))
                if (platform != SubsystemPlatform.XRP) add(SubsystemStateFieldDocument("currentAmps", "Current", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, unit = "A", defaultNumber = 0.0, minimum = 0.0))
            },
            controlLoops = listOf(
                SubsystemControlLoopDocument(
                    "primary", "Primary control", strategy, "motor", "target",
                    measurementFieldId = measurementId.takeIf { closedLoop },
                    kP = if (closedLoop) 1.0 else 0.0,
                )
            ),
            safety = SubsystemSafetyDocument(
                feedbackTimeoutMs = 250L,
                requiresCurrentMonitoring = platform != SubsystemPlatform.XRP,
            ),
            autonomousResourceKey = id,
        )
    }

    private fun elevator(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = homed(id, displayName, kotlinTypeName, platform)
        return base.copy(
            description = "Profiled elevator with limit-switch homing, gravity feedforward, soft limits, and cached current safety.",
            template = SubsystemTemplate.ELEVATOR_LIFT,
            stateFields = base.stateFields.map { field ->
                when (field.fieldId) {
                    "target" -> field.copy(displayName = "Target height", unit = "m", minimum = 0.0, maximum = 1.5)
                    "position" -> field.copy(displayName = "Height", unit = "m", minimum = 0.0, maximum = 1.6)
                    "velocity" -> field.copy(displayName = "Lift velocity", unit = "m/s")
                    else -> field
                }
            },
            controlLoops = base.controlLoops.map { loop ->
                loop.copy(
                    strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
                    kP = 8.0,
                    motionProfile = SubsystemMotionProfileDocument(maximumVelocity = 0.8, maximumAcceleration = 1.5),
                    feedforward = SubsystemFeedforwardDocument(kind = SubsystemFeedforwardKind.ELEVATOR, kG = 0.6),
                    tolerance = 0.01,
                )
            },
        )
    }

    private fun armPivot(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = homed(id, displayName, kotlinTypeName, platform)
        return base.copy(
            description = "Profiled rotating arm with homing, cosine gravity feedforward, angular limits, and cached current safety.",
            template = SubsystemTemplate.ARM_PIVOT,
            stateFields = base.stateFields.map { field ->
                when (field.fieldId) {
                    "target" -> field.copy(displayName = "Target angle", unit = "rad", minimum = -1.57, maximum = 1.57)
                    "position" -> field.copy(displayName = "Arm angle", unit = "rad", minimum = -1.75, maximum = 1.75)
                    "velocity" -> field.copy(displayName = "Angular velocity", unit = "rad/s")
                    else -> field
                }
            },
            controlLoops = base.controlLoops.map { loop ->
                loop.copy(
                    strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
                    kP = 6.0,
                    motionProfile = SubsystemMotionProfileDocument(maximumVelocity = 2.0, maximumAcceleration = 4.0),
                    feedforward = SubsystemFeedforwardDocument(
                        kind = SubsystemFeedforwardKind.ARM,
                        kG = 0.5,
                        gravityAngleFieldId = "position",
                    ),
                    tolerance = 0.02,
                )
            },
        )
    }

    private fun flywheel(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.VELOCITY_PID)
        return base.copy(
            description = "Velocity-controlled flywheel with motor feedforward, ready tolerance, and cached current monitoring.",
            template = SubsystemTemplate.FLYWHEEL_SHOOTER,
            stateFields = base.stateFields.map { field ->
                when (field.fieldId) {
                    "target" -> field.copy(displayName = "Target speed", unit = "rad/s", minimum = 0.0, maximum = 700.0)
                    "position" -> field.copy(unit = "rad")
                    "velocity" -> field.copy(displayName = "Flywheel speed", unit = "rad/s", minimum = 0.0, maximum = 750.0)
                    else -> field
                }
            },
            controlLoops = base.controlLoops.map { loop ->
                loop.copy(
                    kP = 0.08,
                    feedforward = SubsystemFeedforwardDocument(kind = SubsystemFeedforwardKind.SIMPLE_MOTOR, kS = 0.2, kV = 0.017),
                    tolerance = 5.0,
                    minimumOutput = 0.0,
                )
            },
        )
    }

    private fun intakeConveyor(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.DIRECT)
        return base.copy(
            description = "Intake/conveyor with bounded anti-jam recovery, cached current monitoring, and simulator game-piece interaction.",
            template = SubsystemTemplate.INTAKE_CONVEYOR,
            stateFields = base.stateFields.map { field ->
                if (field.fieldId == "target") field.copy(displayName = "Requested voltage", unit = "V", minimum = -12.0, maximum = 12.0) else field
            },
            implementation = base.implementation.copy(
                simulation = base.implementation.simulation.copy(
                    interaction = SubsystemSimInteractionDocument(
                        role = SimInteractionRole.CONVEYOR_INDEXER,
                        triggerActuatorId = "motor",
                        triggerThreshold = 1.0,
                    )
                )
            ),
            safety = base.safety.copy(
                faultRecovery = SubsystemFaultRecoveryDocument(
                    enabled = true,
                    actuatorId = "motor",
                    currentFieldId = "currentAmps",
                    currentThresholdAmps = 12.0,
                )
            ),
        )
    }

    private fun dualMotorFollower(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        return base.copy(
            description = "Two-motor mechanism with one controller, explicit follower direction, group neutral, and follower-write fault handling.",
            template = SubsystemTemplate.DUAL_MOTOR_FOLLOWER,
            hardware = base.hardware + SubsystemHardwareDocument(
                hardwareId = "followerMotor",
                displayName = "Follower motor",
                kind = SubsystemHardwareKind.MOTOR,
                connection = motorConnection(platform, "follower_motor", 2),
                safeOutput = 0.0,
                following = SubsystemFollowerDocument("motor", SubsystemFollowerTransform.INVERTED),
            ),
        )
    }

    private fun positionalServo(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        displayName = displayName,
        kotlinTypeName = kotlinTypeName,
        description = "Positional servo with normalized target, declared safe position, and optional mirrored followers.",
        platform = platform,
        template = SubsystemTemplate.POSITIONAL_SERVO,
        hardware = listOf(
            SubsystemHardwareDocument(
                "servo", "Servo", SubsystemHardwareKind.POSITIONAL_SERVO,
                when (platform) {
                    SubsystemPlatform.FTC -> SubsystemHardwareConnection(hardwareMapName = "servo")
                    SubsystemPlatform.FRC -> SubsystemHardwareConnection(channel = 0)
                    SubsystemPlatform.XRP -> SubsystemHardwareConnection(channel = 1)
                },
                safeOutput = 0.5,
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument("target", "Target position", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, unit = "normalized", defaultNumber = 0.5, minimum = 0.0, maximum = 1.0)
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument("primary", "Servo position", SubsystemControlStrategy.SERVO_POSITION, "servo", "target", minimumOutput = 0.0, maximumOutput = 1.0)
        ),
        safety = SubsystemSafetyDocument(feedbackTimeoutMs = null, requiresCurrentMonitoring = false),
        autonomousResourceKey = id,
    )

    private fun continuousServo(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        displayName = displayName,
        kotlinTypeName = kotlinTypeName,
        description = "Continuous-rotation servo with normalized power, declared neutral, inversion, and follower support.",
        platform = platform,
        template = SubsystemTemplate.CONTINUOUS_SERVO,
        hardware = listOf(
            SubsystemHardwareDocument(
                "servo", "Continuous servo", SubsystemHardwareKind.CONTINUOUS_SERVO,
                if (platform == SubsystemPlatform.FTC || platform == SubsystemPlatform.XRP) SubsystemHardwareConnection(hardwareMapName = "servo") else SubsystemHardwareConnection(channel = 0),
                safeOutput = 0.0,
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument("target", "Requested power", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, unit = "normalized", defaultNumber = 0.0, minimum = -1.0, maximum = 1.0)
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument("primary", "Servo power", SubsystemControlStrategy.DIRECT, "servo", "target", minimumOutput = -1.0, maximumOutput = 1.0)
        ),
        safety = SubsystemSafetyDocument(feedbackTimeoutMs = null, requiresCurrentMonitoring = false),
        autonomousResourceKey = id,
    )

    private fun sensorOnly(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        displayName = displayName,
        kotlinTypeName = kotlinTypeName,
        description = "Read-only cached sensor subsystem with no actuator output path.",
        platform = platform,
        template = SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM,
        hardware = listOf(
            SubsystemHardwareDocument(
                "sensor", "Sensor", SubsystemHardwareKind.DIGITAL_INPUT, digitalConnection(platform, "sensor"),
                measurements = listOf(SubsystemMeasurementDocument("active", SubsystemMeasurementSource.DIGITAL_STATE)),
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument("active", "Active", SubsystemValueType.BOOLEAN, SubsystemFieldRole.MEASUREMENT, defaultBoolean = false)
        ),
        safety = SubsystemSafetyDocument(
            feedbackTimeoutMs = 250,
            requiresConfigurationHealth = true,
            requiresCurrentMonitoring = false,
            latchOutputFaults = false,
            requiresExplicitNeutralRecovery = false,
        ),
    )

    private fun homed(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        return base.copy(
            description = "Homed position mechanism with soft limits and explicit neutral recovery.",
            template = SubsystemTemplate.HOMED_MECHANISM,
            hardware = base.hardware + SubsystemHardwareDocument(
                "homeSwitch", "Home switch", SubsystemHardwareKind.DIGITAL_INPUT,
                digitalConnection(platform, "home_switch"),
                measurements = listOf(SubsystemMeasurementDocument("homeSwitchActive", SubsystemMeasurementSource.DIGITAL_STATE)),
            ),
            stateFields = base.stateFields.map {
                when (it.fieldId) {
                    "target" -> it.copy(unit = "rot", minimum = 0.0, maximum = 10.0)
                    "position" -> it.copy(unit = "rot")
                    else -> it
                }
            } + SubsystemStateFieldDocument(
                "homeSwitchActive", "Home switch active", SubsystemValueType.BOOLEAN,
                SubsystemFieldRole.MEASUREMENT, defaultBoolean = false,
            ),
            safety = base.safety.copy(
                homing = SubsystemHomingDocument(
                    method = SubsystemHomingMethod.DIGITAL_SENSOR,
                    actuatorId = "motor",
                    searchOutput = -2.0,
                    evidence = listOf(
                        SubsystemHomingEvidenceDocument(
                            "homeSwitchActive",
                            SubsystemHomingComparison.TRUE,
                        )
                    ),
                )
            ),
        )
    }

    private fun currentHomed(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        return base.copy(
            description = "Sensorless homing mechanism using a bounded current-stall search, evidence dwell, timeout, and neutral-before-zero.",
            template = SubsystemTemplate.CURRENT_HOMED_MECHANISM,
            safety = base.safety.copy(
                homing = SubsystemHomingDocument(
                    method = SubsystemHomingMethod.CURRENT_STALL,
                    actuatorId = "motor",
                    searchOutput = -2.0,
                    evidence = listOf(
                        SubsystemHomingEvidenceDocument("currentAmps", SubsystemHomingComparison.AT_OR_ABOVE, 5.0)
                    ),
                ),
                requiresCurrentMonitoring = true,
            ),
        )
    }

    private fun velocityHomed(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        return base.copy(
            description = "Sensorless homing mechanism using bounded low-velocity stall evidence, dwell, timeout, and neutral-before-zero.",
            template = SubsystemTemplate.VELOCITY_HOMED_MECHANISM,
            safety = base.safety.copy(
                homing = SubsystemHomingDocument(
                    method = SubsystemHomingMethod.VELOCITY_STALL,
                    actuatorId = "motor",
                    searchOutput = -2.0,
                    evidence = listOf(
                        SubsystemHomingEvidenceDocument("velocity", SubsystemHomingComparison.ABS_AT_OR_BELOW, 0.5)
                    ),
                )
            ),
        )
    }

    private fun composite(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.DIRECT)
        val follower = SubsystemHardwareDocument(
            "secondaryMotor", "Secondary motor", SubsystemHardwareKind.MOTOR,
            motorConnection(platform, "secondary_motor", 2), safeOutput = 0.0,
        )
        return base.copy(
            description = "Composite mechanism with two independently safe actuator outputs.",
            template = SubsystemTemplate.COMPOSITE_MECHANISM,
            hardware = base.hardware + follower,
            controlLoops = base.controlLoops + SubsystemControlLoopDocument(
                "secondary", "Secondary control", SubsystemControlStrategy.DIRECT,
                "secondaryMotor", "target",
            ),
        )
    }

    private fun twoDofArm(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform): SubsystemDocument {
        fun motor(
            hardwareId: String,
            name: String,
            canId: Int,
            prefix: String,
        ) = SubsystemHardwareDocument(
            hardwareId = hardwareId,
            displayName = name,
            kind = SubsystemHardwareKind.MOTOR,
            connection = motorConnection(platform, hardwareId, canId),
            measurements = listOf(
                SubsystemMeasurementDocument("${prefix}Angle", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE),
                SubsystemMeasurementDocument("${prefix}Velocity", SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND),
                SubsystemMeasurementDocument("${prefix}CurrentAmps", SubsystemMeasurementSource.MOTOR_CURRENT_AMPS, validMinimum = 0.0),
            ),
            safeOutput = 0.0,
        )
        fun fields(prefix: String, label: String) = listOf(
            SubsystemStateFieldDocument("${prefix}Target", "$label target angle", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, unit = "rad", defaultNumber = 0.0, minimum = -3.14, maximum = 3.14),
            SubsystemStateFieldDocument("${prefix}Angle", "$label angle", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, unit = "rad", defaultNumber = 0.0, minimum = -3.2, maximum = 3.2),
            SubsystemStateFieldDocument("${prefix}Velocity", "$label angular velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, unit = "rad/s", defaultNumber = 0.0),
            SubsystemStateFieldDocument("${prefix}CurrentAmps", "$label current", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, unit = "A", defaultNumber = 0.0, minimum = 0.0),
        )
        fun loop(prefix: String, hardwareId: String, joint: Int) = SubsystemControlLoopDocument(
            loopId = "${prefix}Control",
            displayName = "Joint $joint profiled control",
            strategy = SubsystemControlStrategy.PROFILED_POSITION_PID,
            actuatorId = hardwareId,
            targetFieldId = "${prefix}Target",
            measurementFieldId = "${prefix}Angle",
            kP = 5.0,
            motionProfile = SubsystemMotionProfileDocument(2.0, 4.0),
            feedforward = SubsystemFeedforwardDocument(
                kind = SubsystemFeedforwardKind.TWO_DOF_ARM,
                kG = 1.0,
                linkageJoint = joint,
            ),
            tolerance = 0.02,
            uid = "${prefix}-control",
        )
        return SubsystemDocument(
            documentId = id,
            displayName = displayName,
            kotlinTypeName = kotlinTypeName,
            description = "Two-joint arm with explicit geometry, coupled gravity feedforward, profiled motion, cached current safety, and generated linkage simulation.",
            platform = platform,
            template = SubsystemTemplate.TWO_DOF_ARM,
            hardware = listOf(motor("joint1Motor", "Shoulder motor", 1, "joint1"), motor("joint2Motor", "Elbow motor", 2, "joint2")),
            stateFields = fields("joint1", "Shoulder") + fields("joint2", "Elbow"),
            controlLoops = listOf(loop("joint1", "joint1Motor", 1), loop("joint2", "joint2Motor", 2)),
            safety = SubsystemSafetyDocument(feedbackTimeoutMs = 250L, requiresCurrentMonitoring = true),
            linkage = SubsystemLinkageDocument(
                enabled = true,
                joint1ActuatorId = "joint1Motor",
                joint2ActuatorId = "joint2Motor",
                joint1AngleFieldId = "joint1Angle",
                joint2AngleFieldId = "joint2Angle",
            ),
            autonomousResourceKey = id,
        )
    }

    private fun indicatorLight(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        displayName = displayName,
        kotlinTypeName = kotlinTypeName,
        description = "PWM servo-port RGB indicator light with normalized color presets and safe off state.",
        platform = platform,
        template = SubsystemTemplate.INDICATOR_LIGHT_PWM,
        hardware = listOf(
            SubsystemHardwareDocument(
                "light", "Indicator light", SubsystemHardwareKind.INDICATOR_LIGHT,
                if (platform == SubsystemPlatform.FTC || platform == SubsystemPlatform.XRP) SubsystemHardwareConnection(hardwareMapName = "light")
                else SubsystemHardwareConnection(channel = 0),
                safeOutput = 0.0,
                visualPlacement = SubsystemVisualPlacementDocument(
                    anchor = SubsystemVisualAnchor.LEFT_SIDE,
                    leftFraction = 0.5,
                ),
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument("targetColor", "Target color", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, defaultNumber = 0.0, minimum = 0.0, maximum = 1.0),
            SubsystemStateFieldDocument("enabled", "Enabled", SubsystemValueType.BOOLEAN, SubsystemFieldRole.STATUS, defaultBoolean = true),
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument(
                "primary", "Light color control", SubsystemControlStrategy.DIRECT,
                "light", "targetColor",
            )
        ),
        safety = SubsystemSafetyDocument(
            feedbackTimeoutMs = 250L,
            requiresCurrentMonitoring = false,
            latchOutputFaults = false,
            requiresExplicitNeutralRecovery = false,
        ),
        autonomousResourceKey = id,
    )

    private fun prismDriver(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        displayName = displayName,
        kotlinTypeName = kotlinTypeName,
        description = "Prism I2C/PWM driver with pattern presets, brightness control, and safe neutral output.",
        platform = platform,
        template = SubsystemTemplate.PRISM_LED_DRIVER,
        hardware = listOf(
            SubsystemHardwareDocument(
                "prism", "Prism driver", SubsystemHardwareKind.PRISM_DRIVER,
                if (platform == SubsystemPlatform.FTC || platform == SubsystemPlatform.XRP) SubsystemHardwareConnection(hardwareMapName = "prism")
                else SubsystemHardwareConnection(channel = 0),
                safeOutput = 1000.0,
                visualPlacement = SubsystemVisualPlacementDocument(
                    anchor = SubsystemVisualAnchor.UNDERBODY,
                ),
            )
        ),
        stateFields = listOf(
            SubsystemStateFieldDocument("targetPulseWidthUs", "Target pulse width", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, defaultNumber = 1000.0, minimum = 500.0, maximum = 2500.0, unit = "µs"),
            SubsystemStateFieldDocument("brightnessPercent", "Brightness", SubsystemValueType.DOUBLE, SubsystemFieldRole.CONFIGURATION, defaultNumber = 75.0, minimum = 0.0, maximum = 100.0, unit = "%"),
            SubsystemStateFieldDocument("enabled", "Enabled", SubsystemValueType.BOOLEAN, SubsystemFieldRole.STATUS, defaultBoolean = true),
        ),
        controlLoops = listOf(
            SubsystemControlLoopDocument(
                "primary", "Prism pattern control", SubsystemControlStrategy.DIRECT,
                "prism", "targetPulseWidthUs",
            )
        ),
        safety = SubsystemSafetyDocument(
            feedbackTimeoutMs = 250L,
            requiresCurrentMonitoring = false,
            latchOutputFaults = false,
            requiresExplicitNeutralRecovery = false,
        ),
        autonomousResourceKey = id,
    )

    private fun advanced(id: String, displayName: String, kotlinTypeName: String, platform: SubsystemPlatform) =
        actuator(id, displayName, kotlinTypeName, platform, SubsystemControlStrategy.DIRECT).copy(
            description = "Advanced starter: review every capability and customize each explicit boundary.",
            template = SubsystemTemplate.ADVANCED_CUSTOM,
        )

    /**
     * Gives every applicable capability template a complete, typed starting tuning surface.
     *
     * These declarations are consumed by generated controllers; they are not decorative metadata.
     * Values remain disabled-only until a reviewed tuning profile applies them, so a novice can see
     * what may be tuned without accidentally changing an enabled mechanism.
     */
    private fun SubsystemDocument.withRecommendedTuningParameters(): SubsystemDocument {
        if (tuningParameters.isNotEmpty()) return this
        val documentKey = documentId.toTuningKeySegment()
        val parameters = buildList {
            controlLoops.forEach { loop ->
                val loopKey = loop.loopId.toTuningKeySegment()
                val targetUnit = stateFields.firstOrNull { it.fieldId == loop.targetFieldId }?.unit

                fun addDouble(
                    suffix: String,
                    displayName: String,
                    description: String,
                    value: Double,
                    unit: String? = null,
                    minimum: Double? = null,
                    maximum: Double? = null,
                ) {
                    add(
                        TuningParameterDeclaration(
                            uid = "${uid}.tuning.${loop.uid.toTuningUidSegment()}.$suffix",
                            key = "subsystem.$documentKey.$loopKey.$suffix",
                            componentUid = loop.uid,
                            displayName = displayName,
                            description = "$description Changes apply only while disabled; verify the response in simulation before physical use.",
                            type = TuningParameterType.DOUBLE,
                            unit = unit,
                            minimum = minimum,
                            maximum = maximum,
                            defaultValue = TuningValue(doubleValue = value),
                            applyPolicy = TuningApplyPolicy.DISABLED_ONLY,
                        )
                    )
                }

                if (loop.strategy in setOf(
                        SubsystemControlStrategy.POSITION_PID,
                        SubsystemControlStrategy.PROFILED_POSITION_PID,
                        SubsystemControlStrategy.VELOCITY_PID,
                    )
                ) {
                    addDouble("kp", "Proportional gain (kP)", "Corrects present controller error.", loop.kP, minimum = 0.0, maximum = 100.0)
                    addDouble("ki", "Integral gain (kI)", "Corrects error that persists over time.", loop.kI, minimum = 0.0, maximum = 100.0)
                    addDouble("kd", "Derivative gain (kD)", "Damps rapid changes in controller error.", loop.kD, minimum = 0.0, maximum = 100.0)
                }

                if (loop.feedforward.kind != SubsystemFeedforwardKind.NONE) {
                    addDouble("ks", "Static feedforward (kS)", "Offsets static friction before motion begins.", loop.feedforward.kS, "V", 0.0, 24.0)
                    addDouble("kv", "Velocity feedforward (kV)", "Predicts output needed for the requested velocity.", loop.feedforward.kV, minimum = 0.0, maximum = 1_000.0)
                    addDouble("ka", "Acceleration feedforward (kA)", "Predicts output needed for the requested acceleration.", loop.feedforward.kA, minimum = 0.0, maximum = 1_000.0)
                    if (loop.feedforward.kind != SubsystemFeedforwardKind.SIMPLE_MOTOR) {
                        addDouble("kg", "Gravity feedforward (kG)", "Offsets gravity for the declared mechanism model.", loop.feedforward.kG, "V", -24.0, 24.0)
                    }
                }

                if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
                    addDouble(
                        "maxvelocity",
                        "Maximum profile velocity",
                        "Limits how quickly the motion profile may move toward its goal.",
                        loop.motionProfile.maximumVelocity,
                        targetUnit?.let { "$it/s" },
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumVelocity * 10.0),
                    )
                    addDouble(
                        "maxacceleration",
                        "Maximum profile acceleration",
                        "Limits how quickly the motion profile may change velocity.",
                        loop.motionProfile.maximumAcceleration,
                        targetUnit?.let { "$it/s²" },
                        0.000001,
                        maxOf(100.0, loop.motionProfile.maximumAcceleration * 10.0),
                    )
                }
            }
        }
        return copy(tuningParameters = parameters)
    }

    private fun templateLabel(strategy: SubsystemControlStrategy): String = when (strategy) {
        SubsystemControlStrategy.DIRECT -> "Simple actuator"
        SubsystemControlStrategy.POSITION_PID -> "Position-controlled mechanism"
        SubsystemControlStrategy.PROFILED_POSITION_PID -> "Profiled position mechanism"
        SubsystemControlStrategy.VELOCITY_PID -> "Velocity-controlled mechanism"
        else -> "Controlled mechanism"
    }

    private fun String.toDisplayWords(): String =
        replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim().ifBlank { this }

    private fun String.toTuningKeySegment(): String = split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
        .mapIndexed { index, part ->
            val lower = part.lowercase()
            if (index == 0) lower else lower.replaceFirstChar(Char::uppercase)
        }
        .joinToString("")
        .ifBlank { "component" }
        .let { if (it.first().isLetter()) it else "value$it" }

    private fun String.toTuningUidSegment(): String = lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "control" }
}
