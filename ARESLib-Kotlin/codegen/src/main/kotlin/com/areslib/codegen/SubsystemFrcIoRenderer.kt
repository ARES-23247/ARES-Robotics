package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemValueType

/** Renders FRC vendor/WPILib adapters with cached reads and fail-closed output semantics. */
internal object SubsystemFrcIoRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf(
            "com.areslib.hardware.HardwareRegistry",
            "com.areslib.util.RobotClock",
        )
        document.hardware.forEach { device ->
            imports += when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "com.ctre.phoenix6.hardware.TalonFX"
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.PRISM_DRIVER -> "edu.wpi.first.wpilibj.Servo"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "edu.wpi.first.wpilibj.motorcontrol.PWMSparkMax"
                SubsystemHardwareKind.ABSOLUTE_ENCODER -> "edu.wpi.first.wpilibj.DutyCycleEncoder"
                SubsystemHardwareKind.QUADRATURE_ENCODER -> "edu.wpi.first.wpilibj.Encoder"
                SubsystemHardwareKind.DIGITAL_INPUT -> "edu.wpi.first.wpilibj.DigitalInput"
                SubsystemHardwareKind.ANALOG_INPUT -> "edu.wpi.first.wpilibj.AnalogInput"
                SubsystemHardwareKind.DISTANCE_SENSOR -> "edu.wpi.first.wpilibj.AnalogInput"
                SubsystemHardwareKind.IMU -> "edu.wpi.first.wpilibj.ADXRS450_Gyro"
                SubsystemHardwareKind.SOLENOID -> "edu.wpi.first.wpilibj.Solenoid"
                SubsystemHardwareKind.COLOR_SENSOR -> error("FRC color sensors are rejected by validation")
            }
        }
        if (document.hardware.any { it.kind == SubsystemHardwareKind.SOLENOID }) {
            imports += "edu.wpi.first.wpilibj.PneumaticsModuleType"
        }
        if (document.hardware.any { it.kind == SubsystemHardwareKind.MOTOR }) {
            imports += "com.ctre.phoenix6.configs.TalonFXConfiguration"
        }
        val fields = document.hardware.joinToString("\n") { device ->
            val constructor = when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "TalonFX(${device.connection.canId}, ${device.connection.canBus.quoted()})"
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.PRISM_DRIVER -> "Servo(${device.connection.channel})"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "PWMSparkMax(${device.connection.channel})"
                SubsystemHardwareKind.ABSOLUTE_ENCODER -> "DutyCycleEncoder(${device.connection.channel})"
                SubsystemHardwareKind.QUADRATURE_ENCODER ->
                    "Encoder(${device.connection.channel}, ${device.connection.secondaryChannel})"
                SubsystemHardwareKind.DIGITAL_INPUT -> "DigitalInput(${device.connection.channel})"
                SubsystemHardwareKind.ANALOG_INPUT -> "AnalogInput(${device.connection.channel})"
                SubsystemHardwareKind.DISTANCE_SENSOR -> "AnalogInput(${device.connection.channel})"
                SubsystemHardwareKind.IMU -> "ADXRS450_Gyro()"
                SubsystemHardwareKind.SOLENOID -> {
                    val module = when (device.connection.pneumaticsModuleType) {
                        com.areslib.subsystem.SubsystemPneumaticsModuleType.REV_PH -> "PneumaticsModuleType.REVPH"
                        com.areslib.subsystem.SubsystemPneumaticsModuleType.CTRE_PCM -> "PneumaticsModuleType.CTREPCM"
                        null -> error("Validated solenoid requires a pneumatics module type")
                    }
                    "Solenoid(${device.connection.canId}, $module, ${device.connection.channel})"
                }
                SubsystemHardwareKind.COLOR_SENSOR -> error("Unsupported")
            }
            "    private val ${device.hardwareId} = $constructor"
        }
        val cached = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val commandFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    private var ${device.hardwareId}Command: Double = ${requireNotNull(device.safeOutput).kotlinDouble()}"
        }
        val init = document.hardware.filter {
            it.kind == SubsystemHardwareKind.MOTOR ||
                (it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO && it.inverted) ||
                it.kind == SubsystemHardwareKind.QUADRATURE_ENCODER
        }
            .joinToString("\n") { device ->
                if (device.kind == SubsystemHardwareKind.QUADRATURE_ENCODER) {
                    return@joinToString "            ${device.hardwareId}.distancePerPulse = 1.0 / ${requireNotNull(device.encoderCountsPerRevolution).kotlinDouble()}"
                }
                if (device.kind == SubsystemHardwareKind.CONTINUOUS_SERVO) {
                    return@joinToString "        ${device.hardwareId}.setInverted(true)"
                }
                val configName = "${device.hardwareId}Configuration"
                buildString {
                    append("        val $configName = TalonFXConfiguration()\n")
                    if (device.inverted) {
                        append("        $configName.MotorOutput.Inverted = com.ctre.phoenix6.signals.InvertedValue.Clockwise_Positive\n")
                    }
                    device.currentLimitAmps?.let { limit ->
                        append("        $configName.CurrentLimits.SupplyCurrentLimitEnable = true\n")
                        append("        $configName.CurrentLimits.SupplyCurrentLimit = ${limit.kotlinDouble()}\n")
                    }
                    append("            check(${device.hardwareId}.configurator.apply($configName).isOK) { ${("Failed to configure ${device.displayName}").quoted()} }\n")
                    append("            ${device.hardwareId}.optimizeBusUtilization()")
                }
            }
            .ifBlank { "            // No TalonFX configuration is required." }
        val readings = document.hardware.flatMap { device -> device.measurements.map { device to it } }.mapNotNull { (device, measurement) ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            val read = when (measurement.source) {
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE -> "${device.hardwareId}.position.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND -> "${device.hardwareId}.velocity.valueAsDouble"
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS -> "${device.hardwareId}.statorCurrent.valueAsDouble"
                SubsystemMeasurementSource.ENCODER_POSITION_TURNS -> when (device.kind) {
                    SubsystemHardwareKind.ABSOLUTE_ENCODER -> "${device.hardwareId}.get()"
                    SubsystemHardwareKind.QUADRATURE_ENCODER -> "${device.hardwareId}.distance"
                    else -> error("Validated encoder position is attached to ${device.kind}")
                }
                SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND -> "${device.hardwareId}.rate"
                SubsystemMeasurementSource.DIGITAL_STATE -> "${device.hardwareId}.get()"
                SubsystemMeasurementSource.ANALOG_VOLTAGE -> "${device.hardwareId}.voltage"
                SubsystemMeasurementSource.DISTANCE_METERS ->
                    "${device.hardwareId}.voltage * ${requireNotNull(device.distanceMetersPerVolt).kotlinDouble()}"
                // WPILib Gyro raw angle/rate are clockwise-positive. Negate at the hardware
                // boundary so generated state preserves the ARES CCW-positive convention.
                SubsystemMeasurementSource.IMU_YAW_RADIANS -> "Math.toRadians(-${device.hardwareId}.angle)"
                SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND -> "Math.toRadians(-${device.hardwareId}.rate)"
                SubsystemMeasurementSource.COLOR_ARGB -> error("FRC color sensors are rejected by validation")
            }
            val converted = if (field.type == SubsystemValueType.DOUBLE) {
                "($read) * ${measurement.scale.kotlinDouble()} + ${measurement.offset.kotlinDouble()}"
            } else read
            val next = "next${field.fieldId.pascalCase()}"
            val finiteCheck = if (field.type == SubsystemValueType.DOUBLE) {
                buildString {
                    append("\n            require($next.isFinite()) { ${("Non-finite ${field.displayName}").quoted()} }")
                    measurement.validMinimum?.let { append("\n            require($next >= ${it.kotlinDouble()}) { ${("${field.displayName} below its valid minimum").quoted()} }") }
                    measurement.validMaximum?.let { append("\n            require($next <= ${it.kotlinDouble()}) { ${("${field.displayName} above its valid maximum").quoted()} }") }
                }
            } else ""
            "            val $next = $converted$finiteCheck"
        }.distinct().joinToString("\n").ifBlank { "            // This subsystem has no readable sensors." }
        val commitReadings = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            document.field(measurement.fieldId)?.let { field ->
                "            cached${field.fieldId.pascalCase()} = next${field.fieldId.pascalCase()}"
            }
        }.distinct().joinToString("\n")
        val homingCondition = SubsystemIoRenderingSupport.homingConditionExpression(document, "cached")
        val currentFields = document.hardware.flatMap { device ->
            device.measurements.filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
        }.map { it.fieldId }.distinct()
        val currentValidity = if (document.safety.requiresCurrentMonitoring) {
            currentFields.joinToString(" && ") {
                "cached${it.pascalCase()}.isFinite() && cached${it.pascalCase()} >= 0.0"
            }.ifBlank { "false" }
        } else "true"
        val telemetry = SubsystemIoRenderingSupport.telemetryBody(document)
        val commands = document.actuatorLeaders().joinToString("\n\n") { device ->
            val neutral = requireNotNull(device.safeOutput).kotlinDouble()
            val command = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n            ") { (target, expression) ->
                val applied = if (target.kind == SubsystemHardwareKind.POSITIONAL_SERVO ||
                    target.kind == SubsystemHardwareKind.INDICATOR_LIGHT ||
                    target.kind == SubsystemHardwareKind.PRISM_DRIVER ||
                    target.kind == SubsystemHardwareKind.SOLENOID
                ) {
                    target.invertedExpression(expression)
                } else {
                    expression
                }
                val bounded = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "($applied).coerceIn(-12.0, 12.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "($applied).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.PRISM_DRIVER -> "($applied).coerceIn(500.0, 2500.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "($applied).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.SOLENOID -> "($applied).coerceIn(0.0, 1.0)"
                    else -> error("Not actuator")
                }
                val write = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "${target.hardwareId}.setVoltage(${target.hardwareId}Command)"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "${target.hardwareId}.set(${target.hardwareId}Command)"
                    SubsystemHardwareKind.PRISM_DRIVER -> "${target.hardwareId}.set(((${target.hardwareId}Command - 500.0) / 2000.0).coerceIn(0.0, 1.0))"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${target.hardwareId}.set(${target.hardwareId}Command)"
                    SubsystemHardwareKind.SOLENOID -> "${target.hardwareId}.set(${target.hardwareId}Command >= 0.5)"
                    else -> error("Not actuator")
                }
                "${target.hardwareId}Command = $bounded\n            $write"
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        try {
            $command
        } catch (_: Exception) {
            outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
        }
    }"""
        }
        val neutralWrites = document.hardware.filter { it.kind.isActuator() }
            .joinToString("\n") { device ->
                val neutral = requireNotNull(device.safeOutput).kotlinDouble()
                val appliedNeutral = if (device.kind == SubsystemHardwareKind.POSITIONAL_SERVO ||
                    device.kind == SubsystemHardwareKind.INDICATOR_LIGHT ||
                    device.kind == SubsystemHardwareKind.PRISM_DRIVER) {
                    device.invertedExpression(neutral)
                } else {
                    neutral
                }
                val command = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}.setVoltage($appliedNeutral.coerceIn(-12.0, 12.0))"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "${device.hardwareId}.set($appliedNeutral.coerceIn(0.0, 1.0))"
                    SubsystemHardwareKind.PRISM_DRIVER -> "${device.hardwareId}.set((($appliedNeutral - 500.0) / 2000.0).coerceIn(0.0, 1.0))"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}.set($appliedNeutral.coerceIn(-1.0, 1.0))"
                    SubsystemHardwareKind.SOLENOID -> "${device.hardwareId}.set($appliedNeutral >= 0.5)"
                    else -> error("Not an FRC actuator")
                }
                "        try { $command; ${device.hardwareId}Command = $appliedNeutral } catch (_: Exception) { succeeded = false }"
            }
            .ifBlank { "        // Sensor-only subsystem: neutral is already satisfied." }
        val close = document.hardware.joinToString("\n") { device ->
            when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "        try { ${device.hardwareId}.close() } catch (_: Exception) { /* Continue closing. */ }"
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.CONTINUOUS_SERVO,
                SubsystemHardwareKind.ABSOLUTE_ENCODER,
                SubsystemHardwareKind.QUADRATURE_ENCODER,
                SubsystemHardwareKind.DIGITAL_INPUT,
                SubsystemHardwareKind.ANALOG_INPUT,
                SubsystemHardwareKind.DISTANCE_SENSOR,
                SubsystemHardwareKind.IMU,
                SubsystemHardwareKind.SOLENOID,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.PRISM_DRIVER -> "        try { ${device.hardwareId}.close() } catch (_: Exception) { /* Continue closing. */ }"
                SubsystemHardwareKind.COLOR_SENSOR -> ""
            }
        }
        return """
            package $pkg

            ${imports.sorted().joinToString("\n") { "import $it" }}

            /**
             * FRC adapter starter. All device reads occur in [refresh], configuration is checked,
             * and failed writes latch until [recoverWithNeutral] applies every declared neutral.
             */
            class Frc${document.kotlinTypeName}IO : ${document.kotlinTypeName}IO {
            $fields
            $cached
            $commandFields
                override var feedbackValid: Boolean = false
                    private set
                override var feedbackTimestampMs: Long = 0L
                    private set
                override var configurationHealthy: Boolean = false
                    private set
                override var homed: Boolean = ${(!document.requiresHoming())}
                    private set
                override var homingConditionMet: Boolean = false
                    private set
                override var homingFaultLatched: Boolean = false
                    private set
                override var calibrated: Boolean = ${(!document.safety.requiresCalibration)}
                    private set
                override var currentReadingValid: Boolean = ${(!document.safety.requiresCurrentMonitoring)}
                    private set
                override var outputFaultLatched: Boolean = false
                    private set
                private var closed = false

                init {
                    configurationHealthy = try {
            $init
                        true
                    } catch (_: Exception) {
                        false
                    }
                    HardwareRegistry.registerTelemetryDevice(${("Subsystems/${document.documentId}").quoted()}, this)
                }

                override fun refresh() {
                    if (closed) return
                    try {
            $readings
            $commitReadings
                        feedbackTimestampMs = RobotClock.currentTimeMillis()
                        feedbackValid = true
                        currentReadingValid = $currentValidity
                        homingConditionMet = $homingCondition
                    } catch (_: Exception) {
                        feedbackValid = false
                        currentReadingValid = ${(!document.safety.requiresCurrentMonitoring)}
                    }
                }

            $commands

                override fun safe() {
                    if (!applyNeutral()) outputFaultLatched = ${document.safety.latchOutputFaults}
                }

                override fun recoverWithNeutral(): Boolean {
                    val recovered = applyNeutral()
                    if (recovered) outputFaultLatched = false
                    return recovered
                }

${SubsystemIoRenderingSupport.automaticRecoveryMethods(document)}

                override fun establishCalibration() {
                    if (configurationHealthy) calibrated = true
                }

${SubsystemIoRenderingSupport.homingMethods(document, isFtc = false)}

                private fun applyNeutral(): Boolean {
                    var succeeded = true
            $neutralWrites
                    return succeeded
                }

                override fun logTelemetry(telemetry: com.areslib.telemetry.ITelemetry, prefix: String) {
$telemetry
                }

                override fun close() {
                    if (closed) return
                    closed = true
                    safe()
            $close
                }
            }
        """.trimIndent() + "\n"
    }
}
