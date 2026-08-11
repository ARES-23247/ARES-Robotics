package com.areslib.ftc.hardware

import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.IMU
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.ftc.vision.FtcLimelightIO
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.vision.CompositeVisionIO
import com.areslib.hardware.vision.VisionIO

/**
 * Exception-safe hardware factory for querying and instantiating FTC hardware sensors from [HardwareMap].
 *
 * Provides factory methods for [PinpointIO], [ImuIO], and [VisionIO] (Limelight 3A), catching hardware missing exceptions
 * gracefully and returning `null` or [CompositeVisionIO] instances for multi-camera setups.
 *
 * @see PinpointIO
 * @see FtcLimelightIO
 * @see CompositeVisionIO
 */
object FtcHardwareMapInitializer {

    /**
     * Instantiates and calibrates a GoBilda Pinpoint Computer hardware IO interface.
     *
     * @param hardwareMap FTC OpMode hardware map instance.
     * @param pinpointName Hardware map name string for the Pinpoint device (or `null` to bypass initialization).
     * @param xOffsetMm Transverse X odometry pod offset relative to robot center in millimeters ($mm$).
     * @param yOffsetMm Longitudinal Y odometry pod offset relative to robot center in millimeters ($mm$).
     * @param encoderResolution Optional custom encoder tick resolution rating in ticks per millimeter ($ticks/mm$).
     * @param xDirection Encoder count polarity direction for the X pod (`FORWARD` vs `REVERSE`).
     * @param yDirection Encoder count polarity direction for the Y pod (`FORWARD` vs `REVERSE`).
     * @param isCcwPositive Set `true` if physical orientation yields CCW+ heading directly (default `false` for upside-down mounts).
     * @return Initialized [PinpointIO] instance with IMU recalibrated, or `null` if driver lookup fails.
     */
    fun initPinpoint(
        hardwareMap: HardwareMap,
        pinpointName: String?,
        xOffsetMm: Double,
        yOffsetMm: Double,
        encoderResolution: Double?,
        xDirection: GoBildaPinpointDriver.EncoderDirection,
        yDirection: GoBildaPinpointDriver.EncoderDirection,
        isCcwPositive: Boolean
    ): PinpointIO? {
        if (pinpointName == null) return null
        return try {
            val pinpointDriver = hardwareMap.get(GoBildaPinpointDriver::class.java, pinpointName)
            PinpointIO(
                driver = pinpointDriver,
                xOffsetMm = xOffsetMm,
                yOffsetMm = yOffsetMm,
                encoderResolution = encoderResolution,
                xDirection = xDirection,
                yDirection = yDirection,
                isHeadingCcwPositive = isCcwPositive
            ).apply { recalibrateIMU() }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Instantiates an internal IMU sensor wrapper from the FTC hardware map.
     *
     * @param hardwareMap FTC OpMode hardware map instance.
     * @param imuName Hardware map name string for the IMU device (or `null` to bypass initialization).
     * @return Initialized [ImuIO] wrapper instance, or `null` if driver lookup fails.
     */
    fun initImu(hardwareMap: HardwareMap, imuName: String?): ImuIO? {
        if (imuName == null) return null
        return try {
            val imuDriver = hardwareMap.get(IMU::class.java, imuName)
            FtcImu(imuDriver)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Instantiates single or multi-camera Limelight 3A vision tracker interfaces from the FTC hardware map.
     *
     * Supports comma-separated hardware names (e.g. `"limelight-front, limelight-back"`) to construct a [CompositeVisionIO].
     *
     * @param hardwareMap FTC OpMode hardware map instance.
     * @param limelightName Single hardware name or comma-separated name list string (or `null` to bypass).
     * @return Initialized single [VisionIO] or multi-camera [CompositeVisionIO] instance, or `null` if lookup fails.
     */
    fun initLimelight(hardwareMap: HardwareMap, limelightName: String?): VisionIO? {
        if (limelightName == null) return null
        return try {
            val names = limelightName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            when {
                names.size > 1 -> {
                    val ios = names.map { name ->
                        val limelightDriver = hardwareMap.get(Limelight3A::class.java, name)
                        FtcLimelightIO(limelightDriver, sourceId = name)
                    }
                    CompositeVisionIO(ios)
                }
                names.size == 1 -> {
                    val limelightDriver = hardwareMap.get(Limelight3A::class.java, names[0])
                    FtcLimelightIO(limelightDriver, sourceId = names[0])
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
