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
    init {
        require(name.isNotBlank()) { "Module name must not be blank" }
        require(driveId.isNotBlank() && steerId.isNotBlank() && encoderId.isNotBlank()) {
            "Module hardware identifiers must not be blank"
        }
        require(positionXMeters.isFinite() && positionYMeters.isFinite() && offsetRotations.isFinite()) {
            "Module geometry and calibration must be finite"
        }
    }

    /**
     * Nonnegative numeric CAN identity for hardware setup. FTC hardware names are retained verbatim but
     * reject CAN access. Vendor-specific ID limits and bus/device collisions require platform validation.
     */
    val driveCanId: Int get() {
        val parsedDriveCanId = driveId.toIntOrNull() ?: -1
        require(parsedDriveCanId >= 0) { "Drive identifier is not a nonnegative CAN ID: $driveId" }
        return parsedDriveCanId
    }
    /** See [driveCanId]. */
    val steerCanId: Int get() {
        val parsedSteerCanId = steerId.toIntOrNull() ?: -1
        require(parsedSteerCanId >= 0) { "Steer identifier is not a nonnegative CAN ID: $steerId" }
        return parsedSteerCanId
    }
    /** See [driveCanId]. */
    val encoderCanId: Int get() {
        val parsedEncoderCanId = encoderId.toIntOrNull() ?: -1
        require(parsedEncoderCanId >= 0) { "Encoder identifier is not a nonnegative CAN ID: $encoderId" }
        return parsedEncoderCanId
    }

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
        driveId = driveCanId.also { require(it >= 0) { "Drive CAN ID must be nonnegative" } }.toString(),
        steerId = steerCanId.also { require(it >= 0) { "Steer CAN ID must be nonnegative" } }.toString(),
        encoderId = encoderCanId.also { require(it >= 0) { "Encoder CAN ID must be nonnegative" } }.toString(),
        positionXMeters = positionXMeters,
        positionYMeters = positionYMeters,
        driveInverted = driveInverted,
        steerInverted = steerInverted,
        encoderInverted = encoderInverted,
        offsetRotations = offsetRotations
    )
}
