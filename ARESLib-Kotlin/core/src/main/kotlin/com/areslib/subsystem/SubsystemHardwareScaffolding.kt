package com.areslib.subsystem

/** Explicit state/control additions naturally provided by one hardware device. */
data class SubsystemHardwareScaffold(
    val hardware: SubsystemHardwareDocument,
    val stateFields: List<SubsystemStateFieldDocument>,
    val controlLoops: List<SubsystemControlLoopDocument>,
)

/**
 * Novice-safe hardware scaffolding shared by templates and the Builder.
 *
 * The resulting fields remain explicit in the saved descriptor. This avoids hidden inference while
 * ensuring students do not have to rediscover that motors provide position, velocity, and current,
 * or that PWM devices need a commanded position/power state.
 */
object SubsystemHardwareScaffolding {
    fun create(
        kind: SubsystemHardwareKind,
        hardwareId: String,
        displayName: String,
        platform: SubsystemPlatform,
        hardwareMapName: String = hardwareId,
        canId: Int = 1,
        channel: Int = 0,
    ): SubsystemHardwareScaffold {
        val connection = when (platform) {
            SubsystemPlatform.FTC,
            SubsystemPlatform.XRP -> SubsystemHardwareConnection(hardwareMapName = hardwareMapName)
            SubsystemPlatform.FRC -> when (kind) {
                SubsystemHardwareKind.MOTOR -> SubsystemHardwareConnection(canId = canId)
                SubsystemHardwareKind.QUADRATURE_ENCODER ->
                    SubsystemHardwareConnection(channel = channel, secondaryChannel = channel + 1)
                SubsystemHardwareKind.IMU -> SubsystemHardwareConnection()
                SubsystemHardwareKind.SOLENOID -> SubsystemHardwareConnection(
                    canId = canId,
                    channel = channel,
                    pneumaticsModuleType = SubsystemPneumaticsModuleType.REV_PH,
                )
                else -> SubsystemHardwareConnection(channel = channel)
            }
        }
        fun fieldId(suffix: String) = if (hardwareId == "motor" || hardwareId == "sensor") suffix else hardwareId + suffix.replaceFirstChar(Char::uppercase)
        return when (kind) {
            SubsystemHardwareKind.MOTOR -> {
                val target = fieldId("targetVoltage")
                val position = fieldId("position")
                val velocity = fieldId("velocity")
                val current = fieldId("currentAmps")
                SubsystemHardwareScaffold(
                    hardware = SubsystemHardwareDocument(
                        hardwareId,
                        displayName,
                        kind,
                        connection,
                        measurements = listOf(
                            SubsystemMeasurementDocument(position, SubsystemMeasurementSource.MOTOR_POSITION_NATIVE),
                            SubsystemMeasurementDocument(velocity, SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND),
                            SubsystemMeasurementDocument(
                                current,
                                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS,
                                validMinimum = 0.0,
                            ),
                        ),
                        safeOutput = 0.0,
                    ),
                    stateFields = listOf(
                        SubsystemStateFieldDocument(target, "Target voltage", SubsystemValueType.DOUBLE, SubsystemFieldRole.TARGET, "V", defaultNumber = 0.0, minimum = -12.0, maximum = 12.0),
                        SubsystemStateFieldDocument(position, "Position", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0),
                        SubsystemStateFieldDocument(velocity, "Velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, defaultNumber = 0.0),
                        SubsystemStateFieldDocument(current, "Current", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "A", defaultNumber = 0.0, minimum = 0.0),
                    ),
                    controlLoops = listOf(
                        SubsystemControlLoopDocument(
                            fieldId("control"),
                            "$displayName open-loop control",
                            SubsystemControlStrategy.DIRECT,
                            hardwareId,
                            target,
                        )
                    ),
                )
            }
            SubsystemHardwareKind.POSITIONAL_SERVO -> outputScaffold(
                kind,
                hardwareId,
                displayName,
                connection,
                fieldId("position"),
                "PWM position",
                0.0,
                1.0,
                SubsystemControlStrategy.SERVO_POSITION,
            )
            SubsystemHardwareKind.CONTINUOUS_SERVO -> outputScaffold(
                kind,
                hardwareId,
                displayName,
                connection,
                fieldId("power"),
                "PWM power",
                -1.0,
                1.0,
                SubsystemControlStrategy.DIRECT,
            )
            SubsystemHardwareKind.ABSOLUTE_ENCODER -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(
                        SubsystemMeasurementDocument(
                            fieldId("angle"),
                            SubsystemMeasurementSource.ENCODER_POSITION_TURNS,
                            scale = Math.PI * 2.0,
                        )
                    ),
                    description = "Absolute angle sensor; use measurement offset for the reviewed mechanical zero.",
                ),
                SubsystemStateFieldDocument(
                    fieldId("angle"), "Angle", SubsystemValueType.DOUBLE,
                    SubsystemFieldRole.MEASUREMENT, "rad", defaultNumber = 0.0,
                    minimum = -Math.PI * 2.0, maximum = Math.PI * 2.0,
                ),
            )
            SubsystemHardwareKind.QUADRATURE_ENCODER -> {
                val position = fieldId("position")
                val velocity = fieldId("velocity")
                SubsystemHardwareScaffold(
                    hardware = SubsystemHardwareDocument(
                        hardwareId, displayName, kind, connection,
                        measurements = listOf(
                            SubsystemMeasurementDocument(position, SubsystemMeasurementSource.ENCODER_POSITION_TURNS, scale = Math.PI * 2.0),
                            SubsystemMeasurementDocument(velocity, SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND, scale = Math.PI * 2.0),
                        ),
                        description = "Incremental A/B encoder; confirm counts per revolution before build.",
                        encoderCountsPerRevolution = 1.0,
                    ),
                    stateFields = listOf(
                        SubsystemStateFieldDocument(position, "Position", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad", defaultNumber = 0.0),
                        SubsystemStateFieldDocument(velocity, "Velocity", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad/s", defaultNumber = 0.0),
                    ),
                    controlLoops = emptyList(),
                )
            }
            SubsystemHardwareKind.DIGITAL_INPUT -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(SubsystemMeasurementDocument(fieldId("active"), SubsystemMeasurementSource.DIGITAL_STATE)),
                ),
                SubsystemStateFieldDocument(fieldId("active"), "Active", SubsystemValueType.BOOLEAN, SubsystemFieldRole.MEASUREMENT, defaultBoolean = false),
            )
            SubsystemHardwareKind.ANALOG_INPUT -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(SubsystemMeasurementDocument(fieldId("voltage"), SubsystemMeasurementSource.ANALOG_VOLTAGE)),
                ),
                SubsystemStateFieldDocument(fieldId("voltage"), "Voltage", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "V", defaultNumber = 0.0),
            )
            SubsystemHardwareKind.DISTANCE_SENSOR -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(
                        SubsystemMeasurementDocument(
                            fieldId("distance"),
                            SubsystemMeasurementSource.DISTANCE_METERS,
                            validMinimum = 0.0,
                            validMaximum = 10.0,
                        )
                    ),
                    description = if (platform == SubsystemPlatform.FRC) {
                        "Analog distance sensor; confirm meters per volt from its datasheet."
                    } else {
                        "FTC DistanceSensor sampled in meters once per loop."
                    },
                    distanceMetersPerVolt = if (platform == SubsystemPlatform.FRC) 1.0 else null,
                ),
                SubsystemStateFieldDocument(
                    fieldId("distance"), "Distance", SubsystemValueType.DOUBLE,
                    SubsystemFieldRole.MEASUREMENT, "m", defaultNumber = 0.0,
                    minimum = 0.0, maximum = 10.0,
                ),
            )
            SubsystemHardwareKind.IMU -> {
                val yaw = fieldId("yaw")
                val yawRate = fieldId("yawRate")
                SubsystemHardwareScaffold(
                    hardware = SubsystemHardwareDocument(
                        hardwareId, displayName, kind, connection,
                        measurements = listOf(
                            SubsystemMeasurementDocument(yaw, SubsystemMeasurementSource.IMU_YAW_RADIANS),
                            SubsystemMeasurementDocument(yawRate, SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND),
                        ),
                        description = if (platform == SubsystemPlatform.FTC) {
                            "Cached CCW-positive yaw and yaw rate. Confirm how the Control Hub is mounted before build."
                        } else {
                            "Cached CCW-positive yaw and yaw rate. The generated FRC adapter uses the roboRIO onboard SPI gyro."
                        },
                        imuLogoFacingDirection = if (platform == SubsystemPlatform.FTC) {
                            SubsystemHubFacingDirection.UP
                        } else null,
                        imuUsbFacingDirection = if (platform == SubsystemPlatform.FTC) {
                            SubsystemHubFacingDirection.FORWARD
                        } else null,
                    ),
                    stateFields = listOf(
                        SubsystemStateFieldDocument(yaw, "Yaw", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad", defaultNumber = 0.0, minimum = -Math.PI, maximum = Math.PI),
                        SubsystemStateFieldDocument(yawRate, "Yaw rate", SubsystemValueType.DOUBLE, SubsystemFieldRole.MEASUREMENT, "rad/s", defaultNumber = 0.0),
                    ),
                    controlLoops = emptyList(),
                )
            }
            SubsystemHardwareKind.COLOR_SENSOR -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(SubsystemMeasurementDocument(fieldId("argb"), SubsystemMeasurementSource.COLOR_ARGB)),
                ),
                SubsystemStateFieldDocument(fieldId("argb"), "ARGB color", SubsystemValueType.INT, SubsystemFieldRole.MEASUREMENT, defaultInt = 0),
            )
            SubsystemHardwareKind.SOLENOID -> outputScaffold(
                kind,
                hardwareId,
                displayName,
                connection,
                fieldId("active"),
                "Requested state (0 off, 1 on)",
                0.0,
                1.0,
                SubsystemControlStrategy.DIRECT,
            )
            SubsystemHardwareKind.INDICATOR_LIGHT -> outputScaffold(
                kind,
                hardwareId,
                displayName,
                connection,
                fieldId("colorPosition"),
                "Color spectrum position",
                0.0,
                1.0,
                SubsystemControlStrategy.DIRECT,
            )
            SubsystemHardwareKind.PRISM_DRIVER -> outputScaffold(
                kind,
                hardwareId,
                displayName,
                connection,
                fieldId("animationPattern"),
                "Animation pattern",
                0.0,
                2500.0,
                SubsystemControlStrategy.DIRECT,
            )
        }
    }

    private fun outputScaffold(
        kind: SubsystemHardwareKind,
        hardwareId: String,
        displayName: String,
        connection: SubsystemHardwareConnection,
        targetId: String,
        targetName: String,
        minimum: Double,
        maximum: Double,
        strategy: SubsystemControlStrategy,
    ) = SubsystemHardwareScaffold(
        SubsystemHardwareDocument(hardwareId, displayName, kind, connection, safeOutput = 0.0),
        listOf(
            SubsystemStateFieldDocument(
                targetId,
                targetName,
                SubsystemValueType.DOUBLE,
                SubsystemFieldRole.TARGET,
                defaultNumber = 0.0,
                minimum = minimum,
                maximum = maximum,
                description = "Desired PWM command; this is intent state, not measured feedback.",
            )
        ),
        listOf(
            SubsystemControlLoopDocument(
                "${hardwareId}Control",
                "$displayName control",
                strategy,
                hardwareId,
                targetId,
                minimumOutput = minimum,
                maximumOutput = maximum,
            )
        ),
    )

    private fun sensorScaffold(
        hardware: SubsystemHardwareDocument,
        field: SubsystemStateFieldDocument,
    ) = SubsystemHardwareScaffold(hardware, listOf(field), emptyList())
}
