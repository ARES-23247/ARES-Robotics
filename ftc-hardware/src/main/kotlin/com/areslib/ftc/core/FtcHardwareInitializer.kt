package com.areslib.ftc.core

import com.qualcomm.robotcore.hardware.HardwareMap
import com.areslib.ftc.hardware.FtcHardwareMapInitializer
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.vision.VisionIO

/**
 * Lazy hardware initializer managing sensor IO abstractions for FTC target platforms.
 *
 * Instantiates lazy delegates for the GoBilda Pinpoint odometry computer ([pinpointIO]), Control Hub IMU ([imuIO]),
 * and Limelight 3A vision camera ([limelightIO]), applying physical offset parameters ($mm$, $ticks/mm$) and heading polarity.
 *
 * ### Physical Units & Hardware Boundaries:
 * - **GoBilda Pinpoint**: Mounting offsets $X, Y$ in millimeters ($mm$), encoder resolution in $ticks/mm$.
 *   Heading polarity defaults to CCW-positive standard (`isCcwPositive`).
 * - **Control Hub IMU**: Micro-electromechanical System (MEMS) gyro providing heading and angular velocity ($rad, rad/s$).
 * - **Limelight 3A**: 3D AprilTag pose tracking stream.
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 * @param pinpointName Hardware map name for GoBilda Pinpoint computer (default `"pinpoint"`). Pass `null` if unattached.
 * @param limelightName Hardware map name for Limelight camera (default `"limelight"`). Pass `null` if unattached.
 * @param imuName Hardware map name for Control Hub IMU (default `"imu"`). Pass `null` if unattached.
 * @param pinpointXOffsetMm Pinpoint computer physical mounting offset along robot X-axis ($mm$).
 * @param pinpointYOffsetMm Pinpoint computer physical mounting offset along robot Y-axis ($mm$).
 * @param pinpointEncoderResolution Pinpoint odometry wheel encoder resolution ($ticks/mm$).
 * @param pinpointXDirection Encoder direction for X pod.
 * @param pinpointYDirection Encoder direction for Y pod.
 * @param pinpointIsCcwPositive Physical mounting polarity flag. Set `true` if mounting orientation outputs CCW+ heading natively.
 *
 * @see PinpointIO
 * @see FtcHardwareMapInitializer
 */
class FtcHardwareInitializer(
    private val hardwareMap: HardwareMap,
    private val pinpointName: String? = "pinpoint",
    private val limelightName: String? = "limelight",
    private val imuName: String? = "imu",
    private val pinpointXOffsetMm: Double = 0.0,
    private val pinpointYOffsetMm: Double = 0.0,
    private val pinpointEncoderResolution: Double? = null,
    private val pinpointXDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    private val pinpointYDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    private val pinpointIsCcwPositive: Boolean = true
) {
    /** Lazy-initialized GoBilda Pinpoint odometry IO interface. */
    val pinpointIO: PinpointIO? by lazy {
        FtcHardwareMapInitializer.initPinpoint(
            hardwareMap = hardwareMap,
            pinpointName = pinpointName,
            xOffsetMm = pinpointXOffsetMm,
            yOffsetMm = pinpointYOffsetMm,
            encoderResolution = pinpointEncoderResolution,
            xDirection = pinpointXDirection,
            yDirection = pinpointYDirection,
            isCcwPositive = pinpointIsCcwPositive
        )
    }

    /** Lazy-initialized Control Hub IMU IO interface. */
    val imuIO: ImuIO? by lazy {
        FtcHardwareMapInitializer.initImu(hardwareMap, imuName)
    }

    /** Lazy-initialized Limelight 3A vision IO interface. */
    val limelightIO: VisionIO? by lazy {
        FtcHardwareMapInitializer.initLimelight(hardwareMap, limelightName)
    }

    /**
     * Safely closes hardware IO resources and unregisters motor instances from the global registry.
     */
    fun close() {
        pinpointIO?.close()
        try {
            (imuIO as? AutoCloseable)?.close()
        } catch (_: Exception) {}
        try {
            (limelightIO as? AutoCloseable)?.close()
        } catch (_: Exception) {}
        com.areslib.hardware.HardwareRegistry.closeAll()
        com.areslib.ftc.hardware.FtcMotor.unregisterAll()
    }
}
