package com.areslib.drivetrain

/**
 * Platform-agnostic configuration for an individual swerve module.
 *
 * Supports both FTC (HardwareMap string names) and FRC (integer CAN IDs).
 *
 * @param name Descriptive identifier for the module (e.g. "FrontLeft", "fl").
 * @param driveId Identifier for the drive motor (HardwareMap name or CAN ID string).
 * @param steerId Identifier for the steer motor (HardwareMap name or CAN ID string).
 * @param encoderId Identifier for the absolute encoder (HardwareMap name or CAN ID string).
 * @param positionXMeters X coordinate relative to robot center in meters (+X forward).
 * @param positionYMeters Y coordinate relative to robot center in meters (+Y left).
 * @param driveInverted Whether the drive motor rotation is inverted.
 * @param steerInverted Whether the steer motor rotation is inverted.
 * @param encoderInverted Whether the absolute encoder direction is inverted.
 * @param offsetRotations Absolute zero calibration offset in rotations.
 */
data class SwerveModuleConfig(
    val name: String,
    val driveId: String,
    val steerId: String,
    val encoderId: String,
    val positionXMeters: Double,
    val positionYMeters: Double,
    val driveInverted: Boolean = false,
    val steerInverted: Boolean = false,
    val encoderInverted: Boolean = false,
    val offsetRotations: Double = 0.0
) {
    /** Helper getter for FRC CAN ID (parses string to Int). */
    val driveCanId: Int get() = driveId.toIntOrNull() ?: 0
    val steerCanId: Int get() = steerId.toIntOrNull() ?: 0
    val encoderCanId: Int get() = encoderId.toIntOrNull() ?: 0

    /** Secondary constructor accepting integer CAN IDs for FRC convenience. */
    constructor(
        name: String,
        driveCanId: Int,
        steerCanId: Int,
        encoderCanId: Int,
        positionXMeters: Double,
        positionYMeters: Double,
        driveInverted: Boolean = false,
        steerInverted: Boolean = false,
        encoderInverted: Boolean = false,
        offsetRotations: Double = 0.0
    ) : this(
        name = name,
        driveId = driveCanId.toString(),
        steerId = steerCanId.toString(),
        encoderId = encoderCanId.toString(),
        positionXMeters = positionXMeters,
        positionYMeters = positionYMeters,
        driveInverted = driveInverted,
        steerInverted = steerInverted,
        encoderInverted = encoderInverted,
        offsetRotations = offsetRotations
    )
}
