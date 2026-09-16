@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware.configuration.annotations

/**
 * Desktop-compatible metadata used by FTC hardware configuration discovery.
 *
 * Annotates user-defined and third-party hardware sensor or actuator classes so the
 * configuration subsystem can identify their XML element tags, human-readable display names,
 * and description strings when inspecting hardware maps.
 *
 * In desktop simulation, this metadata allows automated driver discovery and fixture setup
 * without requiring the full Android APK manifest inspection layer.
 *
 * @property name Human-readable device name shown in configuration interfaces.
 * @property xmlTag XML element tag name used in robot configuration files.
 * @property description Detailed explanation of the hardware device and manufacturer.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeviceProperties(
    val name: String,
    val xmlTag: String,
    val description: String
)

/**
 * Marks a desktop mock or hardware adapter as an FTC I2C device type.
 *
 * Used by hardware reflection registries to distinguish I2C communication devices
 * from analog/digital channels and serial USB peripherals during automated mapping.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class I2cDeviceType
