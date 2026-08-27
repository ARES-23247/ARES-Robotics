package com.areslib.ftc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Translation3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.transformBy
import org.firstinspires.ftc.vision.apriltag.AprilTagProcessor

/**
 * FTC VisionPortal AprilTag adapter using the active field configuration for absolute robot poses.
 *
 * FTC poses arrive in inches/degrees and are converted to meters/radians. A non-null detection list,
 * including an empty one, means the processor is connected; exceptions mark it disconnected and are
 * rate-limited on stderr. The adapter uses finite ring pools for lists and mutable geometry objects.
 * Returned measurements are therefore ephemeral and must be snapshotted before retention beyond the
 * pool horizon. [updateInputs] is single-loop-owned and performs the only processor read.
 */
class FtcVisionPortalIO(
    private val aprilTagProcessor: AprilTagProcessor,
    override val cameraPoses: List<Pose3d> = listOf(Pose3d(Translation3d(0.18, 0.0, 0.0), Rotation3d(0.0, 0.0, 0.0)))
) : VisionIO {

    private var lastWarningTime = 0L
    private val measurementsBuffer = ArrayList<VisionMeasurement>(10)
    
    // Object pools to prevent GC overhead
    private val visionMeasurementPool = Array(10) { VisionMeasurement() }
    private val translationPool = Array(20) { Translation3d() }
    private val rotationPool = Array(20) { Rotation3d() }
    private val posePool = Array(20) { Pose3d() }
    private val measurementListPool = Array(10) { ArrayList<VisionMeasurement>(10) }
    
    private var measurementPoolIndex = 0
    private var translationPoolIndex = 0
    private var rotationPoolIndex = 0
    private var posePoolIndex = 0
    private var measurementListPoolIndex = 0

    /** Replaces connection/measurement fields from one `freshDetections` read. */
    override fun updateInputs(inputs: VisionIOInputs) {
        inputs.cameraPoses = cameraPoses
        try {
            val detections = aprilTagProcessor.freshDetections
            
            if (detections != null && detections.isNotEmpty()) {
                inputs.isConnected = true
                
                measurementsBuffer.clear()
                for (i in 0 until detections.size) {
                    val detection = detections[i]
                    val pose = detection.ftcPose
                    // VisionPortal returns position in inches and rotation in degrees
                    // We convert inches to meters (1 inch = 0.0254 meters)
                    val t = translationPool[translationPoolIndex].apply {
                        x = pose.x * 0.0254
                        y = pose.y * 0.0254
                        z = pose.z * 0.0254
                    }
                    translationPoolIndex = (translationPoolIndex + 1) % 20

                    val r = rotationPool[rotationPoolIndex].apply {
                        setEulerAngles(
                            Math.toRadians(pose.roll),
                            Math.toRadians(pose.pitch),
                            Math.toRadians(pose.yaw)
                        )
                    }
                    rotationPoolIndex = (rotationPoolIndex + 1) % 20

                    val poseMeters = posePool[posePoolIndex].apply {
                        translation = t
                        rotation = r
                    }
                    posePoolIndex = (posePoolIndex + 1) % 20
                    
                    val tagConfig = com.areslib.state.RobotFieldManager.activeConfig.apriltags.find { it.id == detection.id }
                    if (tagConfig != null) {
                        val tagFieldTranslation = translationPool[translationPoolIndex].apply {
                            x = tagConfig.x
                            y = tagConfig.y
                            z = tagConfig.z
                        }
                        translationPoolIndex = (translationPoolIndex + 1) % 20
                        
                        val tagFieldRotation = rotationPool[rotationPoolIndex].apply {
                            setEulerAngles(
                                Math.toRadians(tagConfig.roll),
                                Math.toRadians(tagConfig.pitch),
                                Math.toRadians(tagConfig.yaw),
                            )
                        }
                        rotationPoolIndex = (rotationPoolIndex + 1) % 20
                        
                        val tagFieldPose = posePool[posePoolIndex].apply {
                            translation = tagFieldTranslation
                            rotation = tagFieldRotation
                        }
                        posePoolIndex = (posePoolIndex + 1) % 20
                        
                        val cameraToTag = com.areslib.math.geometry.Transform3d(poseMeters.translation, poseMeters.rotation)
                        val robotToCamera = com.areslib.math.geometry.Transform3d(cameraPoses[0].translation, cameraPoses[0].rotation)
                        
                        val absoluteRobotPose = tagFieldPose.transformBy(cameraToTag.inverse()).transformBy(robotToCamera.inverse())
                        
                        val measurement = visionMeasurementPool[measurementPoolIndex].apply {
                            timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
                            targetPose = absoluteRobotPose
                            tagId = detection.id
                            ambiguity = 0.0
                            robotPoseTargetSpace = poseMeters
                        }
                        measurementPoolIndex = (measurementPoolIndex + 1) % 10
                        measurementsBuffer.add(measurement)
                    }
                }
                
                // Rotate through pre-sized lists to avoid resizing in the sampling loop. Elements and
                // lists remain pool-owned; a consumer that retains them must create an owned snapshot.
                val safeMeasurements = measurementListPool[measurementListPoolIndex]
                safeMeasurements.clear()
                for (i in 0 until measurementsBuffer.size) safeMeasurements.add(measurementsBuffer[i])
                measurementListPoolIndex = (measurementListPoolIndex + 1) % 10
                inputs.measurements = safeMeasurements
            } else {
                inputs.isConnected = detections != null
                inputs.measurements = emptyList()
            }
        } catch (e: Exception) {
            inputs.isConnected = false
            inputs.measurements = emptyList()
            val now = com.areslib.util.RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("FtcVisionPortalIO: Error reading fresh AprilTag detections from hardware. Error: ${e.message}")
                lastWarningTime = now
            }
        }
    }
}
