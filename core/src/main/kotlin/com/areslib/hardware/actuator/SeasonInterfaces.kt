package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO
import com.areslib.telemetry.ITelemetry

/**
 * Pure abstraction for reading and writing to the physical dual-motor Flywheel shooter.
 * De-couples the pure math state engine from physical motor hardware libraries.
 */
interface FlywheelIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        val temperature = tempCelsius
        telemetry.putNumber("$prefix/VelocityRpm", velocityRpm)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
        telemetry.putNumber("$prefix/TempCelsius", temperature)
        telemetry.putBoolean("$prefix/TempReadingValid", temperature.isFinite())
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the target velocity of the flywheel using closed-loop controller on the motor */
    fun setVelocityRpm(rpm: Double)

    /**
     * Preserves the requested terminal speed while limiting the electrical effort available to
     * reach it. Hardware implementations should override when their controller supports an output
     * or current cap; the fallback preserves the lifecycle emergency-stop contract.
     */
    fun setVelocityRpm(rpm: Double, maxEffortScale: Double) {
        if (maxEffortScale <= 0.0) setAppliedVoltage(0.0) else setVelocityRpm(rpm)
    }

    /** Sets the applied voltage of the flywheel motors directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Applies identified velocity-loop parameters; unsupported controllers may keep their current configuration. */
    fun configureVelocityController(
        gains: com.areslib.control.tuning.PIDFCoefficients,
        feedforward: com.areslib.control.tuning.SimpleFeedforwardCoeffs
    ) {}

    /** Gets the measured rotational velocity of the flywheel in RPM */
    val velocityRpm: Double
        get() = 0.0

    /** True only when [velocityRpm] was refreshed successfully this loop. */
    val velocityValid: Boolean
        get() = false

    /** Gets the measured stator current of the flywheel motors in Amperes */
    override val currentAmps: Double
        get() = Double.NaN

    /** Gets the cached master-motor temperature in Celsius, or NaN when unavailable. */
    val tempCelsius: Double
        get() = Double.NaN
}

/**
 * Pure abstraction for the adjustable angle cowl/hood.
 */
interface CowlIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/AngleRotations", angleRotations)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the target absolute position angle in rotations */
    fun setTargetAngle(rotations: Double)

    /**
     * Sets the target angle without scaling mechanism geometry while limiting the
     * closed-loop effort available to reach it.
     *
     * Implementations with hardware closed-loop control should override this method.
     * The compatibility fallback can only provide a full-effort command or a zero-effort
     * safety stop.
     */
    fun setTargetAngle(rotations: Double, maxEffortScale: Double) {
        if (maxEffortScale <= 0.0) setAppliedVoltage(0.0) else setTargetAngle(rotations)
    }

    /** Sets the applied voltage directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Gets the current absolute angle in rotations */
    val angleRotations: Double
        get() = 0.0

    /** True only when [angleRotations] was refreshed successfully this loop. */
    val angleValid: Boolean
        get() = false

    /** Gets the stator current draw in Amperes */
    override val currentAmps: Double
        get() = Double.NaN
}

/**
 * Pure abstraction for the deployed pivot-arm intake and active rollers.
 */
