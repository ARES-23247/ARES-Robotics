package com.areslib.ftc.hardware

import com.areslib.ftc.hardware.rev.*
import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.actuator.RevEncoderVersion
import com.areslib.hardware.actuator.ServoIO
import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.sensor.ImuInputs
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.CRServo
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.DigitalChannel
import com.qualcomm.robotcore.hardware.IMU
import com.qualcomm.robotcore.hardware.Servo

/**
 * Hardware IO facade for REV Expansion Hub and Control Hub `DcMotorEx` actuators.
 *
 * Delegates hardware control to [RevMotorController], performing bulk caching registration, position and velocity reads,
 * current monitoring, and power updates.
 *
 * @param motor FTC SDK [DcMotorEx] hardware instance.
 * @param name Optional hardware configuration name for telemetry logging.
 *
 * @see MotorIO
 * @see RevMotorController
 */
class FtcMotor(motor: DcMotorEx, name: String? = null) : MotorIO, AutoCloseable {
    private val delegate = RevMotorController(motor, name)
    override var powerScale: Double
        get() = delegate.powerScale
        set(value) { delegate.powerScale = value }
    override var power: Double
        get() = delegate.power
        set(value) { delegate.power = value }
    override val velocity: Double get() = delegate.velocity
    override val position: Double get() = delegate.position
    override val currentAmps: Double get() = delegate.currentAmps

    /** Updates motor input values from the active REV bulk data cache. */
    fun updateInputs() = delegate.updateInputs()

    /** Synchronously queries physical motor current draw in Amperes ($A$). */
    fun pollCurrentSync() = delegate.pollCurrentSync()

    /** Refreshes motor position and velocity measurements. */
    override fun refresh() = delegate.refresh()

    /** Resets the physical encoder count position to zero. */
    override fun resetEncoder() = delegate.resetEncoder()

    /** Unregisters motor from bulk data reader thread. */
    override fun close() = delegate.close()
    
    companion object {
        /** Unregisters all active motors from REV bulk caching trackers. */
        fun unregisterAll() = RevBulkDataReader.unregisterAll()
    }
}

/**
 * Hardware IO facade for Continuous Rotation (CR) Servos driven by a [CRServo] instance.
 *
 * @param crServo FTC SDK [CRServo] hardware instance.
 * @param externalEncoder Optional external encoder interface ([MotorIO]) for position and velocity feedback.
 * @param name Optional hardware configuration name.
 *
 * @see MotorIO
 * @see RevCRServoController
 */
class FtcCRServo(crServo: CRServo, externalEncoder: MotorIO? = null, name: String? = null) : MotorIO {
    private val delegate = RevCRServoController(crServo, externalEncoder, name)
    override var powerScale: Double
        get() = delegate.powerScale
        set(value) { delegate.powerScale = value }
    override var power: Double
        get() = delegate.power
        set(value) { delegate.power = value }
    override val velocity: Double get() = delegate.velocity
    override val position: Double get() = delegate.position
    override val currentAmps: Double get() = delegate.currentAmps

    /** Resets external encoder position reference if present. */
    override fun resetEncoder() = delegate.resetEncoder()
}

/**
 * Read-only hardware IO interface for standalone quadrature or optical encoders connected to REV Hub motor ports.
 *
 * @param motor FTC SDK [DcMotorEx] hardware instance acting as encoder counter input.
 * @param name Optional hardware configuration name.
 *
 * @see MotorIO
 * @see RevEncoderController
 */
class FtcEncoder(motor: DcMotorEx, name: String? = null) : MotorIO {
    private val delegate = RevEncoderController(motor, name)
    override var power: Double
        get() = delegate.power
        set(value) { delegate.power = value }
    override val velocity: Double get() = delegate.velocity
    override val position: Double get() = delegate.position
    override val currentAmps: Double get() = delegate.currentAmps

    /** Updates encoder position and velocity from bulk data cache. */
    fun updateInputs() = delegate.updateInputs()

    /** Refreshes encoder position and velocity data. */
    override fun refresh() = delegate.refresh()

    /** Resets raw encoder position counter to zero. */
    override fun resetEncoder() = delegate.resetEncoder()
}

/**
 * Composite hardware IO adapter decoupling an actuator motor from a separate feedback sensor.
 *
 * Useful for joint mechanisms driven by a motor with position feedback provided by an external encoder.
 *
 * @param actuator [MotorIO] handling voltage power commands.
 * @param sensor [MotorIO] providing position and velocity telemetry feedback.
 *
 * @see RevCompositeMotorController
 */
