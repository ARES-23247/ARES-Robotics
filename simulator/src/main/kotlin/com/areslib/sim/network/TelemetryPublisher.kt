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

    // --- Web Dashboard Inputs Subscribers ---
    private val webVxSub = ntInst.getDoubleTopic("ARES/Input/vx").subscribe(0.0)
    private val webVySub = ntInst.getDoubleTopic("ARES/Input/vy").subscribe(0.0)
    private val webOmegaSub = ntInst.getDoubleTopic("ARES/Input/omega").subscribe(0.0)
    private val webIntakeSub = ntInst.getBooleanTopic("ARES/Input/isIntaking").subscribe(false)
    private val webFlywheelSub = ntInst.getBooleanTopic("ARES/Input/isFlywheelOn").subscribe(false)
    private val webTransferSub = ntInst.getBooleanTopic("ARES/Input/isTransferring").subscribe(false)
    private val webTeleopSub = ntInst.getBooleanTopic("ARES/Input/isTeleopMode").subscribe(true)
    private val webFieldCentricSub = ntInst.getBooleanTopic("ARES/Input/isFieldCentric").subscribe(false)
    private val webRedAllianceSub = ntInst.getBooleanTopic("ARES/Input/isRedAlliance").subscribe(true)
    private val webHeartbeatSub = ntInst.getIntegerTopic("ARES/Input/heartbeat").subscribe(0L)
    private val webButtonASub = ntInst.getBooleanTopic("ARES/Input/isButtonAPressed").subscribe(false)
    private val webButtonBSub = ntInst.getBooleanTopic("ARES/Input/isButtonBPressed").subscribe(false)
    private val webButtonXSub = ntInst.getBooleanTopic("ARES/Input/isButtonXPressed").subscribe(false)
    private val webPoseResetSub = ntInst.getBooleanTopic("ARES/Input/isPoseReset").subscribe(false)
    
    /**
     * Obstacles input subscriber path. Receives costmap bounding boxes and obstacles from the dashboard editor.
     */
    val obstaclesSub = ntInst.getStringTopic("ARES/Input/obstacles").subscribe("")

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

    private var lastWebHeartbeatTimestamp = 0L
    private var lastWebInputReceiveTime = 0L

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
    fun pollWebInputs(driverStation: VirtualDriverStation) {
        val vx = getWebVx()
        val vy = getWebVy()
        val omega = getWebOmega()

        driverStation.webVx = vx
        driverStation.webVy = vy
        driverStation.webOmega = omega

        com.areslib.telemetry.SimInputBridge.rawWebVx = vx
        com.areslib.telemetry.SimInputBridge.rawWebVy = vy
        com.areslib.telemetry.SimInputBridge.rawWebOmega = omega

        driverStation.isIntaking = webIntakeSub.get()
        driverStation.isFlywheelOn = webFlywheelSub.get()
        driverStation.isTransferring = webTransferSub.get()
        driverStation.isTeleopMode = webTeleopSub.get()
        driverStation.isFieldCentric = webFieldCentricSub.get()
        val newRedAlliance = webRedAllianceSub.get()
        if (driverStation.isRedAlliance != newRedAlliance) {
            driverStation.isRedAlliance = newRedAlliance
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robot ->
                val allianceEnum = if (newRedAlliance) com.areslib.state.Alliance.RED else com.areslib.state.Alliance.BLUE
                robot.store.dispatch(com.areslib.action.RobotAction.SetAlliance(allianceEnum))
            }
        }
        driverStation.isButtonAPressed = webButtonASub.get()
        driverStation.isButtonBPressed = webButtonBSub.get()
        driverStation.isButtonXPressed = webButtonXSub.get()
        driverStation.isPoseReset = webPoseResetSub.get()
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
