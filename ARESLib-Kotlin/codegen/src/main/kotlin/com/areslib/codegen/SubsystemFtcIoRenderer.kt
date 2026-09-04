package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemMeasurementSource
import com.areslib.subsystem.SubsystemValueType

/** Renders FTC hardware adapters with cached reads and fail-closed output semantics. */
internal object SubsystemFtcIoRenderer {
    fun render(document: SubsystemDocument, pkg: String): String {
        val imports = linkedSetOf(
            "com.areslib.util.RobotClock",
            "com.qualcomm.robotcore.hardware.HardwareMap",
        )
        document.hardware.forEach { device ->
            imports += when (device.kind) {
                SubsystemHardwareKind.MOTOR -> "com.qualcomm.robotcore.hardware.DcMotorEx"
                SubsystemHardwareKind.POSITIONAL_SERVO,
                SubsystemHardwareKind.INDICATOR_LIGHT,
                SubsystemHardwareKind.PRISM_DRIVER -> "com.qualcomm.robotcore.hardware.Servo"
                SubsystemHardwareKind.CONTINUOUS_SERVO -> "com.qualcomm.robotcore.hardware.CRServo"
                SubsystemHardwareKind.ABSOLUTE_ENCODER -> "com.qualcomm.robotcore.hardware.AnalogInput"
                SubsystemHardwareKind.QUADRATURE_ENCODER -> "com.qualcomm.robotcore.hardware.DcMotorEx"
                SubsystemHardwareKind.DIGITAL_INPUT -> "com.qualcomm.robotcore.hardware.DigitalChannel"
                SubsystemHardwareKind.ANALOG_INPUT -> "com.qualcomm.robotcore.hardware.AnalogInput"
                SubsystemHardwareKind.DISTANCE_SENSOR -> "com.qualcomm.robotcore.hardware.DistanceSensor"
                SubsystemHardwareKind.IMU -> "com.qualcomm.robotcore.hardware.IMU"
                SubsystemHardwareKind.COLOR_SENSOR -> "com.qualcomm.robotcore.hardware.ColorSensor"
                SubsystemHardwareKind.SOLENOID,
                SubsystemHardwareKind.DIGITAL_OUTPUT,
                SubsystemHardwareKind.PWM_OUTPUT,
                SubsystemHardwareKind.BUZZER -> error("${device.kind} is rejected by FTC validation")
            }
        }
        if (document.hardware.any { it.kind == SubsystemHardwareKind.DISTANCE_SENSOR }) {
            imports += "org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit"
        }
        if (document.hardware.any { it.kind == SubsystemHardwareKind.IMU }) {
            imports += "org.firstinspires.ftc.robotcore.external.navigation.AngleUnit"
            imports += "com.qualcomm.hardware.rev.RevHubOrientationOnRobot"
        }
        if (document.hardware.any {
                it.inverted && (it.kind == SubsystemHardwareKind.MOTOR ||
                    it.kind == SubsystemHardwareKind.CONTINUOUS_SERVO)
            }
        ) {
            imports += "com.qualcomm.robotcore.hardware.DcMotorSimple"
        }
        if (document.hardware.any { device -> device.measurements.any { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS } }) {
            imports += "org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit"
        }
        val fields = document.hardware.joinToString("\n") { device ->
            val type = device.ftcType()
            val name = requireNotNull(device.connection.hardwareMapName)
            val initializer = if (device.required) {
                "hardwareMap.get($type::class.java, ${name.quoted()})"
            } else {
                "try { hardwareMap.get($type::class.java, ${name.quoted()}) } catch (_: Exception) { null }"
            }
            "    private val ${device.hardwareId}: $type? = $initializer"
        }
        val cached = document.hardware.flatMap { it.measurements }.mapNotNull { measurement ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            "    private var cached${field.fieldId.pascalCase()}: ${field.kotlinType()} = ${field.defaultKotlinLiteral()}\n" +
                "    override val ${field.fieldId}: ${field.kotlinType()} get() = cached${field.fieldId.pascalCase()}"
        }.distinct().joinToString("\n")
        val commandFields = document.hardware.filter { it.kind.isActuator() }.joinToString("\n") { device ->
            "    private var ${device.hardwareId}Command: Double = ${requireNotNull(device.safeOutput).kotlinDouble()}"
        }
        val configure = document.hardware.mapNotNull { device ->
            when {
                (device.kind == SubsystemHardwareKind.MOTOR ||
                    device.kind == SubsystemHardwareKind.CONTINUOUS_SERVO) && device.inverted ->
                    "            ${device.hardwareId}?.direction = DcMotorSimple.Direction.REVERSE"
                (device.kind == SubsystemHardwareKind.POSITIONAL_SERVO ||
                    device.kind == SubsystemHardwareKind.INDICATOR_LIGHT ||
                    device.kind == SubsystemHardwareKind.PRISM_DRIVER) && device.inverted ->
                    "            ${device.hardwareId}?.direction = Servo.Direction.REVERSE"
                device.kind == SubsystemHardwareKind.DIGITAL_INPUT ->
                    "            ${device.hardwareId}?.mode = DigitalChannel.Mode.INPUT"
                device.kind == SubsystemHardwareKind.IMU -> {
                    val logo = requireNotNull(device.imuLogoFacingDirection)
                    val usb = requireNotNull(device.imuUsbFacingDirection)
                    """            ${device.hardwareId}?.let { imu ->
                require(imu.initialize(IMU.Parameters(RevHubOrientationOnRobot(
                    RevHubOrientationOnRobot.LogoFacingDirection.$logo,
                    RevHubOrientationOnRobot.UsbFacingDirection.$usb,
                )))) { ${("Failed to initialize ${device.displayName} with the declared Control Hub orientation").quoted()} }
            }"""
                }
                else -> null
            }
        }.joinToString("\n").ifBlank { "            // No one-time device configuration is required." }
        val readings = document.hardware.flatMap { device -> device.measurements.map { device to it } }.mapNotNull { (device, measurement) ->
            val field = document.field(measurement.fieldId) ?: return@mapNotNull null
            val read = when (measurement.source) {
                SubsystemMeasurementSource.MOTOR_POSITION_NATIVE ->
                    "${device.hardwareId}?.currentPosition?.toDouble() ?: 0.0"
                SubsystemMeasurementSource.MOTOR_VELOCITY_NATIVE_PER_SECOND ->
                    "${device.hardwareId}?.velocity ?: 0.0"
                SubsystemMeasurementSource.MOTOR_CURRENT_AMPS ->
                    "${device.hardwareId}?.getCurrent(CurrentUnit.AMPS) ?: Double.NaN"
                SubsystemMeasurementSource.ENCODER_POSITION_TURNS -> when (device.kind) {
                    SubsystemHardwareKind.ABSOLUTE_ENCODER ->
                        "${device.hardwareId}?.let { input -> input.voltage / input.maxVoltage } ?: Double.NaN"
                    SubsystemHardwareKind.QUADRATURE_ENCODER ->
                        "${device.hardwareId}?.currentPosition?.toDouble()?.div(${requireNotNull(device.encoderCountsPerRevolution).kotlinDouble()}) ?: Double.NaN"
                    else -> error("Validated encoder position is attached to ${device.kind}")
                }
                SubsystemMeasurementSource.ENCODER_VELOCITY_TURNS_PER_SECOND ->
                    "${device.hardwareId}?.velocity?.div(${requireNotNull(device.encoderCountsPerRevolution).kotlinDouble()}) ?: Double.NaN"
                SubsystemMeasurementSource.DIGITAL_STATE -> "${device.hardwareId}?.state ?: false"
                SubsystemMeasurementSource.ANALOG_VOLTAGE -> "${device.hardwareId}?.voltage ?: 0.0"
                SubsystemMeasurementSource.REFLECTANCE_NORMALIZED,
                SubsystemMeasurementSource.IMU_PITCH_RADIANS,
                SubsystemMeasurementSource.IMU_ROLL_RADIANS,
                SubsystemMeasurementSource.IMU_GYRO_X_RADIANS_PER_SECOND,
                SubsystemMeasurementSource.IMU_GYRO_Y_RADIANS_PER_SECOND,
                SubsystemMeasurementSource.IMU_ACCEL_X_METERS_PER_SECOND_SQUARED,
                SubsystemMeasurementSource.IMU_ACCEL_Y_METERS_PER_SECOND_SQUARED,
                SubsystemMeasurementSource.IMU_ACCEL_Z_METERS_PER_SECOND_SQUARED ->
                    error("${measurement.source} is rejected by FTC validation")
                SubsystemMeasurementSource.DISTANCE_METERS ->
                    "${device.hardwareId}?.getDistance(DistanceUnit.METER) ?: Double.NaN"
                SubsystemMeasurementSource.IMU_YAW_RADIANS ->
                    "${device.hardwareId}?.robotYawPitchRollAngles?.getYaw(AngleUnit.RADIANS) ?: Double.NaN"
                SubsystemMeasurementSource.IMU_YAW_RATE_RADIANS_PER_SECOND ->
                    "${device.hardwareId}?.getRobotAngularVelocity(AngleUnit.RADIANS)?.zRotationRate?.toDouble() ?: Double.NaN"
                SubsystemMeasurementSource.COLOR_ARGB -> "${device.hardwareId}?.argb() ?: 0"
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
            val assignment = (listOf(device to "requested") + document.followersOf(device.hardwareId).map { follower ->
                follower to follower.following!!.transformedExpression("requested")
            }).joinToString("\n            ") { (target, expression) ->
                val bounded = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "($expression).coerceIn(-12.0, 12.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "($expression).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.PRISM_DRIVER -> "($expression).coerceIn(500.0, 2500.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "($expression).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.SOLENOID -> error("FTC solenoids are rejected by validation")
                    else -> error("Not an actuator")
                }
                val write = when (target.kind) {
                    SubsystemHardwareKind.MOTOR -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.power = ${target.hardwareId}Command / 12.0"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.position = ${target.hardwareId}Command"
                    SubsystemHardwareKind.PRISM_DRIVER -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.position = ((${target.hardwareId}Command - 500.0) / 2000.0).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "requireNotNull(${target.hardwareId}) { ${("Missing ${target.displayName}").quoted()} }.power = ${target.hardwareId}Command"
                    SubsystemHardwareKind.SOLENOID -> error("FTC solenoids are rejected by validation")
                    else -> error("Not an actuator")
                }
                "${target.hardwareId}Command = $bounded\n            $write"
            }
            """    override fun ${device.commandName()}(value: Double) {
        val requested = value.takeIf(Double::isFinite) ?: $neutral
        if (outputFaultLatched && requested != $neutral) return
        if (requested != $neutral && (!configurationHealthy || !homed || !calibrated ||
                !feedbackValid || !currentReadingValid)) return
        try {
            $assignment
        } catch (_: Exception) {
                    outputFaultLatched = ${document.safety.latchOutputFaults}
            safe()
        }
    }"""
        }
        val neutralWrites = document.hardware.filter { it.kind.isActuator() }
            .joinToString("\n") { device ->
                val neutral = requireNotNull(device.safeOutput).kotlinDouble()
                val assignment = when (device.kind) {
                    SubsystemHardwareKind.MOTOR -> "${device.hardwareId}?.power = ($neutral / 12.0).coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.POSITIONAL_SERVO,
                    SubsystemHardwareKind.INDICATOR_LIGHT -> "${device.hardwareId}?.position = $neutral.coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.PRISM_DRIVER -> "${device.hardwareId}?.position = (($neutral - 500.0) / 2000.0).coerceIn(0.0, 1.0)"
                    SubsystemHardwareKind.CONTINUOUS_SERVO -> "${device.hardwareId}?.power = $neutral.coerceIn(-1.0, 1.0)"
                    SubsystemHardwareKind.SOLENOID -> error("FTC solenoids are rejected by validation")
                    else -> error("Not an FTC actuator")
                }
                "        try { $assignment; ${device.hardwareId}Command = $neutral } catch (_: Exception) { succeeded = false }"
            }
            .ifBlank { "        // Sensor-only subsystem: neutral is already satisfied." }
        return """
            package $pkg

            ${imports.sorted().joinToString("\n") { "import $it" }}

            /**
             * FTC adapter starter. All SDK reads occur in [refresh], all getters are cached, and
             * failed writes latch until [recoverWithNeutral] successfully applies every neutral.
             */
            class Ftc${document.kotlinTypeName}IO(hardwareMap: HardwareMap) : ${document.kotlinTypeName}IO {
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
            $configure
                        true
                    } catch (_: Exception) {
                        false
                    }
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

${SubsystemIoRenderingSupport.homingMethods(document, isFtc = true)}

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
                }
            }
        """.trimIndent() + "\n"
    }
}
