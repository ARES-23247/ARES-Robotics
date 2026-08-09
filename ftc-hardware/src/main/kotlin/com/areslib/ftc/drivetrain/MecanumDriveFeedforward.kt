package com.areslib.ftc.drivetrain

import com.areslib.control.feedback.PIDController
import com.areslib.math.filter.SlewRateLimiter
import kotlin.math.abs
import kotlin.math.sign

/**
 * Feedforward and feedback power controller for 4-wheel Mecanum drivetrains.
 *
 * Manages static friction feedforward ($k_S$), battery voltage compensation, wheel velocity PID feedback loops,
 * and voltage-scaled slew rate acceleration limits.
 *
 * ### Mathematical Formulation:
 * Feedforward calculation:
 * $$u_{FF} = \left(\frac{v_{desired}}{v_{max}} + k_S \cdot \text{sign}(v_{desired})\right) \cdot \frac{12.0}{V_{battery}}$$
 * Power effort with PID feedback and voltage scaling:
 * $$u_{out} = \text{coerceIn}\left((u_{FF} + u_{PID}) \cdot \text{powerScale}, -1.0, 1.0\right)$$
 *
 * ### Physical Units & Range Boundaries:
 * - Wheel Surface Velocities: Meters per second ($m/s$).
 * - Measured Voltage: Volts ($V$).
 * - Static Friction $k_S$: Dimensionless normalized voltage feedforward offset $[0.0, 1.0]$.
 * - Output Power: Duty cycle percent $[-1.0, 1.0]$.
 *
 * ### Zero-GC Compliance:
 * Computes all 4 motor power outputs in [calculateMotorPowers] using pre-allocated arrays and primitive variables with zero dynamic heap allocations.
 *
 * @param initialKs Static friction feedforward voltage offset $k_S$.
 * @param motorKp Proportional gain $K_p$ for wheel velocity PID feedback.
 * @param motorKi Integral gain $K_i$ for wheel velocity PID feedback.
 * @param motorKd Derivative gain $K_d$ for wheel velocity PID feedback.
 * @param initialSlewRateLimit Acceleration slew rate limit ($1/s$).
 */
