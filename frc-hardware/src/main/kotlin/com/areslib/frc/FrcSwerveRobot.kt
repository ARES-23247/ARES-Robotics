package com.areslib.frc

import com.areslib.action.RobotAction
import com.areslib.frc.power.FrcPowerManager
import com.areslib.frc.telemetry.FrcTelemetryManager
import com.areslib.frc.vision.FrcVisionTracker
import com.areslib.hardware.drive.SwerveHardwareIO
import com.areslib.hardware.vision.VisionIO
import com.areslib.reducer.rootReducer
import com.areslib.state.RobotState
import com.areslib.subsystem.DriveSubsystem
import com.areslib.subsystem.SwerveDriveFacade
import com.areslib.telemetry.*
import kotlin.math.abs
import com.areslib.tuning.TuningManager
import java.io.File

/**
 * FRC Swerve Robot — high-level drivebase robot container facade.
 *
 * Extends [FrcBaseRobot] to wire CTRE Phoenix 6 swerve drivetrain IO ([SwerveHardwareIO]),
 * AprilTag vision tracking ([FrcVisionTracker]), and beached-chassis traction loss recovery.
 *
 * ### Beached-Chassis Recovery Logic:
 * Detects chassis high-centering when IMU pitch/roll $>8.0^\circ$ and wheel module speeds $>1.5\text{m/s}$ with current draw $<8.0\text{A}$ across $\ge 2$ modules.
 * Holds EKF pose estimation constant and re-seeds CANivore odometry upon recovery.
 *
 * ### Physical Units & Coordinates:
 * - Position: Meters ($m$)
 * - Velocity: Meters per second ($m/s$)
 * - Heading: Radians ($rad$), **CCW-positive** standard
 *
 * @param swerveIO Underlying CTRE Phoenix 6 swerve hardware IO (or `null` in simulation).
 * @param visionIO Optional Limelight / AprilTag vision IO (or `null`).
 * @param isSimulation `true` when running in WPILib simulation mode.
 * @param initialState Initial immutable [RobotState] snapshot.
 * @param reducer Root Redux reducer function composing state transitions.
 * @param baseTelemetry Platform telemetry backend ([FRCTelemetry]).
 * @param isEnabledProvider Lambda returning active DriverStation enable state.
 * @param robotModeProvider Lambda returning active FRC match mode string.
 *
 * @see FrcBaseRobot
 * @see SwerveHardwareIO
 * @see SwerveDriveFacade
 */
