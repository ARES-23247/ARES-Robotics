package com.ares.analytics.service.hardware

import com.ares.analytics.service.drivebase.DriveHardwareRole
import com.areslib.subsystem.SubsystemHardwareDocument
import com.areslib.subsystem.SubsystemHardwareKind

internal fun DriveHardwareRole.readableName(): String = name.lowercase().replace('_', ' ')

internal fun SubsystemHardwareKind.readableName(): String = name.lowercase().replace('_', ' ')

internal fun SubsystemHardwareDocument.addressKind(): HardwareAddressKind = when (kind) {
    SubsystemHardwareKind.MOTOR -> HardwareAddressKind.CAN
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
    SubsystemHardwareKind.PWM_OUTPUT,
    SubsystemHardwareKind.BUZZER,
    SubsystemHardwareKind.INDICATOR_LIGHT,
    SubsystemHardwareKind.PRISM_DRIVER -> HardwareAddressKind.PWM
    SubsystemHardwareKind.COLOR_SENSOR -> HardwareAddressKind.I2C
    SubsystemHardwareKind.DIGITAL_INPUT,
    SubsystemHardwareKind.DIGITAL_OUTPUT,
    SubsystemHardwareKind.QUADRATURE_ENCODER -> HardwareAddressKind.DIO
    SubsystemHardwareKind.ANALOG_INPUT,
    SubsystemHardwareKind.ABSOLUTE_ENCODER,
    SubsystemHardwareKind.DISTANCE_SENSOR -> HardwareAddressKind.ANALOG
    SubsystemHardwareKind.IMU -> HardwareAddressKind.SPI
    SubsystemHardwareKind.SOLENOID -> HardwareAddressKind.PNEUMATICS
}

internal fun SubsystemHardwareDocument.configurationDetails(): List<String> = buildList {
    following?.takeIf { it.leaderId.isNotBlank() }?.let { follower ->
        add("Follower of hardware ID: ${follower.leaderId} (${follower.transform.name.lowercase().replace('_', ' ')})")
    }
    encoderCountsPerRevolution?.let { add("Encoder resolution: ${formatSetupNumber(it)} counts/revolution") }
    distanceMetersPerVolt?.let { add("Distance calibration: ${formatSetupNumber(it)} meters/volt") }
    if (kind == SubsystemHardwareKind.IMU) {
        val logo = imuLogoFacingDirection
        val usb = imuUsbFacingDirection
        when {
            logo != null && usb != null -> add(
                "Control Hub mounting: logo faces ${logo.name.lowercase()}, USB faces ${usb.name.lowercase()}",
            )
            logo == null && usb == null ->
                add("Onboard SPI gyro; ARES converts the raw heading to CCW-positive radians")
            else -> error("$displayName has an incomplete IMU orientation declaration.")
        }
    }
    safeOutput?.let { add("Safe neutral output: ${formatSetupNumber(it)}") }
    currentLimitAmps?.let { add("Configured current limit: ${formatSetupNumber(it)} A") }
}

internal fun formatSetupNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

internal val MOTION_ACTUATOR_KINDS = setOf(
    SubsystemHardwareKind.MOTOR,
    SubsystemHardwareKind.POSITIONAL_SERVO,
    SubsystemHardwareKind.CONTINUOUS_SERVO,
)
