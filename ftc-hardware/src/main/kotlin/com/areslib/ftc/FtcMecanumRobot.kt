package com.areslib.ftc

import com.areslib.action.RobotAction
import com.areslib.control.assist.SysIdManager
import com.areslib.control.drivetrain.VisionAlignController
import com.areslib.control.tuning.PIDFCoefficients
import com.areslib.control.tuning.SimpleFeedforwardCoeffs
import com.areslib.ftc.calibration.FtcMecanumCalibrationController
import com.areslib.ftc.drivetrain.MecanumFallbackOdometry
import com.areslib.ftc.drivetrain.MecanumHardwareIO
import com.areslib.ftc.drivetrain.MecanumKinematicsController
import com.areslib.ftc.drivetrain.MecanumTrajectoryFollower
import com.areslib.ftc.telemetry.LimelightProxyAutoStart
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Vector3
import com.areslib.pathing.AutoBuilder
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.state.TuningState
import com.areslib.subsystem.DriveSubsystem
import com.areslib.subsystem.MecanumDriveFacade
import com.areslib.telemetry.logDriveMotor
import com.areslib.tuning.TuningManager
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import java.io.File

/**
 * Ready-to-use standard 4-wheel FTC Mecanum Robot facade in ARESLib-Kotlin.
 *
 * Extends [FtcBaseRobot] to provide out-of-the-box forward/inverse kinematics, Closed-Loop PIDF velocity motor control,
 * voltage-compensated feedforward drive scaling, path following, trajectory execution, and AprilTag alignment.
 *
 * ### Drivetrain Kinematics & Mechanics:
 * Wheel layout: Front-Left ($FL$), Front-Right ($FR$), Rear-Left ($RL$), Rear-Right ($RR$).
 * Given chassis velocity $\mathbf{v} = [v_x, v_y, \omega]^T$ ($m/s, m/s, rad/s$):
 * $$v_{FL} = v_x - v_y - \omega (L_x + L_y)$$
 * $$v_{FR} = v_x + v_y + \omega (L_x + L_y)$$
 * $$v_{RL} = v_x + v_y - \omega (L_x + L_y)$$
 * $$v_{RR} = v_x - v_y + \omega (L_x + L_y)$$
 * where track width $W = 2 L_y$ and wheel base $B = 2 L_x$ in meters ($m$).
 *
 * ### Physical Units:
 * - Position $(x, y)$: Meters ($m$)
 * - Heading $\theta$: Radians ($rad$), **CCW-positive** (0 rad = +X, $\pi/2$ rad = +Y)
 * - Linear Velocity: Meters per second ($m/s$)
 * - Angular Velocity: Radians per second ($rad/s$)
 * - Electrical: Volts ($V$), Amperes ($A$), Power Scaling $[0.0, 1.0]$
 * - Motor Encoders: Ticks per meter ($ticks/m$, default 2000.0 ticks/m)
 *
 * ### Zero-GC Compliance Guarantee:
 * All high-frequency update loops ([updateHardwareInputs], [updateSubsystems], [driveFieldCentric]) run with zero heap allocations.
 * Intermediate velocity buffers, wheel power arrays, and target poses are pre-allocated and updated in-place.
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 * @param flName Front Left motor hardware name. Defaults to `"fl"`.
 * @param frName Front Right motor hardware name. Defaults to `"fr"`.
 * @param rlName Rear Left motor hardware name. Defaults to `"rl"`.
 * @param rrName Rear Right motor hardware name. Defaults to `"rr"`.
 * @param flDirection Front Left motor direction.
 * @param frDirection Front Right motor direction.
 * @param rlDirection Rear Left motor direction.
 * @param rrDirection Rear Right motor direction.
 * @param pinpointName Hardware map name for GoBilda Pinpoint computer (or `null`).
 * @param limelightName Hardware map name for Limelight 3A camera (or `null`).
 * @param imuName Hardware map name for internal Control Hub IMU (default `"imu"`).
 * @param localTelemetry FTC telemetry channel for Driver Station / Dashboard logging.
 * @param trackWidthMeters Lateral distance between left and right wheel centers ($m$).
 * @param wheelBaseMeters Longitudinal distance between front and rear wheel centers ($m$).
 * @param headingGains PIDF gain coefficients for heading stabilization controller.
 * @param headingDeadzoneDeg Angular deadzone for heading targeting ($deg$).
 * @param driveFeedforward Feedforward coefficients $(kS, kV, kA)$ for motor voltage feedforward calculations.
 * @param useClosedLoopVelocity Enables FTC SDK velocity closed-loop control mode on motor encoders.
 * @param driveSlewRateLimit Maximum allowable acceleration slew rate ($1/s$).
 * @param pathTranslationGains PIDF gains for path-following translation controllers.
 * @param pathRotationGains PIDF gains for path-following heading controllers.
 * @param odomQx EKF process noise for X pose estimation ($m^2$).
 * @param odomQy EKF process noise for Y pose estimation ($m^2$).
 * @param odomQtheta EKF process noise for heading pose estimation ($rad^2$).
 * @param pinpointXOffsetMm GoBilda Pinpoint mounting offset along robot X-axis ($mm$).
 * @param pinpointYOffsetMm GoBilda Pinpoint mounting offset along robot Y-axis ($mm$).
 * @param pinpointEncoderResolution Pinpoint encoder resolution ($ticks/mm$).
 * @param pinpointXDirection Direction configuration for X odometry pod encoder.
 * @param pinpointYDirection Direction configuration for Y odometry pod encoder.
 * @param pinpointIsCcwPositive Physical mounting polarity flag. Set `true` if mounting orientation outputs CCW+ heading natively.
 * @param motorGains Motor velocity closed-loop PIDF coefficients.
 * @param ticksPerMeter Drive wheel encoder resolution ($ticks/m$).
 * @param visionStdDevs AprilTag vision measurement standard deviations $(m, m, rad)$.
 * @param visionFilterConfig Outlier rejection threshold parameters for vision updates.
 * @param reducer Custom state reducer function (defaults to [rootReducer]).
 */
