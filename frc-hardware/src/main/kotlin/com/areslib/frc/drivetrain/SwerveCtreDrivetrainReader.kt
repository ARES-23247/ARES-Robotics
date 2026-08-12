package com.areslib.frc.drivetrain

import com.areslib.state.DriveState
import com.ctre.phoenix6.StatusSignal
import com.ctre.phoenix6.swerve.SwerveDrivetrain

/**
 * Telemetry reader for CTRE Phoenix 6 [SwerveDrivetrain] hardware platforms.
 *
 * Configures CANivore CAN-FD signal update frequencies ($50\text{Hz}$ CANcoder absolute positions, $20\text{Hz}$ motor current draws,
 * $20\text{Hz}$ Pigeon2 pitch/roll, and $4\text{Hz}$ hardware fault diagnostics). Phoenix's public
 * grouped-refresh overload allocates a JNI array internally on every call, so [refresh] advances a
 * prebuilt signal array individually to preserve the 50 Hz zero-GC contract.
 *
 * ### Physical Units & Conventions:
 * - Motor Current: Amperes ($A$).
 * - Module Absolute Encoded Steering: Rotations / Radians ($rad$).
 * - Inclination: Pitch and Roll in Degrees ($^\circ$).
 * - Odometry Position: Meters ($m$).
 * - Chassis Velocities: Meters per second ($m/s$) and Radians per second ($rad/s$).
 * - Heading: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 *
 * ### Zero-GC Guarantee:
 * [refresh], [getCurrents], [getEncoderPositions], and [getModuleSpeeds] write directly into pre-allocated primitive array targets.
 *
 * @param drivetrain Physical CTRE [SwerveDrivetrain] instance.
 *
 * @see SwerveDrivetrain
 * @see StatusSignal
 * @see DriveState
 */
class SwerveCtreDrivetrainReader(private val drivetrain: SwerveDrivetrain<*, *, *>) {

    /** True only when the most recent grouped CTRE refresh completed successfully. */
    var encoderPositionsValid: Boolean = false
        private set
    var currentMeasurementsValid: Boolean = false
        private set
    var signalLatencyMs: Double = Double.POSITIVE_INFINITY
        private set

    private val currentDraw1 = drivetrain.getModule(0).driveMotor.supplyCurrent
    private val currentDraw2 = drivetrain.getModule(1).driveMotor.supplyCurrent
    private val currentDraw3 = drivetrain.getModule(2).driveMotor.supplyCurrent
    private val currentDraw4 = drivetrain.getModule(3).driveMotor.supplyCurrent

    private val absEnc1 = (drivetrain.getModule(0).encoder as com.ctre.phoenix6.hardware.CANcoder).absolutePosition
    private val absEnc2 = (drivetrain.getModule(1).encoder as com.ctre.phoenix6.hardware.CANcoder).absolutePosition
    private val absEnc3 = (drivetrain.getModule(2).encoder as com.ctre.phoenix6.hardware.CANcoder).absolutePosition
    private val absEnc4 = (drivetrain.getModule(3).encoder as com.ctre.phoenix6.hardware.CANcoder).absolutePosition

    private val faultHardware = Array(4) { i -> drivetrain.getModule(i).driveMotor.getFault_Hardware() }
    private val faultBrownout = Array(4) { i -> drivetrain.getModule(i).driveMotor.getFault_BridgeBrownout() }
    private val faultTemp = Array(4) { i -> drivetrain.getModule(i).driveMotor.getFault_DeviceTemp() }
    private val steerFaultHardware = Array(4) { i -> drivetrain.getModule(i).steerMotor.getFault_Hardware() }
    private val steerFaultBrownout = Array(4) { i -> drivetrain.getModule(i).steerMotor.getFault_BridgeBrownout() }
    private val steerFaultTemp = Array(4) { i -> drivetrain.getModule(i).steerMotor.getFault_DeviceTemp() }

    private val pigeon = drivetrain.pigeon2
    private val pitchSignal = pigeon.pitch
    private val rollSignal = pigeon.roll
    private val yawSignal = pigeon.yaw
    private val yawRateSignal = pigeon.angularVelocityZWorld
    private val refreshSignals: Array<StatusSignal<*>> = arrayOf(
        currentDraw1, currentDraw2, currentDraw3, currentDraw4,
        absEnc1, absEnc2, absEnc3, absEnc4,
        pitchSignal, rollSignal, yawSignal, yawRateSignal,
        faultHardware[0], faultHardware[1], faultHardware[2], faultHardware[3],
        faultBrownout[0], faultBrownout[1], faultBrownout[2], faultBrownout[3],
        faultTemp[0], faultTemp[1], faultTemp[2], faultTemp[3],
        steerFaultHardware[0], steerFaultHardware[1], steerFaultHardware[2], steerFaultHardware[3],
        steerFaultBrownout[0], steerFaultBrownout[1], steerFaultBrownout[2], steerFaultBrownout[3],
        steerFaultTemp[0], steerFaultTemp[1], steerFaultTemp[2], steerFaultTemp[3],
    )

