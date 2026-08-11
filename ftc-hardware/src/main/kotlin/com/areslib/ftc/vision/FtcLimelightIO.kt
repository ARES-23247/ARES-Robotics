package com.areslib.ftc.vision

import com.areslib.hardware.vision.VisionIO
import com.areslib.hardware.vision.VisionIOInputs
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
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
 * - **$Y+$**: Downward along the AprilTag face in meters ($m$).
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
    override val cameraPoses: List<Pose3d> = emptyList(),
    /** Stable hardware-map camera name used for per-source de-duplication. */
    private val sourceId: String = "ftc-limelight"
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

    /** Supplies the Control Hub IMU yaw required by MegaTag2 before polling a frame. */
    override fun setOrientation(
        yawDegrees: Double,
        yawRateDegPerSec: Double,
        pitchDegrees: Double,
        pitchRateDegPerSec: Double,
        rollDegrees: Double,
        rollRateDegPerSec: Double,
        linearVelocityMps: Double
    ) {
        if (yawDegrees.isFinite()) {
            limelight.updateRobotOrientation(yawDegrees)
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
            val connected = limelight.isConnected()
            val result = limelight.getLatestResult()

            if (connected && result != null && result.isValid() &&
                result.getStaleness() <= MAX_RESULT_STALENESS_MS &&
                limelight.getTimeSinceLastUpdate() <= MAX_RESULT_STALENESS_MS) {


                inputs.isConnected = true
                measurementListPoolIndex = (measurementListPoolIndex + 1) % measurementListPool.size
                val currentMeasurementList = measurementListPool[measurementListPoolIndex]
                currentMeasurementList.clear()

                val fiducials = result.getFiducialResults()
                
                // MegaTag2 uses the robot IMU heading supplied above and is substantially
                // more resistant to single-tag pose ambiguity. Fall back to MT1 only when
                // the camera/firmware does not provide an MT2 solve.
                val megaTag2Pose = result.getBotpose_MT2()
                val megaTag1Pose = result.getBotpose()
                val botposeRaw = megaTag2Pose ?: megaTag1Pose
                val usingMegaTag2 = megaTag2Pose != null

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

                    var recoveryFieldPose = fieldPose
                    if (megaTag1Pose != null && megaTag1Pose !== botposeRaw) {
                        translationPoolIndex = (translationPoolIndex + 1) % translationPool.size
                        val recoveryTrans = translationPool[translationPoolIndex]
                        recoveryTrans.x = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(megaTag1Pose.position.unit, megaTag1Pose.position.x)
                        recoveryTrans.y = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(megaTag1Pose.position.unit, megaTag1Pose.position.y)
                        recoveryTrans.z = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(megaTag1Pose.position.unit, megaTag1Pose.position.z)
                        rotationPoolIndex = (rotationPoolIndex + 1) % rotationPool.size
                        val recoveryRot = rotationPool[rotationPoolIndex]
                        recoveryRot.setEulerAngles(
                            Math.toRadians(megaTag1Pose.orientation.getRoll(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                            Math.toRadians(megaTag1Pose.orientation.getPitch(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES)),
                            Math.toRadians(megaTag1Pose.orientation.getYaw(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.DEGREES))
                        )
                        posePoolIndex = (posePoolIndex + 1) % posePool.size
                        recoveryFieldPose = posePool[posePoolIndex]
                        recoveryFieldPose.translation = recoveryTrans
                        recoveryFieldPose.rotation = recoveryRot
                    }

                    if (fiducials.isNotEmpty()) {
                        // getBotpose() is one camera-frame field-pose solve. Emitting that
                        // same pose once per fiducial would apply correlated information N
                        // times and make EKF covariance artificially small. Keep one field
                        // observation and retain the number of contributing tags as metadata.
                        var representative = fiducials[0]
                        var representativeDistanceSquared = Double.POSITIVE_INFINITY
                        for (i in fiducials.indices) {
                            val candidate = fiducials[i]
                            val candidatePose = candidate.getRobotPoseTargetSpace()
                            val cx = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(candidatePose.position.unit, candidatePose.position.x)
                            val cy = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(candidatePose.position.unit, candidatePose.position.y)
                            val cz = org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit.METER.fromUnit(candidatePose.position.unit, candidatePose.position.z)
                            val distanceSquared = cx * cx + cy * cy + cz * cz
                            if (distanceSquared < representativeDistanceSquared) {
                                representative = candidate
                                representativeDistanceSquared = distanceSquared
                            }
                        }

                        val targetPoseRaw = representative.getRobotPoseTargetSpace()
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

                        // The SDK stamps the result when it is parsed on the Control Hub. Unlike
                        // loop poll time, this remains stable when getLatestResult() returns the
                        // same cached camera frame on multiple robot loops.
                        val totalLatencyMs = (result.captureLatency + result.targetingLatency).toLong()
                        measurement.timestampMs = result.getControlHubTimeStamp() - totalLatencyMs
                        measurement.captureTimestampMicros = measurement.timestampMs * 1_000L
                        measurement.targetPose = fieldPose
                        measurement.recoveryPose = recoveryFieldPose
                        measurement.hasRecoveryPose = megaTag1Pose != null
                        measurement.robotPoseTargetSpace = tPose
                        measurement.tagId = representative.getFiducialId()
                        val reportedTagCount = result.getBotposeTagCount()
                        measurement.tagCount = if (reportedTagCount > 0) reportedTagCount else fiducials.size
                        // FTC's LLResult API does not expose pose-solve ambiguity. Mark
                        // that fact explicitly and use real std-dev/geometry metrics.
                        measurement.ambiguity = 0.0
                        measurement.ambiguityAvailable = false
                        measurement.tagSpanMeters = positiveMetric(result.getBotposeSpan())
                        measurement.averageTagDistanceMeters = positiveMetric(result.getBotposeAvgDist()).let {
                            if (it > 0.0) it else kotlin.math.sqrt(representativeDistanceSquared)
                        }
                        measurement.averageTagAreaPercent = positiveMetric(result.getBotposeAvgArea())
                        measurement.sourceId = sourceId
                        measurement.frameId = result.getControlHubTimeStamp()
                        measurement.solverType = if (usingMegaTag2) VisionSolverType.MEGATAG2 else VisionSolverType.MEGATAG1
                        measurement.latencyMs = totalLatencyMs.toDouble()
                        measurement.recoveryAmbiguity = 0.0
                        measurement.recoveryAmbiguityAvailable = false
                        applyObservationStdDevs(measurement, result, usingMegaTag2)

                        currentMeasurementList.add(measurement)
                    }
                }

                inputs.measurements = currentMeasurementList
            } else {
                inputs.isConnected = connected
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
        limelight.stop()
    }

    private fun applyObservationStdDevs(
        measurement: VisionMeasurement,
        result: com.qualcomm.hardware.limelightvision.LLResult,
        usingMegaTag2: Boolean
    ) {
        val reported = if (usingMegaTag2) result.getStddevMt2() else result.getStddevMt1()
        val reportedX = reported.getOrNull(0)
        val reportedY = reported.getOrNull(1)
        val reportedHeadingDegrees = reported.getOrNull(5)
        if (reportedX != null && reportedY != null && reportedHeadingDegrees != null &&
            reportedX.isFinite() && reportedX > 0.0 &&
            reportedY.isFinite() && reportedY > 0.0 &&
            reportedHeadingDegrees.isFinite() && reportedHeadingDegrees > 0.0) {
            measurement.stdDevXMeters = reportedX
            measurement.stdDevYMeters = reportedY
            measurement.stdDevHeadingRadians = if (usingMegaTag2) 1.0e6 else Math.toRadians(reportedHeadingDegrees)
        } else {
            // Older Limelight firmware does not publish these metrics. The reducer will
            // fall back to the robot's field-calibrated baseline covariance.
            measurement.stdDevXMeters = 0.0
            measurement.stdDevYMeters = 0.0
            measurement.stdDevHeadingRadians = 0.0
        }
    }

    private fun positiveMetric(value: Double): Double =
        if (value.isFinite() && value > 0.0) value else -1.0

    private companion object {
        const val MAX_RESULT_STALENESS_MS = 250L
    }
}
