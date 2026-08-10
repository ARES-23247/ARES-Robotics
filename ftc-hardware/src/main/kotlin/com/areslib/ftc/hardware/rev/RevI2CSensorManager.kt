package com.areslib.ftc.hardware.rev

import com.areslib.hardware.sensor.ImuIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.IMU
import com.qualcomm.hardware.rev.RevHubOrientationOnRobot
import org.firstinspires.ftc.robotcore.external.navigation.AngleUnit
import com.qualcomm.robotcore.hardware.AnalogInput
import com.qualcomm.robotcore.hardware.DigitalChannel
import com.areslib.hardware.actuator.MotorIO

/**
 * Asynchronous hardware IO controller for REV Control Hub internal IMU (BNO055 / BHI260AP).
 *
 * Polling executes on a dedicated 50Hz background thread (`ARES-Asynchronous-IMU-Thread`) to read robot yaw, pitch, roll,
 * and Z-axis rotational rate without blocking the main control loop.
 *
 * ### Physical Units & Coordinate System:
 * - Angles: Radians ($rad$), **CCW-positive** standard ($0 = +X$, $\pi/2 = +Y$).
 * - Angular Rate: Radians per second ($rad/s$).
 * - Default Orientation: Logo facing UP, USB facing FORWARD.
 *
 * @param imu Underlying FTC SDK [IMU] hardware instance.
 *
 * @see ImuIO
 * @see ImuInputs
 */
class RevImuController(private val imu: IMU) : ImuIO, AutoCloseable {
    private var headingOffset = 0.0
    private val lock = Any()

    private var latestYaw = 0.0
    private var latestPitch = 0.0
    private var latestRoll = 0.0
    private var latestYawVel = 0.0
    private var latestTimestamp = 0L
    @Volatile private var running = true

    private val imuThread = Thread {
        while (running) {
            try {
                val yawPitchRoll = imu.getRobotYawPitchRollAngles()
                val angularVel = imu.getRobotAngularVelocity(AngleUnit.RADIANS)
                synchronized(lock) {
                    latestYaw = yawPitchRoll.getYaw(AngleUnit.RADIANS)
                    latestPitch = yawPitchRoll.getPitch(AngleUnit.RADIANS)
                    latestRoll = yawPitchRoll.getRoll(AngleUnit.RADIANS)
                    latestYawVel = angularVel.getZRotationRate(AngleUnit.RADIANS).toDouble()
                    latestTimestamp = com.areslib.util.RobotClock.currentTimeMillis()
                }
            } catch (_: Exception) {}
            
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }.apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
        name = "ARES-Asynchronous-IMU-Thread"
    }

    init {
        try {
            val orientation = RevHubOrientationOnRobot(
                RevHubOrientationOnRobot.LogoFacingDirection.UP,
                RevHubOrientationOnRobot.UsbFacingDirection.FORWARD
            )
            val parameters = IMU.Parameters(orientation)
            imu.initialize(parameters)
        } catch (_: Exception) {}

        try {
            val yawPitchRoll = imu.getRobotYawPitchRollAngles()
            val angularVel = imu.getRobotAngularVelocity(AngleUnit.RADIANS)
            synchronized(lock) {
                latestYaw = yawPitchRoll.getYaw(AngleUnit.RADIANS)
                latestPitch = yawPitchRoll.getPitch(AngleUnit.RADIANS)
                latestRoll = yawPitchRoll.getRoll(AngleUnit.RADIANS)
                latestYawVel = angularVel.getZRotationRate(AngleUnit.RADIANS).toDouble()
                latestTimestamp = com.areslib.util.RobotClock.currentTimeMillis()
            }
        } catch (_: Exception) {}

        HardwareRegistry.registerCloseable(this)
        imuThread.start()
    }

    /**
     * Updates IMU heading, pitch, roll, and yaw velocity inputs into pre-allocated [ImuInputs] target.
     *
     * @param inputs Pre-allocated [ImuInputs] object to update in-place.
     */
    override fun updateInputs(inputs: com.areslib.hardware.sensor.ImuInputs) {
        synchronized(lock) {
            inputs.headingRadians = latestYaw - headingOffset
            inputs.pitchRadians = latestPitch
            inputs.rollRadians = latestRoll
            inputs.yawVelocityRadPerSec = latestYawVel
            inputs.timestampMs = latestTimestamp
        }
    }

    /**
     * Resets IMU zero heading offset reference to current physical yaw position.
     */
    override fun resetHeading() {
        synchronized(lock) {
            headingOffset = latestYaw
        }
    }

    /**
     * Terminates background thread execution and releases IMU hardware handles.
     */
    override fun close() {
        running = false
        imuThread.interrupt()
        joinWorker(imuThread)
    }
}

/**
 * Asynchronous hardware IO controller for generic analog voltage sensors plugged into REV Hub analog ports.
 *
 * Runs a 50Hz background sampling thread (`ARES-AnalogSensor-Thread`) to cache voltage readings.
 *
 * @param analogInput FTC SDK [AnalogInput] hardware interface.
 */
class RevAnalogSensorController(private val analogInput: AnalogInput) : AutoCloseable {
    private val lock = Any()
    @Volatile private var running = true
    private var latestVoltage = 0.0

