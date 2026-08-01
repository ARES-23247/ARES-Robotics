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
 * An out-of-the-box standard FTC Mecanum Robot base class.
 *
 * Provides standard bindings for a 4-wheel mecanum drive and generic superstructure execution.
 * Handles WPILib-style background subsystem execution via an internal Redux Store.
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

    val drive = DriveSubsystem(store)
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

    val sysIdManager: SysIdManager get() = calibrationController.sysIdManager
    var customSysIdVelocityProvider: (() -> Double)?
        get() = calibrationController.customSysIdVelocityProvider
        set(value) { calibrationController.customSysIdVelocityProvider = value }

    val autoBuilder: AutoBuilder get() = trajectoryFollower.autoBuilder
    private var lastLocalTelemetryUpdateMs = 0L

    init {
        val maxSpeed = mecanumIO.maxWheelSpeedMetersPerSecond
        drive.maxSpeedMps = maxSpeed
        mecanumDrive.maxSpeedMps = maxSpeed
        mecanumDrive.maxAngularSpeedRps = maxSpeed / kinematicsController.kinematics.k
    }

    /**
     * updateHardwareInputs declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
     * updateSubsystems declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
     * publishRobotTelemetry declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
            timestamp, store, telemetryManager, mecanumIO, imuIO, visionTracker, ticksPerMeter, 2000.0
        )
    }

    /**
     * safeHardware declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun safeHardware() {
        com.areslib.hardware.HardwareRegistry.safeAll()
        stopAll()
    }

    /**
     * drive declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun drive(x: Double, y: Double, rotation: Double) = driveFieldCentric(x, y, rotation)

    /**
     * driveFieldCentric declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun driveFieldCentric(x: Double, y: Double, rotation: Double) {
        mecanumDrive.fieldRelativeDrive(x, y, rotation)
    }

    /**
     * driveRobotCentric declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun driveRobotCentric(x: Double, y: Double, rotation: Double) {
        store.dispatch(RobotAction.JoystickDriveIntent(x, y, rotation, isFieldCentric = false))
    }

    /**
     * alignToTag declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun alignToTag(tagId: Int) {
        visionAlignController.calculate(store.state, tagId, true)?.let { store.dispatch(it) }
    }

    @kotlin.jvm.JvmOverloads
    /**
     * driveToPose declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun driveToPose(targetPose: Pose2d, isRequested: Boolean, mirrorForAlliance: Boolean = true) {
        trajectoryFollower.driveToPose(store, mecanumIO, targetPose, isRequested, mirrorForAlliance)
    }

    @kotlin.jvm.JvmOverloads
    /**
     * driveToWaypoint declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun driveToWaypoint(name: String, isRequested: Boolean, mirrorForAlliance: Boolean = true) {
        trajectoryFollower.driveToWaypoint(store, mecanumIO, telemetryManager, name, isRequested, mirrorForAlliance)
    }

    /**
     * stopAll declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun stopAll() = com.areslib.hardware.HardwareRegistry.safeAll()
    
    /**
     * stop declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun stop() = stopAll()

    /**
     * followTrajectory declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun followTrajectory(path: Any? = null) { } // Compatibility alias as requested

    /**
     * getFallbackPoseUpdate declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun getFallbackPoseUpdate(timestampMs: Long): RobotAction.PoseUpdate {
        val heading = imuIO?.let {
            val inputs = com.areslib.hardware.sensor.ImuInputs()
            it.updateInputs(inputs)
            inputs.headingRadians
        } ?: 0.0

        return fallbackOdometry.getFallbackPoseUpdate(
            timestampMs, mecanumIO.flIO.position, mecanumIO.frIO.position, mecanumIO.rlIO.position, mecanumIO.rrIO.position,
            store.state.tuning.ticksPerMeter, ticksPerMeter, heading
        )
    }

    /**
     * close declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun close() {
        super.close()
        if (isAndroid) LimelightProxyAutoStart.start()
    }
}
