package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.ftc.calibration.FtcMecanumCalibrationController
import com.areslib.ftc.telemetry.FtcTelemetryManager
import com.areslib.kinematics.MecanumKinematics
import com.areslib.state.TuningState
import com.areslib.subsystem.DriveSubsystem
import com.areslib.subsystem.MecanumDriveFacade

/**
 * Controller managing kinematics modeling, live gain tuning updates, and subsystem drives for FTC Mecanum Robots.
 *
 * Re-instantiates [MecanumKinematics] solvers upon track width ($W$, $m$) or wheel base ($B$, $m$) tuning updates,
 * updates motor velocity PIDF gains, and routes drive commands between physical calibration controllers and normal OpMode operation.
 *
 * ### Physical Units & Kinematics Parameters:
 * - Track Width $W$: Lateral distance between left and right wheel centers in meters ($m$).
 * - Wheel Base $B$: Longitudinal distance between front and rear wheel centers in meters ($m$).
 * - Kinematic constant $k$:
 *   $$k = \frac{W + B}{2}$$
 * - Linear Velocity: Meters per second ($m/s$).
 * - Angular Velocity: Radians per second ($rad/s$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 *
 * ### Zero-GC Guarantee:
 * Executes [updateSubsystems] without dynamic heap allocations, mutating stored kinematics references in-place.
 *
 * @param mecanumIO Low-level mecanum hardware IO interface.
 * @param drive Drive subsystem state model.
 * @param mecanumDrive Mecanum drive subsystem facade.
 * @param calibrationController Physical calibration state machine controller.
 *
 * @see MecanumKinematics
 * @see MecanumHardwareIO
 */
class MecanumKinematicsController(
    val mecanumIO: MecanumHardwareIO,
    private val drive: DriveSubsystem,
    private val mecanumDrive: MecanumDriveFacade,
    private val calibrationController: FtcMecanumCalibrationController
) {
    /** Current active [MecanumKinematics] solver instance. */
    var kinematics = MecanumKinematics(0.45, 0.45)
        private set

    /**
     * Updates kinematics geometry parameters and motor controller gains from a new [TuningState] snapshot.
     *
     * @param currentTuning Desired tuning parameters snapshot from Redux state.
     */
    fun updateTuning(currentTuning: TuningState) {
        val driveTuning = currentTuning.drive
        kinematics = MecanumKinematics(driveTuning.trackWidthMeters, driveTuning.wheelBaseMeters)
        mecanumIO.kS = driveTuning.driveFeedforward.kS
        mecanumIO.kV = driveTuning.driveFeedforward.kV
        mecanumIO.kA = driveTuning.driveFeedforward.kA
        mecanumIO.slewRateLimit = driveTuning.driveSlewRateLimit
        mecanumIO.ticksPerMeter = driveTuning.ftc.ticksPerMeter
        if (driveTuning.driveFeedforward.kV > 1e-4) {
            mecanumIO.maxWheelSpeedMetersPerSecond = 1.0 / driveTuning.driveFeedforward.kV
        }
        val gains = driveTuning.ftc.motorGains
        if (gains != null) {
            mecanumIO.updateMotorGains(gains.kP, gains.kI, gains.kD)
        }

        val maxSpeed = mecanumIO.maxWheelSpeedMetersPerSecond
        val maxAngularSpeed = maxSpeed / kinematics.k
        drive.maxSpeedMps = maxSpeed
        mecanumDrive.maxSpeedMps = maxSpeed
        mecanumDrive.maxAngularSpeedRps = maxAngularSpeed
    }

    /**
     * Updates drivetrain subsystem execution, delegating to [calibrationController] if SysId or calibration is active,
     * or executing normal inverse kinematics driving via [mecanumIO].
     *
     * @param store Redux state store reference.
     * @param batteryVoltage Measured battery voltage in Volts ($V$).
     * @param dtSeconds Loop time step interval in seconds ($s$).
     * @param telemetryManager Telemetry manager for NT4 logging.
     * @param onResetTuning Callback to reset tuning flags upon calibration stop.
     */
    fun updateSubsystems(
        store: Store,
        batteryVoltage: Double,
        dtSeconds: Double,
        telemetryManager: FtcTelemetryManager,
        onResetTuning: () -> Unit
    ) {
        val isCalibrationHandlingDrive = calibrationController.updateSubsystems(
            store = store,
            batteryVoltage = batteryVoltage,
            mecanumIO = mecanumIO,
            telemetryManager = telemetryManager,
            onResetTuning = onResetTuning
        )

        if (!isCalibrationHandlingDrive) {
            mecanumIO.drive(store.state.drive, kinematics, batteryVoltage, dtSeconds)
        }
    }
}
