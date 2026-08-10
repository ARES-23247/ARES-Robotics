package com.areslib.sim.network

import com.areslib.networktables.NT4Server
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.state.RobotState
import com.areslib.sim.infra.VirtualDriverStation
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.StructPublisher
import edu.wpi.first.wpilibj.DataLogManager

/**
 * World-class Telemetry Publisher for the ARES simulation environment.
 * Coordinates real-time NetworkTables (NT4) state publishing, client-side input polling,
 * and AdvantageScope-compatible swerve/pose visualizations.
 */
object TelemetryPublisher {
    private val ntInst = NetworkTableInstance.getDefault()
    private val statePublisher: StructPublisher<RobotState>
    private val targetPosePublisher = ntInst.getDoubleArrayTopic("ARES/TargetPose").publish()
    private val estimatedPosePublisher = ntInst.getDoubleArrayTopic("ARES/EstimatedPose").publish(
        edu.wpi.first.networktables.PubSubOption.periodic(0.01)
    )
    private val truePosePublisher = ntInst.getDoubleArrayTopic("ARES/TruePose").publish(
        edu.wpi.first.networktables.PubSubOption.periodic(0.01)
    )
    private val gamePiecesPublisher = ntInst.getDoubleArrayTopic("ARES/GamePieces").publish()
    private val timestampPub = ntInst.getIntegerTopic("TimestampMs").publish()

    // --- AdvantageKit-level Swerve Module Telemetry ---
    private val moduleSpeedsTargetPub = ntInst.getDoubleArrayTopic("Swerve/ModuleSpeedsTarget").publish()
    private val moduleAnglesTargetPub = ntInst.getDoubleArrayTopic("Swerve/ModuleAnglesTarget").publish()
    private val moduleSpeedsActualPub = ntInst.getDoubleArrayTopic("Swerve/ModuleSpeedsActual").publish()
    private val moduleAnglesActualPub = ntInst.getDoubleArrayTopic("Swerve/ModuleAnglesActual").publish()
    
    // Chassis Speeds
    private val chassisVxPub = ntInst.getDoubleTopic("Swerve/ChassisSpeeds/vx").publish()
    private val chassisVyPub = ntInst.getDoubleTopic("Swerve/ChassisSpeeds/vy").publish()
    private val chassisOmegaPub = ntInst.getDoubleTopic("Swerve/ChassisSpeeds/omega").publish()

    // Drive mode
    private val fieldCentricPub = ntInst.getBooleanTopic("Drive/FieldCentric").publish()
    private val teleopModePub = ntInst.getBooleanTopic("Drive/TeleopMode").publish()
    private val redAlliancePub = ntInst.getBooleanTopic("Drive/RedAlliance").publish()

    private val targetPoseBuf = DoubleArray(3)
    private val estimatedPoseBuf = DoubleArray(3)
    private val truePoseBuf = DoubleArray(3)

    private var lastObstaclesJson = ""

    fun getWebVx(): Double {
        val v = com.areslib.networktables.NT4Server.getDouble("ARES/Input/vx", 0.0)
        com.areslib.telemetry.SimInputBridge.rawWebVx = v
        return v
    }
    fun getWebVy(): Double {
        val v = com.areslib.networktables.NT4Server.getDouble("ARES/Input/vy", 0.0)
        com.areslib.telemetry.SimInputBridge.rawWebVy = v
        return v
    }
    fun getWebOmega(): Double {
        val v = com.areslib.networktables.NT4Server.getDouble("ARES/Input/omega", 0.0)
        com.areslib.telemetry.SimInputBridge.rawWebOmega = v
        return v
    }

    fun getWebIsIntaking(): Boolean = NT4Server.getBoolean("ARES/Input/isIntaking", false)
    fun getWebIsFlywheelOn(): Boolean = NT4Server.getBoolean("ARES/Input/isFlywheelOn", false)
    fun getWebIsTransferring(): Boolean = NT4Server.getBoolean("ARES/Input/isTransferring", false)
    fun getWebIsTeleopMode(): Boolean = NT4Server.getBoolean("ARES/Input/isTeleopMode", true)
    fun getWebIsFieldCentric(): Boolean = NT4Server.getBoolean("ARES/Input/isFieldCentric", false)
    fun getWebIsRedAlliance(): Boolean = NT4Server.getBoolean("ARES/Input/isRedAlliance", true)
    fun getWebIsButtonAPressed(): Boolean = NT4Server.getBoolean("ARES/Input/isButtonAPressed", false)
    fun getWebIsButtonBPressed(): Boolean = NT4Server.getBoolean("ARES/Input/isButtonBPressed", false)
    fun getWebIsButtonXPressed(): Boolean = NT4Server.getBoolean("ARES/Input/isButtonXPressed", false)
    fun getWebIsPoseReset(): Boolean = NT4Server.getBoolean("ARES/Input/isPoseReset", false)
    fun getWebObstacles(): String = NT4Server.getString("ARES/Input/obstacles", "")

