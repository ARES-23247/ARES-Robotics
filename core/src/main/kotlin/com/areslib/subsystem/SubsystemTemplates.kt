package com.areslib.subsystem

/**
 * Documented, capability-oriented subsystem starters.
 *
 * Templates deliberately preserve domain, controller, IO, hardware, simulation, lifecycle, and
 * verification boundaries. They provide safe declarations; teams still review every generated
 * starter before it becomes user-owned code.
 */
object SubsystemTemplates {
    fun create(
        template: SubsystemTemplate,
        documentId: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        displayName: String = kotlinTypeName.toDisplayWords(),
    ): SubsystemDocument = when (template) {
        SubsystemTemplate.SIMPLE_ACTUATOR -> actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.DIRECT)
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM ->
            actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.POSITION_PID)
        SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM ->
            actuator(documentId, displayName, kotlinTypeName, platform, SubsystemControlStrategy.VELOCITY_PID)
        SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM -> sensorOnly(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.HOMED_MECHANISM -> homed(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.COMPOSITE_MECHANISM -> composite(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.INDICATOR_LIGHT_PWM -> indicatorLight(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.PRISM_LED_DRIVER -> prismDriver(documentId, displayName, kotlinTypeName, platform)
        SubsystemTemplate.ADVANCED_CUSTOM -> advanced(documentId, displayName, kotlinTypeName, platform)
    }

    private fun motorConnection(platform: SubsystemPlatform, name: String, canId: Int = 1) =
        if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = name)
        else SubsystemHardwareConnection(canId = canId)

    private fun digitalConnection(platform: SubsystemPlatform, name: String, channel: Int = 0) =
        if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = name)
        else SubsystemHardwareConnection(channel = channel)

    private fun actuator(
        id: String,
        displayName: String,
        kotlinTypeName: String,
        platform: SubsystemPlatform,
        strategy: SubsystemControlStrategy,
    ): SubsystemDocument {
        val closedLoop = strategy == SubsystemControlStrategy.POSITION_PID || strategy == SubsystemControlStrategy.VELOCITY_PID
        val measurementId = if (strategy == SubsystemControlStrategy.VELOCITY_PID) "velocity" else "position"
        return SubsystemDocument(
            documentId = id,
            displayName = displayName,
            kotlinTypeName = kotlinTypeName,
            description = "${templateLabel(strategy)} with cached inputs and fail-closed output handling.",
            platform = platform,
            template = when (strategy) {
                SubsystemControlStrategy.DIRECT -> SubsystemTemplate.SIMPLE_ACTUATOR
                SubsystemControlStrategy.POSITION_PID -> SubsystemTemplate.POSITION_CONTROLLED_MECHANISM
                else -> SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM
            },
            hardware = listOf(
                SubsystemHardwareDocument(
                    "motor", "Motor", SubsystemHardwareKind.MOTOR, motorConnection(platform, "motor"),
                    measurements = listOf(
                        SubsystemMeasurementDocument("position", SubsystemMeasurementSource.MOTOR_POSITION_NATIVE),
                        SubsystemMeasurementDocument("velocity", SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND),
                        SubsystemMeasurementDocument(
                            "currentAmps",
                            SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
                            validMinimum = 0.0,
                        ),
                    ),
                    safeOutput = 0.0,
                )
            ),
            stateFields = buildList {
                add(SubsystemStateFieldDocument("target", "Target", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, defaultNumber = 0.0))
                add(SubsystemStateFieldDocument("position", "Position", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0))
                add(SubsystemStateFieldDocument("velocity", "Velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0))
                add(SubsystemStateFieldDocument("currentAmps", "Current", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, unit = "A", defaultNumber = 0.0, minimum = 0.0))
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
                requiresCurrentMonitoring = true,
            ),
            autonomousResourceKey = id,
        )
    }

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
                if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = "light")
                else SubsystemHardwareConnection(channel = 0),
                safeOutput = 0.0,
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
                if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = "prism")
                else SubsystemHardwareConnection(canId = 1),
                safeOutput = 1000.0,
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

    private fun templateLabel(strategy: SubsystemControlStrategy): String = when (strategy) {
        SubsystemControlStrategy.DIRECT -> "Simple actuator"
        SubsystemControlStrategy.POSITION_PID -> "Position-controlled mechanism"
        SubsystemControlStrategy.VELOCITY_PID -> "Velocity-controlled mechanism"
        else -> "Controlled mechanism"
    }

    private fun String.toDisplayWords(): String =
        replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").trim().ifBlank { this }
}
