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
 * $$u_{FF} = k_V v_{desired} + k_A a_{desired} + k_S \operatorname{sign}(v_{desired})$$
 * Raw requested effort with PID feedback and voltage compensation:
 * $$u_{raw} = \text{coerceIn}\left((u_{FF} + u_{PID}) \cdot \frac{12}{V_{battery}}, -1.0, 1.0\right)$$
 *
 * The drivetrain's safety scale is deliberately not applied here. [MecanumMotorCluster] owns that
 * final hardware-boundary multiplication so [com.areslib.hardware.actuator.MotorIO.power] remains
 * the unscaled request and current estimation does not apply the same scale twice.
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
    initialKs: Double = 0.0,
    motorKp: Double? = null,
    motorKi: Double? = null,
    motorKd: Double? = null,
    initialSlewRateLimit: Double? = null
) {
    private var batchingGains = false

    /** Legacy constructor property; assignments also update [kS]. */
    var initialKs: Double = initialKs
        set(value) { field = value; kS = value }
    var motorKp: Double? = motorKp
        set(value) { if (field == value) return; field = value; if (!batchingGains) configureControllers() }
    var motorKi: Double? = motorKi
        set(value) { if (field == value) return; field = value; if (!batchingGains) configureControllers() }
    var motorKd: Double? = motorKd
        set(value) { if (field == value) return; field = value; if (!batchingGains) configureControllers() }
    /** Legacy constructor property; assignments also update [slewRateLimit]. */
    var initialSlewRateLimit: Double? = initialSlewRateLimit
        set(value) { field = value; slewRateLimit = value }

    var kS: Double = initialKs
        set(value) { if (field == value) return; field = value; reset() }
    var kV: Double = 0.0
        set(value) { if (field == value) return; field = value; reset() }
    var kA: Double = 0.0
        set(value) { if (field == value) return; field = value; reset() }

    private val previousSpeeds = DoubleArray(4)
    private var flController: PIDController? = null
    private var frController: PIDController? = null
    private var rlController: PIDController? = null
    private var rrController: PIDController? = null
    private var gainsValid = true
    private var flLimiter: SlewRateLimiter? = null
    private var frLimiter: SlewRateLimiter? = null
    private var rlLimiter: SlewRateLimiter? = null
    private var rrLimiter: SlewRateLimiter? = null

    /** Null disables limiting. Nonpositive/nonfinite values invalidate the configuration. */
    var slewRateLimit: Double? = null
        set(value) {
            if (field == value) return
            field = value
            if (value != null && value.isFinite() && value > 0.0) {
                // Changing a valid rate preserves ramp state and reuses existing limiters.
                flLimiter = flLimiter ?: SlewRateLimiter(value)
                frLimiter = frLimiter ?: SlewRateLimiter(value)
                rlLimiter = rlLimiter ?: SlewRateLimiter(value)
                rrLimiter = rrLimiter ?: SlewRateLimiter(value)
                flLimiter?.setRateLimits(value); frLimiter?.setRateLimits(value)
                rlLimiter?.setRateLimits(value); rrLimiter?.setRateLimits(value)
            } else {
                reset()
                flLimiter = null; frLimiter = null; rlLimiter = null; rrLimiter = null
            }
        }

    var enableVoltageCompensatedSlew: Boolean = false
    internal var lastCalculationValid: Boolean = false
        private set

    init {
        configureControllers()
        slewRateLimit = initialSlewRateLimit
    }

    /** Updates all gains together; unchanged configuration preserves controller history. */
    fun updateMotorGains(kp: Double, ki: Double, kd: Double) {
        restoreMotorGains(kp, ki, kd)
    }

    /** Restores optional construction settings; three nulls restore feedback-free operation. */
    internal fun restoreMotorGains(kp: Double?, ki: Double?, kd: Double?) {
        if (motorKp == kp && motorKi == ki && motorKd == kd) return
        batchingGains = true
        motorKp = kp; motorKi = ki; motorKd = kd
        batchingGains = false
        configureControllers()
    }

    private fun configureControllers() {
        reset()
        val kp = motorKp ?: 0.0
        val ki = motorKi ?: 0.0
        val kd = motorKd ?: 0.0
        gainsValid = kp.isFinite() && ki.isFinite() && kd.isFinite()
        if (!gainsValid || motorKp == null && motorKi == null && motorKd == null) {
            flController = null; frController = null; rlController = null; rrController = null
            return
        }
        if (flController == null) {
            flController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            frController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rlController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
            rrController = PIDController(kp, ki, kd).apply { setOutputLimits(-1.0, 1.0) }
        } else {
            flController?.let { it.p = kp; it.i = ki; it.d = kd }
            frController?.let { it.p = kp; it.i = ki; it.d = kd }
            rlController?.let { it.p = kp; it.i = ki; it.d = kd }
            rrController?.let { it.p = kp; it.i = ki; it.d = kd }
        }
    }

    /** Clears all dynamic state without allocating or changing configuration. */
    internal fun reset() {
        lastCalculationValid = false
        previousSpeeds.fill(0.0)
        flController?.reset(); frController?.reset(); rlController?.reset(); rrController?.reset()
        flLimiter?.reset(0.0); frLimiter?.reset(0.0); rlLimiter?.reset(0.0); rrLimiter?.reset(0.0)
    }

    /**
     * Calculates four coupled duty-cycle requests, with caller-owned buffers that may alias.
     * Rejected input/configuration clears the available output slots and all dynamic history.
     * Extra output slots beyond the four wheels remain untouched. Feedforward-only operation
     * needs no encoder observation; hub/software velocity control requires all four observations.
     */
    fun calculateMotorPowers(
        speeds: DoubleArray,
        maxWheelSpeedMps: Double,
        batteryVolts: Double,
        dtSeconds: Double,
        useClosedLoopVelocity: Boolean,
        ticksPerMeter: Double,
        flVel: Double,
        frVel: Double,
        rlVel: Double,
        rrVel: Double,
        outputPowers: DoubleArray
    ) {
        // Capture the input before clearing output: callers can reuse the same array.
        val flRequest = if (speeds.size >= 4) speeds[0] else Double.NaN
        val frRequest = if (speeds.size >= 4) speeds[1] else Double.NaN
        val rlRequest = if (speeds.size >= 4) speeds[2] else Double.NaN
        val rrRequest = if (speeds.size >= 4) speeds[3] else Double.NaN
        for (i in 0 until minOf(4, outputPowers.size)) outputPowers[i] = 0.0
        lastCalculationValid = false
        val limit = slewRateLimit
        if (outputPowers.size < 4 || speeds.size < 4 ||
            !flRequest.isFinite() || !frRequest.isFinite() || !rlRequest.isFinite() || !rrRequest.isFinite() ||
            !maxWheelSpeedMps.isFinite() || maxWheelSpeedMps <= 0.0 ||
            !batteryVolts.isFinite() || batteryVolts <= 0.1 || !dtSeconds.isFinite() || dtSeconds <= 0.0 ||
            !kS.isFinite() || !kV.isFinite() || !kA.isFinite() || !gainsValid ||
            limit != null && (!limit.isFinite() || limit <= 0.0)) return reset()

        val softwareFeedback = !useClosedLoopVelocity && flController != null
        if ((useClosedLoopVelocity || softwareFeedback) &&
            (!flVel.isFinite() || !frVel.isFinite() || !rlVel.isFinite() || !rrVel.isFinite())) return reset()
        if (softwareFeedback && (!ticksPerMeter.isFinite() || ticksPerMeter <= 1e-9)) return reset()
        // Convert once, validate once, and pass primitive SI values directly into feedback.
        val flMeasured = if (softwareFeedback) flVel / ticksPerMeter else 0.0
        val frMeasured = if (softwareFeedback) frVel / ticksPerMeter else 0.0
        val rlMeasured = if (softwareFeedback) rlVel / ticksPerMeter else 0.0
        val rrMeasured = if (softwareFeedback) rrVel / ticksPerMeter else 0.0
        if (!flMeasured.isFinite() || !frMeasured.isFinite() || !rlMeasured.isFinite() || !rrMeasured.isFinite()) return reset()

        val flTarget = flRequest.coerceIn(-maxWheelSpeedMps, maxWheelSpeedMps)
        val frTarget = frRequest.coerceIn(-maxWheelSpeedMps, maxWheelSpeedMps)
        val rlTarget = rlRequest.coerceIn(-maxWheelSpeedMps, maxWheelSpeedMps)
        val rrTarget = rrRequest.coerceIn(-maxWheelSpeedMps, maxWheelSpeedMps)
        if (abs(flTarget) < 1e-4) flController?.reset()
        if (abs(frTarget) < 1e-4) frController?.reset()
        if (abs(rlTarget) < 1e-4) rlController?.reset()
        if (abs(rrTarget) < 1e-4) rrController?.reset()

        var flPower = feedforward(flTarget, previousSpeeds[0], dtSeconds) + feedback(flController, flMeasured, flTarget, dtSeconds, softwareFeedback)
        var frPower = feedforward(frTarget, previousSpeeds[1], dtSeconds) + feedback(frController, frMeasured, frTarget, dtSeconds, softwareFeedback)
        var rlPower = feedforward(rlTarget, previousSpeeds[2], dtSeconds) + feedback(rlController, rlMeasured, rlTarget, dtSeconds, softwareFeedback)
        var rrPower = feedforward(rrTarget, previousSpeeds[3], dtSeconds) + feedback(rrController, rrMeasured, rrTarget, dtSeconds, softwareFeedback)
        if (!flPower.isFinite() || !frPower.isFinite() || !rlPower.isFinite() || !rrPower.isFinite()) return reset()
        previousSpeeds[0] = flTarget; previousSpeeds[1] = frTarget
        previousSpeeds[2] = rlTarget; previousSpeeds[3] = rrTarget

        if (limit != null) {
            val positive = if (enableVoltageCompensatedSlew)
                limit * ((batteryVolts - 7.5) / 4.5).coerceIn(0.2, 1.0) else limit
            flLimiter?.setRateLimits(positive, -limit); frLimiter?.setRateLimits(positive, -limit)
            rlLimiter?.setRateLimits(positive, -limit); rrLimiter?.setRateLimits(positive, -limit)
        }
        flLimiter?.let { flPower = it.calculate(flPower, dtSeconds) }
        frLimiter?.let { frPower = it.calculate(frPower, dtSeconds) }
        rlLimiter?.let { rlPower = it.calculate(rlPower, dtSeconds) }
        rrLimiter?.let { rrPower = it.calculate(rrPower, dtSeconds) }
        if (!flPower.isFinite() || !frPower.isFinite() || !rlPower.isFinite() || !rrPower.isFinite()) return reset()

        // Clamp before multiplying so a finite saturated request cannot overflow to infinity.
        val nominalLimit = batteryVolts / 12.0
        val compensation = 12.0 / batteryVolts
        outputPowers[0] = (flPower.coerceIn(-nominalLimit, nominalLimit) * compensation).coerceIn(-1.0, 1.0)
        outputPowers[1] = (frPower.coerceIn(-nominalLimit, nominalLimit) * compensation).coerceIn(-1.0, 1.0)
        outputPowers[2] = (rlPower.coerceIn(-nominalLimit, nominalLimit) * compensation).coerceIn(-1.0, 1.0)
        outputPowers[3] = (rrPower.coerceIn(-nominalLimit, nominalLimit) * compensation).coerceIn(-1.0, 1.0)
        lastCalculationValid = true
    }

    private fun feedforward(target: Double, previous: Double, dt: Double): Double {
        if (abs(target) < 1e-4) return 0.0
        val acceleration = if (kA == 0.0) 0.0 else kA * ((target - previous) / dt)
        return kV * target + acceleration + kS * sign(target)
    }

    private fun feedback(controller: PIDController?, measured: Double, target: Double, dt: Double, enabled: Boolean): Double {
        if (!enabled || controller == null) return 0.0
        val effort = controller.calculate(measured, target, dt)
        return if (controller.lastCalculationValid) effort else Double.NaN
    }
}
