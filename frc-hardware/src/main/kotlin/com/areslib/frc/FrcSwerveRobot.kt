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
    }
) : FrcBaseRobot(initialState, reducer, baseTelemetry, isEnabledProvider, robotModeProvider) {

    // ── Subsystem Facades ──
    /** Redux-aware drive subsystem for action dispatch. */
    val drive = DriveSubsystem(store)

    /** High-level swerve drive API (field-centric, robot-centric, path following). */
    val swerveDrive = SwerveDriveFacade(store)

    // ── Modular Managers ──
    /** Direct access to the swerve drivetrain IO layer. */
    val swerveDrivetrainIO: SwerveHardwareIO? get() = swerveIO

    override val telemetryManager = FrcTelemetryManager(baseTelemetry, store, swerveIO)
    override val powerManager = FrcPowerManager()

    private val _visionTracker = FrcVisionTracker(store, visionIO, swerveIO, isSimulation)

    init {
        visionTracker = _visionTracker

        // Register swerve drivetrain with HardwareRegistry for automated lifecycle management
        swerveIO?.let { com.areslib.hardware.HardwareRegistry.registerDevice("Swerve", it) }
        visionIO?.let { com.areslib.hardware.HardwareRegistry.registerDevice("Vision", it) }
    }

    // ── Pre-allocated buffers for zero-GC beached detection ──
    private var wasBeached = false
    private val scratchSpeeds = DoubleArray(4)
    private val scratchCurrents = DoubleArray(4)

    /**
     * Reads swerve drivetrain sensors and dispatches a [RobotAction.PoseUpdate] to the store.
     * Handles beached-chassis recovery by holding the last known EKF pose when traction is lost.
     */
    override fun updateHardwareInputs(timestampMs: Long) {
        if (!isSimulation && swerveIO != null) {
            val driveState = swerveIO.read()
            val currentlyBeached = isBeached
            val lastPose = store.state.drive.poseEstimator.estimatedPose
            val x = if (currentlyBeached) lastPose.x else driveState.odometryX
            val y = if (currentlyBeached) lastPose.y else driveState.odometryY

            if (wasBeached && !currentlyBeached) {
                swerveIO.seedPose(lastPose)
            }
            wasBeached = currentlyBeached

            store.dispatch(RobotAction.PoseUpdate(
                xMeters = x,
                yMeters = y,
                headingRadians = driveState.odometryHeading,
                timestampMs = timestampMs,
                pitchDegrees = swerveIO.pitchDegrees,
                rollDegrees = swerveIO.rollDegrees
            ))
        }
    }

    /**
     * Writes the current drive state to the swerve hardware IO.
     */
    override fun writeHardwareOutputs(powerScale: Double, batteryVoltage: Double) {
        if (!isSimulation && swerveIO != null) {
            swerveIO.write(store.state.drive)
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
            if (isSimulation || swerveIO == null) return false
            val pitch = swerveIO.pitchDegrees
            val roll = swerveIO.rollDegrees

            // 8.0 degrees prevents false positives from normal suspension travel
            val isTilted = abs(pitch) > 8.0 || abs(roll) > 8.0

            // Loss of traction: high speed but very low current draw
            swerveIO.getModuleSpeeds(scratchSpeeds)
            swerveIO.getCurrents(scratchCurrents)
            var slipCount = 0
            for (i in 0..3) {
                if (abs(scratchSpeeds[i]) > 1.5 && abs(scratchCurrents[i]) < 8.0) {
                    slipCount++
                }
            }
            return isTilted && slipCount >= 2
        }

    companion object {}
}

