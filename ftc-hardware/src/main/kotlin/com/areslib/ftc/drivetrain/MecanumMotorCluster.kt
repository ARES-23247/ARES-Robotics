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
    val motorKf: Double? = null
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

    init {
        frontLeft.direction = flDirection
        frontRight.direction = frDirection
        rearLeft.direction = rlDirection
        rearRight.direction = rrDirection

        HardwareRegistry.registerMotor(flName, flIO)
        HardwareRegistry.registerMotor(frName, frIO)
        HardwareRegistry.registerMotor(rlName, rlIO)
        HardwareRegistry.registerMotor(rrName, rrIO)

        HardwareRegistry.registerSyncPolledDevice(flIO)
        HardwareRegistry.registerSyncPolledDevice(frIO)
        HardwareRegistry.registerSyncPolledDevice(rlIO)
        HardwareRegistry.registerSyncPolledDevice(rrIO)

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
     * Commands duty-cycle power settings (-1.0 to 1.0) to all 4 motors simultaneously.
     *
     * @param fl Front-left motor power.
     * @param fr Front-right motor power.
     * @param rl Rear-left motor power.
     * @param rr Rear-right motor power.
     */
    fun setMotorPowers(fl: Double, fr: Double, rl: Double, rr: Double) {
        safeSetPower(frontLeft, fl, "frontLeft")
        safeSetPower(frontRight, fr, "frontRight")
        safeSetPower(rearLeft, rl, "rearLeft")
        safeSetPower(rearRight, rr, "rearRight")

        flIO.power = fl
        frIO.power = fr
        rlIO.power = rl
        rrIO.power = rr
    }

    /**
     * Applies a global master power scaling factor (0.0 to 1.0) to motor IO caches.
     *
     * @param scale Master power scale factor.
     */
    fun applyPowerScale(scale: Double) {
        val s = scale.coerceIn(0.0, 1.0)
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
        safeSetPower(frontLeft, 0.0, "frontLeft")
        safeSetPower(frontRight, 0.0, "frontRight")
        safeSetPower(rearLeft, 0.0, "rearLeft")
        safeSetPower(rearRight, 0.0, "rearRight")
        flIO.power = 0.0
        frIO.power = 0.0
        rlIO.power = 0.0
        rrIO.power = 0.0
    }

    private fun safeSetPower(motor: DcMotorEx, power: Double, name: String) {
        try {
            motor.power = power
        } catch (e: Exception) {
            val now = RobotClock.currentTimeMillis()
            if (now - lastWarningTime > 2000L) {
                System.err.println("MecanumMotorCluster: Failed to set $name power. Error: ${e.message}")
                lastWarningTime = now
            }
        }
    }

    /**
     * Releases motor IO resources upon OpMode completion.
     */
    override fun close() {
        flIO.close()
        frIO.close()
        rlIO.close()
        rrIO.close()
    }
}
