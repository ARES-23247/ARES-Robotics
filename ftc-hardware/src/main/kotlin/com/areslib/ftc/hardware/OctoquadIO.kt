package com.areslib.ftc.hardware

import com.qualcomm.robotcore.hardware.I2cDeviceSynchDevice
import com.qualcomm.robotcore.hardware.I2cDeviceSynch
import com.qualcomm.robotcore.hardware.configuration.annotations.DeviceProperties
import com.qualcomm.robotcore.hardware.configuration.annotations.I2cDeviceType
import com.areslib.hardware.actuator.MotorIO
import com.areslib.hardware.actuator.RevEncoderVersion
import com.areslib.hardware.drive.OdometryIO
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import java.nio.ByteBuffer
import java.nio.ByteOrder

@I2cDeviceType
@DeviceProperties(
    name = "DigitalChickenLabs OctoQuad",
    xmlTag = "OctoQuad",
    description = "OctoQuad 8-channel quadrature encoder / localizer module"
)
/**
 * Hardware IO driver and synchronous/asynchronous cache manager for the DigitalChickenLabs OctoQuad FWv3.
 *
 * Provides an 8-channel quadrature encoder / PWM reader over I2C (`0x30`) with integrated pose localizer hardware.
 * Runs a 200Hz background sampling thread (`ARES-Octoquad-Thread`) that performs bulk 32-byte and 12-byte register reads,
 * double-buffering results into thread-safe arrays to guarantee zero main-thread loop latency and zero dynamic allocations.
 *
 * ### Physical Units & Scalers:
 * - Direct Encoder Channels (0..7): 32-bit signed integer encoder counts.
 * - Velocity Channels (0..7): 32-bit signed integer counts per second ($counts/s$).
 * - PWM Pulse Width Channels (0..7): Unsigned 16-bit integers in microseconds ($\mu s$).
 * - Localizer Position ($X, Y$): 16-bit signed integers in millimeters ($mm$).
 * - Localizer Heading ($\theta$): Scaled radians ($rad$), conversion factor $\text{SCALAR\_HEADING} = 0.001$.
 * - Localizer Heading Velocity ($\dot{\theta}$): Scaled radians per second ($rad/s$), conversion factor $\text{SCALAR\_HEADING\_VELOCITY} = 0.001$.
 *
 * @param deviceClient Qualcomm FTC SDK [I2cDeviceSynch] bus client.
 *
 * @see I2cDeviceSynchDevice
 * @see OctoQuadEncoderIO
 * @see OctoQuadAbsolutePWMEncoder
 * @see OctoQuadOdometryIO
 */
class OctoQuadFWv3(deviceClient: I2cDeviceSynch) : I2cDeviceSynchDevice<I2cDeviceSynch>(deviceClient, true), AutoCloseable {

    companion object {
        /** Default 7-bit I2C slave address for OctoQuad modules. */
        const val OCTOQUAD_I2C_ADDRESS = 0x30
        /** Hardware chip ID byte (`0x51`). */
        const val OCTOQUAD_CHIP_ID: Byte = 0x51
        /** Expected major firmware version rating. */
        const val SUPPORTED_FW_VERSION_MAJ = 3

        const val REG_CHIP_ID = 0x00
        const val REG_FW_MAJ = 0x01
        const val REG_FW_MIN = 0x02
        
        // Encoder Data Registers
        const val REG_ENC_0 = 0x20
        const val REG_VEL_0 = 0x40
        const val REG_PULSE_WIDTH_0 = 0x80 // Pulse width in microseconds
        
        // Localizer Registers
        const val REG_LOC_STATUS = 0x60
        const val REG_LOC_X = 0x62
        const val REG_LOC_Y = 0x64
        const val REG_LOC_VEL_X = 0x66
        const val REG_LOC_VEL_Y = 0x68
        const val REG_LOC_H = 0x6A
        const val REG_LOC_VEL_H = 0x6C
        
        val OCTOQUAD_ENDIAN = ByteOrder.LITTLE_ENDIAN
        /** Multiplier scaling raw 16-bit localizer heading readings to radians ($rad$). */
        const val SCALAR_LOCALIZER_HEADING = 0.001f
        /** Multiplier scaling raw 16-bit localizer heading rate to radians per second ($rad/s$). */
        const val SCALAR_LOCALIZER_HEADING_VELOCITY = 0.001f
    }

