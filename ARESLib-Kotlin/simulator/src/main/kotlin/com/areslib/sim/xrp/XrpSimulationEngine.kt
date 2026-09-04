package com.areslib.sim.xrp

import com.areslib.kinematics.DifferentialDriveKinematics
import com.areslib.kinematics.DifferentialWheelSpeeds
import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.math.wrapAngle
import com.areslib.networktables.NT4Server
import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.state.RobotFieldConfig
import org.dyn4j.geometry.Vector2
import kotlin.math.cos
import kotlin.math.sin

/** Drivetrain architecture configuration for simulated XRP. */
enum class XrpDrivetrainType {
    DIFFERENTIAL,
    MECANUM
}

/**
 * High-fidelity 2D Dyn4j physics simulation engine for XRP robots.
 *
 * Supports both standard differential drivetrain (2-wheel) and 4-wheel mecanum variants.
 * Fuses SparkFun OTOS downward-facing optical tracking odometry and IMU heading.
 * Connects bidirectional NT4 telemetry with ARES Robotics Studio.
 */
class XrpSimulationEngine(
    val drivetrainType: XrpDrivetrainType = XrpDrivetrainType.DIFFERENTIAL,
    val trackWidthMeters: Double = 0.155,
    val wheelBaseMeters: Double = 0.140,
    val wheelRadiusMeters: Double = 0.030,
    val maxLinearSpeedMetersPerSecond: Double = 0.85,
    val maxAngularSpeedRadPerSec: Double = 8.0,
    val activeConfig: RobotFieldConfig? = null
) {
    val physicsWorld = SimPhysicsWorld(
        chassisWidth = 0.155,
        chassisHeight = 0.155,
        massKg = if (drivetrainType == XrpDrivetrainType.DIFFERENTIAL) 0.60 else 0.70,
        linearDamping = 1.0,
        angularDamping = 2.0
    )

    private val diffKinematics = DifferentialDriveKinematics(trackWidthMeters)
    private val mecanumKinematics = MecanumKinematics(trackWidthMeters, wheelBaseMeters)

    // Actuator power inputs [-1.0, 1.0]
    @Volatile var leftPower: Double = 0.0
    @Volatile var rightPower: Double = 0.0
    @Volatile var flPower: Double = 0.0
    @Volatile var frPower: Double = 0.0
    @Volatile var rlPower: Double = 0.0
    @Volatile var rrPower: Double = 0.0

    // Simulated OTOS state (m, m, rad)
    @Volatile var otosX: Double = 0.0
    @Volatile var otosY: Double = 0.0
    @Volatile var otosHeading: Double = 0.0
    @Volatile var otosVx: Double = 0.0
    @Volatile var otosVy: Double = 0.0
    @Volatile var otosOmega: Double = 0.0

    private val poseFrameBuffer = DoubleArray(10)
    private var sequence: Long = 0L

    init {
        physicsWorld.loadFieldElements(activeConfig)
        resetPose(0.35, 0.7112, 0.0) // default spawn on XRP field
    }

    fun resetPose(x: Double, y: Double, headingRad: Double) {
        physicsWorld.robotBody.transform.setTranslation(x, y)
        physicsWorld.robotBody.transform.setRotation(headingRad)
        physicsWorld.robotBody.linearVelocity = Vector2(0.0, 0.0)
        physicsWorld.robotBody.angularVelocity = 0.0
        otosX = x
        otosY = y
        otosHeading = headingRad
        otosVx = 0.0
        otosVy = 0.0
        otosOmega = 0.0
    }

    /** Advances the physics simulation by [dt] seconds. */
    fun step(dt: Double = 0.02) {
        val currentHeading = physicsWorld.robotBody.transform.rotationAngle

        val robotVx: Double
        val robotVy: Double
        val omega: Double

        when (drivetrainType) {
            XrpDrivetrainType.DIFFERENTIAL -> {
                val vL = leftPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val vR = rightPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val speeds = diffKinematics.toChassisSpeeds(DifferentialWheelSpeeds(vL, vR))
                robotVx = speeds.vxMetersPerSecond
                robotVy = 0.0
                omega = speeds.omegaRadiansPerSecond.coerceIn(-maxAngularSpeedRadPerSec, maxAngularSpeedRadPerSec)
            }
            XrpDrivetrainType.MECANUM -> {
                val flV = flPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val frV = frPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val rlV = rlPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val rrV = rrPower.coerceIn(-1.0, 1.0) * maxLinearSpeedMetersPerSecond
                val speeds = mecanumKinematics.toChassisSpeeds(flV, frV, rlV, rrV)
                robotVx = speeds.vxMetersPerSecond
                robotVy = speeds.vyMetersPerSecond
                omega = speeds.omegaRadiansPerSecond.coerceIn(-maxAngularSpeedRadPerSec, maxAngularSpeedRadPerSec)
            }
        }

        val cosH = cos(currentHeading)
        val sinH = sin(currentHeading)
        val fieldVx = robotVx * cosH - robotVy * sinH
        val fieldVy = robotVx * sinH + robotVy * cosH

        val isNoInput = kotlin.math.abs(robotVx) < 1e-4 && kotlin.math.abs(robotVy) < 1e-4 && kotlin.math.abs(omega) < 1e-4
        if (isNoInput) {
            physicsWorld.robotBody.linearVelocity = Vector2(0.0, 0.0)
            physicsWorld.robotBody.angularVelocity = 0.0
        } else {
            physicsWorld.robotBody.setAtRest(false)
            physicsWorld.robotBody.linearVelocity = Vector2(fieldVx, fieldVy)
            physicsWorld.robotBody.angularVelocity = omega
        }

        physicsWorld.world.step(1, dt)

        val trueX = physicsWorld.robotBody.transform.translationX
        val trueY = physicsWorld.robotBody.transform.translationY
        val trueHeading = wrapAngle(physicsWorld.robotBody.transform.rotationAngle)
        val actualFieldVx = physicsWorld.robotBody.linearVelocity.x
        val actualFieldVy = physicsWorld.robotBody.linearVelocity.y
        val actualOmega = physicsWorld.robotBody.angularVelocity

        val postCosH = cos(trueHeading)
        val postSinH = sin(trueHeading)
        val actualRobotVx = actualFieldVx * postCosH + actualFieldVy * postSinH
        val actualRobotVy = -actualFieldVx * postSinH + actualFieldVy * postCosH

        otosX = trueX
        otosY = trueY
        otosHeading = trueHeading
        otosVx = actualRobotVx
        otosVy = actualRobotVy
        otosOmega = actualOmega

        sequence++
    }

    /** Publishes full telemetry to NT4 according to the canonical contract. */
    fun publishTelemetry() {
        val trueX = physicsWorld.robotBody.transform.translationX
        val trueY = physicsWorld.robotBody.transform.translationY
        val trueH = wrapAngle(physicsWorld.robotBody.transform.rotationAngle)

        poseFrameBuffer[0] = trueX
        poseFrameBuffer[1] = trueY
        poseFrameBuffer[2] = trueH
        poseFrameBuffer[3] = otosX
        poseFrameBuffer[4] = otosY
        poseFrameBuffer[5] = otosHeading
        poseFrameBuffer[6] = otosX
        poseFrameBuffer[7] = otosY
        poseFrameBuffer[8] = otosHeading
        poseFrameBuffer[9] = sequence.toDouble()

        NT4Server.publishTopic("ARES/SimulatorPoseFrame", poseFrameBuffer.clone())

        NT4Server.publishTopic("ARES/TruePose/0", trueX)
        NT4Server.publishTopic("ARES/TruePose/1", trueY)
        NT4Server.publishTopic("ARES/TruePose/2", trueH)

        NT4Server.publishTopic("ARES/EstimatedPose/0", otosX)
        NT4Server.publishTopic("ARES/EstimatedPose/1", otosY)
        NT4Server.publishTopic("ARES/EstimatedPose/2", otosHeading)

        NT4Server.publishTopic("Drive/Pose_X", otosX)
        NT4Server.publishTopic("Drive/Pose_Y", otosY)
        NT4Server.publishTopic("Drive/Pose_Heading", otosHeading)

        NT4Server.publishTopic("Drive/Odom_X", otosX)
        NT4Server.publishTopic("Drive/Odom_Y", otosY)
        NT4Server.publishTopic("Drive/Odom_Heading", otosHeading)

        when (drivetrainType) {
            XrpDrivetrainType.DIFFERENTIAL -> {
                NT4Server.publishTopic("Hardware/Motors/left/Power", leftPower)
                NT4Server.publishTopic("Hardware/Motors/right/Power", rightPower)
                NT4Server.publishTopic("Hardware/Motors/left/Velocity", otosVx - otosOmega * (trackWidthMeters / 2.0))
                NT4Server.publishTopic("Hardware/Motors/right/Velocity", otosVx + otosOmega * (trackWidthMeters / 2.0))
            }
            XrpDrivetrainType.MECANUM -> {
                NT4Server.publishTopic("Hardware/Motors/fl/Power", flPower)
                NT4Server.publishTopic("Hardware/Motors/fr/Power", frPower)
                NT4Server.publishTopic("Hardware/Motors/rl/Power", rlPower)
                NT4Server.publishTopic("Hardware/Motors/rr/Power", rrPower)
            }
        }
    }

    /**
     * Consumes leased control frame double[8] from ARES-Analytics dashboard.
     * Frame format: [vx, vy, omega, mode, ...]
     */
    fun processDriveFrame(frame: DoubleArray) {
        if (frame.size < 3) return
        val vx = frame[0]
        val vy = frame[1]
        val omega = frame[2]

        when (drivetrainType) {
            XrpDrivetrainType.DIFFERENTIAL -> {
                val speeds = diffKinematics.toWheelSpeeds(ChassisSpeeds(vx, 0.0, omega))
                leftPower = (speeds.leftMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
                rightPower = (speeds.rightMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
            }
            XrpDrivetrainType.MECANUM -> {
                val wheelSpeeds = mecanumKinematics.toWheelSpeeds(ChassisSpeeds(vx, vy, omega))
                flPower = (wheelSpeeds.frontLeftMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
                frPower = (wheelSpeeds.frontRightMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
                rlPower = (wheelSpeeds.backLeftMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
                rrPower = (wheelSpeeds.backRightMetersPerSecond / maxLinearSpeedMetersPerSecond).coerceIn(-1.0, 1.0)
            }
        }
    }
}