class CompositeMotorIO(actuator: MotorIO, sensor: MotorIO) : MotorIO {
    private val delegate = RevCompositeMotorController(actuator, sensor)
    override var power: Double
        get() = delegate.power
        set(value) { delegate.power = value }
    override val velocity: Double get() = delegate.velocity
    override val position: Double get() = delegate.position
    override val currentAmps: Double get() = delegate.currentAmps

    /** Resets feedback sensor encoder position. */
    override fun resetEncoder() = delegate.resetEncoder()
}

/**
 * Hardware IO wrapper for absolute analog position encoders (e.g. REV Through Bore Encoder, Axon absolute encoder, Lamprey).
 *
 * Maps $0.0\text{V} \dots 3.3\text{V}$ analog input voltages to continuous absolute angular position in radians ($rad$).
 *
 * @param analogInput FTC SDK [AnalogInput] hardware interface.
 * @param version REV encoder hardware revision variant ([RevEncoderVersion.V1] vs [RevEncoderVersion.V2]).
 * @param ticksPerRev Encoder tick resolution rating ($ticks/rev$, default 8192.0).
 * @param name Optional hardware configuration name.
 *
 * @see MotorIO
 * @see RevAbsoluteAnalogEncoderController
 */
class FtcAbsoluteAnalogEncoder @kotlin.jvm.JvmOverloads constructor(
    analogInput: AnalogInput,
    version: RevEncoderVersion = RevEncoderVersion.V1,
    ticksPerRev: Double = 8192.0,
    name: String? = null
) : MotorIO, AutoCloseable {
    private val delegate = RevAbsoluteAnalogEncoderController(analogInput, version, ticksPerRev, name)
    override var power: Double
        get() = delegate.power
        set(value) { delegate.power = value }
    override val velocity: Double get() = delegate.velocity
    override val position: Double get() = delegate.position
    override val currentAmps: Double get() = delegate.currentAmps

    /** Updates absolute position and velocity inputs from analog telemetry. */
    fun updateInputs() = delegate.updateInputs()

    /** Resets position offset reference. */
    override fun resetEncoder() = delegate.resetEncoder()

    /** Releases encoder sampling resources. */
    override fun close() = delegate.close()
}

/**
 * Hardware IO wrapper for standard 180° / 270° PWM servos driven by REV Hub servo ports.
 *
 * @param servo FTC SDK [Servo] hardware instance.
 * @param name Optional hardware configuration name.
 *
 * @see ServoIO
 * @see RevServoController
 */
class FtcServo(servo: Servo, name: String? = null) : ServoIO {
    private val delegate = RevServoController(servo, name)
    override var position: Double
        get() = delegate.position
        set(value) { delegate.position = value }
}

/**
 * Hardware IO wrapper for REV Control Hub internal IMU (BNO055 / BHI260AP).
 *
 * Returns CCW-positive fused heading angles in radians ($rad$).
 *
 * @param imu FTC SDK [IMU] hardware instance.
 *
 * @see ImuIO
 * @see RevImuController
 */
class FtcImu(imu: IMU) : ImuIO, AutoCloseable {
    private val delegate = RevImuController(imu)

    /** Updates IMU orientation inputs into [ImuInputs] buffer. */
    override fun updateInputs(inputs: ImuInputs) = delegate.updateInputs(inputs)

    /** Resets internal IMU zero heading offset reference. */
    override fun resetHeading() = delegate.resetHeading()

    /** Closes IMU driver resources. */
    override fun close() = delegate.close()
}

/**
 * Hardware IO wrapper for generic analog voltage sensors.
 *
 * @param analogInput FTC SDK [AnalogInput] hardware interface.
 *
 * @see RevAnalogSensorController
 */
class FtcAnalogSensor(analogInput: AnalogInput) : AutoCloseable {
    private val delegate = RevAnalogSensorController(analogInput)

    /** Reads instantaneous analog voltage level in Volts ($V$). */
    fun getVoltage(): Double = delegate.getVoltage()

    /** Releases sensor resources. */
    override fun close() = delegate.close()
}

/**
 * Hardware IO wrapper for generic digital channels (e.g. limit switches, beam break sensors).
 *
 * @param digitalChannel FTC SDK [DigitalChannel] hardware interface.
 *
 * @see RevDigitalSensorController
 */
class FtcDigitalSensor(digitalChannel: DigitalChannel) : AutoCloseable {
    private val delegate = RevDigitalSensorController(digitalChannel)

    /** Reads active digital pin logic state (`true` for HIGH, `false` for LOW). */
    fun getState(): Boolean = delegate.getState()

    /** Releases digital channel resources. */
    override fun close() = delegate.close()
}

