package com.areslib.ftc.hardware.rev

import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.DcMotor
import com.qualcomm.robotcore.hardware.CRServo
import com.areslib.hardware.actuator.ServoIO
import com.qualcomm.robotcore.hardware.Servo

/**
 * Direct hardware IO controller for REV Expansion Hub and Control Hub [DcMotorEx] actuators.
 *
 * Implements motor power scaling, stall detection ($>9.2\text{A}$ current spike or low-velocity drive command for $>500\text{ms}$),
 * encoder count caching, and I2C power write caching ($0.001$ change threshold).
 *
 * ### Physical Units & Limits:
 * - Duty Cycle Output Power: Range $[-1.0, 1.0]$.
 * - Current Monitoring: Amperes ($A$), stall protection limit threshold $9.2\text{A}$.
 * - Velocity: Encoder ticks per second ($ticks/s$).
 * - Position: Encoder ticks ($ticks$).
 *
 * ### Zero-GC Compliance:
 * [updateInputs] and [pollCurrentSync] populate primitive properties (`cachedPosition`, `cachedVelocity`, `cachedAmps`) in-place.
 *
 * @param motor FTC SDK [DcMotorEx] hardware instance.
 * @param name Optional hardware configuration name for [HardwareRegistry] registration.
 *
 * @see MotorIO
 * @see RevBulkDataReader
 */
class RevMotorController(
    private val motor: DcMotorEx,
    val name: String? = null
) : MotorIO, AutoCloseable {
    private var encoderOffset = 0.0
    private var cachedPosition = 0.0
    private var cachedVelocity = 0.0
    private var cachedAmps = 0.0
    private val currentLock = Any()

    init {
        try {
            motor.mode = DcMotor.RunMode.STOP_AND_RESET_ENCODER
            motor.mode = DcMotor.RunMode.RUN_WITHOUT_ENCODER
        } catch (_: Exception) {}

        if (name != null) {
            HardwareRegistry.registerMotor(name, this)
        }
        
        RevBulkDataReader.registerMotor(this)
        HardwareRegistry.registerCloseable(this)
    }

    private var targetPower: Double = 0.0
    private var stallStartTimeMs = 0L
    private var isStalled = false
    private var lastSentPower = Double.NaN

    override var powerScale: Double = 1.0
        set(value) {
            field = sanitizeScale(value)
            if (!isStalled) {
                try {
                    val commandPower = targetPower * field
                    if (lastSentPower.isNaN() || kotlin.math.abs(commandPower - lastSentPower) > 0.001) {
                        motor.power = commandPower
                        lastSentPower = commandPower
                    }
                } catch (_: Exception) {}
            }
        }

    override var power: Double
        get() = targetPower
        set(value) {
            val safePower = sanitizePower(value)
            targetPower = safePower
            val timeMs = com.areslib.util.RobotClock.currentTimeMillis()
            val currentVel = this.velocity

            if (kotlin.math.abs(safePower) > 0.5 && kotlin.math.abs(currentVel) < 10.0) {
                when {
                    stallStartTimeMs == 0L -> stallStartTimeMs = timeMs
                    timeMs - stallStartTimeMs > 500 -> isStalled = true
                }
            } else {
                stallStartTimeMs = 0L
                isStalled = false
            }

            val amps = this.currentAmps
            if (amps > 9.2) {
                isStalled = true
            }

            try {
                val commandPower = if (isStalled) 0.0 else safePower * powerScale
                if (lastSentPower.isNaN() || kotlin.math.abs(commandPower - lastSentPower) > 0.001) {
                    motor.power = commandPower
                    lastSentPower = commandPower
                }
            } catch (_: Exception) {}
        }

    /** Updates motor position and velocity cache variables from REV bulk data cache. */
    fun updateInputs() {
        try {
            cachedPosition = motor.currentPosition.toDouble() - encoderOffset
        } catch (_: Exception) {}
        try {
            cachedVelocity = motor.velocity
        } catch (_: Exception) {}
        if (motor.javaClass.simpleName.contains("Mock")) {
            pollCurrentSync()
        }
    }

    /** Synchronously queries physical motor current draw in Amperes ($A$). */
    fun pollCurrentSync() {
        try {
            if (motor.javaClass.simpleName.contains("Mock")) {
                Thread.sleep(2)
            }
            val amps = motor.getCurrent(org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit.AMPS)
            synchronized(currentLock) {
                cachedAmps = amps
            }
        } catch (_: Exception) {}
    }

    /** Refreshes motor input values. */
    override fun refresh() {
        updateInputs()
    }

    override val velocity: Double
        get() = cachedVelocity

    override val position: Double
        get() = cachedPosition

    override val currentAmps: Double
        get() = synchronized(currentLock) { cachedAmps }

    /** Resets the physical encoder count position to zero. */
    override fun resetEncoder() {
        try {
            encoderOffset = motor.currentPosition.toDouble()
            cachedPosition = 0.0
        } catch (_: Exception) {}
    }

    /** Unregisters motor from background current polling thread. */
    override fun close() {
        RevBulkDataReader.unregisterMotor(this)
    }
}