    // Session log file path publisher
    private val logFilePathPub = ntInst.getStringTopic("ARES/Session/LogFilePath").publish()

    private var nt4Telemetry: com.areslib.telemetry.NT4Telemetry? = null
    private var networkStatePublisher: com.areslib.telemetry.ARESNetworkStatePublisher? = null

    fun init(
        nt4Telemetry: com.areslib.telemetry.NT4Telemetry,
        networkStatePublisher: com.areslib.telemetry.ARESNetworkStatePublisher
    ) {
        this.nt4Telemetry = nt4Telemetry
        this.networkStatePublisher = networkStatePublisher
    }

    init {
        // Register the custom struct so NT4 knows how to serialize it
        statePublisher = ntInst.getStructTopic("ARES/RobotState", RobotStateStruct()).publish()
    }


    /**
     * Publishes the current state to NT4 and DataLog.
     *
     * @param state The current immutable robot state to serialize and publish.
     */
    fun publish(state: RobotState, dtSeconds: Double? = null) {
        statePublisher.set(state)
        networkStatePublisher?.publish(state, dtSeconds = dtSeconds)
        nt4Telemetry?.let { com.areslib.hardware.HardwareRegistry.publishAll(it) }
        timestampPub.set(com.areslib.util.RobotClock.currentTimeMillis())
    }

    /**
     * Publishes the target pose for AdvantageScope rendering.
     *
     * @param pose The field-relative target pose.
     */
    fun publishTargetPose(pose: com.areslib.math.geometry.Pose2d) {
        targetPoseBuf[0] = pose.x
        targetPoseBuf[1] = pose.y
        targetPoseBuf[2] = pose.heading.radians
        targetPosePublisher.set(targetPoseBuf)
        NT4Server.publishTopic("ARES/TargetPose", targetPoseBuf)
        ntInst.flush()
    }

    /**
     * Publishes the estimated pose from the Kalman Filter (EKF) for AdvantageScope rendering.
     *
     * @param pose The field-relative estimated pose.
     */
    fun publishEstimatedPose(pose: com.areslib.math.geometry.Pose2d) {
        estimatedPoseBuf[0] = pose.x
        estimatedPoseBuf[1] = pose.y
        estimatedPoseBuf[2] = pose.heading.radians
        estimatedPosePublisher.set(estimatedPoseBuf)
        NT4Server.publishTopic("ARES/EstimatedPose", estimatedPoseBuf)
        NT4Server.publishTopic("ARES/EstimatedPose/0", pose.x)
        NT4Server.publishTopic("ARES/EstimatedPose/1", pose.y)
        NT4Server.publishTopic("ARES/EstimatedPose/2", pose.heading.radians)
        NT4Server.publishTopic("Drive/Pose_X", pose.x)
        NT4Server.publishTopic("Drive/Pose_Y", pose.y)
        NT4Server.publishTopic("Drive/Drive_Heading", pose.heading.radians)
        ntInst.flush()
    }

    /**
     * Publishes the true ground truth physics pose from Dyn4j.
     *
     * @param pose The true field-relative physics pose.
     */
    fun publishTruePose(pose: com.areslib.math.geometry.Pose2d) {
        truePoseBuf[0] = pose.x
        truePoseBuf[1] = pose.y
        truePoseBuf[2] = pose.heading.radians
        truePosePublisher.set(truePoseBuf)
        NT4Server.publishTopic("ARES/TruePose", truePoseBuf)
        NT4Server.publishTopic("ARES/TruePose/0", pose.x)
        NT4Server.publishTopic("ARES/TruePose/1", pose.y)
        NT4Server.publishTopic("ARES/TruePose/2", pose.heading.radians)
        ntInst.flush()
    }

    /**
     * Publishes the locations of game pieces on the field.
     *
     * @param gamePieces Packed array representation of game pieces coordinates.
     */
    fun publishGamePieces(gamePieces: DoubleArray) {
        gamePiecesPublisher.set(gamePieces)
        com.areslib.networktables.NT4Server.publishTopic("ARES/GamePieces", gamePieces)
    }

