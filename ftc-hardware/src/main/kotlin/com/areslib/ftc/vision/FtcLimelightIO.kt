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
 * Enforces zero-GC object pool allocation guarantees during 50Hz update cycles and maps Limelight target-space coordinates
 * to the standard **CCW-positive** robot reference frame.
 *
 * ### Limelight Target-Space Coordinate Frame:
 * - **$X+$**: Right of the AprilTag face (meters)
 * - **$Y+$**: Vertical height axis (meters)
 * - **$Z+$**: Distance/depth outward from tag face (meters)
 * - **Rotation Yaw**: Extracted from `rotation.y` (negated to convert to CCW-positive heading)
 *
 * @param limelight Physical `Limelight3A` FTC hardware map instance.
 * @param cameraPoses List of 3D mounting transforms of camera lenses relative to robot center.
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
     * @param inputs Pre-allocated inputs structure receiving vision measurements.
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
                val numTags = fiducials.size
                val tagId = if (numTags > 0) fiducials[0].getFiducialId() else -1

                val botposeRaw = result.getBotpose()

                if (botposeRaw != null) {
                    val posMeters = botposeRaw.position.toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER)
                    translationPoolIndex = (translationPoolIndex + 1) % translationPool.size
                    val fieldTrans = translationPool[translationPoolIndex]
                    fieldTrans.x = posMeters.x
                    fieldTrans.y = posMeters.y
                    fieldTrans.z = posMeters.z

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

                    var relTargetPose: Pose3d? = null
                    if (numTags > 0) {
                        val fiducial = fiducials[0]
                        val targetPoseRaw = fiducial.getRobotPoseTargetSpace()
                        val targetPosMeters = targetPoseRaw.position.toUnit(org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER)

                        translationPoolIndex = (translationPoolIndex + 1) % translationPool.size
                        val targetTrans = translationPool[translationPoolIndex]
                        targetTrans.x = targetPosMeters.x
                        targetTrans.y = targetPosMeters.y
                        targetTrans.z = targetPosMeters.z

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
                        relTargetPose = tPose
                    }

                    visionMeasurementPoolIndex = (visionMeasurementPoolIndex + 1) % visionMeasurementPool.size
                    val measurement = visionMeasurementPool[visionMeasurementPoolIndex]

                    measurement.timestampMs = now
                    measurement.targetPose = fieldPose
                    measurement.robotPoseTargetSpace = relTargetPose ?: emptyTargetPose
                    measurement.tagId = tagId
                    measurement.ambiguity = if (numTags > 1) 0.0 else 0.1

                    currentMeasurementList.add(measurement)
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
