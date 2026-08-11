package com.areslib.ftc.config

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.hardware.limelightvision.Limelight3A
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor
import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.CompositeVisionIO
import com.areslib.ftc.vision.FtcLimelightIO
import com.areslib.ftc.vision.FtcVisionPortalIO

/**
 * Central hardware dependency injector and factory for FTC target platforms.
 *
 * Encapsulates Qualcomm `hardwareMap.get` calls and abstracts vision hardware initialization away from OpModes.
 * Automatically wraps single or multi-camera setups (Limelight 3A via [FtcLimelightIO] or [CompositeVisionIO])
 * and FTC SDK [AprilTagProcessor] pipelines via [FtcVisionPortalIO].
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 *
 * @see FtcLimelightIO
 * @see CompositeVisionIO
 * @see FtcVisionPortalIO
 */
class RobotConfig(private val hardwareMap: HardwareMap) {

    /**
     * Instantiates a single or multi-camera [VisionIO] wrapper for Limelight 3A hardware.
     *
     * Supports comma-separated device names (e.g. `"limelight_front, limelight_back"`).
     * If multiple names are passed, wraps individual [FtcLimelightIO] instances in a single [CompositeVisionIO].
     *
     * @param deviceName Comma-separated hardware map name string for Limelight camera(s). Defaults to `"limelight"`.
     * @return Initialized [VisionIO] interface instance.
     */
    fun getLimelight(deviceName: String = "limelight"): VisionIO {
        val names = deviceName.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (names.size > 1) {
            val ios = names.map { name ->
                val ll = hardwareMap.get(Limelight3A::class.java, name)
                FtcLimelightIO(ll, sourceId = name)
            }
            CompositeVisionIO(ios)
        } else {
            val name = names.firstOrNull() ?: "limelight"
            val ll = hardwareMap.get(Limelight3A::class.java, name)
            FtcLimelightIO(ll, sourceId = name)
        }
    }
    
    /**
     * Instantiates a [VisionIO] wrapper for an active FTC SDK [AprilTagProcessor].
     *
     * @param processor Pre-configured and built [AprilTagProcessor] instance from VisionPortal.
     * @return Initialized [FtcVisionPortalIO] interface instance.
     */
    fun getAprilTagVision(processor: AprilTagProcessor): VisionIO {
        return FtcVisionPortalIO(processor)
    }
    
    // Future expansion: getDriveMotors(), getImu(), etc.
}

