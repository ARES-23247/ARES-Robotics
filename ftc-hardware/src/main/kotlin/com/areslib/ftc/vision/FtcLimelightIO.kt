package com.areslib.ftc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.qualcomm.hardware.limelightvision.Limelight3A

/**
 * Limelight 3A hardware IO wrapper for AprilTag tracking, MegaTag2 field localization, and target alignment.
 *
 * Pre-allocates fixed object pools (`visionMeasurementPool`, `translationPool`, `rotationPool`, `posePool`) to guarantee
 * zero-GC heap allocations during high-frequency 50Hz update cycles.
 *
 * ### Limelight Target-Space Coordinate Frame:
 * - **$X+$**: Right of the AprilTag face in meters ($m$).
 * - **$Y+$**: Vertical height axis in meters ($m$).
 * - **$Z+$**: Distance/depth outward from tag face in meters ($m$).
 * - **Rotation Yaw**: Extracted from `rotation.y` (negated to align with **CCW-positive** robot heading standard).
 *
 * @param limelight Physical [Limelight3A] FTC hardware map instance.
 * @param cameraPoses List of 3D mounting transforms ([Pose3d]) of camera lenses relative to robot center.
 *
 * @see VisionIO
 * @see VisionIOInputs
 * @see VisionMeasurement
 */
class FtcLimelightIO(
    private val limelight: Limelight3A,
    override val cameraPoses: List<Pose3d> = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)))
) : VisionIO, AutoCloseable {
    
    private var lastWarningTime = 0L
    
    // Object pools to prevent GC overhead
    private val visionMeasurementPool = Array(10) { VisionMeasurement() }
    private val translationPool = Array(20) { Translation3d() }
    private val rotationPool = Array(20) { Rotation3d() }
    private val emptyTargetPose = Pose3d()
    private val posePool = Array(20) { Pose3d() }
    private var visionMeasurementPoolIndex = 0
    private var translationPoolIndex = 0
    private var rotationPoolIndex = 0
    private var posePoolIndex = 0

    private val measurementListPool = Array(10) { ArrayList<VisionMeasurement>(10) }
    private var measurementListPoolIndex = 0


    init {
        try {
            limelight.start()
        } catch (e: Throwable) {
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            System.err.println("FtcLimelightIO: Failed to start Limelight during initialization. Error: ${e.message}")
            e.printStackTrace()
            lastWarningTime = now
        }
    }

    /**
     * Polled 50Hz update cycle extracting latest AprilTag vision measurements into [inputs].
     *
     * @param inputs Pre-allocated [VisionIOInputs] structure receiving vision measurements in-place.
     */
    override fun updateInputs(inputs: VisionIOInputs) {

        inputs.cameraPoses = cameraPoses
        try {
            val result = limelight.getLatestResult()

            if (result != null && result.isValid()) {


                inputs.isConnected = true
                measurementListPoolIndex = (measurementListPoolIndex + 1) % measurementListPool.size
                val currentMeasurementList = measurementListPool[measurementListPoolIndex]
                currentMeasurementList.clear()

                val now = com.areslib.util.RobotClock.currentTimeMillis()
                val fiducials = result.getFiducialResults()
                
                val botposeRaw = result.getBotpose()

                if (botposeRaw != null) {
                    translationPoolIndex = (translationPoolIndex + 1) % translationPool.size
                    val fieldTrans = translationPool[translationPoolIndex]
                    fieldTrans.x = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(botposeRaw.position.unit, botposeRaw.position.x)
                    fieldTrans.y = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(botposeRaw.position.unit, botposeRaw.position.y)
                    fieldTrans.z = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(botposeRaw.position.unit, botposeRaw.position.z)

                    rotationPoolIndex = (rotationPoolIndex + 1) % rotationPool.size
                    val fieldRot = rotationPool[rotationPoolIndex]
                    fieldRot.setEulerAngles(
                        Math.toRadians(botposeRaw.orientation.getRoll(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                        Math.toRadians(botposeRaw.orientation.getPitch(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                        Math.toRadians(botposeRaw.orientation.getYaw(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES))
                    )

                    posePoolIndex = (posePoolIndex + 1) % posePool.size
                    val fieldPose = posePool[posePoolIndex]
                    fieldPose.translation = fieldTrans
                    fieldPose.rotation = fieldRot

                    for (fiducial in fiducials) {
                        val targetPoseRaw = fiducial.getRobotPoseTargetSpace()
                        translationPoolIndex = (translationPoolIndex + 1) % translationPool.size
                        val targetTrans = translationPool[translationPoolIndex]
                        targetTrans.x = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(targetPoseRaw.position.unit, targetPoseRaw.position.x)
                        targetTrans.y = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(targetPoseRaw.position.unit, targetPoseRaw.position.y)
                        targetTrans.z = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(targetPoseRaw.position.unit, targetPoseRaw.position.z)

                        rotationPoolIndex = (rotationPoolIndex + 1) % rotationPool.size
                        val targetRot = rotationPool[rotationPoolIndex]
                        targetRot.setEulerAngles(
                            Math.toRadians(targetPoseRaw.orientation.getRoll(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                            Math.toRadians(targetPoseRaw.orientation.getPitch(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                            Math.toRadians(targetPoseRaw.orientation.getYaw(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES))
                        )

                        posePoolIndex = (posePoolIndex + 1) % posePool.size
                        val tPose = posePool[posePoolIndex]
                        tPose.translation = targetTrans
                        tPose.rotation = targetRot

                        visionMeasurementPoolIndex = (visionMeasurementPoolIndex + 1) % visionMeasurementPool.size
                        val measurement = visionMeasurementPool[visionMeasurementPoolIndex]

                        measurement.timestampMs = now - 11L
                        measurement.targetPose = fieldPose
                        measurement.robotPoseTargetSpace = tPose
                        measurement.tagId = fiducial.getFiducialId()
                        measurement.ambiguity = 0.1

                        currentMeasurementList.add(measurement)
                    }
                }

                inputs.measurements = currentMeasurementList
            } else {
                inputs.isConnected = result != null
                inputs.measurements = emptyList()
            }
        } catch (e: Throwable) {
            inputs.isConnected = false
            inputs.measurements = emptyList()
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("FtcLimelightIO: Exception in updateInputs: ${e.message}")
                lastWarningTime = now
            }
        }
    }

    /**
     * Releases vision resources.
     */
    override fun close() {
    }
}
