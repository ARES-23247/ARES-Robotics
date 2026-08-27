package com.areslib.drivetrain

/**
 * Master platform-agnostic configuration for a 4-module Swerve Drivetrain.
 *
 * Encapsulates kinematics dimensions, gear ratios, physical wheel radius,
 * and zero offset calibration data.
 *
 * @param trackWidthMeters Distance between left and right wheels in meters.
 * @param wheelBaseMeters Distance between front and back wheels in meters.
 * @param wheelRadiusMeters Radius of swerve wheels in meters.
 * @param driveGearRatio Total gear reduction from drive motor to wheel.
 * @param steerGearRatio Total gear reduction from steer motor to module.
 * @param offsets Calibration zero offsets for the 4 module encoders.
 * @param frontLeft Configuration for Front-Left module.
 * @param frontRight Configuration for Front-Right module.
 * @param backLeft Configuration for Back-Left module.
 * @param backRight Configuration for Back-Right module.
 */
data class SwerveConstants(
    val trackWidthMeters: Double = 0.552,
    val wheelBaseMeters: Double = 0.552,
    val wheelRadiusMeters: Double = 0.0508,
    val driveGearRatio: Double = 6.75,
    val steerGearRatio: Double = 12.8,
    val offsets: SwerveOffsetData = SwerveOffsetData(),
    val frontLeft: SwerveModuleConfig = SwerveModuleConfig("FrontLeft", 1, 2, 11, 0.276, 0.276),
    val frontRight: SwerveModuleConfig = SwerveModuleConfig("FrontRight", 3, 4, 12, 0.276, -0.276),
    val backLeft: SwerveModuleConfig = SwerveModuleConfig("BackLeft", 5, 6, 13, -0.276, 0.276),
    val backRight: SwerveModuleConfig = SwerveModuleConfig("BackRight", 7, 8, 14, -0.276, -0.276)
)