    init {
        for (i in 0..3) {
            drivetrain.getModule(i).driveMotor.supplyCurrent.setUpdateFrequency(20.0, 0.0)
            drivetrain.getModule(i).steerMotor.supplyCurrent.setUpdateFrequency(20.0, 0.0)
            (drivetrain.getModule(i).encoder as com.ctre.phoenix6.hardware.CANcoder).absolutePosition.setUpdateFrequency(50.0, 0.0)
            faultHardware[i].setUpdateFrequency(4.0, 0.0)
            faultBrownout[i].setUpdateFrequency(4.0, 0.0)
            faultTemp[i].setUpdateFrequency(4.0, 0.0)
            
            steerFaultHardware[i].setUpdateFrequency(4.0, 0.0)
            steerFaultBrownout[i].setUpdateFrequency(4.0, 0.0)
            steerFaultTemp[i].setUpdateFrequency(4.0, 0.0)
        }
        pitchSignal.setUpdateFrequency(20.0, 0.0)
        rollSignal.setUpdateFrequency(20.0, 0.0)
    }

    /**
     * Synchronously refreshes all BaseStatusSignals registered.
     * Must be called once per loop prior to fetching values.
     * Zero-GC allocation.
     */
    fun refresh() {
        var allSignalsValid = true
        var index = 0
        while (index < refreshSignals.size) {
            val signal = refreshSignals[index]
            signal.refresh()
            if (!signal.status.isOK) allSignalsValid = false
            index++
        }
        encoderPositionsValid = allSignalsValid
        currentMeasurementsValid = allSignalsValid
        signalLatencyMs = if (allSignalsValid) {
            maxOf(
                absEnc1.timestamp.latency,
                absEnc2.timestamp.latency,
                absEnc3.timestamp.latency,
                absEnc4.timestamp.latency
            ) * 1_000.0
        } else {
            Double.POSITIVE_INFINITY
        }
    }

    /**
     * Writes per-module live fault bitfields: bit 0 drive hardware, bit 1 drive brownout,
     * bit 2 drive temperature, bit 3 steer hardware, bit 4 steer brownout, bit 5 steer temperature.
     */
    fun getFaults(out: IntArray) {
        require(out.size >= 4) { "Swerve fault output must contain four modules" }
        for (i in 0..3) {
            var bits = 0
            if (faultHardware[i].value == true) bits = bits or 0x01
            if (faultBrownout[i].value == true) bits = bits or 0x02
            if (faultTemp[i].value == true) bits = bits or 0x04
            if (steerFaultHardware[i].value == true) bits = bits or 0x08
            if (steerFaultBrownout[i].value == true) bits = bits or 0x10
            if (steerFaultTemp[i].value == true) bits = bits or 0x20
            out[i] = bits
        }
    }

    /**
     * Reads the current draw of all four drive motors into the provided array.
     *
     * @param out A 4-element DoubleArray to populate with current draws in $A$.
     */
    fun getCurrents(out: DoubleArray) {
        out[0] = currentDraw1.valueAsDouble
        out[1] = currentDraw2.valueAsDouble
        out[2] = currentDraw3.valueAsDouble
        out[3] = currentDraw4.valueAsDouble
    }

    /**
     * Reads the absolute encoder positions of the steering modules into the provided array.
     *
     * @param out A 4-element DoubleArray to populate with positions in rotations/radians depending on CTR config.
     */
    fun getEncoderPositions(out: DoubleArray) {
        out[0] = absEnc1.valueAsDouble
        out[1] = absEnc2.valueAsDouble
        out[2] = absEnc3.valueAsDouble
        out[3] = absEnc4.valueAsDouble
    }

    /**
     * Gets the Pigeon pitch angle.
     * 
     * @return Pitch in degrees.
     */
    val pitchDegrees: Double
        get() = pitchSignal.valueAsDouble

    /**
     * Gets the Pigeon roll angle.
     * 
     * @return Roll in degrees.
     */
    val rollDegrees: Double
        get() = rollSignal.valueAsDouble

    /** Raw Pigeon yaw in degrees for gyro-assisted camera localization. */
    val rawGyroYawDegrees: Double
        get() = yawSignal.valueAsDouble

    /** Raw Pigeon world-Z angular velocity in degrees per second. */
    val yawRateDegreesPerSecond: Double
        get() = yawRateSignal.valueAsDouble

    /**
     * Reads module wheel speeds into the provided array.
     * 
     * @param out A 4-element DoubleArray to populate with speeds in $m/s$.
     */
    fun getModuleSpeeds(out: DoubleArray) {
        out[0] = drivetrain.state.ModuleStates[0].speedMetersPerSecond
        out[1] = drivetrain.state.ModuleStates[1].speedMetersPerSecond
        out[2] = drivetrain.state.ModuleStates[2].speedMetersPerSecond
        out[3] = drivetrain.state.ModuleStates[3].speedMetersPerSecond
    }

    /**
     * Reads the core drivetrain state including odometry and chassis speeds.
     * Zero-GC if internal CTRE state access avoids allocations.
     * 
     * @return The updated [DriveState] populated with coordinates in $m$ and $rad$ (CCW-positive).
     */
    fun read(): DriveState {
        val driveStateObj = drivetrain.state
        val pose = driveStateObj.Pose

        return DriveState(
            xVelocityMetersPerSecond = driveStateObj.Speeds.vxMetersPerSecond,
            yVelocityMetersPerSecond = driveStateObj.Speeds.vyMetersPerSecond,
            angularVelocityRadiansPerSecond = driveStateObj.Speeds.omegaRadiansPerSecond,
            odometryX = pose.x,
            odometryY = pose.y,
            odometryHeading = pose.rotation.radians
        )
    }
}