class FrcSwerveRobot(

    private val swerveIO: SwerveHardwareIO? = null,
    private val visionIO: VisionIO? = null,
    private val isSimulation: Boolean = false,
    initialState: RobotState = RobotState(
        vision = com.areslib.state.VisionState(
            filterConfig = com.areslib.hardware.vision.VisionFilterConfig.frcDefaults()
        )
    ),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
    baseTelemetry: ITelemetry = FRCTelemetry(),
    isEnabledProvider: () -> Boolean = {
        try {
            edu.wpi.first.wpilibj.DriverStation.isEnabled()
        } catch (_: Throwable) {
            false
        }
    },
    robotModeProvider: () -> String = {
        try {
            when {
                edu.wpi.first.wpilibj.DriverStation.isAutonomous() -> "Auto"
                edu.wpi.first.wpilibj.DriverStation.isTeleop() -> "Teleop"
                edu.wpi.first.wpilibj.DriverStation.isTest() -> "Test"
                else -> "Disabled"
            }
        } catch (_: Throwable) {
            "Active"
        }
    },
    tuningFile: File = File(if (File("/home/lvuser").isDirectory) "/home/lvuser/ares_tuning.json" else "ares_tuning-frc.json"),
    telemetryManagerFactory: (com.areslib.Store, ITelemetry, SwerveHardwareIO?) -> FrcTelemetryManager =
        { store, telemetry, driveIO -> FrcTelemetryManager(telemetry, store, driveIO) }
) : FrcBaseRobot(
    initialState = initialState,
    reducer = reducer,
    baseTelemetry = baseTelemetry,
    telemetryManagerFactory = { store, telemetry -> telemetryManagerFactory(store, telemetry, swerveIO) },
    isEnabledProvider = isEnabledProvider,
    robotModeProvider = robotModeProvider
) {

    // ── Subsystem Facades ──
    /** Redux-aware drive subsystem for action dispatch. */
    val drive = DriveSubsystem(store)

    /** High-level swerve drive API (field-centric, robot-centric, path following). */
    val swerveDrive = SwerveDriveFacade(store)

    // ── Modular Managers ──
    /** Direct access to the swerve drivetrain IO layer. */
    val swerveDrivetrainIO: SwerveHardwareIO? get() = swerveIO

    override val powerManager = FrcPowerManager()
    private val tuningManager = TuningManager(store, baseTelemetry, tuningFile)
    var isLiveTuningEnabled: Boolean = false

    private val _visionTracker = FrcVisionTracker(store, visionIO, swerveIO, isSimulation)

    init {
        visionTracker = _visionTracker

        // Register swerve drivetrain with HardwareRegistry for automated lifecycle management
        swerveIO?.let { com.areslib.hardware.HardwareRegistry.registerDevice("Swerve", it) }
        visionIO?.let { com.areslib.hardware.HardwareRegistry.registerDevice("Vision", it) }
    }

    // ── Pre-allocated buffers for zero-GC beached detection ──
    private var wasBeached = false
    private var beachedSignalsValid = false
    private val scratchSpeeds = DoubleArray(4)
    private val scratchCurrents = DoubleArray(4)

    private enum class BeachedSignalState {
        BEACHED,
        NOT_BEACHED,
        UNKNOWN
    }

    /**
     * Reads swerve drivetrain sensors and dispatches a [RobotAction.PoseUpdate] to the store.
     * Handles beached-chassis recovery by holding the last known EKF pose when traction is lost.
     */
    override fun updateHardwareInputs(timestampMs: Long) {
        if (isLiveTuningEnabled) tuningManager.update(timestampMs)
        if (!isSimulation && swerveIO != null) {
            val driveState = swerveIO.read()
            val hardwareMeasurementsValid = swerveIO.currentMeasurementsValid
            val pitchDegrees = swerveIO.pitchDegrees
            val rollDegrees = swerveIO.rollDegrees
            val beachedState = evaluateBeachedState(
                hardwareMeasurementsValid,
                pitchDegrees,
                rollDegrees
            )
            val currentlyBeached = when (beachedState) {
                BeachedSignalState.BEACHED -> true
                BeachedSignalState.NOT_BEACHED -> false
                BeachedSignalState.UNKNOWN -> wasBeached
            }
            val lastPose = store.state.drive.poseEstimator.estimatedPose
            val odometryPoseValid = driveState.odometryX.isFinite() &&
                driveState.odometryY.isFinite() && driveState.odometryHeading.isFinite()
            val x = if (currentlyBeached || !odometryPoseValid) lastPose.x else driveState.odometryX
            val y = if (currentlyBeached || !odometryPoseValid) lastPose.y else driveState.odometryY
            // Heading remains observable while translational odometry is frozen; this preserves
            // the established beached contract and gives the recovery seed the latest yaw.
            val heading = if (odometryPoseValid) driveState.odometryHeading else lastPose.heading.radians
            val motionMeasurementsValid = !currentlyBeached &&
                hardwareMeasurementsValid && odometryPoseValid &&
                driveState.xVelocityMetersPerSecond.isFinite() &&
                driveState.yVelocityMetersPerSecond.isFinite() &&
                driveState.angularVelocityRadiansPerSecond.isFinite()
            val imuMeasurementsValid = hardwareMeasurementsValid &&
                pitchDegrees.isFinite() && rollDegrees.isFinite()
            val cosHeading = if (motionMeasurementsValid) {
                kotlin.math.cos(heading)
            } else 1.0
            val sinHeading = if (motionMeasurementsValid) {
                kotlin.math.sin(heading)
            } else 0.0
            // CTRE reports chassis speeds in the robot frame. Store measured speeds
            // separately from commanded Redux drive intent and convert them to the
            // field frame consumed by shoot-on-the-move prediction.
            val measuredFieldVx = if (motionMeasurementsValid) {
                driveState.xVelocityMetersPerSecond * cosHeading -
                    driveState.yVelocityMetersPerSecond * sinHeading
            } else 0.0
            val measuredFieldVy = if (motionMeasurementsValid) {
                driveState.xVelocityMetersPerSecond * sinHeading +
                    driveState.yVelocityMetersPerSecond * cosHeading
            } else 0.0

            if (wasBeached && beachedState == BeachedSignalState.NOT_BEACHED) {
                swerveIO.seedPose(lastPose)
            }
            wasBeached = currentlyBeached

            store.dispatch(RobotAction.PoseUpdate(
                xMeters = x,
                yMeters = y,
                headingRadians = heading,
                timestampMs = timestampMs,
                pitchDegrees = if (imuMeasurementsValid) pitchDegrees else 0.0,
                rollDegrees = if (imuMeasurementsValid) rollDegrees else 0.0,
                angularVelocityRadiansPerSecond = if (motionMeasurementsValid) {
                    driveState.angularVelocityRadiansPerSecond
                } else 0.0,
                xVelocityMetersPerSecond = measuredFieldVx,
                yVelocityMetersPerSecond = measuredFieldVy,
                motionMeasurementsValid = motionMeasurementsValid,
                imuMeasurementsValid = imuMeasurementsValid,
                // CTRE has already fused module, gyro, and accepted vision data.
                // Mirror that authoritative estimate; do not run it through ARES as
                // another odometry observation.
                isExternalEstimate = true
            ))
        }
    }

    /**
     * Writes the current drive state to the swerve hardware IO.
     */
    override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) {
        if (!isSimulation && swerveIO != null) {
            val state = store.state.drive
            swerveIO.write(state, powerScale)
        }
    }

    /**
     * Detects whether the chassis is "beached" — tilted with wheels losing traction.
     *
     * Uses a combination of IMU pitch/roll thresholds (>8°) and per-module
     * slip detection (high speed + low current draw) to prevent odometry drift
     * when the robot rides up on game pieces or field obstacles.
     */
    val isBeached: Boolean
        get() {
            if (isSimulation || swerveIO == null) {
                beachedSignalsValid = false
                return false
            }
            return when (evaluateBeachedState(
                swerveIO.currentMeasurementsValid,
                swerveIO.pitchDegrees,
                swerveIO.rollDegrees
            )) {
                BeachedSignalState.BEACHED -> true
                BeachedSignalState.NOT_BEACHED -> false
                BeachedSignalState.UNKNOWN -> wasBeached
            }
        }

    private fun evaluateBeachedState(
        hardwareMeasurementsValid: Boolean,
        pitch: Double,
        roll: Double
    ): BeachedSignalState {
        if (!hardwareMeasurementsValid || !pitch.isFinite() || !roll.isFinite()) {
            beachedSignalsValid = false
            return BeachedSignalState.UNKNOWN
        }

        // 8.0 degrees prevents false positives from normal suspension travel
        val isTilted = abs(pitch) > 8.0 || abs(roll) > 8.0

        // Loss of traction: high speed but very low current draw
        val io = swerveIO ?: run {
            beachedSignalsValid = false
            return BeachedSignalState.UNKNOWN
        }
        io.getModuleSpeeds(scratchSpeeds)
        io.getCurrents(scratchCurrents)
        var slipCount = 0
        for (i in 0..3) {
            if (!scratchSpeeds[i].isFinite() || !scratchCurrents[i].isFinite()) {
                beachedSignalsValid = false
                return BeachedSignalState.UNKNOWN
            }
            if (abs(scratchSpeeds[i]) > 1.5 && abs(scratchCurrents[i]) < 8.0) {
                slipCount++
            }
        }
        beachedSignalsValid = true
        return if (isTilted && slipCount >= 2) {
            BeachedSignalState.BEACHED
        } else {
            BeachedSignalState.NOT_BEACHED
        }
    }

    override fun publishRobotTelemetry(timestampMs: Long) {
        telemetry.putBoolean("Diagnostics/Drive/BeachedSignalsValid", beachedSignalsValid)
        telemetry.putBoolean("Diagnostics/Drive/Beached", wasBeached)
        telemetry.putBoolean(
            "Diagnostics/Drive/MotionMeasurementsValid",
            store.state.drive.measuredMotionValid
        )
        telemetry.putBoolean(
            "Diagnostics/Drive/ImuMeasurementsValid",
            store.state.drive.imuMeasurementsValid
        )
    }

    companion object {}
}

