package com.areslib.codegen

import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFollowerDocument
import com.areslib.subsystem.SubsystemFollowerTransform
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind

/** Shared hardware and follower expressions used consistently by runtime and test renderers. */
internal fun SubsystemHardwareKind.isActuator(): Boolean = this == SubsystemHardwareKind.MOTOR ||
    this == SubsystemHardwareKind.POSITIONAL_SERVO || this == SubsystemHardwareKind.CONTINUOUS_SERVO ||
    this == SubsystemHardwareKind.DIGITAL_OUTPUT || this == SubsystemHardwareKind.PWM_OUTPUT ||
    this == SubsystemHardwareKind.INDICATOR_LIGHT || this == SubsystemHardwareKind.PRISM_DRIVER ||
    this == SubsystemHardwareKind.BUZZER || this == SubsystemHardwareKind.SOLENOID

internal fun SubsystemHardwareDocument.commandName(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "set${hardwareId.pascalCase()}Voltage"
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT -> "set${hardwareId.pascalCase()}Position"
    SubsystemHardwareKind.PRISM_DRIVER -> "set${hardwareId.pascalCase()}PulseWidthUs"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "set${hardwareId.pascalCase()}Power"
    SubsystemHardwareKind.DIGITAL_OUTPUT -> "set${hardwareId.pascalCase()}Active"
    SubsystemHardwareKind.PWM_OUTPUT -> "set${hardwareId.pascalCase()}DutyCycle"
    SubsystemHardwareKind.BUZZER -> "set${hardwareId.pascalCase()}MidiNote"
    SubsystemHardwareKind.SOLENOID -> "set${hardwareId.pascalCase()}Active"
    else -> error("$kind is not an actuator")
}

internal fun SubsystemHardwareDocument.ftcType(): String = when (kind) {
    SubsystemHardwareKind.MOTOR -> "DcMotorEx"
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.PRISM_DRIVER -> "Servo"
    SubsystemHardwareKind.CONTINUOUS_SERVO -> "CRServo"
    SubsystemHardwareKind.ABSOLUTE_ENCODER -> "AnalogInput"
    SubsystemHardwareKind.QUADRATURE_ENCODER -> "DcMotorEx"
    SubsystemHardwareKind.DIGITAL_INPUT -> "DigitalChannel"
    SubsystemHardwareKind.ANALOG_INPUT -> "AnalogInput"
    SubsystemHardwareKind.DISTANCE_SENSOR -> "DistanceSensor"
    SubsystemHardwareKind.IMU -> "IMU"
    SubsystemHardwareKind.COLOR_SENSOR -> "ColorSensor"
    SubsystemHardwareKind.SOLENOID,
    SubsystemHardwareKind.DIGITAL_OUTPUT,
    SubsystemHardwareKind.PWM_OUTPUT,
    SubsystemHardwareKind.BUZZER -> error("$kind has no generated FTC adapter")
}

internal fun SubsystemDocument.actuatorLeaders(): List<SubsystemHardwareDocument> =
    hardware.filter { it.kind.isActuator() && it.following == null }

internal fun SubsystemDocument.followersOf(leaderId: String): List<SubsystemHardwareDocument> =
    hardware.filter { it.following?.leaderId == leaderId }

internal fun SubsystemFollowerDocument.transformedExpression(requested: String): String = when (transform) {
    SubsystemFollowerTransform.SAME_DIRECTION -> requested
    SubsystemFollowerTransform.INVERTED -> "-($requested)"
    SubsystemFollowerTransform.MIRRORED_POSITION -> "1.0 - ($requested)"
}

/**
 * Converts a logical mechanism command into the direction applied by this physical device.
 * Relationship transforms are evaluated first, then mounting inversion is applied.
 */
internal fun SubsystemHardwareDocument.invertedExpression(requested: String): String {
    if (!inverted) return requested
    return when (kind) {
        SubsystemHardwareKind.MOTOR,
        SubsystemHardwareKind.CONTINUOUS_SERVO -> "-($requested)"
        SubsystemHardwareKind.POSITIONAL_SERVO,
        SubsystemHardwareKind.INDICATOR_LIGHT,
        SubsystemHardwareKind.SOLENOID -> "1.0 - ($requested)"
        SubsystemHardwareKind.PRISM_DRIVER -> "3000.0 - ($requested)"
        else -> requested
    }
}

