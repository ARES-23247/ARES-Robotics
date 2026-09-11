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

    private val initialMaxWheelSpeed = mecanumIO.maxWheelSpeedMetersPerSecond
    private var trackWidth = 0.45
    private var wheelBase = 0.45

    /**
     * Validates all consumed tuning before applying it. Null motor gains restore construction defaults;
     * kV <= 1e-4 restores the construction speed limit. Unchanged geometry reuses its solver.
     * Rejection neutralizes and requires valid tuning followed by explicit neutral recovery.
     */
    fun updateTuning(currentTuning: TuningState) {
        try {
            mecanumIO.requireOpenForTuning()
            val tuning = currentTuning.drive
            val width = tuning.trackWidthMeters
            val length = tuning.wheelBaseMeters
            val feedforward = tuning.driveFeedforward
            val slew = tuning.driveSlewRateLimit
            val gains = tuning.ftc.motorGains
            require(width.isFinite() && width > 0.0 && length.isFinite() && length > 0.0) {
                "Mecanum dimensions must be finite and positive"
            }
            require(feedforward.kS.isFinite() && feedforward.kV.isFinite() && feedforward.kV >= 0.0 &&
                feedforward.kA.isFinite()) { "Drive feedforward must be finite with nonnegative kV" }
            require(slew == null || slew.isFinite() && slew > 0.0) { "Slew rate must be null or finite and positive" }
            require(tuning.ftc.ticksPerMeter.isFinite() && tuning.ftc.ticksPerMeter > 1e-9) {
                "Encoder resolution must be finite and greater than 1e-9 ticks per meter"
            }
            require(gains == null || MecanumNativeConfiguration.valid(gains.kP, gains.kI, gains.kD, gains.kF)) {
                "Motor gains must be finite"
            }
            val nextKinematics = if (width == trackWidth && length == wheelBase) kinematics
                else MecanumKinematics(width, length)
            val maxSpeed = if (feedforward.kV > 1e-4) 1.0 / feedforward.kV else initialMaxWheelSpeed
            val maxAngularSpeed = maxSpeed / nextKinematics.k
            require(maxSpeed.isFinite() && maxSpeed > 0.0 && maxAngularSpeed.isFinite() && maxAngularSpeed > 0.0) {
                "Derived drive limits must be finite and positive"
            }

            // Validate the entire consumed proposal before hardware writes. A rejected SDK update
            // leaves the previous software settings intact; native failure still inhibits output.
            if (gains == null) mecanumIO.restoreInitialMotorGains()
            else mecanumIO.updateMotorGains(gains.kP, gains.kI, gains.kD, gains.kF)
            kinematics = nextKinematics
            trackWidth = width
            wheelBase = length
            mecanumIO.kS = feedforward.kS
            mecanumIO.kV = feedforward.kV
            mecanumIO.kA = feedforward.kA
            mecanumIO.slewRateLimit = slew
            mecanumIO.ticksPerMeter = tuning.ftc.ticksPerMeter
            mecanumIO.maxWheelSpeedMetersPerSecond = maxSpeed
            drive.maxSpeedMps = maxSpeed
            drive.maxAngularSpeedRadiansPerSecond = maxAngularSpeed
            mecanumDrive.maxSpeedMps = maxSpeed
            mecanumDrive.maxAngularSpeedRps = maxAngularSpeed
            mecanumIO.acceptTuningConfiguration()
        } catch (failure: Exception) {
            mecanumIO.rejectTuningConfiguration()
            throw failure
        }
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
