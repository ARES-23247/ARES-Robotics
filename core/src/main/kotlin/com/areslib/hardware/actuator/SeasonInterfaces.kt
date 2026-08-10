package com.areslib.hardware.actuator

import com.areslib.hardware.SubsystemIO
import com.areslib.telemetry.ITelemetry

/**
 * Pure abstraction for reading and writing to the physical dual-motor Flywheel shooter.
 * De-couples the pure math state engine from physical motor hardware libraries.
 */
interface FlywheelIO : SubsystemIO {
    override fun logTelemetry(telemetry: ITelemetry, prefix: String) {
        telemetry.putNumber("$prefix/VelocityRpm", velocityRpm)
        telemetry.putNumber("$prefix/CurrentAmps", currentAmps)
        telemetry.putNumber("$prefix/TempCelsius", tempCelsius)
    }

    override fun safe() {
        setAppliedVoltage(0.0)
    }

    /** Sets the target velocity of the flywheel using closed-loop controller on the motor */
    fun setVelocityRpm(rpm: Double)

    /** Sets the applied voltage of the flywheel motors directly (-12.0 to 12.0 volts) */
    fun setAppliedVoltage(volts: Double)

    /** Gets the measured rotational velocity of the flywheel in RPM */
    val velocityRpm: Double
        get() = 0.0

    /** True only when [velocityRpm] was refreshed successfully this loop. */
    val velocityValid: Boolean
        get() = false

    /** Gets the measured stator current of the flywheel motors in Amperes */
    val currentAmps: Double
        get() = 0.0

    /** Gets the temperature of the master motor in Celsius */
    val tempCelsius: Double
        get() = 0.0
}

/**
 * Pure abstraction for the adjustable angle cowl/hood.
 */
interface CowlIO : SubsystemIO {
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

    /** Gets the stator current draw in Amperes */
    val currentAmps: Double
        get() = 0.0
}

/**
 * Pure abstraction for the deployed pivot-arm intake and active rollers.
 */
interface IntakeIO : SubsystemIO {
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

    /** Gets the measured current of the pivot motor in Amperes */
    val pivotCurrentAmps: Double
        get() = 0.0

    /** Gets the measured current of the roller motor in Amperes */
    val rollerCurrentAmps: Double
        get() = 0.0

    /** True only when [rollerCurrentAmps] is a fresh, trustworthy observation. */
    val rollerCurrentValid: Boolean
        get() = true

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
interface FeederIO : SubsystemIO {
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
    val currentAmps: Double
        get() = 0.0
}

/**
 * Pure abstraction for the fast-climber vertical elevator.
 */
interface ClimberIO : SubsystemIO {
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

    /** Gets the stator current draw in Amperes */
    val currentAmps: Double
        get() = 0.0
}

/**
 * Pure abstraction for the floor rollers.
 */
interface FloorIO : SubsystemIO {
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
    val currentAmps: Double
        get() = 0.0
}
