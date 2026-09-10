package com.areslib.sim.xrp

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.state.Alliance
import com.areslib.telemetry.DriveFrameReceiver
import com.areslib.telemetry.SimInputBridge
import com.areslib.telemetry.TelemetryTopicConstants
import com.areslib.util.RobotClock
import com.areslib.kinematics.DifferentialDriveKinematics
import com.areslib.kinematics.MecanumKinematics
import com.areslib.math.wrapAngle
import com.areslib.networktables.NT4Server
import com.areslib.sim.physics.SimPhysicsWorld
import com.areslib.state.RobotFieldConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Drivetrain architecture configuration for simulated XRP. */
enum class XrpDrivetrainType {
    DIFFERENTIAL,
    MECANUM
}

/**
 * Velocity-driven Dyn4j XRP simulation with ideal optical odometry observations and a Store-owned
 * EKF. This model has collisions/damping, not motor torque, slip, sensor noise or independent IMU
 * fusion. Network commands use an instance-owned v2 lease receiver; field-relative intent uses
 * estimator heading. Direct power fields are fixture inputs until network control takes ownership.
 * Engine mutation and telemetry publication belong to one simulation thread.
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
    init {
        require(wheelRadiusMeters.isFinite() && wheelRadiusMeters > 0.0) { "Wheel radius must be finite and positive" }
        require(maxLinearSpeedMetersPerSecond.isFinite() && maxLinearSpeedMetersPerSecond > 0.0) { "Maximum linear speed must be finite and positive" }
        require(maxAngularSpeedRadPerSec.isFinite() && maxAngularSpeedRadPerSec > 0.0) { "Maximum angular speed must be finite and positive" }
    }

    val store = Store()
    private val receiver = DriveFrameReceiver()
    private var networkControlOwned = false
    private var lastAppliedCommand: SimInputBridge.CommandFrame? = null
    private val wheelBuffer = DoubleArray(4)
    private val acknowledgement = DoubleArray(SimInputBridge.ACK_VALUE_COUNT)
    private var simulationTimeMs = 0.0
    private val poseUpdate = RobotAction.PoseUpdate(0.0, 0.0, 0.0, 0L,
        applyControlHubGyroCorrection = false, imuMeasurementsValid = false)
    private val driveIntent = RobotAction.JoystickDriveIntent(0.0, 0.0, 0.0, isFieldCentric = false)

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
        require(x.isFinite() && y.isFinite() && headingRad.isFinite()) { "Pose components must be finite" }
        stop()
        networkControlOwned = false
        physicsWorld.robotBody.transform.setTranslation(x, y)
        physicsWorld.robotBody.transform.setRotation(headingRad)
        physicsWorld.robotBody.setLinearVelocity(0.0, 0.0)
        physicsWorld.robotBody.angularVelocity = 0.0
        otosX = x
        otosY = y
        otosHeading = headingRad
        otosVx = 0.0
        otosVy = 0.0
        otosOmega = 0.0
        observePose(isReset = true)
    }

    /** Advances the physics simulation by [dt] seconds. */
    fun step(dt: Double = 0.02) {
        try {
            require(dt.isFinite() && dt > 0.0 && dt <= 0.1) { "Physics dt must be finite and in (0, 0.1] seconds" }
            if (networkControlOwned) applyCommand(receiver.acceptFrame(null, RobotClock.currentTimeMillis()))
            stepPhysics(dt)
        } catch (failure: Throwable) {
            try { stop() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun stepPhysics(dt: Double) {
        val currentHeading = physicsWorld.robotBody.transform.rotationAngle

        val robotVx: Double
        val robotVy: Double
        val omega: Double

        if (drivetrainType == XrpDrivetrainType.DIFFERENTIAL) {
            if (!leftPower.isFinite() || !rightPower.isFinite()) clearPowers()
            leftPower = leftPower.coerceIn(-1.0, 1.0)
            rightPower = rightPower.coerceIn(-1.0, 1.0)
        } else {
            if (!flPower.isFinite() || !frPower.isFinite() || !rlPower.isFinite() || !rrPower.isFinite()) clearPowers()
            flPower = flPower.coerceIn(-1.0, 1.0); frPower = frPower.coerceIn(-1.0, 1.0)
            rlPower = rlPower.coerceIn(-1.0, 1.0); rrPower = rrPower.coerceIn(-1.0, 1.0)
        }

        when (drivetrainType) {
            XrpDrivetrainType.DIFFERENTIAL -> {
                val vL = leftPower * maxLinearSpeedMetersPerSecond
                val vR = rightPower * maxLinearSpeedMetersPerSecond
                val speeds = diffKinematics.toChassisSpeeds(vL, vR)
                robotVx = speeds.vxMetersPerSecond
                robotVy = 0.0
                omega = speeds.omegaRadiansPerSecond.coerceIn(-maxAngularSpeedRadPerSec, maxAngularSpeedRadPerSec)
            }
            XrpDrivetrainType.MECANUM -> {
                val flV = flPower * maxLinearSpeedMetersPerSecond
                val frV = frPower * maxLinearSpeedMetersPerSecond
                val rlV = rlPower * maxLinearSpeedMetersPerSecond
                val rrV = rrPower * maxLinearSpeedMetersPerSecond
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
            physicsWorld.robotBody.setLinearVelocity(0.0, 0.0)
            physicsWorld.robotBody.angularVelocity = 0.0
        } else {
            physicsWorld.robotBody.setAtRest(false)
            physicsWorld.robotBody.setLinearVelocity(fieldVx, fieldVy)
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

        simulationTimeMs += dt * 1000.0
        observePose(isReset = false)
        sequence = if (sequence >= 9_007_199_254_740_991L) 0L else sequence + 1L
    }

    private fun observePose(isReset: Boolean) {
        poseUpdate.xMeters = otosX
        poseUpdate.yMeters = otosY
        poseUpdate.headingRadians = otosHeading
        poseUpdate.timestampMs = simulationTimeMs.toLong()
        poseUpdate.isReset = isReset
        poseUpdate.xVelocityMetersPerSecond = physicsWorld.robotBody.linearVelocity.x
        poseUpdate.yVelocityMetersPerSecond = physicsWorld.robotBody.linearVelocity.y
        poseUpdate.angularVelocityRadiansPerSecond = otosOmega
        store.dispatch(poseUpdate)
    }

    /** Publishes full telemetry to NT4 according to the canonical contract. */
    fun publishTelemetry() {
        val trueX = physicsWorld.robotBody.transform.translationX
        val trueY = physicsWorld.robotBody.transform.translationY
        val trueH = wrapAngle(physicsWorld.robotBody.transform.rotationAngle)

        val drive = store.state.drive
        val estimate = drive.poseEstimator
        poseFrameBuffer[0] = trueX
        poseFrameBuffer[1] = trueY
        poseFrameBuffer[2] = trueH
        poseFrameBuffer[3] = estimate.estimatedPoseX
        poseFrameBuffer[4] = estimate.estimatedPoseY
        poseFrameBuffer[5] = estimate.estimatedPoseHeading
        poseFrameBuffer[6] = drive.odometryX
        poseFrameBuffer[7] = drive.odometryY
        poseFrameBuffer[8] = drive.odometryHeading
        poseFrameBuffer[9] = sequence.toDouble()

        NT4Server.publishTopic("ARES/SimulatorPoseFrame", poseFrameBuffer)
        receiver.copyAcknowledgement(acknowledgement)
        NT4Server.publishTopic(TelemetryTopicConstants.DRIVE_INPUT_ACK, acknowledgement)

        NT4Server.publishTopic("ARES/TruePose/0", trueX)
        NT4Server.publishTopic("ARES/TruePose/1", trueY)
        NT4Server.publishTopic("ARES/TruePose/2", trueH)

        NT4Server.publishTopic("ARES/EstimatedPose/0", estimate.estimatedPoseX)
        NT4Server.publishTopic("ARES/EstimatedPose/1", estimate.estimatedPoseY)
        NT4Server.publishTopic("ARES/EstimatedPose/2", estimate.estimatedPoseHeading)

        NT4Server.publishTopic("Drive/Pose_X", estimate.estimatedPoseX)
        NT4Server.publishTopic("Drive/Pose_Y", estimate.estimatedPoseY)
        NT4Server.publishTopic("Drive/Pose_Heading", estimate.estimatedPoseHeading)

        NT4Server.publishTopic("Drive/Odom_X", drive.odometryX)
        NT4Server.publishTopic("Drive/Odom_Y", drive.odometryY)
        NT4Server.publishTopic("Drive/Odom_Heading", drive.odometryHeading)

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

    /** Accepts only canonical v2 frames; retained frames never renew the receiver-time lease. */
    fun processDriveFrame(frame: DoubleArray) {
        networkControlOwned = true
        try {
            applyCommand(receiver.acceptFrame(frame, RobotClock.currentTimeMillis()))
        } catch (failure: Throwable) {
            try { stop() } catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun applyCommand(command: SimInputBridge.CommandFrame) {
        if (command !== lastAppliedCommand) {
            lastAppliedCommand = command
            val enabled = command.isTeleopMode && command.sessionNonce > 0L
            driveIntent.targetXVelocity = if (enabled) command.vx else 0.0
            driveIntent.targetYVelocity = if (enabled) command.vy else 0.0
            driveIntent.targetAngularVelocity = if (enabled) command.omega else 0.0
            driveIntent.isFieldCentric = command.isFieldCentric
            driveIntent.timestampMs = simulationTimeMs.toLong()
            store.dispatch(driveIntent)
            val alliance = if (command.isRedAlliance) Alliance.RED else Alliance.BLUE
            if (store.state.drive.alliance != alliance) store.dispatch(RobotAction.SetAlliance(alliance, simulationTimeMs.toLong()))
            if (command.isPoseReset) { resetPose(0.35, 0.7112, 0.0); networkControlOwned = true; return }
        }
        // Store intent is not authority: another producer cannot override a disabled/expired lease.
        if (!command.isTeleopMode || command.sessionNonce <= 0L) {
            clearPowers()
            return
        }
        val drive = store.state.drive
        var vx = drive.xVelocityMetersPerSecond
        var vy = drive.yVelocityMetersPerSecond
        val omega = drive.angularVelocityRadiansPerSecond
        if (drive.isFieldCentric) {
            val heading = drive.poseEstimator.estimatedPoseHeading
            val c = cos(heading); val s = sin(heading)
            val robotX = vx * c + vy * s
            vy = -vx * s + vy * c
            vx = robotX
        }
        when (drivetrainType) {
            XrpDrivetrainType.DIFFERENTIAL -> {
                diffKinematics.toWheelSpeeds(vx, omega, wheelBuffer)
                val divisor = maxOf(maxLinearSpeedMetersPerSecond, maxOf(abs(wheelBuffer[0]), abs(wheelBuffer[1])))
                leftPower = wheelBuffer[0] / divisor
                rightPower = wheelBuffer[1] / divisor
                if (!leftPower.isFinite() || !rightPower.isFinite()) clearPowers()
            }
            XrpDrivetrainType.MECANUM -> {
                mecanumKinematics.toWheelSpeeds(vx, vy, omega, wheelBuffer)
                val divisor = maxOf(maxLinearSpeedMetersPerSecond,
                    maxOf(maxOf(abs(wheelBuffer[0]), abs(wheelBuffer[1])), maxOf(abs(wheelBuffer[2]), abs(wheelBuffer[3]))))
                flPower = wheelBuffer[0] / divisor; frPower = wheelBuffer[1] / divisor
                rlPower = wheelBuffer[2] / divisor; rrPower = wheelBuffer[3] / divisor
                if (!flPower.isFinite() || !frPower.isFinite() || !rlPower.isFinite() || !rrPower.isFinite()) clearPowers()
            }
        }
    }

    /** Neutralizes outputs and invalidates network authority until another neutral handshake. */
    fun stop() {
        clearPowers()
        physicsWorld.robotBody.setLinearVelocity(0.0, 0.0)
        physicsWorld.robotBody.angularVelocity = 0.0
        receiver.reset()
        networkControlOwned = true
        lastAppliedCommand = null
        driveIntent.targetXVelocity = 0.0; driveIntent.targetYVelocity = 0.0
        driveIntent.targetAngularVelocity = 0.0
        driveIntent.timestampMs = simulationTimeMs.toLong()
        store.dispatch(driveIntent)
    }

    private fun clearPowers() {
        leftPower = 0.0; rightPower = 0.0
        flPower = 0.0; frPower = 0.0; rlPower = 0.0; rrPower = 0.0
    }
}
