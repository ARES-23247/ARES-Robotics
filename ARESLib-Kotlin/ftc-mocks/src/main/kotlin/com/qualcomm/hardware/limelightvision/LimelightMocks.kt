@file:Suppress("UNUSED_PARAMETER")
package com.qualcomm.hardware.limelightvision

import org.firstinspires.ftc.robotcore.external.navigation.Pose3D
import org.firstinspires.ftc.robotcore.external.navigation.Position
import org.firstinspires.ftc.robotcore.external.navigation.YawPitchRollAngles
import org.firstinspires.ftc.robotcore.external.navigation.DistanceUnit
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit

/**
 * Class implementation for L L Result Types.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class LLResultTypes {
    /**
     * Class implementation for Fiducial Result.
     *
     * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
     */
    open class FiducialResult(
        private val fiducialId: Int,
        private val tx: Double,
        private val ty: Double,
        private val pose3d: Pose3D,
        private val robotPoseTargetSpace: Pose3D = Pose3D(),
        val poseAmbiguity: Double = 0.0
    ) {
        open fun getFiducialId(): Int = fiducialId
        open fun getTx(): Double = tx
        open fun getTy(): Double = ty
        open fun getPose3D(): Pose3D = pose3d
        open fun getRobotPoseTargetSpace(): Pose3D = robotPoseTargetSpace
    }
}

/**
 * Class implementation for L L Result.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class LLResult(
    private val controlHubTimestampMs: Long = com.areslib.util.RobotClock.currentTimeMillis(),
    open val ta: Double = 0.0,
    open val tx: Double = 0.0,
    open val ty: Double = 0.0,
    open val targetingLatency: Double = 0.0,
    open val captureLatency: Double = 0.0
) {
    
    open fun isValid(): Boolean = false
    open fun getControlHubTimeStamp(): Long = controlHubTimestampMs
    open fun getBotpose(): Pose3D? = null
    open fun getBotpose_MT2(): Pose3D? = null
    open fun getStddevMt1(): DoubleArray = DoubleArray(0)
    open fun getStddevMt2(): DoubleArray = DoubleArray(0)
    open fun getStaleness(): Long = 0L
    open fun getBotposeTagCount(): Int = 0
    open fun getBotposeSpan(): Double = 0.0
    open fun getBotposeAvgDist(): Double = 0.0
    open fun getBotposeAvgArea(): Double = 0.0
    open fun getFiducialResults(): List<LLResultTypes.FiducialResult> = emptyList()
}

/**
 * Class implementation for Limelight3 A.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
open class Limelight3A {
    @Volatile var simulatedResult: LLResult? = null

    open fun start() {}
    open fun stop() {}
    open fun isConnected(): Boolean = true
    open fun getTimeSinceLastUpdate(): Long = 0L

    open fun updateRobotOrientation(yawDegrees: Double): Boolean = true

    open fun getLatestResult(): LLResult? = simulatedResult

    open fun setLatestResult(result: LLResult?) {
        simulatedResult = result
    }

    fun setSimulatedPose(xMeters: Double, yMeters: Double, yawDegrees: Double, tagId: Int = 11) {
        simulatedResult = object : LLResult() {
            override fun isValid(): Boolean = true
            override fun getBotpose(): Pose3D = Pose3D(
                Position(DistanceUnit.METER, xMeters, yMeters, 0.0, 0L),
                YawPitchRollAngles(AngleUnit.DEGREES, yawDegrees, 0.0, 0.0, 0L)
            )
            override fun getBotpose_MT2(): Pose3D = getBotpose()!!
            override fun getFiducialResults(): List<LLResultTypes.FiducialResult> = listOf(
                LLResultTypes.FiducialResult(tagId, 0.0, 0.0, getBotpose()!!)
            )
        }
    }
}