/**
 * Direct hardware IO controller for Continuous Rotation (CR) Servos.
 *
 * @param crServo FTC SDK [CRServo] hardware instance.
 * @param externalEncoder Optional external feedback encoder ([MotorIO]).
 * @param name Optional hardware configuration name.
 *
 * @see MotorIO
 */
class RevCRServoController(
    private val crServo: CRServo,
    private val externalEncoder: MotorIO? = null,
    val name: String? = null
) : MotorIO {
    init {
        if (name != null) {
            HardwareRegistry.registerMotor(name, this)
        }
    }
    private var targetPower: Double = 0.0
    private var lastSentPower = Double.NaN

    override var powerScale: Double = 1.0
        set(value) {
            field = sanitizeScale(value)
            try {
                val commandPower = targetPower * field
                if (lastSentPower.isNaN() || kotlin.math.abs(commandPower - lastSentPower) > 0.001) {
                    crServo.power = commandPower
                    lastSentPower = commandPower
                }
            } catch (_: Exception) {}
        }

    override var power: Double
        get() = targetPower
        set(value) {
            val safePower = sanitizePower(value)
            targetPower = safePower
            try {
                val commandPower = safePower * powerScale
                if (lastSentPower.isNaN() || kotlin.math.abs(commandPower - lastSentPower) > 0.001) {
                    crServo.power = commandPower
                    lastSentPower = commandPower
                }
            } catch (_: Exception) {}
        }

    override val velocity: Double
        get() = externalEncoder?.velocity ?: 0.0

    override val position: Double
        get() = externalEncoder?.position ?: 0.0

    /** Resets external encoder position reference if present. */
    override fun resetEncoder() {
        externalEncoder?.resetEncoder()
    }
}

private fun sanitizePower(value: Double): Double =
    if (value.isFinite()) value.coerceIn(-1.0, 1.0) else 0.0

private fun sanitizeScale(value: Double): Double =
    if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

/**
 * Direct hardware IO controller for standalone motor port quadrature encoders.
 *
 * @param motor FTC SDK [DcMotorEx] hardware instance acting as encoder counter input.
 * @param name Optional hardware configuration name.
 *
 * @see MotorIO
 */
class RevEncoderController(
    private val motor: DcMotorEx,
    val name: String? = null
) : MotorIO {
    private var encoderOffset = 0.0
    private var cachedPosition = 0.0
    private var cachedVelocity = 0.0

    init {
        if (name != null) {
            HardwareRegistry.registerMotor(name, this)
        }
    }

    override var power: Double
        get() = 0.0
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /** Updates position and velocity cache variables from REV bulk data. */
    fun updateInputs() {
        try {
            cachedPosition = motor.currentPosition.toDouble() - encoderOffset
        } catch (_: Exception) {}
        try {
            cachedVelocity = motor.velocity
        } catch (_: Exception) {}
    }

    /** Refreshes encoder position and velocity readings. */
    override fun refresh() {
        updateInputs()
    }

    override val velocity: Double
        get() = cachedVelocity

    override val position: Double
        get() = cachedPosition

    /** Resets raw encoder position counter to zero. */
    override fun resetEncoder() {
        try {
            encoderOffset = motor.currentPosition.toDouble()
            cachedPosition = 0.0
        } catch (_: Exception) {}
    }
}

/**
 * Composite hardware controller pairing a motor power output actuator with an independent encoder feedback sensor.
 *
 * @param actuator Actuator motor interface ([MotorIO]).
 * @param sensor Feedback sensor interface ([MotorIO]).
 *
 * @see MotorIO
 */
class RevCompositeMotorController(
    private val actuator: MotorIO,
    private val sensor: MotorIO
) : MotorIO {
    override var power: Double
        get() = actuator.power
        set(value) {
            actuator.power = value
        }

    override val velocity: Double
        get() = sensor.velocity

    override val position: Double
        get() = sensor.position

    override val currentAmps: Double
        get() = actuator.currentAmps

    /** Resets feedback sensor encoder position. */
    override fun resetEncoder() {
        sensor.resetEncoder()
    }
}

/**
 * Direct hardware IO controller for standard 180° / 270° PWM servos plugged into REV Hub servo ports.
 *
 * Performs write caching with $0.001$ position change tolerance threshold.
 *
 * @param servo FTC SDK [Servo] hardware instance.
 * @param name Optional hardware configuration name.
 *
 * @see ServoIO
 */
class RevServoController(
    private val servo: Servo,
    val name: String? = null
) : ServoIO {
    private var lastSentPosition = Double.NaN

    init {
        if (name != null) {
            HardwareRegistry.registerServo(name, this)
        }
    }

    override var position: Double
        get() = try {
            servo.position
        } catch (_: Exception) {
            0.0
        }
        set(value) {
            try {
                if (lastSentPosition.isNaN() || kotlin.math.abs(value - lastSentPosition) > 0.001) {
                    servo.position = value
                    lastSentPosition = value
                }
            } catch (_: Exception) {}
        }
}