open class FtcMecanumRobot @kotlin.jvm.JvmOverloads constructor(
    hardwareMap: HardwareMap,
    flName: String = "fl",
    frName: String = "fr",
    rlName: String = "rl",
    rrName: String = "rr",
    flDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    frDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    rlDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    rrDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    pinpointName: String? = null,
    limelightName: String? = null,
    imuName: String? = "imu",
    localTelemetry: Telemetry? = null,

    // Drivetrain Tunable Constants
    val trackWidthMeters: Double = 0.45,
    val wheelBaseMeters: Double = 0.45,
    val headingGains: PIDFCoefficients = PIDFCoefficients(2.2, 0.0, 0.12),
    val headingDeadzoneDeg: Double = 0.75,
    val driveFeedforward: SimpleFeedforwardCoeffs = SimpleFeedforwardCoeffs(0.05, 0.638, 0.02),
    val useClosedLoopVelocity: Boolean = false,
    val driveSlewRateLimit: Double? = null,
    val pathTranslationGains: PIDFCoefficients = PIDFCoefficients(2.0, 0.0, 0.2),
    val pathRotationGains: PIDFCoefficients = PIDFCoefficients(2.5, 0.0, 0.2),

    // EKF Process Noise Constants
    odomQx: Double = 0.01,
    odomQy: Double = 0.01,
    odomQtheta: Double = 0.01,

    // Pinpoint physical parameters
    pinpointXOffsetMm: Double = 0.0,
    pinpointYOffsetMm: Double = 0.0,
    pinpointEncoderResolution: Double? = null,
    pinpointXDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    pinpointYDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    pinpointIsCcwPositive: Boolean = true,

    // Motor Tunable Constants
    val motorGains: PIDFCoefficients? = PIDFCoefficients(0.4, 0.1, 0.01, 0.0),
    val ticksPerMeter: Double = 2000.0,

    // Vision Filtering Constants
    visionStdDevs: Vector3 = Vector3(0.35, 0.35, 0.80),
    visionFilterConfig: com.areslib.hardware.vision.VisionFilterConfig = com.areslib.hardware.vision.VisionFilterConfig.ftcDefaults(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) : FtcBaseRobot(
    hardwareMap = hardwareMap,
    pinpointName = pinpointName,
    limelightName = limelightName,
    imuName = imuName,
    localTelemetry = localTelemetry,
    odomQx = odomQx,
    odomQy = odomQy,
    odomQtheta = odomQtheta,
    pinpointXOffsetMm = pinpointXOffsetMm,
    pinpointYOffsetMm = pinpointYOffsetMm,
    pinpointEncoderResolution = pinpointEncoderResolution,
    pinpointXDirection = pinpointXDirection,
    pinpointYDirection = pinpointYDirection,
    pinpointIsCcwPositive = pinpointIsCcwPositive,
    visionStdDevs = visionStdDevs,
    visionFilterConfig = visionFilterConfig,
    reducer = reducer
) {

    /** Subsystem state holder for drive states. */
    val drive = DriveSubsystem(store)

    /** Subsystem facade managing closed-loop field-centric mecanum drive controls. */
    val mecanumDrive = MecanumDriveFacade(store, headingGains, headingDeadzoneDeg)

    private val visionAlignController = VisionAlignController()
    private val tuningManager = TuningManager(
        store = store,
        telemetry = telemetryManager.dataLoggingTelemetry,
        saveFile = File(if (isAndroid) "/sdcard/FIRST/ares_tuning.json" else "ares_tuning.json")
    )

    init {
        if (isAndroid) LimelightProxyAutoStart.start()
    }

    /** Low-level IO cluster managing physical drive motors ($FL, FR, RL, RR$). */
    val mecanumIO = MecanumHardwareIO(
        hardwareMap = hardwareMap,
        flName = flName, frName = frName, rlName = rlName, rrName = rrName,
        flDirection = flDirection, frDirection = frDirection, rlDirection = rlDirection, rrDirection = rrDirection,
        initialKs = driveFeedforward.kS,
        useClosedLoopVelocity = useClosedLoopVelocity,
        ticksPerMeter = ticksPerMeter,
        initialSlewRateLimit = driveSlewRateLimit,
        motorKp = motorGains?.kP, motorKi = motorGains?.kI, motorKd = motorGains?.kD, motorKf = motorGains?.kF
    )

    private var lastTuning: TuningState? = null
    private val calibrationController = FtcMecanumCalibrationController()
    private val fallbackOdometry = MecanumFallbackOdometry()

    // Delegated Controllers
    private val kinematicsController = MecanumKinematicsController(mecanumIO, drive, mecanumDrive, calibrationController)
    private val trajectoryFollower = MecanumTrajectoryFollower(drive)

    /** Accessor to System Identification (SysId) empirical gain characterization suite. */
    val sysIdManager: SysIdManager get() = calibrationController.sysIdManager

    /** Optional custom velocity supplier function for SysId calibration runs. */
    var customSysIdVelocityProvider: (() -> Double)?
        get() = calibrationController.customSysIdVelocityProvider
        set(value) { calibrationController.customSysIdVelocityProvider = value }

    /** Season flywheel used by the shared SysId mechanism adapter. */
    var sysIdFlywheelIO: com.areslib.hardware.actuator.FlywheelIO?
        get() = calibrationController.flywheelIO
        set(value) { calibrationController.flywheelIO = value }

    /** Autonomous trajectory builder providing high-level motion path generation. */
    val autoBuilder: AutoBuilder get() = trajectoryFollower.autoBuilder

    /** Shared follower used by native ARES auto compilation and online pathfinding. */
    val pathFollower get() = trajectoryFollower.pathfindFollower
    private var lastLocalTelemetryUpdateMs = 0L

    init {
        val maxSpeed = mecanumIO.maxWheelSpeedMetersPerSecond
        val maxAngularSpeed = maxSpeed / kinematicsController.kinematics.k
        drive.maxSpeedMps = maxSpeed
        drive.maxAngularSpeedRadiansPerSecond = maxAngularSpeed
        mecanumDrive.maxSpeedMps = maxSpeed
        mecanumDrive.maxAngularSpeedRps = maxAngularSpeed
    }

    /**
     * Refreshes all physical hardware inputs and runs live tuning updates.
     */
    override fun updateHardwareInputs() {
        com.areslib.hardware.HardwareRegistry.refreshAll()
        if (isLiveTuningEnabled) {
            tuningManager.update()
        }
        calibrationController.updateHardwareInputs(
            store = store,
            telemetryManager = telemetryManager,
            mecanumIO = mecanumIO,
            pinpointIO = pinpointIO,
            onResetTuning = { lastTuning = null }
        )
    }

    /**
     * Executes drivetrain subsystem updates, applying kinematic inverse solvers and voltage feedforward outputs.
     *
     * @param dtSeconds Delta time step in seconds ($s$).
     * @param batteryVoltage Measured battery voltage ($V$).
     * @param powerScale Brownout protection power scaling coefficient $[0.0, 1.0]$.
     */
    override fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double) {
        val currentTuning = store.state.tuning
        if (currentTuning !== lastTuning) {
            kinematicsController.updateTuning(currentTuning)
            trajectoryFollower.updateTuning(currentTuning)

            visionTracker.stdDevs = Vector3(currentTuning.visionStdDevsX, currentTuning.visionStdDevsY, currentTuning.visionStdDevsHeading)
            com.areslib.math.estimation.PoseEstimator.qX = currentTuning.odomQx
            com.areslib.math.estimation.PoseEstimator.qY = currentTuning.odomQy
            com.areslib.math.estimation.PoseEstimator.qTheta = currentTuning.odomQtheta

            pinpointIO?.let {
                if (currentTuning.pinpointXOffsetMm != 0.0 || currentTuning.pinpointYOffsetMm != 0.0) {
                    it.setOffsets(currentTuning.pinpointXOffsetMm, currentTuning.pinpointYOffsetMm)
                }
                if (currentTuning.pinpointEncoderResolution != 0.0) {
                    it.setEncoderResolution(currentTuning.pinpointEncoderResolution)
                }
            }
            lastTuning = currentTuning
        }

        kinematicsController.updateSubsystems(store, batteryVoltage, dtSeconds, telemetryManager) { lastTuning = null }
    }

    /**
     * Emits motor voltage, encoder velocities, current draw ($A$), and diagnostic parameters to Driver Station & NT4.
     *
     * @param timestamp System clock timestamp in milliseconds ($ms$).
     */
    override fun publishRobotTelemetry(timestamp: Long) {
        if (timestamp - lastLocalTelemetryUpdateMs >= 100L) {
            telemetryManager.customDriverStationText["Motor Powers"] = String.format("FL:%.2f | FR:%.2f | RL:%.2f | RR:%.2f",
                mecanumIO.flIO.power * mecanumIO.flIO.powerScale, mecanumIO.frIO.power * mecanumIO.frIO.powerScale,
                mecanumIO.rlIO.power * mecanumIO.rlIO.powerScale, mecanumIO.rrIO.power * mecanumIO.rrIO.powerScale
            )
            telemetryManager.customDriverStationText["Current Draw"] = if (powerManager.floodgate != null) {
                String.format("%.1f A (Physical)", powerManager.floodgate.current)
            } else {
                String.format("%.1f A (Estimated)", powerManager.currentAmps)
            }
            lastLocalTelemetryUpdateMs = timestamp
        }

        telemetryManager.dataLoggingTelemetry.logDriveMotor("fl", mecanumIO.flIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("fr", mecanumIO.frIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("rl", mecanumIO.rlIO)
        telemetryManager.dataLoggingTelemetry.logDriveMotor("rr", mecanumIO.rrIO)

        calibrationController.publishRobotTelemetry(
            timestamp, store, telemetryManager, mecanumIO, visionTracker, ticksPerMeter, 2000.0
        )
    }

    /**
     * Safely halts all drivetrain motors and resets output states.
     */
    override fun safeHardware() {
        com.areslib.hardware.HardwareRegistry.safeAll()
        stopAll()
    }

    /**
     * Drives the robot field-centrically with joystick normalized inputs.
     *
     * @param x Forward translation input $[-1.0, 1.0]$.
     * @param y Strafe translation input $[-1.0, 1.0]$.
     * @param rotation Angular rotation input $[-1.0, 1.0]$.
     */
    fun drive(x: Double, y: Double, rotation: Double) = driveFieldCentric(x, y, rotation)

    /**
     * Dispatches a field-centric driver command into the Redux store.
     *
     * @param x Field X forward velocity intent $[-1.0, 1.0]$.
     * @param y Field Y leftward velocity intent $[-1.0, 1.0]$.
     * @param rotation Angular CCW rotation velocity intent $[-1.0, 1.0]$.
     */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) {
        mecanumDrive.driveFieldRelativeNormalized(x, y, rotation)
    }

    /**
     * Dispatches a robot-centric driver command into the Redux store.
     *
     * @param x Robot forward velocity intent $[-1.0, 1.0]$.
     * @param y Robot strafe left velocity intent $[-1.0, 1.0]$.
     * @param rotation Angular CCW rotation velocity intent $[-1.0, 1.0]$.
     */
    fun driveRobotCentric(x: Double, y: Double, rotation: Double) {
        mecanumDrive.driveRobotRelativeNormalized(x, y, rotation)
    }

    /**
     * Locks drivetrain heading and position relative to an active Limelight AprilTag target ID.
     *
     * @param tagId AprilTag identification integer.
     */
    fun alignToTag(tagId: Int) {
        visionAlignController.calculate(store.state, tagId, true)?.let { store.dispatch(it) }
    }

    /**
     * Commands the path follower to navigate directly to a specified field coordinate target pose.
     *
     * @param targetPose Target destination pose $(x, y, \theta)$ ($m, m, rad$).
     * @param isRequested Enables or disables target tracking task.
     * @param mirrorForAlliance Inverts coordinate axes if current Redux state is Blue Alliance.
     */
    @kotlin.jvm.JvmOverloads
    fun driveToPose(targetPose: Pose2d, isRequested: Boolean, mirrorForAlliance: Boolean = true) {
        trajectoryFollower.driveToPose(store, mecanumIO, targetPose, isRequested, mirrorForAlliance)
    }

    /**
     * Commands the robot to navigate to a named waypoint loaded from autonomous configuration.
     *
     * @param name Named waypoint identifier string.
     * @param isRequested Enables or disables waypoint tracking task.
     * @param mirrorForAlliance Inverts coordinate axes if current Redux state is Blue Alliance.
     */
    @kotlin.jvm.JvmOverloads
    fun driveToWaypoint(name: String, isRequested: Boolean, mirrorForAlliance: Boolean = true) {
        trajectoryFollower.driveToWaypoint(store, mecanumIO, telemetryManager, name, isRequested, mirrorForAlliance)
    }

    /** Stops all hardware devices registered in [com.areslib.hardware.HardwareRegistry]. */
    fun stopAll() = com.areslib.hardware.HardwareRegistry.safeAll()
    
    /** Alias for [stopAll]. */
    fun stop() = stopAll()

    /**
     * Computes dead-reckoning fallback odometry when Pinpoint hardware is offline.
     *
     * @param timestampMs System clock timestamp in milliseconds ($ms$).
     * @return Formatted pose update structure derived from wheel encoder ticks.
     */
    override fun getFallbackPoseUpdate(timestampMs: Long): RobotAction.PoseUpdate {
        return fallbackOdometry.getFallbackPoseUpdate(
            timestampMs, mecanumIO.flIO.position, mecanumIO.frIO.position, mecanumIO.rlIO.position, mecanumIO.rrIO.position,
            store.state.tuning.ticksPerMeter, ticksPerMeter,
            cachedImuInputs.headingRadians,
            cachedImuInputs.yawVelocityRadPerSec
        )
    }

    override fun prepareFallbackOdometry(pose: Pose2d, rawImuHeadingRadians: Double) {
        fallbackOdometry.reset(pose, rawImuHeadingRadians)
    }

    /**
     * Shuts down subsystem threads, disables motor hardware, and clears proxy servers.
     */
    override fun close() {
        super.close()
        if (isAndroid) LimelightProxyAutoStart.start()
    }
}