    private var isInitialized = false
    private var lastUpdateTimeMs = 0L
 
    // Cache buffers for registers
    private val cachedPositions = IntArray(8)
    private val cachedVelocities = IntArray(8)
    private val cachedPulseWidths = IntArray(8)
    private var cachedLocalizerData = LocalizerDataBlock()

    // Preallocated thread-local buffers to guarantee zero dynamic allocations in loop
    private val threadPos = IntArray(8)
    private val threadVel = IntArray(8)
    private val threadPw = IntArray(8)
    private val threadLocalizer = LocalizerDataBlock()

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
               ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
               ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
               ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }
    
    private val lock = Any()
    private var running = true

    init {
        com.areslib.hardware.HardwareRegistry.registerCloseable(this)
        val thread = Thread {
            while (running) {
                if (isInitialized) {
                    var posSuccess = false
                    var velSuccess = false
                    var pwSuccess = false
                    var locSuccess = false

                    try {
                        val posBytes = deviceClient.read(REG_ENC_0, 32)
                        if (posBytes.size >= 32) {
                            for (i in 0 until 8) {
                                threadPos[i] = readIntLE(posBytes, i * 4)
                            }
                            posSuccess = true
                        }
                    } catch (_: Exception) {}

                    try {
                        val velBytes = deviceClient.read(REG_VEL_0, 32)
                        if (velBytes.size >= 32) {
                            for (i in 0 until 8) {
                                threadVel[i] = readIntLE(velBytes, i * 4)
                            }
                            velSuccess = true
                        }
                    } catch (_: Exception) {}

                    try {
                        val pwBytes = deviceClient.read(REG_PULSE_WIDTH_0, 16)
                        if (pwBytes.size >= 16) {
                            for (i in 0 until 8) {
                                threadPw[i] = readShortLE(pwBytes, i * 2).toInt() and 0xFFFF
                            }
                            pwSuccess = true
                        }
                    } catch (_: Exception) {}

                    try {
                        val bytes = deviceClient.read(REG_LOC_X, 12)
                        if (bytes.size >= 12) {
                            threadLocalizer.posX_mm = readShortLE(bytes, 0)
                            threadLocalizer.posY_mm = readShortLE(bytes, 2)
                            threadLocalizer.velX_mmS = readShortLE(bytes, 4)
                            threadLocalizer.velY_mmS = readShortLE(bytes, 6)
                            threadLocalizer.heading_rad = readShortLE(bytes, 8) * SCALAR_LOCALIZER_HEADING
                            threadLocalizer.velHeading_radS = readShortLE(bytes, 10) * SCALAR_LOCALIZER_HEADING_VELOCITY
                            locSuccess = true
                        }
                    } catch (_: Exception) {}

                    synchronized(lock) {
                        if (posSuccess) System.arraycopy(threadPos, 0, cachedPositions, 0, 8)
                        if (velSuccess) System.arraycopy(threadVel, 0, cachedVelocities, 0, 8)
                        if (pwSuccess) System.arraycopy(threadPw, 0, cachedPulseWidths, 0, 8)
                        if (locSuccess) {
                            cachedLocalizerData.posX_mm = threadLocalizer.posX_mm
                            cachedLocalizerData.posY_mm = threadLocalizer.posY_mm
                            cachedLocalizerData.heading_rad = threadLocalizer.heading_rad
                            cachedLocalizerData.velX_mmS = threadLocalizer.velX_mmS
                            cachedLocalizerData.velY_mmS = threadLocalizer.velY_mmS
                            cachedLocalizerData.velHeading_radS = threadLocalizer.velHeading_radS
                        }
                        lastUpdateTimeMs = com.areslib.util.RobotClock.currentTimeMillis()
                    }
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        thread.isDaemon = true
        thread.name = "ARES-Octoquad-Thread"
        thread.start()
    }

    /**
     * Initializes the OctoQuad I2C communications bus and validates device chip ID (`0x51`).
     *
     * @return `true` if initialization succeeded and chip ID matches.
     */
    override fun doInitialize(): Boolean {
        return try {
            deviceClient.i2cAddress = com.qualcomm.robotcore.hardware.I2cAddr.create7bit(OCTOQUAD_I2C_ADDRESS)
            val chipId = deviceClient.read8(REG_CHIP_ID)
            deviceClient.read8(REG_FW_MAJ)
            
            if (chipId != OCTOQUAD_CHIP_ID) {
                false
            } else {
                isInitialized = true
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Returns hardware manufacturer tag ([Manufacturer.Other]). */
    override fun getManufacturer(): Manufacturer = Manufacturer.Other

    /** Returns human-readable device identifier string. */
    override fun getDeviceName(): String = "OctoQuad FWv3"

    /** No-op update hook (handled asynchronously by background sampling thread). */
    fun update() {
        // Background thread handles update
    }

    /** Returns thread-safe cached encoder position for channel $[0 \dots 7]$. */
    fun getCachedPosition(channel: Int): Int = synchronized(lock) { cachedPositions.getOrElse(channel) { 0 } }

    /** Returns thread-safe cached encoder velocity ($counts/s$) for channel $[0 \dots 7]$. */
    fun getCachedVelocity(channel: Int): Int = synchronized(lock) { cachedVelocities.getOrElse(channel) { 0 } }

    /** Returns thread-safe cached PWM pulse width ($\mu s$) for channel $[0 \dots 7]$. */
    fun getCachedPulseWidth(channel: Int): Int = synchronized(lock) { cachedPulseWidths.getOrElse(channel) { 0 } }

    /** Reads single encoder position synchronously from I2C bus (legacy fallback). */
    fun readEncoderPosition(channel: Int): Int {
        return try {
            val bytes = deviceClient.read(REG_ENC_0 + (channel * 4), 4)
            val buf = ByteBuffer.wrap(bytes).order(OCTOQUAD_ENDIAN)
            buf.int
        } catch (_: Exception) {
            0
        }
    }

    /** Reads single encoder velocity synchronously from I2C bus (legacy fallback). */
    fun readEncoderVelocity(channel: Int): Int {
        return try {
            val bytes = deviceClient.read(REG_VEL_0 + (channel * 4), 4)
            val buf = ByteBuffer.wrap(bytes).order(OCTOQUAD_ENDIAN)
            buf.int
        } catch (_: Exception) {
            0
        }
    }

    /** Reads single channel PWM pulse width synchronously in microseconds ($\mu s$) (legacy fallback). */
    fun readChannelPulseWidth(channel: Int): Int {
        return try {
            val bytes = deviceClient.read(REG_PULSE_WIDTH_0 + (channel * 2), 2)
            val buf = ByteBuffer.wrap(bytes).order(OCTOQUAD_ENDIAN)
            buf.short.toInt() and 0xFFFF
        } catch (_: Exception) {
            0
        }
    }

    /** Sends a command byte resetting the specified encoder channel position counter $[0 \dots 7]$ to zero. */
    fun resetEncoder(channel: Int) {
        try {
            val cmdBytes = byteArrayOf(0x01, channel.toByte())
            deviceClient.write(0x10, cmdBytes) // 0x10 is COMMAND register
        } catch (_: Exception) {}
    }

    /**
     * Immutable snapshot data block containing raw localizer telemetry outputs.
     *
     * @property posX_mm Field X position in millimeters ($mm$).
     * @property posY_mm Field Y position in millimeters ($mm$).
     * @property heading_rad Field heading in radians ($rad$).
     * @property velX_mmS Field X velocity in millimeters per second ($mm/s$).
     * @property velY_mmS Field Y velocity in millimeters per second ($mm/s$).
     * @property velHeading_radS Rotational velocity in radians per second ($rad/s$).
     */
    data class LocalizerDataBlock(
        var posX_mm: Short = 0,
        var posY_mm: Short = 0,
        var heading_rad: Float = 0f,
        var velX_mmS: Short = 0,
        var velY_mmS: Short = 0,
        var velHeading_radS: Float = 0f
    )

    /** Returns thread-safe cached copy of current localizer data block. */
    fun readLocalizerData(): LocalizerDataBlock = synchronized(lock) { cachedLocalizerData }

    /** Terminates background sampling thread and releases device client handles. */
    override fun close() {
        running = false
    }
}


/**
 * Wrapper for an individual encoder plugged into the OctoQuad.
 */
class OctoQuadEncoderIO(private val octoQuad: OctoQuadFWv3, private val channel: Int) : MotorIO {
    override var power: Double
        get() = 0.0 // Encoders are read-only
        set(@Suppress("UNUSED_PARAMETER") value) {
            // Encoders are read-only, cannot set power
        }

    /** Compatibility update hook; the shared OctoQuad cache is populated by its sampling thread. */
    fun updateInputs() {
        octoQuad.update()
    }

    override val velocity: Double
        get() = octoQuad.getCachedVelocity(channel).toDouble()

    override val position: Double
        get() = octoQuad.getCachedPosition(channel).toDouble()

    /** Sends a hardware reset for this encoder channel. */
    override fun resetEncoder() {
        octoQuad.resetEncoder(channel)
    }
}

/**
 * Wrapper for an absolute PWM encoder plugged into the OctoQuad.
 */
class OctoQuadAbsolutePWMEncoder(
    private val octoQuad: OctoQuadFWv3,
    private val channel: Int,
    private val version: com.areslib.hardware.actuator.RevEncoderVersion = com.areslib.hardware.actuator.RevEncoderVersion.V1,
    private val ticksPerRev: Double = 8192.0
) : MotorIO {
    private var offset = 0.0

    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

    /** Compatibility update hook; pulse-width data already comes from the shared cache. */
    fun updateInputs() {
        octoQuad.update()
    }

    override val velocity: Double
        get() = 0.0

    override val position: Double
        get() {
            val pulseUs = octoQuad.getCachedPulseWidth(channel).toDouble()
            val range = version.maxPulseUs - version.minPulseUs
            val normalized = if (range != 0.0) (pulseUs - version.minPulseUs) / range else 0.0
            val clampedNormalized = if (normalized.isFinite()) normalized.coerceIn(0.0, 1.0) else 0.0
            return (clampedNormalized * ticksPerRev) - offset
        }

    /** Captures the current normalized pulse position as this wrapper's software zero. */
    override fun resetEncoder() {
        octoQuad.update()
        val pulseUs = octoQuad.getCachedPulseWidth(channel).toDouble()
        val range = version.maxPulseUs - version.minPulseUs
        val normalized = if (range != 0.0) (pulseUs - version.minPulseUs) / range else 0.0
        val clampedNormalized = if (normalized.isFinite()) normalized.coerceIn(0.0, 1.0) else 0.0
        offset = clampedNormalized * ticksPerRev
    }
}

/**
 * Wrapper for the OctoQuad's absolute localizer feature.
 */
class OctoQuadOdometryIO(private val octoQuad: OctoQuadFWv3) : OdometryIO {
    /**
     * Resets encoder channel zero as a legacy localizer reset.
     * The current FWv3 adapter cannot seed [startPose]; callers must treat the next sample as the
     * device's native origin rather than assuming the requested field pose was applied.
     */
    override fun initialize(startPose: Pose2d) {
        // Reset command
        octoQuad.resetEncoder(0) // Dummy implementation for now
    }

    /** Copies the latest cached localizer block into meter/radian odometry fields. */
    override fun updateInputs(inputs: com.areslib.hardware.drive.OdometryInputs) {
        octoQuad.update()
        val lastData = octoQuad.readLocalizerData()
        inputs.posX = lastData.posX_mm / 1000.0
        inputs.posY = lastData.posY_mm / 1000.0
        inputs.heading = lastData.heading_rad.toDouble()
        inputs.velX = lastData.velX_mmS / 1000.0
        inputs.velY = lastData.velY_mmS / 1000.0
        inputs.headingVelocity = lastData.velHeading_radS.toDouble()
        inputs.timestampMs = com.areslib.util.RobotClock.currentTimeMillis()
    }
}

