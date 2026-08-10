package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.areslib.kinematics.MecanumWheelSpeeds
import com.areslib.hardware.SubsystemIO
import com.areslib.hardware.HardwareRegistry

/**
 * Facade class combining physical motor hardware cluster management with drive motion feedforwards.
 *
 * Provides complete hardware abstraction and voltage-compensated motor control for 4-wheel FTC Mecanum drivetrains.
 *
 * ### Physical Units & Coordinate Conventions:
 * - Position: Meters ($m$).
 * - Linear Velocity: Meters per second ($m/s$).
 * - Angular Velocity: Radians per second ($rad/s$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Electrical: Volts ($V$), Amperes ($A$).
 * - Encoders: Ticks per meter ($ticks/m$).
 *
 * ### Hot-path behavior:
 * Wheel-speed and motor-power arrays are preallocated and reused by [drive], [apply], and
 * [updateInputs]. Keep future changes allocation-conscious and do not retain either internal buffer.
 *
 * @param hardwareMap Qualcomm FTC SDK hardware map reference.
 * @param flName Front-left motor hardware map name. Defaults to `"fl"`.
 * @param frName Front-right motor hardware map name. Defaults to `"fr"`.
 * @param rlName Rear-left motor hardware map name. Defaults to `"rl"`.
 * @param rrName Rear-right motor hardware map name. Defaults to `"rr"`.
 * @param maxWheelSpeedMetersPerSecond Maximum expected wheel surface speed ($m/s$).
 * @param flDirection Front-left motor direction polarity.
 * @param frDirection Front-right motor direction polarity.
 * @param rlDirection Rear-left motor direction polarity.
 * @param rrDirection Rear-right motor direction polarity.
 * @param initialKs Static friction feedforward constant ($k_S$).
 * @param useClosedLoopVelocity Enables FTC SDK velocity closed-loop control mode on motor encoders.
 * @param ticksPerMeter Drive wheel encoder resolution ($ticks/m$).
 * @param initialSlewRateLimit Maximum acceleration slew rate limit.
 * @param motorKp Velocity PID proportional gain $K_p$.
 * @param motorKi Velocity PID integral gain $K_i$.
 * @param motorKd Velocity PID derivative gain $K_d$.
 * @param motorKf Velocity feedforward gain $K_f$.
 *
 * @see MecanumMotorCluster
 * @see MecanumDriveFeedforward
 */