    private val thread = Thread {
        while (running) {
            try {
                val volt = analogInput.voltage
                synchronized(lock) {
                    latestVoltage = volt
                }
            } catch (_: Exception) {}
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "ARES-AnalogSensor-Thread"
    }

    init {
        HardwareRegistry.registerCloseable(this)
        thread.start()
    }

    /**
     * Reads thread-safe cached analog voltage reading in Volts ($V$).
     *
     * @return Instantaneous analog signal level $[0.0, 3.3] \text{ V}$.
     */
    fun getVoltage(): Double {
        return synchronized(lock) { latestVoltage }
    }

    /**
     * Terminates background thread execution and unregisters sensor.
     */
    override fun close() {
        running = false
        thread.interrupt()
        joinWorker(thread)
    }
}

/**
 * Asynchronous hardware IO controller for digital sensors (e.g. limit switches, optical beam breaks) connected to REV Hub digital ports.
 *
 * Runs a 50Hz background sampling thread (`ARES-DigitalSensor-Thread`) to cache logic states.
 *
 * @param digitalChannel FTC SDK [DigitalChannel] hardware interface.
 */
class RevDigitalSensorController(private val digitalChannel: DigitalChannel) : AutoCloseable {
    private val lock = Any()
    @Volatile private var running = true
    private var latestState = false

    private val thread = Thread {
        while (running) {
            try {
                val state = digitalChannel.state
                synchronized(lock) {
                    latestState = state
                }
            } catch (_: Exception) {}
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "ARES-DigitalSensor-Thread"
    }

    init {
        try {
            digitalChannel.mode = DigitalChannel.Mode.INPUT
        } catch (_: Exception) {}

        HardwareRegistry.registerCloseable(this)
        thread.start()
    }

    /**
     * Reads thread-safe cached digital pin state (`true` for HIGH, `false` for LOW).
     *
     * @return Active digital pin state.
     */
    fun getState(): Boolean {
        return synchronized(lock) { latestState }
    }

    /**
     * Terminates background thread execution and unregisters digital channel.
     */
    override fun close() {
        running = false
        thread.interrupt()
        joinWorker(thread)
    }
}

/**
 * Asynchronous hardware IO controller for absolute analog encoders (e.g. REV Through Bore Encoder, Axon absolute encoder).
 *
 * Runs a 200Hz background sampling thread (`ARES-AnalogEncoder-Thread`) that converts $0.0\text{V} \dots 3.3\text{V}$ signals
 * into scaled encoder position ticks without main-thread latency.
 *
 * @param analogInput FTC SDK [AnalogInput] hardware interface.
 * @param version REV encoder revision variant ([com.areslib.hardware.actuator.RevEncoderVersion.V1] vs [com.areslib.hardware.actuator.RevEncoderVersion.V2]).
 * @param ticksPerRev Encoder tick resolution rating ($ticks/rev$, default 8192.0).
 * @param name Optional hardware configuration name for registry binding.
 *
 * @see MotorIO
 */
class RevAbsoluteAnalogEncoderController @kotlin.jvm.JvmOverloads constructor(
    private val analogInput: AnalogInput,
    private val version: com.areslib.hardware.actuator.RevEncoderVersion = com.areslib.hardware.actuator.RevEncoderVersion.V1,
    private val ticksPerRev: Double = 8192.0,
    val name: String? = null
) : MotorIO, AutoCloseable {
    private var offset = 0.0
    private var cachedPosition = 0.0
    private val lock = Any()
    @Volatile private var running = true
    private var latestVoltage = 0.0

    private val thread = Thread {
        while (running) {
            try {
                val volt = analogInput.voltage
                synchronized(lock) {
                    latestVoltage = volt
                }
            } catch (_: Exception) {}
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "ARES-AnalogEncoder-Thread-${name ?: "unnamed"}"
    }

    init {
        if (name != null) {
            HardwareRegistry.registerMotor(name, this)
        }
        HardwareRegistry.registerCloseable(this)
        thread.start()
    }

    override var power: Double
        get() = 0.0
        @Suppress("UNUSED_PARAMETER")
        set(value) {}

    /**
     * Calculates scaled position ticks from latest analog voltage reading.
     */
    fun updateInputs() {
        try {
            val volt = synchronized(lock) { latestVoltage }
            val normalized = volt / version.maxVoltage
            cachedPosition = (normalized * ticksPerRev) - offset
        } catch (_: Exception) {}
    }

    override val velocity: Double
        get() = 0.0

    override val position: Double
        get() = cachedPosition

    /**
     * Calibrates zero position offset reference to current analog voltage position.
     */
    override fun resetEncoder() {
        try {
            val volt = synchronized(lock) { latestVoltage }
            offset = (volt / version.maxVoltage) * ticksPerRev
            cachedPosition = 0.0
        } catch (_: Exception) {}
    }

    /**
     * Terminates background thread execution and unregisters encoder.
     */
    override fun close() {
        running = false
        thread.interrupt()
        joinWorker(thread)
    }
}

private fun joinWorker(thread: Thread) {
    if (Thread.currentThread() === thread) return
    try {
        thread.join(100L)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
