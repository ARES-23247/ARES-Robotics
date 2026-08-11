package com.areslib.sim.network

import com.areslib.networktables.NT4Server
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.state.RobotState
import com.areslib.sim.infra.VirtualDriverStation
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.networktables.StructPublisher
import edu.wpi.first.wpilibj.DataLogManager

/**
 * Simulator bridge for ARES custom NT4 topics and WPILib/AdvantageScope topics.
 *
 * Canonical custom-server keys omit a leading slash. Pose translations are field-relative meters;
 * headings and module angles are CCW-positive radians. Reusable pose arrays are shared by the
 * simulation thread and must be consumed synchronously by each backend. [init] attaches the shared
 * robot-state publisher; pose/driver-station compatibility topics work independently.
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
    private val gamePiecesPublisher = ntInst.getDoubleArrayTopic(com.areslib.telemetry.TelemetryTopicConstants.GAME_PIECES).publish()
    private val gamePiecesCountPublisher = ntInst.getIntegerTopic(com.areslib.telemetry.TelemetryTopicConstants.GAME_PIECES_COUNT).publish()
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
    private var lastFieldConfigJson = ""

    fun getWebVx(): Double {
        return com.areslib.telemetry.SimInputBridge.currentFrame().vx
    }
    fun getWebVy(): Double {
        return com.areslib.telemetry.SimInputBridge.currentFrame().vy
    }
    fun getWebOmega(): Double {
        return com.areslib.telemetry.SimInputBridge.currentFrame().omega
    }

    fun getWebIsIntaking(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isIntaking
    fun getWebIsFlywheelOn(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isFlywheelOn
    fun getWebIsTransferring(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isTransferring
    fun getWebIsTeleopMode(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isTeleopMode
    fun getWebIsFieldCentric(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isFieldCentric
    fun getWebIsRedAlliance(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isRedAlliance
    fun getWebIsButtonAPressed(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isButtonAPressed
    fun getWebIsButtonBPressed(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isButtonBPressed
    fun getWebIsButtonXPressed(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isButtonXPressed
    fun getWebIsPoseReset(): Boolean = com.areslib.telemetry.SimInputBridge.currentFrame().isPoseReset
    fun getWebObstacles(): String = NT4Server.getString("ARES/Input/obstacles", "")
    fun getWebFieldConfig(): String = NT4Server.getString("ARES/Input/fieldConfig", "")

    /** Returns a canonical field document only when the dashboard publishes a new revision. */
    fun pollWebFieldConfig(): String? {
        val fieldConfigJson = getWebFieldConfig()
        return if (fieldConfigJson.isNotBlank() && fieldConfigJson != lastFieldConfigJson) {
            lastFieldConfigJson = fieldConfigJson
            fieldConfigJson
        } else {
            null
        }
    }

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
     * Publishes the simulator's dashboard pose under estimated-pose compatibility topics.
     *
     * [com.areslib.sim.DesktopSimLauncher] deliberately supplies Dyn4j ground truth because its local Redux state is
     * not the OpMode estimator. The same pose is mirrored to `Drive/Pose_X`, `Drive/Pose_Y`, and
     * `Drive/Pose_Heading` for the fused-pose dashboard contract.
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
        NT4Server.publishTopic("Drive/Pose_Heading", pose.heading.radians)
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
     * [count] is authoritative so consumers can discard stale tail records and reconcile removals.
     * An explicit zero count is always accompanied by the shared empty array.
     *
     * @param gamePieces Packed array representation of game pieces coordinates.
     * @param count Number of live seven-double records in [gamePieces].
     */
    fun publishGamePieces(gamePieces: DoubleArray, count: Int = gamePieces.size / GAME_PIECE_RECORD_WIDTH) {
        require(count >= 0 && count * GAME_PIECE_RECORD_WIDTH <= gamePieces.size) {
            "Game-piece count does not fit packed telemetry array"
        }
        val payload = if (count == 0) EMPTY_GAME_PIECES else gamePieces
        gamePiecesCountPublisher.set(count.toLong())
        gamePiecesPublisher.set(payload)
        com.areslib.networktables.NT4Server.publishTopic(
            com.areslib.telemetry.TelemetryTopicConstants.GAME_PIECES_COUNT,
            count.toLong()
        )
        com.areslib.networktables.NT4Server.publishTopic(
            com.areslib.telemetry.TelemetryTopicConstants.GAME_PIECES,
            payload
        )
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
     * Polls canonical topics under `ARES/Input/` from the custom NT4 server and copies them into
     * [driverStation]. Alliance changes are also dispatched to the active FTC robot store.
     *
     * @return A changed, non-blank obstacle JSON payload, otherwise `null`. Other input values are
     * applied on every call rather than freshness-gated.
     *
     * @param driverStation Target virtual driver station to synchronize.
     */
    fun pollWebInputs(driverStation: VirtualDriverStation): String? {
        val command = com.areslib.telemetry.SimInputBridge.pollNetworkFrame()
        driverStation.webVx = command.vx
        driverStation.webVy = command.vy
        driverStation.webOmega = command.omega

        // Dashboard clients connect to ARESLib's custom NT4 server. Read every web input from
        // that same registry; WPILib's process-local instance is a separate server and otherwise
        // leaves boolean/mode values stuck at their subscriber defaults.
        driverStation.isIntaking = command.isIntaking
        driverStation.isFlywheelOn = command.isFlywheelOn
        driverStation.isTransferring = command.isTransferring
        driverStation.isTeleopMode = command.isTeleopMode
        driverStation.isFieldCentric = command.isFieldCentric
        val newRedAlliance = command.isRedAlliance
        if (driverStation.isRedAlliance != newRedAlliance) {
            driverStation.isRedAlliance = newRedAlliance
            com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robot ->
                val allianceEnum = if (newRedAlliance) com.areslib.state.Alliance.RED else com.areslib.state.Alliance.BLUE
                robot.store.dispatch(com.areslib.action.RobotAction.SetAlliance(allianceEnum))
            }
        }
        driverStation.isButtonAPressed = command.isButtonAPressed
        driverStation.isButtonBPressed = command.isButtonBPressed
        driverStation.isButtonXPressed = command.isButtonXPressed
        driverStation.isPoseReset = command.isPoseReset
        val obstaclesJson = getWebObstacles()
        return if (obstaclesJson.isNotBlank() && obstaclesJson != lastObstaclesJson) {
            lastObstaclesJson = obstaclesJson
            obstaclesJson
        } else {
            null
        }
    }

    /** Caches one publisher per indicator name so repeated frames avoid NT topic lookups. */
    private val indicatorLightPublishers = mutableMapOf<String, edu.wpi.first.networktables.DoublePublisher>()

    /**
     * Publishes indicator light positions from [SuperstructureState] over NT4.
     * Uses cached publishers to avoid per-frame topic lookups.
     *
     * @param state Current immutable robot state containing the indicator positions.
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

    const val GAME_PIECE_RECORD_WIDTH = 7
    private val EMPTY_GAME_PIECES = DoubleArray(0)
}