class MecanumHardwareIO @kotlin.jvm.JvmOverloads constructor(
    val hardwareMap: HardwareMap,
    val flName: String = "fl",
    val frName: String = "fr",
    val rlName: String = "rl",
    val rrName: String = "rr",
    var maxWheelSpeedMetersPerSecond: Double = 3.5,
    val flDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    val frDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    val rlDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    val rrDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    initialKs: Double = 0.0,
    val useClosedLoopVelocity: Boolean = false,
    var ticksPerMeter: Double = 2000.0,
    val initialSlewRateLimit: Double? = null,
    val motorKp: Double? = null,
    val motorKi: Double? = null,
    val motorKd: Double? = null,
    val motorKf: Double? = null
) : SubsystemIO, AutoCloseable {

    private val motorCluster = MecanumMotorCluster(
        hardwareMap = hardwareMap,
        flName = flName,
        frName = frName,
        rlName = rlName,
        rrName = rrName,
        flDirection = flDirection,
        frDirection = frDirection,
        rlDirection = rlDirection,
        rrDirection = rrDirection,
        useClosedLoopVelocity = useClosedLoopVelocity,
        motorKp = motorKp,
        motorKi = motorKi,
        motorKd = motorKd,
        motorKf = motorKf
    )

    private val feedforward = MecanumDriveFeedforward(
        initialKs = initialKs,
        motorKp = motorKp,
        motorKi = motorKi,
        motorKd = motorKd,
        initialSlewRateLimit = initialSlewRateLimit
    )

    /** Front-left `DcMotorEx` hardware instance. */
    val frontLeft: DcMotorEx get() = motorCluster.frontLeft
    /** Front-right `DcMotorEx` hardware instance. */
    val frontRight: DcMotorEx get() = motorCluster.frontRight
    /** Rear-left `DcMotorEx` hardware instance. */
    val rearLeft: DcMotorEx get() = motorCluster.rearLeft
    /** Rear-right `DcMotorEx` hardware instance. */
    val rearRight: DcMotorEx get() = motorCluster.rearRight

    /** Front-left non-blocking IO cache. */
    val flIO: EstimateMotorIO get() = motorCluster.flIO
    /** Front-right non-blocking IO cache. */
    val frIO: EstimateMotorIO get() = motorCluster.frIO
    /** Rear-left non-blocking IO cache. */
    val rlIO: EstimateMotorIO get() = motorCluster.rlIO
    /** Rear-right non-blocking IO cache. */
    val rrIO: EstimateMotorIO get() = motorCluster.rrIO

    /** Static friction feedforward coefficient $k_S$. */
    var kS: Double
        get() = feedforward.kS
        set(value) { feedforward.kS = value }
        
    /** Velocity feedforward coefficient $k_V$. */
    var kV: Double
        get() = feedforward.kV
        set(value) { feedforward.kV = value }
        
    /** Acceleration feedforward coefficient $k_A$. */
    var kA: Double
        get() = feedforward.kA
        set(value) { feedforward.kA = value }

    /** Maximum acceleration slew rate limit. */
    var slewRateLimit: Double?
        get() = feedforward.slewRateLimit
        set(value) { feedforward.slewRateLimit = value }

    /** Enables automatic voltage-compensated slew rate limiting. */
    var enableVoltageCompensatedSlew: Boolean
        get() = feedforward.enableVoltageCompensatedSlew
        set(value) { feedforward.enableVoltageCompensatedSlew = value }

    private val speedBuffer = DoubleArray(4)
    private val powerBuffer = DoubleArray(4)

    init {
        HardwareRegistry.registerDevice("Drivetrain/Mecanum", this)
        HardwareRegistry.registerCloseable(this)
    }

    /**
     * Dynamically updates motor PID gains across velocity controllers.
     * 
     * @param kp Proportional gain $K_p$.
     * @param ki Integral gain $K_i$.
     * @param kd Derivative gain $K_d$.
     */
    fun updateMotorGains(kp: Double, ki: Double, kd: Double) {
        feedforward.updateMotorGains(kp, ki, kd)
    }

    /**
     * Releases motor hardware cluster resources upon OpMode completion.
     */
    override fun close() {
        motorCluster.close()
    }

    /**
     * Refreshes encoder position and velocity inputs from hardware caches.
     */
    override fun refresh() {
        updateInputs()
    }

    /**
     * Safely halts all 4 drivetrain motors by setting zero target power.
     */
    override fun safe() {
        motorCluster.safe()
    }

    /**
     * Solves inverse kinematics for chassis speeds and updates physical motor outputs.
     * Zero-GC execution loop.
     * 
     * @param driveState State containing normalized drive efforts; this FTC boundary scales them by
     * [maxWheelSpeedMetersPerSecond] and the kinematic radius.
     * @param kinematics Mecanum kinematics model solver.
     * @param batteryVolts Current measured battery bus voltage in Volts ($V$).
     * @param dtSeconds Loop time step interval in seconds ($s$).
     */
    fun drive(
        driveState: com.areslib.state.DriveState,
        kinematics: com.areslib.kinematics.MecanumKinematics,
        batteryVolts: Double,
        dtSeconds: Double
    ) {
        val maxSpeed = maxWheelSpeedMetersPerSecond
        val omega = driveState.angularVelocityRadiansPerSecond * (maxSpeed / kinematics.k)
        val rawForward = driveState.xVelocityMetersPerSecond * maxSpeed
        val rawLeft = driveState.yVelocityMetersPerSecond * maxSpeed

        kinematics.toWheelSpeeds(rawForward, rawLeft, omega, speedBuffer)
        com.areslib.kinematics.MecanumKinematics.normalize(speedBuffer, maxSpeed)

        apply(
            speeds = speedBuffer,
            batteryVolts = batteryVolts,
            dtSeconds = dtSeconds,
            powerScale = flIO.powerScale
        )

        applyPowerScale(flIO.powerScale)
    }

    /**
     * Applies computed 4-wheel target speeds to the motor cluster using voltage feedforwards and PID feedback.
     * 
     * @param speeds Array of 4 target wheel surface speeds $[FL, FR, RL, RR]$ in $m/s$.
     * @param batteryVolts Current battery voltage in Volts ($V$).
     * @param dtSeconds Loop step in seconds ($s$).
     * @param powerScale Master power scaling coefficient $[0.0, 1.0]$.
     */
    @kotlin.jvm.JvmOverloads
    fun apply(speeds: DoubleArray, batteryVolts: Double = 12.0, dtSeconds: Double = 0.02, powerScale: Double = 1.0) {
        feedforward.calculateMotorPowers(
            speeds = speeds,
            maxWheelSpeedMps = maxWheelSpeedMetersPerSecond,
            batteryVolts = batteryVolts,
            dtSeconds = dtSeconds,
            powerScale = powerScale,
            useClosedLoopVelocity = useClosedLoopVelocity,
            ticksPerMeter = ticksPerMeter,
            flVel = flIO.velocity,
            frVel = frIO.velocity,
            rlVel = rlIO.velocity,
            rrVel = rrIO.velocity,
            outputPowers = powerBuffer
        )

        motorCluster.setMotorPowers(powerBuffer[0], powerBuffer[1], powerBuffer[2], powerBuffer[3])
    }

    /**
     * Applies target wheel speeds struct to the motor cluster.
     * 
     * @param speeds [MecanumWheelSpeeds] struct containing target speeds for all 4 wheels ($m/s$).
     * @param batteryVolts Current battery voltage in Volts ($V$).
     * @param dtSeconds Loop step in seconds ($s$).
     * @param powerScale Master power scaling coefficient $[0.0, 1.0]$.
     */
    @kotlin.jvm.JvmOverloads
    fun apply(speeds: MecanumWheelSpeeds, batteryVolts: Double = 12.0, dtSeconds: Double = 0.02, powerScale: Double = 1.0) {
        speedBuffer[0] = speeds.frontLeftMetersPerSecond
        speedBuffer[1] = speeds.frontRightMetersPerSecond
        speedBuffer[2] = speeds.backLeftMetersPerSecond
        speedBuffer[3] = speeds.backRightMetersPerSecond
        apply(speedBuffer, batteryVolts, dtSeconds, powerScale)
    }

    /**
     * Sets a uniform power scale factor $[0.0, 1.0]$ across all motor IO caches.
     * 
     * @param scale Master power scale factor.
     */
    fun applyPowerScale(scale: Double) {
        motorCluster.applyPowerScale(scale)
    }

    /**
     * Commands raw duty-cycle powers $[-1.0, 1.0]$ to all 4 drivetrain motors.
     * 
     * @param fl Front-left motor power.
     * @param fr Front-right motor power.
     * @param rl Rear-left motor power.
     * @param rr Rear-right motor power.
     */
    fun setMotorPowers(fl: Double, fr: Double, rl: Double, rr: Double) {
        motorCluster.setMotorPowers(fl, fr, rl, rr)
    }

    /**
     * Refreshes encoder position and velocity registers for all 4 motors. Zero-GC guarantee.
     */
    fun updateInputs() {
        motorCluster.updateInputs()
    }
}
