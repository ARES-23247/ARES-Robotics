package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.areslib.ftc.hardware.CachedDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.areslib.util.RobotClock

/**
 * Manages physical 4-motor hardware cluster ($FL, FR, RL, RR$) for an FTC Mecanum drivetrain.
 *
 * Handles [CachedDcMotorEx] wrapping, direction mapping, encoder run modes (`RUN_USING_ENCODER` vs `RUN_WITHOUT_ENCODER`),
 * PIDF gain configurations, and exception-safe motor power assignments.
 *
 * ### Physical Units & Limits:
 * - Duty Cycle Output Power: Normalized voltage ratio $[-1.0, 1.0]$.
 * - PIDF Coefficients: Closed-loop velocity gains ($K_p, K_i, K_d, K_f$).
 *
 * ### Zero-GC Guarantee:
 * Executes [setMotorPowers], [applyPowerScale], and [updateInputs] without heap object allocations during 50Hz–100Hz execution.
 *
 * @param hardwareMap FTC OpMode hardware map instance.
 * @param flName Front-left motor hardware map name (default `"fl"`).
 * @param frName Front-right motor hardware map name (default `"fr"`).
 * @param rlName Rear-left motor hardware map name (default `"rl"`).
 * @param rrName Rear-right motor hardware map name (default `"rr"`).
 * @param flDirection Front-left motor direction polarity.
 * @param frDirection Front-right motor direction polarity.
 * @param rlDirection Rear-left motor direction polarity.
 * @param rrDirection Rear-right motor direction polarity.
 * @param zeroPowerBehavior FTC neutral behavior applied to all four motors during initialization.
 * @param useClosedLoopVelocity Configures motors in `RUN_USING_ENCODER` mode when `true`.
 * @param motorKp Optional PIDF proportional gain $K_p$.
 * @param motorKi Optional PIDF integral gain $K_i$.
 * @param motorKd Optional PIDF derivative gain $K_d$.
 * @param motorKf Optional PIDF feedforward gain $K_f$.
 *
 * @see CachedDcMotorEx
 * @see EstimateMotorIO
 */
