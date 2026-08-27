package com.areslib.subsystem

/**
 * Immutable configuration profile representing physical Mecanum drivetrain geometry, motor mappings, and limits.
 */
data class MecanumDrivetrainProfile(
    val flName: String = "fl",
    val frName: String = "fr",
    val blName: String = "bl",
    val brName: String = "br",
    val maxWheelSpeedMetersPerSecond: Double = 3.5,
    val trackWidthMeters: Double = 0.45,
    val wheelBaseMeters: Double = 0.45
)
