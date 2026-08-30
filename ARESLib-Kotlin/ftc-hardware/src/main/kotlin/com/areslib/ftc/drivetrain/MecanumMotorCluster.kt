package com.areslib.ftc.drivetrain

import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotorSimple
import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.PIDFCoefficients
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


    /** Front-left `DcMotorEx` hardware wrapper. */
    val frontLeft: DcMotorEx = CachedDcMotorEx(hardwareMap.get(DcMotorEx::class.java, flName))
    /** Front-right `DcMotorEx` hardware wrapper. */
    val frontRight: DcMotorEx = CachedDcMotorEx(hardwareMap.get(DcMotorEx::class.java, frName))
    /** Rear-left `DcMotorEx` hardware wrapper. */
    val rearLeft: DcMotorEx = CachedDcMotorEx(hardwareMap.get(DcMotorEx::class.java, rlName))
    /** Rear-right `DcMotorEx` hardware wrapper. */
    val rearRight: DcMotorEx = CachedDcMotorEx(hardwareMap.get(DcMotorEx::class.java, rrName))

    /** Front-left motor IO hardware cache. */
    val flIO = EstimateMotorIO(frontLeft)
    /** Front-right motor IO hardware cache. */
    val frIO = EstimateMotorIO(frontRight)
    /** Rear-left motor IO hardware cache. */
    val rlIO = EstimateMotorIO(rearLeft)
    /** Rear-right motor IO hardware cache. */
    val rrIO = EstimateMotorIO(rearRight)

    private var lastWarningTime = 0L

    /** True after an invalid request or failed motor write until neutral succeeds explicitly. */
    var outputFaultLatched: Boolean = false
        private set

    init {
        frontLeft.direction = flDirection
        frontRight.direction = frDirection
        rearLeft.direction = rlDirection
        rearRight.direction = rrDirection

        frontLeft.zeroPowerBehavior = zeroPowerBehavior
        frontRight.zeroPowerBehavior = zeroPowerBehavior
        rearLeft.zeroPowerBehavior = zeroPowerBehavior
        rearRight.zeroPowerBehavior = zeroPowerBehavior

        hardwareRegistry.registerMotor(flName, flIO)
        hardwareRegistry.registerMotor(frName, frIO)
        hardwareRegistry.registerMotor(rlName, rlIO)
        hardwareRegistry.registerMotor(rrName, rrIO)

        hardwareRegistry.registerSyncPolledDevice(flIO)
        hardwareRegistry.registerSyncPolledDevice(frIO)
        hardwareRegistry.registerSyncPolledDevice(rlIO)
        hardwareRegistry.registerSyncPolledDevice(rrIO)

        if (useClosedLoopVelocity) {
            listOf(frontLeft, frontRight, rearLeft, rearRight).forEach { motor ->
                motor.mode = DcMotor.RunMode.RUN_USING_ENCODER
            }
            if (motorKp != null || motorKi != null || motorKd != null || motorKf != null) {
                val coefficients = PIDFCoefficients(
                    motorKp ?: 0.0, motorKi ?: 0.0, motorKd ?: 0.0, motorKf ?: 0.0
                )
                listOf(frontLeft, frontRight, rearLeft, rearRight).forEach { motor ->
                    motor.setPIDFCoefficients(DcMotor.RunMode.RUN_USING_ENCODER, coefficients)
                }
            }
        } else {
            listOf(frontLeft, frontRight, rearLeft, rearRight).forEach { motor ->
                motor.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
            }
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
        if (outputFaultLatched || !fl.isFinite() || !fr.isFinite() || !rl.isFinite() || !rr.isFinite()) {
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
        if (!safeSetPower(frontLeft, safeFl * flIO.powerScale, "frontLeft")) succeeded = false
        if (!safeSetPower(frontRight, safeFr * frIO.powerScale, "frontRight")) succeeded = false
        if (!safeSetPower(rearLeft, safeRl * rlIO.powerScale, "rearLeft")) succeeded = false
        if (!safeSetPower(rearRight, safeRr * rrIO.powerScale, "rearRight")) succeeded = false
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
        val s = if (scale.isFinite()) scale.coerceIn(0.0, 1.0) else 0.0
        flIO.powerScale = s
        frIO.powerScale = s
        rlIO.powerScale = s
        rrIO.powerScale = s
    }

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
     * Releases motor IO resources upon OpMode completion.
     */
    override fun close() {
        var firstFailure: Throwable? = null
        for (motor in arrayOf(flIO, frIO, rlIO, rrIO)) {
            try {
                motor.close()
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            }
        }
        firstFailure?.let { throw it }
    }
}
