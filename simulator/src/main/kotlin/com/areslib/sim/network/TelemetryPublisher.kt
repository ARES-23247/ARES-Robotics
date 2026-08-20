package com.areslib.sim.network

import com.areslib.networktables.NT4Server
import com.areslib.math.geometry.ChassisSpeeds
import com.areslib.state.RobotState
import com.areslib.sim.infra.SimGamepadManager
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
    private val simulatorPoseFrameBuf = DoubleArray(SIMULATOR_POSE_FRAME_VALUE_COUNT)
    private var simulatorPoseFrameSequence = 0L

    private var lastObstaclesJson = ""
    private var lastFieldConfigJson = ""

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
        publishTargetPose(pose.x, pose.y, pose.heading.radians)
    }

    /** Allocation-free primitive pose publication for the simulator hot loop. */
    fun publishTargetPose(x: Double, y: Double, headingRadians: Double) {
        targetPoseBuf[0] = x
        targetPoseBuf[1] = y
        targetPoseBuf[2] = headingRadians
        targetPosePublisher.set(targetPoseBuf)
        NT4Server.publishTopic("ARES/TargetPose", targetPoseBuf)
        ntInst.flush()
    }

    /**
     * Legacy compatibility helper for a simulator that has no Redux [RobotState] publisher.
     *
     * [com.areslib.sim.DesktopSimLauncher] must not call this method: [publish] already writes the
     * real Redux EKF to `Drive/Pose_*` and the three `ARES/EstimatedPose` scalar topics. Calling
     * both in one frame
     * alternates EKF and physics truth under the same topic names, hides estimator behavior, and
     * produces a moving ghost in clients that render the intermediate update.
     */
    @Deprecated(
        message = "DesktopSimLauncher must publish the active RobotState; physics truth belongs on ARES/TruePose/*",
        level = DeprecationLevel.ERROR,
    )
    fun publishEstimatedPose(pose: com.areslib.math.geometry.Pose2d) {
        publishEstimatedPoseLegacy(pose.x, pose.y, pose.heading.radians)
    }

    /** Legacy primitive overload; see [publishEstimatedPose]. */
    @Deprecated(
        message = "DesktopSimLauncher must publish the active RobotState; physics truth belongs on ARES/TruePose/*",
        level = DeprecationLevel.ERROR,
    )
    fun publishEstimatedPose(x: Double, y: Double, headingRadians: Double) {
        publishEstimatedPoseLegacy(x, y, headingRadians)
    }

    private fun publishEstimatedPoseLegacy(x: Double, y: Double, headingRadians: Double) {
        estimatedPoseBuf[0] = x
        estimatedPoseBuf[1] = y
        estimatedPoseBuf[2] = headingRadians
        estimatedPosePublisher.set(estimatedPoseBuf)
        NT4Server.publishTopic("ARES/EstimatedPose", estimatedPoseBuf)
        NT4Server.publishTopic("ARES/EstimatedPose/0", x)
        NT4Server.publishTopic("ARES/EstimatedPose/1", y)
        NT4Server.publishTopic("ARES/EstimatedPose/2", headingRadians)
        NT4Server.publishTopic("Drive/Pose_X", x)
        NT4Server.publishTopic("Drive/Pose_Y", y)
        NT4Server.publishTopic("Drive/Pose_Heading", headingRadians)
        ntInst.flush()
    }

    /**
     * Publishes the true ground truth physics pose from Dyn4j.
     *
     * @param pose The true field-relative physics pose.
     */
    fun publishTruePose(pose: com.areslib.math.geometry.Pose2d) {
        publishTruePose(pose.x, pose.y, pose.heading.radians)
    }

    /** Allocation-free primitive pose publication for the simulator hot loop. */
    fun publishTruePose(x: Double, y: Double, headingRadians: Double) {
        truePoseBuf[0] = x
        truePoseBuf[1] = y
        truePoseBuf[2] = headingRadians
        truePosePublisher.set(truePoseBuf)
        NT4Server.publishTopic("ARES/TruePose", truePoseBuf)
        NT4Server.publishTopic("ARES/TruePose/0", x)
        NT4Server.publishTopic("ARES/TruePose/1", y)
        NT4Server.publishTopic("ARES/TruePose/2", headingRadians)
        ntInst.flush()
    }

    /**
     * Publishes one atomic dashboard frame after the Redux state for this observation is complete.
     *
     * Layout: `[trueX, trueY, trueHeading, ekfX, ekfY, ekfHeading, odomX, odomY,
     * odomHeading, sequence]`. The changing sequence forces delivery even when the robot and its
     * heading are stationary; Analytics commits only after receiving the final array element.
     */
    internal fun publishSimulatorPoseFrame(
        trueX: Double,
        trueY: Double,
        trueHeading: Double,
        state: RobotState,
    ) {
        val estimator = state.drive.poseEstimator
        simulatorPoseFrameBuf[0] = trueX
        simulatorPoseFrameBuf[1] = trueY
        simulatorPoseFrameBuf[2] = trueHeading
        simulatorPoseFrameBuf[3] = estimator.estimatedPoseX
        simulatorPoseFrameBuf[4] = estimator.estimatedPoseY
        simulatorPoseFrameBuf[5] = estimator.estimatedPoseHeading
        simulatorPoseFrameBuf[6] = state.drive.odometryX
        simulatorPoseFrameBuf[7] = state.drive.odometryY
        simulatorPoseFrameBuf[8] = state.drive.odometryHeading
        simulatorPoseFrameBuf[9] = simulatorPoseFrameSequence.toDouble()
        simulatorPoseFrameSequence = if (simulatorPoseFrameSequence >= MAX_EXACT_DOUBLE_INTEGER) {
            0L
        } else {
            simulatorPoseFrameSequence + 1L
        }
        NT4Server.publishTopic(SIMULATOR_POSE_FRAME_TOPIC, simulatorPoseFrameBuf)
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
     * [driverStation]. A live atomic lease temporarily owns all command fields; when it expires,
     * untouched local keyboard/gamepad state resumes. Alliance changes are dispatched to the
     * active FTC robot store from that effective authority.
     *
     * @return A changed, non-blank obstacle JSON payload, otherwise `null`. Other input values are
     * applied only while the atomic receiver lease is valid.
     *
     * @param driverStation Target virtual driver station to synchronize.
     */
    fun pollWebInputs(driverStation: SimGamepadManager): String? {
        val command = com.areslib.telemetry.SimInputBridge.pollNetworkFrame()
        if (command.sessionNonce > 0L && command.receivedAtMs >= 0L) {
            driverStation.applyRemoteCommand(command)
        } else {
            driverStation.clearRemoteCommand()
        }

        val effectiveRedAlliance = driverStation.effectiveIsRedAlliance
        com.areslib.ftc.FtcBaseRobot.activeInstance?.let { robot ->
            val allianceEnum = if (effectiveRedAlliance) com.areslib.state.Alliance.RED else com.areslib.state.Alliance.BLUE
            if (robot.store.state.drive.alliance != allianceEnum) {
                robot.store.dispatch(com.areslib.action.RobotAction.SetAlliance(allianceEnum))
            }
        }
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
    private const val SIMULATOR_POSE_FRAME_TOPIC = "ARES/SimulatorPoseFrame"
    private const val SIMULATOR_POSE_FRAME_VALUE_COUNT = 10
    private const val MAX_EXACT_DOUBLE_INTEGER = 9_007_199_254_740_991L
    private val EMPTY_GAME_PIECES = DoubleArray(0)
}