class MecanumMotorCluster(
    val hardwareMap: HardwareMap,
    private val hardwareRegistry: HardwareRegistry,
    val flName: String = "fl",
    val frName: String = "fr",
    val rlName: String = "rl",
    val rrName: String = "rr",
    val flDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    val frDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    val rlDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.FORWARD,
    val rrDirection: DcMotorSimple.Direction = DcMotorSimple.Direction.REVERSE,
    val useClosedLoopVelocity: Boolean = false,
    val motorKp: Double? = null,
    val motorKi: Double? = null,
    val motorKd: Double? = null,
    val motorKf: Double? = null,
    val zeroPowerBehavior: DcMotor.ZeroPowerBehavior = DcMotor.ZeroPowerBehavior.BRAKE,
) : AutoCloseable {


    private val motors = resolveMecanumMotors(hardwareMap, arrayOf(flName, frName, rlName, rrName))
        .map<DcMotorEx, DcMotorEx> { CachedDcMotorEx(it) }.toTypedArray()
    private val nativeConfiguration: MecanumNativeConfiguration?

    /** Front-left `DcMotorEx` hardware wrapper. */
    val frontLeft: DcMotorEx = motors[0]
    /** Front-right `DcMotorEx` hardware wrapper. */
    val frontRight: DcMotorEx = motors[1]
    /** Rear-left `DcMotorEx` hardware wrapper. */
    val rearLeft: DcMotorEx = motors[2]
    /** Rear-right `DcMotorEx` hardware wrapper. */
    val rearRight: DcMotorEx = motors[3]

    /** Front-left motor IO hardware cache. */
    val flIO = EstimateMotorIO(frontLeft)
    /** Front-right motor IO hardware cache. */
    val frIO = EstimateMotorIO(frontRight)
    /** Rear-left motor IO hardware cache. */
    val rlIO = EstimateMotorIO(rearLeft)
    /** Rear-right motor IO hardware cache. */
    val rrIO = EstimateMotorIO(rearRight)

    private var lastWarningTime = 0L
    private var closed = false
    private var closeNeutralConfirmed = false

    /** True after an invalid request or failed motor write until neutral succeeds explicitly. */
    var outputFaultLatched: Boolean = false
        private set

    init {
        try {
            check(applyNeutral()) { "Failed to neutralize drivetrain before configuration" }
            frontLeft.direction = flDirection
            frontRight.direction = frDirection
            rearLeft.direction = rlDirection
            rearRight.direction = rrDirection
            for (motor in motors) {
                motor.zeroPowerBehavior = zeroPowerBehavior
                motor.mode = if (useClosedLoopVelocity) DcMotor.RunMode.RUN_USING_ENCODER
                    else DcMotor.RunMode.RUN_WITHOUT_ENCODER
            }
            nativeConfiguration = if (useClosedLoopVelocity) MecanumNativeConfiguration(motors) else null
            if (nativeConfiguration != null &&
                (motorKp != null || motorKi != null || motorKd != null || motorKf != null)) {
                val kp = motorKp ?: 0.0
                val ki = motorKi ?: 0.0
                val kd = motorKd ?: 0.0
                val kf = motorKf ?: 0.0
                require(MecanumNativeConfiguration.valid(kp, ki, kd, kf)) { "Motor gains must be finite" }
                nativeConfiguration.apply(kp, ki, kd, kf)
            }
            // Mode/direction changes invalidate the power cache. Confirm neutral in the final mode.
            check(applyNeutral()) { "Failed to neutralize configured drivetrain" }
            nativeConfiguration?.captureDefaults()
        } catch (failure: Exception) {
            if (!applyNeutral()) failure.addSuppressed(IllegalStateException("Drivetrain cleanup could not confirm neutral"))
            flIO.close()
            frIO.close()
            rlIO.close()
            rrIO.close()
            throw failure
        }
        // Do not expose or start polling a partially configured drivetrain.
        hardwareRegistry.registerMotor(flName, flIO)
        hardwareRegistry.registerMotor(frName, frIO)
        hardwareRegistry.registerMotor(rlName, rlIO)
        hardwareRegistry.registerMotor(rrName, rrIO)
        hardwareRegistry.registerSyncPolledDevice(flIO)
        hardwareRegistry.registerSyncPolledDevice(frIO)
        hardwareRegistry.registerSyncPolledDevice(rlIO)
        hardwareRegistry.registerSyncPolledDevice(rrIO)
    }

    /** Applies native gains while neutral; null F preserves each channel's last accepted F. */
    internal fun requireOpen() { check(!closed) { "Cannot configure a closed drivetrain" } }

    internal fun updateNativeGains(kp: Double, ki: Double, kd: Double, kf: Double? = null, restore: Boolean = false): Boolean {
        requireOpen()
        val configuration = checkNotNull(nativeConfiguration) { "Native velocity mode is disabled" }
        try {
            require(MecanumNativeConfiguration.valid(kp, ki, kd, kf)) { "Motor gains must be finite" }
            if (if (restore) configuration.matchesDefaults() else configuration.matches(kp, ki, kd, kf)) return false
            configuration.valid = false
            setCachedPowers(0.0, 0.0, 0.0, 0.0)
            check(applyNeutral()) { "Cannot configure motors before neutral succeeds" }
            if (restore) configuration.restoreDefaults() else configuration.apply(kp, ki, kd, kf)
            return true
        } catch (failure: Exception) {
            configuration.valid = false
            latchOutputFault()
            throw failure
        }
    }

    /**
     * Stores unscaled duty-cycle requests and applies each motor's safety scale exactly once at the
     * physical hardware boundary.
     *
     * @param fl Front-left motor power.
     * @param fr Front-right motor power.
     * @param rl Rear-left motor power.
     * @param rr Rear-right motor power.
     */
    fun setMotorPowers(fl: Double, fr: Double, rl: Double, rr: Double) {
        val flScale = flIO.powerScale
        val frScale = frIO.powerScale
        val rlScale = rlIO.powerScale
        val rrScale = rrIO.powerScale
        if (closed || outputFaultLatched || !fl.isFinite() || !fr.isFinite() || !rl.isFinite() || !rr.isFinite() ||
            !validScale(flScale) || !validScale(frScale) || !validScale(rlScale) || !validScale(rrScale)) {
            outputFaultLatched = true
            setCachedPowers(0.0, 0.0, 0.0, 0.0)
            applyNeutral()
            return
        }
        val safeFl = finitePower(fl)
        val safeFr = finitePower(fr)
        val safeRl = finitePower(rl)
        val safeRr = finitePower(rr)

        setCachedPowers(safeFl, safeFr, safeRl, safeRr)

        var succeeded = true
        if (!safeSetPower(frontLeft, safeFl * flScale, "frontLeft")) succeeded = false
        if (!safeSetPower(frontRight, safeFr * frScale, "frontRight")) succeeded = false
        if (!safeSetPower(rearLeft, safeRl * rlScale, "rearLeft")) succeeded = false
        if (!safeSetPower(rearRight, safeRr * rrScale, "rearRight")) succeeded = false
        if (!succeeded) {
            outputFaultLatched = true
            setCachedPowers(0.0, 0.0, 0.0, 0.0)
            applyNeutral()
        }
    }

    /**
     * Applies a global master power scaling factor (0.0 to 1.0) to motor IO caches.
     *
     * @param scale Master power scale factor.
     */
    fun applyPowerScale(scale: Double) {
        val valid = validScale(scale)
        val s = if (!closed && valid) scale else 0.0
        flIO.powerScale = s
        frIO.powerScale = s
        rlIO.powerScale = s
        rrIO.powerScale = s
        if (!valid) latchOutputFault()
    }

    private fun validScale(scale: Double): Boolean = scale.isFinite() && scale in 0.0..1.0

    /**
     * Updates encoder position and velocity caches for all 4 motors from bulk-read registers.
     */
    fun updateInputs() {
        flIO.updateInputs()
        frIO.updateInputs()
        rlIO.updateInputs()
        rrIO.updateInputs()
    }

    /**
     * Safely halts all 4 motors by setting their target power to 0.0.
     */
    fun safe() {
        setCachedPowers(0.0, 0.0, 0.0, 0.0)
        if (!applyNeutral()) outputFaultLatched = true
    }

    /** Latches a caller-detected invalid command and immediately attempts neutral on every motor. */
    fun latchOutputFault() {
        outputFaultLatched = true
        setCachedPowers(0.0, 0.0, 0.0, 0.0)
        applyNeutral()
    }

    /** Clears the latch only after all four motors accept an explicit neutral command. */
    fun recoverWithNeutral(): Boolean {
        if (closed || nativeConfiguration?.valid == false) {
            safe()
            return false
        }
        setCachedPowers(0.0, 0.0, 0.0, 0.0)
        val recovered = applyNeutral()
        outputFaultLatched = !recovered
        return recovered
    }

    private fun applyNeutral(): Boolean {
        var succeeded = true
        if (!safeSetPower(frontLeft, 0.0, "frontLeft")) succeeded = false
        if (!safeSetPower(frontRight, 0.0, "frontRight")) succeeded = false
        if (!safeSetPower(rearLeft, 0.0, "rearLeft")) succeeded = false
        if (!safeSetPower(rearRight, 0.0, "rearRight")) succeeded = false
        return succeeded
    }

    private fun setCachedPowers(fl: Double, fr: Double, rl: Double, rr: Double) {
        flIO.power = fl
        frIO.power = fr
        rlIO.power = rl
        rrIO.power = rr
    }

    private fun safeSetPower(motor: DcMotorEx, power: Double, name: String): Boolean {
        try {
            motor.power = finitePower(power)
            return true
        } catch (e: Exception) {
            val now = RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("MecanumMotorCluster: Failed to set $name power. Error: ${e.message}")
                lastWarningTime = now
            }
            return false
        }
    }

    private fun finitePower(power: Double): Double =
        if (power.isFinite()) power.coerceIn(-1.0, 1.0) else 0.0

    /**
     * Permanently inhibits output, attempts neutral on every motor, then closes cached IO.
     * A failed neutral is reported and may be retried by calling close again; it cannot reopen output.
     */
    override fun close() {
        if (closeNeutralConfirmed) return
        closed = true
        outputFaultLatched = true
        applyPowerScale(0.0)
        setCachedPowers(0.0, 0.0, 0.0, 0.0)
        var firstFailure: Throwable? = if (applyNeutral()) null else
            IllegalStateException("Failed to neutralize every drivetrain motor during close")
        for (motor in arrayOf(flIO, frIO, rlIO, rrIO)) {
            try {
                motor.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
        closeNeutralConfirmed = true
    }
}
