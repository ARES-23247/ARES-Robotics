package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice
import com.qualcomm.robotcore.hardware.I2cDeviceSynch
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType

@I2cDeviceType
@DeviceProperties(
    name = "SRS Hub",
    xmlTag = "SrsHub",
    description = "SRS Robotics Expansion Hub over I2C"
)
/**
 * Low-level I2C hardware driver for SRS Robotics Expansion Hub expansion boards.
 *
 * Implements a 200Hz background polling loop (`ARES-SrsHub-Thread`) executing 256-byte continuous block I2C reads.
 * Automatically parses analog voltages ($V$), digital states, motor encoder ticks, PWM pulse widths ($\mu s$),
 * APDS9151 / VL53L0X distance sensors, VL53L5CX multizone arrays, and GoBilda Pinpoint odometry data into pre-allocated cache buffers.
 *
 * ### Register Memory Map & Physical Units:
 * - Registers `0x00..0x07`: Analog input voltages ($V$), scaled from $0.0\text{V} \dots 3.3\text{V}$.
 * - Register `0x08`: Digital input logic pin bitmask.
 * - Registers `0x09..0x18`: 32-bit signed motor encoder count registers.
 * - Registers `0x18..0x1F`: 16-bit PWM pulse width channels ($\mu s$).
 * - Registers `0x20..0x5F`: I2C sub-device color ($RGB\alpha$) and proximity distance ($m$) channels.
 * - Registers `0x60..0xBF`: GoBilda Pinpoint odometry $X, Y$ ($mm$), heading ($\mu rad$), velocities ($mm/s, \mu rad/s$).
 * - Registers `0xC0..0xFF`: VL53L5CX 64-zone distance matrix ($mm$).
 *
 * [pollHub] parses each SDK-owned I2C read into fixed-size primitive caches. Consumer accessors read
 * those caches and never perform hardware I/O, keeping control-loop work bounded and deterministic.
 *
 * @param deviceClient Underlying Qualcomm FTC SDK [I2cDeviceSynch] bus client.
 *
 * @see I2cDeviceSynchDevice
 */
class SrsHubDriver(deviceClient: I2cDeviceSynch) : I2cDeviceSynchDevice<I2cDeviceSynch>(deviceClient, true), AutoCloseable {
    // Structured bulk cache buffers
    private val cachedAnalog = DoubleArray(4)
    private val cachedDigital = BooleanArray(4)
    private val cachedEncoders = IntArray(4)
    private val cachedPwmPulseWidths = IntArray(4)
    
    // I2C Sub-sensors cached data
    private val cachedVL53L5CX = Array(4) { IntArray(64) }
    
    // APDS9151 and VL53L0X cache (ports 0-3)
    private val cachedColorsRed = IntArray(4)
    private val cachedColorsGreen = IntArray(4)
    private val cachedColorsBlue = IntArray(4)
    private val cachedColorsAlpha = IntArray(4)
    private val cachedI2cDistances = DoubleArray(4)
    
    // Pinpoint Odometry cache (ports 0-3)
    private val cachedOdoX = DoubleArray(4)
    private val cachedOdoY = DoubleArray(4)
    private val cachedOdoHeading = DoubleArray(4)
    private val cachedOdoVelX = DoubleArray(4)
    private val cachedOdoVelY = DoubleArray(4)
    private val cachedOdoHeadingVel = DoubleArray(4)

    private val activePinpoints = BooleanArray(4)
    private val pendingServoPositions = DoubleArray(4) { Double.NaN }
    private val pingBuffers = Array(4) { byteArrayOf(1) }
    private val servoWriteBuffers = Array(4) { ByteArray(2) }

    private val lock = Any()
    @Volatile private var running = true
    @Volatile private var isInitialized = false
    @Volatile
    var lastSuccessfulPollTimestampMs: Long = 0L
        private set