class MecanumDriveFeedforward(
    var initialKs: Double = 0.0,
    var motorKp: Double? = null,
    var motorKi: Double? = null,
    var motorKd: Double? = null,
    var initialSlewRateLimit: Double? = null
) {

    /** Static friction feedforward coefficient $k_S$. */
    var kS: Double = initialKs
    var kV: Double = 0.0
    var kA: Double = 0.0

    private val previousSpeeds = DoubleArray(4)

    private var flController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var frController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var rlController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null
    private var rrController = if (motorKp != null) PIDController(motorKp!!, motorKi ?: 0.0, motorKd ?: 0.0) else null

    private var flLimiter: SlewRateLimiter? = null
    private var frLimiter: SlewRateLimiter? = null
    private var rlLimiter: SlewRateLimiter? = null
    private var rrLimiter: SlewRateLimiter? = null

    /** Maximum acceleration slew rate limit. */
    var slewRateLimit: Double? = initialSlewRateLimit
        set(value) {
            field = value
            if (value != null) {
                flLimiter = SlewRateLimiter(value)
                frLimiter = SlewRateLimiter(value)
                rlLimiter = SlewRateLimiter(value)
                rrLimiter = SlewRateLimiter(value)
            } else {
                flLimiter = null
                frLimiter = null
                rlLimiter = null
                rrLimiter = null
            }
        }

    /** Enables automatic reduction of slew acceleration limits when battery voltage drops below 12.0V. */
    var enableVoltageCompensatedSlew: Boolean = false

    init {
        flController?.setOutputLimits(-1.0, 1.0)
        frController?.setOutputLimits(-1.0, 1.0)
        rlController?.setOutputLimits(-1.0, 1.0)
        rrController?.setOutputLimits(-1.0, 1.0)

        if (initialSlewRateLimit != null) {
            slewRateLimit = initialSlewRateLimit
        }
    }

    /**
     * Dynamically updates PID gains across all 4 wheel velocity controllers.
     *
     * @param kp Proportional gain $K_p$.
     * @param ki Integral gain $K_i$.
     * @param kd Derivative gain $K_d$.
     */
    fun updateMotorGains(kp: Double, ki: Double, kd: Double) {
        val fl = flController
        if (fl == null) {
            flController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            frController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rlController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rrController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
        } else {
            fl.p = kp; fl.i = ki; fl.d = kd
            frController?.let { it.p = kp; it.i = ki; it.d = kd }
            rlController?.let { it.p = kp; it.i = ki; it.d = kd }
            rrController?.let { it.p = kp; it.i = ki; it.d = kd }
        }
    }

    /**
     * Zero-GC calculation cycle for 4 motor duty-cycle powers with feedforward, PID feedback, and slew rate limiting.
     *
     * @param speeds Target 4 wheel surface speeds $[v_{FL}, v_{FR}, v_{RL}, v_{RR}]$ (m/s).
     * @param maxWheelSpeedMps Maximum physical wheel speed capability (m/s).
     * @param batteryVolts Current battery voltage level in Volts ($V$).
     * @param dtSeconds Time step in seconds ($s$).
     * @param powerScale Master power scaling multiplier (0.0 to 1.0).
     * @param useClosedLoopVelocity True if REV Control Hub internal closed-loop velocity PID is enabled.
     * @param ticksPerMeter Encoder resolution in ticks per meter.
     * @param flVel Front-left wheel velocity encoder reading (ticks/s).
     * @param frVel Front-right wheel velocity encoder reading (ticks/s).
     * @param rlVel Rear-left wheel velocity encoder reading (ticks/s).
     * @param rrVel Rear-right wheel velocity encoder reading (ticks/s).
     * @param outputPowers 4-element output array receiving computed motor power duty cycles (-1.0 to 1.0).
     */
    fun calculateMotorPowers(
        speeds: DoubleArray,
        maxWheelSpeedMps: Double,
        batteryVolts: Double,
        dtSeconds: Double,
        powerScale: Double,
        useClosedLoopVelocity: Boolean,
        ticksPerMeter: Double,
        flVel: Double,
        frVel: Double,
        rlVel: Double,
        rrVel: Double,
        outputPowers: DoubleArray
    ) {
        if (speeds.size < 4 || outputPowers.size < 4) return
        val maxVolts = 12.0
        val actualVolts = if (batteryVolts > 0.1) batteryVolts else 12.0
        val voltageCompensationFactor = maxVolts / actualVolts

        fun applyFeedforward(speedMetersPerSecond: Double, prevSpeed: Double): Double {
            if (abs(speedMetersPerSecond) < 1e-4) return 0.0
            val sign = sign(speedMetersPerSecond)
            val acceleration = if (dtSeconds > 1e-4) (speedMetersPerSecond - prevSpeed) / dtSeconds else 0.0
            val velocityFF = speedMetersPerSecond * kV
            val accelFF = acceleration * kA
            val staticFF = sign * kS
            return (velocityFF + accelFF + staticFF)
        }

        if (abs(speeds[0]) < 1e-4) flController?.reset()
        if (abs(speeds[1]) < 1e-4) frController?.reset()
        if (abs(speeds[2]) < 1e-4) rlController?.reset()
        if (abs(speeds[3]) < 1e-4) rrController?.reset()

        val fl = flController
        val fr = frController
        val rl = rlController
        val rr = rrController

        val flFeedback = if (!useClosedLoopVelocity && fl != null) fl.calculate(flVel / ticksPerMeter, speeds[0], dtSeconds) else 0.0
        val frFeedback = if (!useClosedLoopVelocity && fr != null) fr.calculate(frVel / ticksPerMeter, speeds[1], dtSeconds) else 0.0
        val rlFeedback = if (!useClosedLoopVelocity && rl != null) rl.calculate(rlVel / ticksPerMeter, speeds[2], dtSeconds) else 0.0
        val rrFeedback = if (!useClosedLoopVelocity && rr != null) rr.calculate(rrVel / ticksPerMeter, speeds[3], dtSeconds) else 0.0

        var flPower = ((applyFeedforward(speeds[0], previousSpeeds[0]) + flFeedback) * powerScale)
        var frPower = ((applyFeedforward(speeds[1], previousSpeeds[1]) + frFeedback) * powerScale)
        var rlPower = ((applyFeedforward(speeds[2], previousSpeeds[2]) + rlFeedback) * powerScale)
        var rrPower = ((applyFeedforward(speeds[3], previousSpeeds[3]) + rrFeedback) * powerScale)
        
        previousSpeeds[0] = speeds[0]
        previousSpeeds[1] = speeds[1]
        previousSpeeds[2] = speeds[2]
        previousSpeeds[3] = speeds[3]

        val baseLimit = slewRateLimit
        if (baseLimit != null) {
            val posLimit = if (enableVoltageCompensatedSlew) {
                val scale = ((actualVolts - 7.5) / (12.0 - 7.5)).coerceIn(0.2, 1.0)
                baseLimit * scale
            } else {
                baseLimit
            }
            flLimiter?.setRateLimits(posLimit, -baseLimit)
            frLimiter?.setRateLimits(posLimit, -baseLimit)
            rlLimiter?.setRateLimits(posLimit, -baseLimit)
            rrLimiter?.setRateLimits(posLimit, -baseLimit)
        }

        flLimiter?.let { flPower = it.calculate(flPower, dtSeconds) }
        frLimiter?.let { frPower = it.calculate(frPower, dtSeconds) }
        rlLimiter?.let { rlPower = it.calculate(rlPower, dtSeconds) }
        rrLimiter?.let { rrPower = it.calculate(rrPower, dtSeconds) }

        outputPowers[0] = (flPower * voltageCompensationFactor).coerceIn(-1.0, 1.0)
        outputPowers[1] = (frPower * voltageCompensationFactor).coerceIn(-1.0, 1.0)
        outputPowers[2] = (rlPower * voltageCompensationFactor).coerceIn(-1.0, 1.0)
        outputPowers[3] = (rrPower * voltageCompensationFactor).coerceIn(-1.0, 1.0)
    }
}
