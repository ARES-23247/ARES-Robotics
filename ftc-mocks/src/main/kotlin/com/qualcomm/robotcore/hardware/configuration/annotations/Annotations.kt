@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.robotcore.hardware.configuration.annotations

/** Desktop-compatible metadata used by FTC hardware configuration discovery. */
annotation class DeviceProperties(
    val name: String,
    val xmlTag: String,
    val description: String
)

/** Marks a desktop mock as an FTC I2C device type. */
annotation class I2cDeviceType