    private val thread = Thread {
        while (running) {
            if (isInitialized) {
                // 1. Process pending servo writes
                synchronized(lock) {
                    for (port in 0 until 4) {
                        val pos = pendingServoPositions[port]
                        if (!pos.isNaN()) {
                            try {
                                val raw = (pos.coerceIn(0.0, 1.0) * 65535.0).toInt()
                                val buffer = servoWriteBuffers[port]
                                buffer[0] = (raw and 0xFF).toByte()
                                buffer[1] = ((raw shr 8) and 0xFF).toByte()
                                deviceClient.write(16 + port * 2, buffer)
                                pendingServoPositions[port] = Double.NaN
                            } catch (_: Exception) {}
                        }
                    }
                }

                // 2. Trigger updates for active pinpoint sensors
                synchronized(lock) {
                    for (port in 0 until 4) {
                        if (activePinpoints[port]) {
                            try {
                                deviceClient.write(124 + port, pingBuffers[port])
                            } catch (_: Exception) {}
                        }
                    }
                }

                // 3. Poll the hub registers
                pollHub()
            }
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }.apply {
        isDaemon = true
        name = "ARES-SrsHub-Thread"
    }

    /** Registers a GoBilda Pinpoint odometry computer on port $[0 \dots 3]$ to trigger active update pings. */
    fun registerPinpoint(port: Int) {
        synchronized(lock) {
            if (port in 0 until 4) {
                activePinpoints[port] = true
            }
        }
    }

    init {
        thread.start()
    }

    /** Initializes SRS Hub 256-byte repeated read window. */
    override fun doInitialize(): Boolean {
        return try {
            // Set up automatic repeated read window for the entire 256-byte register range
            deviceClient.readWindow = I2cDeviceSynch.ReadWindow(0, 256, I2cDeviceSynch.ReadMode.REPEAT)
            isInitialized = true
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Returns hardware manufacturer tag ([Manufacturer.Other]). */
    override fun getManufacturer(): Manufacturer = Manufacturer.Other

    /** Returns human-readable device identifier string. */
    override fun getDeviceName(): String = "SRS Hub"

    private fun pollHub() {
        try {
            val data = deviceClient.read(0, 256)
            if (data.size < 256) return // Safeguard against incomplete read

            synchronized(lock) {
                // 1. Parse Analog Input Voltages (Registers 0-7, 2 bytes per port)
                for (i in 0 until 4) {
                    val raw = (data[i * 2].toInt() and 0xFF) or ((data[i * 2 + 1].toInt() and 0xFF) shl 8)
                    cachedAnalog[i] = (raw / 65535.0) * 3.3
                }

                // 2. Parse Digital States (Register 8)
                val digitalByte = data[8].toInt()
                for (i in 0 until 4) {
                    cachedDigital[i] = (digitalByte and (1 shl i)) != 0
                }

                // 3. Parse Motor Encoders (Registers 9-24, 4 bytes per port)
                for (i in 0 until 4) {
                    val offset = 9 + i * 4
                    cachedEncoders[i] = readInt32(data, offset)
                }

                // 3b. Parse PWM Pulse Widths (Registers 24-31, 2 bytes per port)
                for (i in 0 until 4) {
                    val offset = 24 + i * 2
                    cachedPwmPulseWidths[i] = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                }

                // 4. Parse APDS9151 and VL53L0X I2C Sub-devices (Registers 32-95, 16 bytes per port)
                for (port in 0 until 4) {
                    val base = 32 + port * 16
                    cachedColorsRed[port] = (data[base].toInt() and 0xFF) or ((data[base + 1].toInt() and 0xFF) shl 8)
                    cachedColorsGreen[port] = (data[base + 2].toInt() and 0xFF) or ((data[base + 3].toInt() and 0xFF) shl 8)
                    cachedColorsBlue[port] = (data[base + 4].toInt() and 0xFF) or ((data[base + 5].toInt() and 0xFF) shl 8)
                    cachedColorsAlpha[port] = (data[base + 6].toInt() and 0xFF) or ((data[base + 7].toInt() and 0xFF) shl 8)
                    
                    val rawDistMm = (data[base + 8].toInt() and 0xFF) or ((data[base + 9].toInt() and 0xFF) shl 8)
                    cachedI2cDistances[port] = rawDistMm / 1000.0 // mm to meters
                }

                // 5. Parse Pinpoint Odometry data (Registers 96-191, 24 bytes per port)
                for (port in 0 until 4) {
                    val base = 96 + port * 24
                    cachedOdoX[port] = readInt32(data, base).toDouble()
                    cachedOdoY[port] = readInt32(data, base + 4).toDouble()
                    
                    val rawHeading = readInt32(data, base + 8)
                    cachedOdoHeading[port] = rawHeading / 1e6 // microradians to radians
                    
                    cachedOdoVelX[port] = readInt32(data, base + 12).toDouble()
                    cachedOdoVelY[port] = readInt32(data, base + 16).toDouble()
                    
                    val rawHeadingVel = readInt32(data, base + 20)
                    cachedOdoHeadingVel[port] = rawHeadingVel / 1e6
                }

                // 6. Parse VL53L5CX Multizone distance data (Registers 192-255, 64 zones of 2-byte values mapped dynamically)
                val vl53Base = 192
                for (i in 0 until 32) { // Retrieve standard 32 zones in single bulk block
                    val offset = vl53Base + (i * 2)
                    if (offset + 1 < data.size) {
                        val mm = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
                        cachedVL53L5CX[0][i] = mm
                    }
                }
                lastSuccessfulPollTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
            }
        } catch (_: Exception) {
            // Swallow I2C read exceptions gracefully
        }
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    // Accessors
    /** Reads cached analog input voltage ($V$) for port $[0 \dots 3]$. */
    fun getAnalogVoltage(port: Int): Double = synchronized(lock) { cachedAnalog.getOrElse(port) { 0.0 } }

    /** Reads cached digital pin logic state for port $[0 \dots 3]$. */
    fun getDigitalState(port: Int): Boolean = synchronized(lock) { cachedDigital.getOrElse(port) { false } }

    /** Reads cached motor encoder count for port $[0 \dots 3]$. */
    fun readEncoder(port: Int): Int = synchronized(lock) { cachedEncoders.getOrElse(port) { 0 } }

    /** Reads cached PWM pulse width ($\mu s$) for port $[0 \dots 3]$. */
    fun getPwmPulseWidth(port: Int): Int = synchronized(lock) { cachedPwmPulseWidths.getOrElse(port) { 0 } }

    /** Returns a caller-owned snapshot of cached VL53L5CX zone distances in millimeters. */
    fun getVL53L5CXDistances(port: Int): IntArray = synchronized(lock) {
        if (port !in cachedVL53L5CX.indices) IntArray(0) else cachedVL53L5CX[port].copyOf()
    }

    /** Copies cached VL53L5CX millimeter readings into caller-owned storage. */
    fun copyVL53L5CXDistances(port: Int, destination: IntArray): Int = synchronized(lock) {
        if (port !in cachedVL53L5CX.indices) return@synchronized 0
        val source = cachedVL53L5CX[port]
        val count = minOf(source.size, destination.size)
        source.copyInto(destination, endIndex = count)
        count
    }

    /** Reads cached red color channel for port $[0 \dots 3]$. */
    fun getI2cColorRed(port: Int): Int = synchronized(lock) { cachedColorsRed.getOrElse(port) { 0 } }

    /** Reads cached green color channel for port $[0 \dots 3]$. */
    fun getI2cColorGreen(port: Int): Int = synchronized(lock) { cachedColorsGreen.getOrElse(port) { 0 } }

    /** Reads cached blue color channel for port $[0 \dots 3]$. */
    fun getI2cColorBlue(port: Int): Int = synchronized(lock) { cachedColorsBlue.getOrElse(port) { 0 } }

    /** Reads cached alpha color channel for port $[0 \dots 3]$. */
    fun getI2cColorAlpha(port: Int): Int = synchronized(lock) { cachedColorsAlpha.getOrElse(port) { 0 } }

    /** Reads cached I2C proximity distance in meters ($m$) for port $[0 \dots 3]$. */
    fun getI2cDistanceMeters(port: Int): Double = synchronized(lock) { cachedI2cDistances.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry X position ($mm$) for port $[0 \dots 3]$. */
    fun getI2cOdometryX(port: Int): Double = synchronized(lock) { cachedOdoX.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry Y position ($mm$) for port $[0 \dots 3]$. */
    fun getI2cOdometryY(port: Int): Double = synchronized(lock) { cachedOdoY.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry heading ($rad$, CCW+) for port $[0 \dots 3]$. */
    fun getI2cOdometryHeading(port: Int): Double = synchronized(lock) { cachedOdoHeading.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry X velocity ($mm/s$) for port $[0 \dots 3]$. */
    fun getI2cOdometryVelX(port: Int): Double = synchronized(lock) { cachedOdoVelX.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry Y velocity ($mm/s$) for port $[0 \dots 3]$. */
    fun getI2cOdometryYVel(port: Int): Double = synchronized(lock) { cachedOdoVelY.getOrElse(port) { 0.0 } }

    /** Reads cached Pinpoint odometry heading velocity ($rad/s$) for port $[0 \dots 3]$. */
    fun getI2cOdometryHeadingVel(port: Int): Double = synchronized(lock) { cachedOdoHeadingVel.getOrElse(port) { 0.0 } }

    /** Enqueues PWM duty cycle write command $[0.0, 1.0]$ for port $[0 \dots 3]$. */
    fun setPwmDutyCycle(port: Int, dutyCycle: Double) {
        synchronized(lock) {
            if (port in 0 until 4) {
                pendingServoPositions[port] = dutyCycle
            }
        }
    }

    /** Sends a reset bit command to Pinpoint odometry computer on port $[0 \dots 3]$. */
    fun resetI2cOdometry(port: Int) {
        try {
            deviceClient.write(120 + port, byteArrayOf(1))
        } catch (_: Exception) {}
    }

    /** Terminates background polling thread and releases device client handle. */
    override fun close() {
        running = false
        thread.interrupt()
        if (Thread.currentThread() !== thread) {
            try {
                thread.join(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        deviceClient.close()
    }
}
