package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import com.areslib.subsystem.AresRobot
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.telemetry.FtcLoopProfiler
import com.areslib.ftc.power.FtcPowerManager
import com.areslib.action.RobotAction
import com.areslib.hardware.sensor.ImuIO
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.state.VisionState
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.math.geometry.Vector3
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.ftc.core.FtcHardwareInitializer
import com.areslib.ftc.core.FtcOpModeLifecycleController

/**
 * Abstract base class for all FTC robots.
 * Manages unified hardware registries, performance parameters,
 * power managers, telemetry pipelines, and vision trackers.
 */
abstract class FtcBaseRobot @kotlin.jvm.JvmOverloads constructor(
    val hardwareMap: HardwareMap,
    val pinpointName: String? = "pinpoint",
    val limelightName: String? = "limelight",
    val imuName: String? = "imu",
    protected val localTelemetry: Telemetry? = null,

    // EKF Process noise
    val odomQx: Double = 0.01,
    val odomQy: Double = 0.01,
    val odomQtheta: Double = 0.01,

    // Pinpoint physical parameters
    val pinpointXOffsetMm: Double = 0.0,
    val pinpointYOffsetMm: Double = 0.0,
    val pinpointEncoderResolution: Double? = null,
    val pinpointXDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    val pinpointYDirection: com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection = com.qualcomm.hardware.gobilda.GoBildaPinpointDriver.EncoderDirection.FORWARD,
    val pinpointIsCcwPositive: Boolean = false,

    // Vision Configuration
    val visionStdDevs: Vector3 = Vector3(0.35, 0.35, 0.80),
    val visionFilterConfig: VisionFilterConfig = VisionFilterConfig.ftcDefaults(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) : AresRobot(
    initialState = RobotState(
        vision = VisionState(
            filterConfig = visionFilterConfig
        ),
        tuning = com.areslib.state.TuningState(
            localization = com.areslib.state.LocalizationTuningState(
                pinpointXOffsetMm = pinpointXOffsetMm,
                pinpointYOffsetMm = pinpointYOffsetMm,
                pinpointEncoderResolution = pinpointEncoderResolution ?: 20.44
            )
        )
    ),
    reducer = reducer
) {

    private val lifecycleController = FtcOpModeLifecycleController()
    private val hardwareInitializer = FtcHardwareInitializer(
        hardwareMap, pinpointName, limelightName, imuName,
        pinpointXOffsetMm, pinpointYOffsetMm, pinpointEncoderResolution,
        pinpointXDirection, pinpointYDirection, pinpointIsCcwPositive
    )

    init {
        com.areslib.hardware.HardwareRegistry.clear()
        lifecycleController.init(hardwareMap)
        activeInstance = this

        com.areslib.math.estimation.PoseEstimator.qX = odomQx
        com.areslib.math.estimation.PoseEstimator.qY = odomQy
        com.areslib.math.estimation.PoseEstimator.qTheta = odomQtheta
    }

    companion object {
        val isAndroid: Boolean by lazy {
            val javaVendor = System.getProperty("java.vendor") ?: ""
            javaVendor.contains("Android", ignoreCase = true) || java.io.File("/sdcard").exists()
        }
        @Volatile
        @JvmStatic
        var activeInstance: FtcBaseRobot? = null
    }

    // Telemetry & recording pipelines
    val telemetryManager = FtcTelemetryManager(store)
    val powerManager = FtcPowerManager(hardwareMap)
    val profiler = FtcLoopProfiler()
    var isLiveTuningEnabled: Boolean = false

    // Sensors
    val pinpointIO: PinpointIO? get() = hardwareInitializer.pinpointIO
    val imuIO: ImuIO? get() = hardwareInitializer.imuIO
    val limelightIO: VisionIO? get() = hardwareInitializer.limelightIO

    // Vision Tracker
    val visionTracker = FtcVisionTracker(store, limelightIO, pinpointIO, visionStdDevs)

    private var lastPinpointWarningTime = 0L
    protected var lastUpdateTime = 0L
    private var hasReadSensorsThisFrame = false

    /**
     * readSensors declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun readSensors() {
        if (hasReadSensorsThisFrame) return
        hasReadSensorsThisFrame = true

        val s0 = com.areslib.util.RobotClock.nanoTime()
        com.areslib.ftc.hardware.FtcPerformanceManager.clearBulkCaches()
        val s1 = com.areslib.util.RobotClock.nanoTime()

        val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
        updateHardwareInputs()
        val s2 = com.areslib.util.RobotClock.nanoTime()

        val poseUpdate = pinpointIO?.getPoseUpdate() ?: getFallbackPoseUpdate(timestamp)
        val s3 = com.areslib.util.RobotClock.nanoTime()

        val isPinpointStale = pinpointIO != null && poseUpdate.timestampMs != 0L && (timestamp - poseUpdate.timestampMs) > 100
        val age = timestamp - poseUpdate.timestampMs
        when {
            isPinpointStale && (timestamp - lastPinpointWarningTime > 2000L) -> {
                System.err.println("FtcBaseRobot: Pinpoint pose update is stale! Age: ${age}ms")
                lastPinpointWarningTime = timestamp
            }
        }
        store.dispatch(poseUpdate)

        visionTracker.update(timestamp)
        val s4 = com.areslib.util.RobotClock.nanoTime()

        profiler.recordSensorsProfiling(
            bulkMs = (s1 - s0) / 1_000_000.0,
            inputsMs = (s2 - s1) / 1_000_000.0,
            pinpointMs = (s3 - s2) / 1_000_000.0,
            visionMs = (s4 - s3) / 1_000_000.0
        )
        profiler.publishSensorsProfiling(telemetryManager)
    }

    protected open fun getFallbackPoseUpdate(timestampMs: Long): RobotAction.PoseUpdate {
        var heading = 0.0
        var yawVel = 0.0
        imuIO?.let {
            val inputs = com.areslib.hardware.sensor.ImuInputs()
            it.updateInputs(inputs)
            heading = inputs.headingRadians
            yawVel = inputs.yawVelocityRadPerSec
        }
        return RobotAction.PoseUpdate(
            xMeters = 0.0,
            yMeters = 0.0,
            headingRadians = heading,
            angularVelocityRadiansPerSecond = yawVel,
            timestampMs = timestampMs
        )
    }

    /**
     * update declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    private var lastWallTime: Long = 0L

    fun update(gamepad1: com.areslib.telemetry.GamepadState? = null, gamepad2: com.areslib.telemetry.GamepadState? = null) {
        lifecycleController.sleepForTargetDt(lastWallTime, isAndroid)
        lastWallTime = System.currentTimeMillis()
        lifecycleController.update()

        try {
            val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
            val dtSeconds = if (lastUpdateTime == 0L || timestamp == lastUpdateTime) 0.02 else (timestamp - lastUpdateTime) / 1000.0
            lastUpdateTime = timestamp

            val t0 = com.areslib.util.RobotClock.nanoTime()
            readSensors()
            hasReadSensorsThisFrame = false
            val t1 = com.areslib.util.RobotClock.nanoTime()

            val effectiveScale = powerManager.update(dtSeconds, timestamp)
            val batteryVoltage = powerManager.batteryVoltage
            val t2 = com.areslib.util.RobotClock.nanoTime()

            updateSubsystems(dtSeconds, batteryVoltage, effectiveScale)
            val t3 = com.areslib.util.RobotClock.nanoTime()

            telemetryManager.publishFull(
                state = store.state,
                gamepad1 = gamepad1,
                gamepad2 = gamepad2,
                dtSeconds = dtSeconds,
                batteryVoltage = batteryVoltage,
                visionTracker = visionTracker,
                timestamp = timestamp,
                localTelemetry = localTelemetry,
                onSubclassPublish = { publishRobotTelemetry(timestamp) }
            )
            val t4 = com.areslib.util.RobotClock.nanoTime()

            profiler.recordAndPublishLoopDiagnostics(telemetryManager, t0, t1, t2, t3, t4)
            lifecycleController.sleepRemaining(timestamp, isAndroid)

        } catch (e: Throwable) {
            if (e is InterruptedException || e.cause is InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            System.err.println("FtcBaseRobot: Exception in update loop: ${e.message}")
            e.printStackTrace()
            safeHardware()
            try {
                telemetryManager.dataLoggingTelemetry.putString("Robot/Error", "FATAL CRASH: ${e.message}")
            } catch (_: Throwable) {}
        }
    }

    protected abstract fun updateHardwareInputs()
    protected abstract fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double)
    protected abstract fun publishRobotTelemetry(timestamp: Long)
    /**
     * safeHardware declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    abstract fun safeHardware()

    @kotlin.jvm.JvmOverloads
    /**
     * resetPose declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun resetPose(pose: Pose2d = Pose2d(), resetHardware: Boolean = false) {
        pinpointIO?.initialize(pose, resetHardware = resetHardware)
        visionTracker.hasInitializedPoseWithVision = true
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = pose.x,
                yMeters = pose.y,
                headingRadians = pose.heading.radians,
                timestampMs = com.areslib.util.RobotClock.currentTimeMillis(),
                isReset = true
            )
        )
    }

    /**
     * resetPoseForAlliance declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun resetPoseForAlliance() {
        val alliance = store.state.drive.alliance
        val startPose = if (alliance == com.areslib.state.Alliance.RED) {
            Pose2d(0.0, -1.2, Rotation2d(Math.PI / 2.0))
        } else {
            Pose2d(0.0, 1.2, Rotation2d(-Math.PI / 2.0))
        }
        resetPose(startPose)
    }

    /**
     * close declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    open fun close() {
        activeInstance = null
        safeHardware()
        lifecycleController.close()
        telemetryManager.close()
        hardwareInitializer.close()
    }
}
