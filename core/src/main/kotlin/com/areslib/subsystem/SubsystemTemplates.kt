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
        name: String,
        platform: SubsystemPlatform,
    ): SubsystemDocument = when (template) {
        SubsystemTemplate.SIMPLE_ACTUATOR -> actuator(documentId, name, platform, SubsystemControlStrategy.DIRECT)
        SubsystemTemplate.POSITION_CONTROLLED_MECHANISM ->
            actuator(documentId, name, platform, SubsystemControlStrategy.POSITION_PID)
        SubsystemTemplate.VELOCITY_CONTROLLED_MECHANISM ->
            actuator(documentId, name, platform, SubsystemControlStrategy.VELOCITY_PID)
        SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM -> sensorOnly(documentId, name, platform)
        SubsystemTemplate.HOMED_MECHANISM -> homed(documentId, name, platform)
        SubsystemTemplate.COMPOSITE_MECHANISM -> composite(documentId, name, platform)
        SubsystemTemplate.ADVANCED_CUSTOM -> advanced(documentId, name, platform)
    }

    private fun motorConnection(platform: SubsystemPlatform, name: String, canId: Int = 1) =
        if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = name)
        else SubsystemHardwareConnection(canId = canId)

    private fun digitalConnection(platform: SubsystemPlatform, name: String, channel: Int = 0) =
        if (platform == SubsystemPlatform.FTC) SubsystemHardwareConnection(hardwareMapName = name)
        else SubsystemHardwareConnection(channel = channel)

    private fun actuator(
        id: String,
        name: String,
        platform: SubsystemPlatform,
        strategy: SubsystemControlStrategy,
    ): SubsystemDocument {
        val closedLoop = strategy == SubsystemControlStrategy.POSITION_PID || strategy == SubsystemControlStrategy.VELOCITY_PID
        val measurementId = if (strategy == SubsystemControlStrategy.VELOCITY_PID) "velocity" else "position"
        val source = if (strategy == SubsystemControlStrategy.VELOCITY_PID) {
            SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND
        } else SubsystemMeasurementSource.MOTOR_POSITION_NATIVE
        return SubsystemDocument(
            documentId = id,
            name = name,
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
                    measurements = buildList {
                        if (closedLoop) add(SubsystemMeasurementDocument(measurementId, source))
                        add(SubsystemMeasurementDocument("currentAmps", SubsystemMeasurementSource.MOTOR_CURRENT_AMPS))
                    },
                    safeOutput = 0.0,
                )
            ),
            stateFields = buildList {
                add(SubsystemStateFieldDocument("target", "Target", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, defaultNumber = 0.0))
                if (closedLoop) add(
                    SubsystemStateFieldDocument(measurementId, measurementId.replaceFirstChar(Char::uppercase), SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0)
                )
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
                feedbackTimeoutMs = 250L.takeIf { closedLoop },
                requiresCurrentMonitoring = true,
            ),
            autonomousResourceKey = id,
        )
    }

    private fun sensorOnly(id: String, name: String, platform: SubsystemPlatform) = SubsystemDocument(
        documentId = id,
        name = name,
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

    private fun homed(id: String, name: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, name, platform, SubsystemControlStrategy.POSITION_PID)
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
            safety = base.safety.copy(requiresHoming = true, homingSensorId = "homeSwitch"),
        )
    }

    private fun composite(id: String, name: String, platform: SubsystemPlatform): SubsystemDocument {
        val base = actuator(id, name, platform, SubsystemControlStrategy.DIRECT)
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

    private fun advanced(id: String, name: String, platform: SubsystemPlatform) =
        actuator(id, name, platform, SubsystemControlStrategy.DIRECT).copy(
            description = "Advanced starter: review every capability and customize each explicit boundary.",
            template = SubsystemTemplate.ADVANCED_CUSTOM,
        )

    private fun templateLabel(strategy: SubsystemControlStrategy): String = when (strategy) {
        SubsystemControlStrategy.DIRECT -> "Simple actuator"
        SubsystemControlStrategy.POSITION_PID -> "Position-controlled mechanism"
        SubsystemControlStrategy.VELOCITY_PID -> "Velocity-controlled mechanism"
        else -> "Controlled mechanism"
    }
}