    /**
     * Publishes per-module swerve telemetry (AdvantageKit-level).
     * Each array is 4 elements [FL, FR, BL, BR].
     *
     * @param speedsTarget Command target speeds per module (m/s).
     * @param anglesTarget Command target angles per module (rad).
     * @param speedsActual Measured speeds per module (m/s).
     * @param anglesActual Measured rotation angles per module (rad).
     */
    fun publishSwerveModules(
        speedsTarget: DoubleArray, anglesTarget: DoubleArray,
        speedsActual: DoubleArray, anglesActual: DoubleArray
    ) {
        moduleSpeedsTargetPub.set(speedsTarget)
        moduleAnglesTargetPub.set(anglesTarget)
        moduleSpeedsActualPub.set(speedsActual)
        moduleAnglesActualPub.set(anglesActual)
    }

    /**
     * Publishes commanded chassis speeds.
     *
     * @param speeds Commands chassis relative linear/angular velocities.
     */
    fun publishChassisSpeeds(speeds: ChassisSpeeds) {
        chassisVxPub.set(speeds.vxMetersPerSecond)
        chassisVyPub.set(speeds.vyMetersPerSecond)
        chassisOmegaPub.set(speeds.omegaRadiansPerSecond)
    }

    /**
     * Publishes drive mode flags.
     *
     * @param fieldCentric Whether field-centric driving is currently active.
     * @param teleopMode Whether TeleOp mode is active.
     * @param redAlliance Active alliance selection flag (true for Red, false for Blue).
     */
    fun publishDriveMode(fieldCentric: Boolean, teleopMode: Boolean, redAlliance: Boolean) {
        fieldCentricPub.set(fieldCentric)
        teleopModePub.set(teleopMode)
        redAlliancePub.set(redAlliance)
    }

    /**
     * Polls `/ARES/Input` topics from NT4. If fresh updates are found,
     * pushes them directly into the VirtualDriverStation instance.
     *
     * @param driverStation Target VirtualDriverStation instance to synchronize inputs with.
     */
    fun pollWebInputs(driverStation: VirtualDriverStation): String? {
        val vx = getWebVx()
        val vy = getWebVy()
        val omega = getWebOmega()

        driverStation.webVx = vx
        driverStation.webVy = vy
        driverStation.webOmega = omega

        com.areslib.telemetry.SimInputBridge.rawWebVx = vx
        com.areslib.telemetry.SimInputBridge.rawWebVy = vy
        com.areslib.telemetry.SimInputBridge.rawWebOmega = omega

        // Dashboard clients connect to ARESLib's custom NT4 server. Read every web input from
        // that same registry; WPILib's process-local instance is a separate server and otherwise
        // leaves boolean/mode values stuck at their subscriber defaults.
        driverStation.isIntaking = getWebIsIntaking()
        driverStation.isFlywheelOn = getWebIsFlywheelOn()
        driverStation.isTransferring = getWebIsTransferring()
        driverStation.isTeleopMode = getWebIsTeleopMode()
        driverStation.isFieldCentric = getWebIsFieldCentric()
        val newRedAlliance = getWebIsRedAlliance()
        if (driverStation.isRedAlliance != newRedAlliance) {
            driverStation.isRedAlliance = newRedAlliance
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robot ->
                val allianceEnum = if (newRedAlliance) com.areslib.state.Alliance.RED else com.areslib.state.Alliance.BLUE
                robot.store.dispatch(com.areslib.action.RobotAction.SetAlliance(allianceEnum))
            }
        }
        driverStation.isButtonAPressed = getWebIsButtonAPressed()
        driverStation.isButtonBPressed = getWebIsButtonBPressed()
        driverStation.isButtonXPressed = getWebIsButtonXPressed()
        driverStation.isPoseReset = getWebIsPoseReset()
        val obstaclesJson = getWebObstacles()
        return if (obstaclesJson.isNotBlank() && obstaclesJson != lastObstaclesJson) {
            lastObstaclesJson = obstaclesJson
            obstaclesJson
        } else {
            null
        }
    }

    /**
     * Publishes superstructure state (flywheel RPM, mode, active flags).
     *
     * @param state The current immutable robot state.
     */
    private val indicatorLightPublishers = mutableMapOf<String, edu.wpi.first.networktables.DoublePublisher>()

    /**
     * Publishes indicator light positions from [SuperstructureState] over NT4.
     * Uses cached publishers to avoid per-frame topic lookups.
     */
    fun publishSuperstructure(state: RobotState) {
        val lights = state.superstructure.indicatorLights
        for ((name, position) in lights) {
            val publisher = indicatorLightPublishers.getOrPut(name) {
                ntInst.getDoubleTopic("Superstructure/IndicatorLight/$name").publish()
            }
            publisher.set(position)
        }
    }

    /**
     * Shutdown telemetry server.
     */
    fun stop() {
        ntInst.stopServer()
        DataLogManager.stop()
    }
}