interface IntakeIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PivotAngleDegrees", pivotAngleDegrees)
        telemetry.putNumber("$prefix/PivotCurrentAmps", pivotCurrentAmps)
        telemetry.putNumber("$prefix/RollerCurrentAmps", rollerCurrentAmps)
    }

    override fun safe() {
        setPivotVoltage(0.0)
        setRollerVoltage(0.0)
    }

    /** Sets the target absolute angle of the pivot arm in degrees */
    fun setPivotAngle(degrees: Double)

    /** Sets the pivot target while independently limiting closed-loop effort. */
    fun setPivotAngle(degrees: Double, maxEffortScale: Double) {
        if (maxEffortScale <= 0.0) setPivotVoltage(0.0) else setPivotAngle(degrees)
    }

    /** Sets the applied voltage of the pivot motor directly (-12.0 to 12.0 volts) */
    fun setPivotVoltage(volts: Double)

    /** Sets the applied voltage of the intake rollers directly (-12.0 to 12.0 volts) */
    fun setRollerVoltage(volts: Double)

    /** Sets the target velocity of the intake rollers in RPS */
    fun setRollerVelocityRps(rps: Double) {
        setRollerVoltage((rps / 10.0) * 12.0)
    }

    /** Gets the current absolute angle of the pivot arm in degrees */
    val pivotAngleDegrees: Double
        get() = 0.0

    /** True only when [pivotAngleDegrees] was refreshed successfully this loop. */
    val pivotAngleValid: Boolean
        get() = false

    /** Gets the measured current of the pivot motor in Amperes */
    val pivotCurrentAmps: Double
        get() = Double.NaN

    /** Gets the measured current of the roller motor in Amperes */
    val rollerCurrentAmps: Double
        get() = Double.NaN

    /** True only when [rollerCurrentAmps] is a fresh, trustworthy observation. */
    val rollerCurrentValid: Boolean
        get() = false

    /** Aggregate pivot and roller current used by the system power budget. */
    override val currentAmps: Double
        get() = pivotCurrentAmps.coerceAtLeast(0.0) + rollerCurrentAmps.coerceAtLeast(0.0)

    override fun isCurrentReadingValid(readingAmps: Double): Boolean =
        rollerCurrentValid && readingAmps.isFinite() && readingAmps >= 0.0 &&
            pivotCurrentAmps.isFinite() && pivotCurrentAmps >= 0.0 &&
            rollerCurrentAmps.isFinite() && rollerCurrentAmps >= 0.0

    /**
     * Gets the roller motor encoder velocity in ticks per second.
     *
     * On FTC hardware with REV bulk caching enabled, this value is read
     * from the bulk response at zero additional I2C bus cost — making it
     * ideal for jam detection in 50Hz hot paths.
     */
    val rollerVelocityTicksPerSec: Double
        get() = 0.0
}

/**
 * Pure abstraction for the transfer/feeder rollers.
 */
interface FeederIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putBoolean("$prefix/PieceDetected", isBeamBroken)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the applied voltage of the feeder motor directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Gets the status of the infrared beam break sensor detecting note presence */
    val isBeamBroken: Boolean
        get() = false

    /**
     * Whether a physical or explicitly configured simulated piece detector exists.
     * Consumers must not interpret [isBeamBroken] when this is false.
     */
    val pieceDetectionValid: Boolean
        get() = false

    /** Gets the stator current draw in Amperes */
    override val currentAmps: Double
        get() = Double.NaN
}

/**
 * Pure abstraction for the fast-climber vertical elevator.
 */
interface ClimberIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/PositionRotations", positionRotations)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the target mechanism position in rotations. */
    fun setTargetPositionRotations(rotations: Double)

    /** Sets the target mechanism position while independently limiting closed-loop effort. */
    fun setTargetPositionRotations(rotations: Double, maxEffortScale: Double) {
        if (maxEffortScale <= 0.0) setAppliedVoltage(0.0) else setTargetPositionRotations(rotations)
    }

    /** Sets the applied voltage of the climber motor directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Gets the current measured mechanism position in rotations. */
    val positionRotations: Double
        get() = 0.0

    /** True only when [positionRotations] was refreshed successfully this loop. */
    val positionValid: Boolean
        get() = false

    /** Gets the stator current draw in Amperes */
    override val currentAmps: Double
        get() = Double.NaN
}

/**
 * Pure abstraction for the floor rollers.
 */
interface FloorIO : SubsystemIO, com.areslib.hardware.CurrentSourceIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/VelocityRps", velocityRps)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the applied voltage of the floor rollers directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Gets the measured rotational velocity of the floor rollers in RPS */
    val velocityRps: Double
        get() = 0.0

    /** Gets the stator current draw in Amperes */
    override val currentAmps: Double
        get() = Double.NaN
}
