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
            SubsystemPlatform.FTC -> SubsystemHardwareConnection(hardwareMapName = hardwareMapName)
            SubsystemPlatform.FRC -> if (kind == SubsystemHardwareKind.MOTOR) {
                SubsystemHardwareConnection(canId = canId)
            } else {
                SubsystemHardwareConnection(channel = channel)
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
            SubsystemHardwareKind.COLOR_SENSOR -> sensorScaffold(
                SubsystemHardwareDocument(
                    hardwareId, displayName, kind, connection,
                    measurements = listOf(SubsystemMeasurementDocument(fieldId("argb"), SubsystemMeasurementSource.COLOR_ARGB)),
                ),
                SubsystemStateFieldDocument(fieldId("argb"), "ARGB color", SubsystemValueType.INT, SubsystemFieldRole.MEASUREMENT, defaultInt = 0),
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
