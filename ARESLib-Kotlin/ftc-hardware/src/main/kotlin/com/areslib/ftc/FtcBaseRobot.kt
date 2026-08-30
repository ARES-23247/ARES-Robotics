package com.areslib.ftc

import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.robotcore.external.Telemetry
import com.areslib.subsystem.AresRobot
import com.areslib.ftc.drivetrain.PinpointIO
import com.areslib.ftc.drivetrain.FtcOdometrySource
import com.areslib.ftc.drivetrain.FtcOdometrySourceArbiter
import com.areslib.hardware.vision.VisionIO
import com.areslib.ftc.vision.FtcVisionTracker
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.ftc.telemetry.FtcLoopProfiler
import com.areslib.ftc.power.FtcPowerManager
import com.areslib.action.RobotAction
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.sensor.ImuInputs
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.state.VisionState
import com.areslib.hardware.vision.VisionFilterConfig
import com.areslib.math.geometry.Vector3
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.ftc.core.FtcHardwareInitializer
import com.areslib.ftc.core.FtcOpModeLifecycleController
import com.areslib.hardware.HardwareRegistry

/**
 * Abstract foundational base class for all FTC robots in ARESLib-Kotlin.
 *
 * `FtcBaseRobot` serves as the central hardware-to-software bridge on FTC target platforms.
 * It manages the lifecycle of the Redux state engine ([store]), physical sensor polling,
 * Extended Kalman Filter (EKF) localization fusion, vision tracking pipelines, power management,
 * loop-time diagnostics profiling, and NT4/Driver Station telemetry.
 *
 * ### Architectural & State Flow:
 * ```
 * Physical Sensors (Pinpoint/IMU/Limelight) ──> readSensors() ──> Dispatch RobotAction.PoseUpdate
 *                                                                             │
 * Redux Store State Transition <── rootReducer <──────────────────────────────┘
 *             │
 *             ├──> updateSubsystems() ──> Physical Motor Outputs / Servos
 *             └──> FtcTelemetryManager ──> Driver Station / Cloud / Log Files
 * ```
 *
 * ### Mathematical Formulations & Coordinate Conventions:
 * - **Field Coordinate Frame**: Origin at field center. Axis $+X$ points forward (0 rad), $+Y$ points left ($\pi/2$ rad, toward Blue Alliance station wall).
 * - **Heading Convention**: Counter-Clockwise (CCW) positive math standard:
 *   $$\theta \in [-\pi, \pi], \quad 0 \text{ rad} = +X, \quad +\frac{\pi}{2} \text{ rad} = +Y$$
 * - **EKF Process Noise Covariance**:
 *   $$\mathbf{Q} = \text{diag}(\sigma_x^2, \sigma_y^2, \sigma_\theta^2) = \text{diag}(\text{odomQx}, \text{odomQy}, \text{odomQtheta})$$
 *
 * ### Hardware Boundaries:
 * - **GoBilda Pinpoint Odometry Computer**: Mounted $x/y$ offsets in millimeters ($mm$). Direction defaults to FORWARD.
 *   Heading polarity is normalized to CCW-positive natively at the [PinpointIO] layer (using `pinpointIsCcwPositive`).
 * - **Limelight 3D Vision**: Fused via [FtcVisionTracker] with customizable standard deviation covariance $\mathbf{R}_{\text{vision}} = \text{diag}(\sigma_x^2, \sigma_y^2, \sigma_\theta^2)$.
 *
 * ### Allocation behavior:
 * Sensor and actuator adapters reuse their loop buffers. Redux state transitions and telemetry may
 * allocate, so target-device loop jitter—not a blanket zero-GC claim—is the performance contract.
 * Sensor values, bulk readers, and diagnostics vectors rely on pre-allocated object instances and thread-safe primitive registers.
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 * @param pinpointName Hardware map name for the GoBilda Pinpoint computer (e.g., `"pinpoint"`). Pass `null` if disabled.
 * @param limelightName Hardware map name for the Limelight 3A camera (e.g., `"limelight"`). Pass `null` if disabled.
 * @param imuName Hardware map name for the REV Lynx/Control Hub IMU (e.g., `"imu"`). Pass `null` if disabled.
 * @param localTelemetry FTC Driver Station [Telemetry] instance for on-screen debugging.
 * @param odomQx EKF process noise covariance along X axis ($m^2$).
 * @param odomQy EKF process noise covariance along Y axis ($m^2$).
 * @param odomQtheta EKF process noise covariance for heading ($rad^2$).
 * @param pinpointXOffsetMm Pinpoint computer physical mounting offset along robot X-axis ($mm$).
 * @param pinpointYOffsetMm Pinpoint computer physical mounting offset along robot Y-axis ($mm$).
 * @param pinpointEncoderResolution Optional custom pod resolution ($ticks/mm$). Null selects the FTC SDK's GoBilda 4-Bar pod calibration.
 * @param pinpointXDirection Direction configuration for X odometry pod encoder.
 * @param pinpointYDirection Direction configuration for Y odometry pod encoder.
 * @param pinpointIsCcwPositive `true` for the normal native CCW-positive Pinpoint convention.
 * @param visionStdDevs Initial standard deviations $(\sigma_x, \sigma_y, \sigma_\theta)$ for AprilTag pose updates ($m, m, rad$).
 * @param visionFilterConfig Outlier rejection threshold and gating configuration for vision updates.
 * @param reducer Redux state reducer function. Defaults to root [rootReducer].
 *
 * @see com.areslib.subsystem.AresRobot
 * @see com.areslib.ftc.drivetrain.PinpointIO
 * @see com.areslib.ftc.vision.FtcVisionTracker
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
    val pinpointIsCcwPositive: Boolean = true,

    // Vision Configuration
    val visionStdDevs: Vector3 = Vector3(0.35, 0.35, 0.80),
    val visionFilterConfig: VisionFilterConfig = VisionFilterConfig.ftcDefaults(),
    /** Canonical generated tuning state used before any controller or tuning transport initializes. */
    initialTuningState: com.areslib.state.TuningState = com.areslib.state.TuningState(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
    hardwareRegistry: HardwareRegistry = HardwareRegistry(),
) : AresRobot(
    initialState = RobotState(
        vision = VisionState(
            filterConfig = visionFilterConfig
        ),
        tuning = initialTuningState.copy(
            localization = initialTuningState.localization.copy(
                ftcPinpoint = com.areslib.state.FtcPinpointTuningState(
                    xOffsetMm = pinpointXOffsetMm,
                    yOffsetMm = pinpointYOffsetMm,
                    // Zero means "retain the named pod calibration selected during hardware init".
                    encoderResolution = pinpointEncoderResolution ?: 0.0
                )
            )
        )
    ),
    reducer = reducer,
    hardwareRegistry = hardwareRegistry,
) {
    /** Optional simulator-only view of cached, post-safety season mechanism outputs. */
    @Volatile
    var simMechanismOutputProvider: com.areslib.hardware.SimMechanismOutputProvider? = null


    private val lifecycleController = FtcOpModeLifecycleController()
    private val hardwareInitializer = FtcHardwareInitializer(
        hardwareMap, pinpointName, limelightName, imuName,
        pinpointXOffsetMm, pinpointYOffsetMm, pinpointEncoderResolution,
        pinpointXDirection, pinpointYDirection, pinpointIsCcwPositive
    )

    init {
        lifecycleController.init(hardwareMap)
        com.areslib.telemetry.RobotStatusTracker.odometrySource = FtcOdometrySource.UNINITIALIZED.name
        com.areslib.telemetry.RobotStatusTracker.odometryStatus = "UNKNOWN"

        com.areslib.math.estimation.PoseEstimator.qX = odomQx
        com.areslib.math.estimation.PoseEstimator.qY = odomQy
        com.areslib.math.estimation.PoseEstimator.qTheta = odomQtheta
    }

    companion object {
        private const val IMU_MAX_SAMPLE_AGE_MS = 100L
        /**
         * Evaluates whether the current runtime environment is an Android OS target (Control Hub / Driver Station).
         */
        val isAndroid: Boolean by lazy {
            val javaVendor = System.getProperty("java.vendor") ?: ""
            javaVendor.contains("Android", ignoreCase = true) || java.io.File("/sdcard").exists()
        }

        /**
         * Static reference to the currently active [FtcBaseRobot] instance.
         */
        @Volatile
        @JvmStatic
        var activeInstance: FtcBaseRobot? = null
    }

    /** Telemetry manager handling dashboard NetworkTables and local logging pipelines. */
    val telemetryManager = FtcTelemetryManager(store, hardwareRegistry)

    /** Robot power monitor tracking battery voltage ($V$) and total current consumption ($A$). */
    val powerManager = FtcPowerManager(hardwareMap, hardwareRegistry)

    /** High-precision loop profiler tracking nano-second hardware reads and subsystem compute stages. */
    val profiler = FtcLoopProfiler()

    /** Toggles real-time live-tuning configuration over NetworkTables. */
    var isLiveTuningEnabled: Boolean = false

    /** GoBilda Pinpoint odometry computer IO layer interface. */
    val pinpointIO: PinpointIO? get() = hardwareInitializer.pinpointIO

    /** Control Hub internal IMU sensor IO layer interface. */
    val imuIO: ImuIO? get() = hardwareInitializer.imuIO

    /** Limelight 3D vision IO layer interface. */
    val limelightIO: VisionIO? get() = hardwareInitializer.limelightIO

    /** AprilTag localization and pose fusion tracking engine. */
    val visionTracker = FtcVisionTracker(
        store,
        limelightIO,
        pinpointIO,
        visionStdDevs,
        onOdometryReseed = ::reseedOdometrySources
    )

    private var lastPinpointWarningTime = 0L
    protected var lastUpdateTime = 0L
    private var hasReadSensorsThisFrame = false
    private val odometrySourceArbiter = FtcOdometrySourceArbiter()
    private var heldFallbackX = 0.0
    private var heldFallbackY = 0.0
    private var heldFallbackHeadingOffset = 0.0

    /** Cached independent Control Hub IMU sample used by drivetrain fallback odometry. */
    protected val cachedImuInputs = ImuInputs()
    private val imuSampleBuffer = ImuInputs()

    /** First fatal loop failure. A robot instance remains inhibited after this is set. */
    @Volatile
    var fatalUpdateFailure: Throwable? = null
        private set

    /** Source currently responsible for advancing the FTC EKF process model. */
    val activeOdometrySource: FtcOdometrySource
        get() = odometrySourceArbiter.activeSource

    /**
     * Executes the cached sensor-sampling cycle for the current robot loop frame.
     *
     * 1. Clears REV Lynx Hub bulk caches via [FtcPerformanceManager.clearBulkCaches].
     * 2. Calls [updateHardwareInputs] for subclass motor and encoder polling.
     * 3. Fetches pose updates from [pinpointIO] or fallback IMU dead-reckoning.
     * 4. Dispatches the resulting [RobotAction.PoseUpdate] into the Redux [store].
     * 5. Updates the [visionTracker] pipeline.
     * 6. Records execution metrics in [profiler].
     *
     * Implementations should reuse hardware input buffers and avoid blocking work in this cycle.
     */
    fun readSensors() {
        if (hasReadSensorsThisFrame) return
        hasReadSensorsThisFrame = true
        try {
        val s0 = com.areslib.util.RobotClock.nanoTime()
        com.areslib.ftc.hardware.FtcPerformanceManager.clearBulkCaches()
        val s1 = com.areslib.util.RobotClock.nanoTime()

        val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
        updateHardwareInputs()
        refreshCachedImu(timestamp)
        val s2 = com.areslib.util.RobotClock.nanoTime()

        val pinpoint = pinpointIO
        val pinpointCandidate = pinpoint?.getPoseUpdate()
        val pinpointHealthy = pinpoint != null && pinpointCandidate != null && pinpoint.isHealthy(timestamp)
        val previousSource = odometrySourceArbiter.activeSource
        var selectedSource = odometrySourceArbiter.update(pinpoint != null, pinpointHealthy)

        var poseUpdate: RobotAction.PoseUpdate
        if (selectedSource == FtcOdometrySource.PINPOINT && pinpoint != null && pinpointCandidate != null) {
            poseUpdate = if (previousSource == FtcOdometrySource.DRIVETRAIN_FALLBACK) {
                // Rebase the recovered primary to the current fused pose before publishing
                // it. This prevents a discontinuity if the robot moved during the outage.
                pinpoint.initialize(store.state.drive.poseEstimator.estimatedPose, resetHardware = false)
                val rebased = pinpoint.getPoseUpdate()
                if (pinpoint.lastInitializeSucceeded && pinpoint.isHealthy(timestamp)) {
                    rebased
                } else {
                    odometrySourceArbiter.forceFallback()
                    selectedSource = FtcOdometrySource.DRIVETRAIN_FALLBACK
                    prepareFallbackOdometry(
                        store.state.drive.poseEstimator.estimatedPose,
                        cachedImuInputs.headingRadians
                    )
                    getFallbackPoseUpdate(timestamp)
                }
            } else {
                pinpointCandidate
            }
        } else {
            if (previousSource != FtcOdometrySource.DRIVETRAIN_FALLBACK) {
                prepareFallbackOdometry(
                    store.state.drive.poseEstimator.estimatedPose,
                    cachedImuInputs.headingRadians
                )
            }
            poseUpdate = getFallbackPoseUpdate(timestamp)
        }

        com.areslib.telemetry.RobotStatusTracker.odometrySource = selectedSource.name
        com.areslib.telemetry.RobotStatusTracker.odometryStatus = pinpoint?.healthStatus?.name
            ?: "PINPOINT_UNAVAILABLE"
        val s3 = com.areslib.util.RobotClock.nanoTime()

        val isPinpointFaulted = pinpoint != null && selectedSource == FtcOdometrySource.DRIVETRAIN_FALLBACK
        when {
            isPinpointFaulted && (timestamp - lastPinpointWarningTime > 2000L) -> {
                System.err.println(
                    "FtcBaseRobot: Pinpoint ${pinpoint?.healthStatus}; using drivetrain + Control Hub IMU odometry"
                )
                lastPinpointWarningTime = timestamp
            }
        }
        // Enrich every odometry source with the once-per-loop Control Hub IMU sample.
        // This activates the estimator tilt/rate gates without adding hardware reads.
        poseUpdate.pitchDegrees = Math.toDegrees(cachedImuInputs.pitchRadians)
        poseUpdate.rollDegrees = Math.toDegrees(cachedImuInputs.rollRadians)
        poseUpdate.pitchVelocityDegPerSec = Math.toDegrees(cachedImuInputs.pitchVelocityRadPerSec)
        poseUpdate.rollVelocityDegPerSec = Math.toDegrees(cachedImuInputs.rollVelocityRadPerSec)
        poseUpdate.angularVelocityRadiansPerSecond = cachedImuInputs.yawVelocityRadPerSec
        poseUpdate.applyControlHubGyroCorrection =
            selectedSource == FtcOdometrySource.DRIVETRAIN_FALLBACK && cachedImuInputs.timestampMs > 0L
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
        } catch (failure: Throwable) {
            hasReadSensorsThisFrame = false
            throw failure
        }
    }

    /**
     * Generates a fallback pose update when physical odometry hardware (e.g. Pinpoint) is unavailable.
     *
     * Pulls yaw velocity ($rad/s$) and heading ($rad$) directly from [imuIO] if configured, defaulting
     * coordinate positions to $(0.0, 0.0)$.
     *
     * @param timestampMs System clock timestamp in milliseconds ($ms$).
     * @return Formatted [RobotAction.PoseUpdate] containing estimated position and heading.
     */
    protected open fun getFallbackPoseUpdate(timestampMs: Long): RobotAction.PoseUpdate {
        return RobotAction.PoseUpdate(
            xMeters = heldFallbackX,
            yMeters = heldFallbackY,
            headingRadians = com.areslib.math.wrapAngle(
                cachedImuInputs.headingRadians + heldFallbackHeadingOffset
            ),
            angularVelocityRadiansPerSecond = cachedImuInputs.yawVelocityRadPerSec,
            timestampMs = timestampMs
        )
    }

    /** Seeds a drivetrain-specific fallback integrator at the current fused pose. */
    protected open fun prepareFallbackOdometry(pose: Pose2d, rawImuHeadingRadians: Double) {
        heldFallbackX = pose.x
        heldFallbackY = pose.y
        heldFallbackHeadingOffset = com.areslib.math.wrapAngle(
            pose.heading.radians - rawImuHeadingRadians
        )
    }

    private fun refreshCachedImu(timestampMs: Long) {
        val imu = imuIO
        if (imu == null) {
            cachedImuInputs.headingRadians = store.state.drive.poseEstimator.estimatedPoseHeading
            cachedImuInputs.pitchRadians = 0.0
            cachedImuInputs.rollRadians = 0.0
            cachedImuInputs.yawVelocityRadPerSec = 0.0
            cachedImuInputs.pitchVelocityRadPerSec = 0.0
            cachedImuInputs.rollVelocityRadPerSec = 0.0
            cachedImuInputs.timestampMs = 0L
            return
        }

        try {
            imu.updateInputs(imuSampleBuffer)
            val sampleAgeMs = timestampMs - imuSampleBuffer.timestampMs
            val valid = imuSampleBuffer.timestampMs > 0L && sampleAgeMs in 0..IMU_MAX_SAMPLE_AGE_MS &&
                imuSampleBuffer.headingRadians.isFinite() && imuSampleBuffer.pitchRadians.isFinite() &&
                imuSampleBuffer.rollRadians.isFinite() && imuSampleBuffer.yawVelocityRadPerSec.isFinite() &&
                imuSampleBuffer.pitchVelocityRadPerSec.isFinite() && imuSampleBuffer.rollVelocityRadPerSec.isFinite()
            if (!valid) {
                invalidateCachedImu()
                return
            }
            cachedImuInputs.headingRadians = imuSampleBuffer.headingRadians
            cachedImuInputs.pitchRadians = imuSampleBuffer.pitchRadians
            cachedImuInputs.rollRadians = imuSampleBuffer.rollRadians
            cachedImuInputs.yawVelocityRadPerSec = imuSampleBuffer.yawVelocityRadPerSec
            cachedImuInputs.pitchVelocityRadPerSec = imuSampleBuffer.pitchVelocityRadPerSec
            cachedImuInputs.rollVelocityRadPerSec = imuSampleBuffer.rollVelocityRadPerSec
            cachedImuInputs.timestampMs = imuSampleBuffer.timestampMs
        } catch (_: Throwable) {
            invalidateCachedImu()
        }
    }

    private fun invalidateCachedImu() {
            cachedImuInputs.headingRadians = store.state.drive.poseEstimator.estimatedPoseHeading
            cachedImuInputs.pitchRadians = 0.0
            cachedImuInputs.rollRadians = 0.0
            cachedImuInputs.yawVelocityRadPerSec = 0.0
            cachedImuInputs.pitchVelocityRadPerSec = 0.0
            cachedImuInputs.rollVelocityRadPerSec = 0.0
            cachedImuInputs.timestampMs = 0L
    }

    private fun reseedOdometrySources(pose: Pose2d) {
        pinpointIO?.initialize(pose, resetHardware = false)
        prepareFallbackOdometry(pose, cachedImuInputs.headingRadians)
    }

    init {
        // Linear OpModes construct robots on their worker thread while the simulator loop reads
        // this global. Publish only after every FtcBaseRobot field is initialized; publishing in
        // the earlier hardware-init block exposed null JVM backing fields such as profiler.
        activeInstance = this
    }

    /**
     * Executes one complete iteration of the robot control loop (50Hz–100Hz frequency).
     *
     * Performs synchronized sensor reads, power monitoring, subsystem logic execution,
     * telemetry transmission, and loop frequency rate-limiting.
     *
     * @param gamepad1 Telemetry snapshot of Driver 1 gamepad inputs.
     * @param gamepad2 Telemetry snapshot of Driver 2 gamepad inputs.
     */
    fun update(gamepad1: com.areslib.telemetry.GamepadState? = null, gamepad2: com.areslib.telemetry.GamepadState? = null) {
        fatalUpdateFailure?.let { failure ->
            runCatching { safeHardware() }
            throw failure
        }
        try {
            lifecycleController.update()

            val timestamp = com.areslib.util.RobotClock.currentTimeMillis()
            val dtSeconds = if (lastUpdateTime == 0L || timestamp == lastUpdateTime) 0.02 else (timestamp - lastUpdateTime) / 1000.0
            lastUpdateTime = timestamp

            val t0 = com.areslib.util.RobotClock.nanoTime()
            try {
                readSensors()
            } finally {
                hasReadSensorsThisFrame = false
            }
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
                powerBrownoutGuard = powerManager.brownoutGuard,
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
            }
            fatalUpdateFailure = e
            System.err.println("FtcBaseRobot: Exception in update loop: ${e.message}")
            e.printStackTrace()
            try {
                safeHardware()
            } catch (safetyFailure: Throwable) {
                e.addSuppressed(safetyFailure)
            }
            try {
                telemetryManager.dataLoggingTelemetry.putString("Robot/Error", "FATAL CRASH: ${e.message}")
            } catch (_: Throwable) {}
            throw e
        }
    }

    /** Subclass hook for sampling hardware encoders, analog sensors, and digital inputs into memory. */
    protected abstract fun updateHardwareInputs()

    /**
     * Subclass hook for processing subsystem logic and dispatching commands to physical actuators.
     *
     * @param dtSeconds Loop time step in seconds ($s$).
     * @param batteryVoltage Measured bus voltage in Volts ($V$).
     * @param powerScale Dynamic power scaling multiplier factor $[0.0, 1.0]$ enforced by brownout protection.
     */
    protected abstract fun updateSubsystems(dtSeconds: Double, batteryVoltage: Double, powerScale: Double)

    /**
     * Subclass hook for emitting custom telemetry data fields to the dashboard and log streams.
     *
     * @param timestamp System clock timestamp in milliseconds ($ms$).
     */
    protected abstract fun publishRobotTelemetry(timestamp: Long)

    /**
     * Safely cuts power to all physical motors and actuators to prevent runaway robot motion upon stop or crash.
     */
    abstract fun safeHardware()

    /**
     * Resets the robot pose estimation to a specified field location.
     *
     * Dispatches a reset [RobotAction.PoseUpdate] to the Redux store and re-initializes the physical
     * [pinpointIO] hardware computer if attached.
     *
     * @param pose Target zero pose in field coordinates $(x, y, \theta)$ ($m, m, rad$). Defaults to $(0,0,0)$.
     * @param resetHardware If `true`, re-homes raw physical odometry encoders on the Pinpoint hardware board.
     */
    @kotlin.jvm.JvmOverloads
    fun resetPose(pose: Pose2d = Pose2d(), resetHardware: Boolean = false) {
        pinpointIO?.initialize(pose, resetHardware = resetHardware)
        prepareFallbackOdometry(pose, cachedImuInputs.headingRadians)
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
     * Resets the robot starting pose according to the current alliance assignment in Redux state.
     *
     * - **Red Alliance**: Pose $(0.0, -1.2 \text{ m}, +\pi/2 \text{ rad})$ facing blue wall.
     * - **Blue Alliance**: Pose $(0.0, +1.2 \text{ m}, -\pi/2 \text{ rad})$ facing red wall.
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
     * Releases active hardware resources, background HTTP/NT4 threads, and closes telemetry channels.
     */
    open fun close() {
        if (activeInstance === this) activeInstance = null
        closeBestEffort(
            { safeHardware() },
            { lifecycleController.close() },
            { telemetryManager.close() },
            { hardwareInitializer.close() }
        )
    }

    private fun closeBestEffort(vararg actions: () -> Unit) {
        var firstFailure: Throwable? = null
        for (action in actions) {
            try {
                action()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }

}
