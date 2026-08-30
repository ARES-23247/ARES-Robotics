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
import com.areslib.math.wrapAngle

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
        
        /** Multiplier scaling raw 16-bit localizer heading readings to radians ($rad$). */
        const val SCALAR_LOCALIZER_HEADING = 0.001f
        /** Multiplier scaling raw 16-bit localizer heading rate to radians per second ($rad/s$). */
        const val SCALAR_LOCALIZER_HEADING_VELOCITY = 0.001f
    }

    @Volatile private var isInitialized = false
    @Volatile
    var lastLocalizerUpdateTimestampMs: Long = 0L
        private set
 
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
    @Volatile private var running = true
    private val pollingThread: Thread

    init {
        pollingThread = Thread {
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
                            lastLocalizerUpdateTimestampMs = com.areslib.util.RobotClock.currentTimeMillis()
                        }
                    }
                }
                try { Thread.sleep(5) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        pollingThread.isDaemon = true
        pollingThread.name = "ARES-Octoquad-Thread"
        pollingThread.start()
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

    /** Returns thread-safe cached encoder position for channel $[0 \dots 7]$. */
    fun getCachedPosition(channel: Int): Int = synchronized(lock) { cachedPositions.getOrElse(channel) { 0 } }

    /** Returns thread-safe cached encoder velocity ($counts/s$) for channel $[0 \dots 7]$. */
    fun getCachedVelocity(channel: Int): Int = synchronized(lock) { cachedVelocities.getOrElse(channel) { 0 } }

    /** Returns thread-safe cached PWM pulse width ($\mu s$) for channel $[0 \dots 7]$. */
    fun getCachedPulseWidth(channel: Int): Int = synchronized(lock) { cachedPulseWidths.getOrElse(channel) { 0 } }

    /** Sends a command byte resetting the specified encoder channel position counter $[0 \dots 7]$ to zero. */
    fun resetEncoder(channel: Int) {
        try {
            val cmdBytes = byteArrayOf(0x01, channel.toByte())
            deviceClient.write(0x10, cmdBytes) // 0x10 is COMMAND register
        } catch (_: Exception) {}
    }

    /**
     * Mutable reusable data block containing raw localizer telemetry outputs.
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

    /** Returns an owned snapshot of the current localizer block. Prefer [copyLocalizerDataInto] in hot paths. */
    fun readLocalizerData(): LocalizerDataBlock = synchronized(lock) { cachedLocalizerData.copy() }

    /** Copies the current localizer block into caller-owned [destination] without allocating. */
    fun copyLocalizerDataInto(destination: LocalizerDataBlock) = synchronized(lock) {
        destination.posX_mm = cachedLocalizerData.posX_mm
        destination.posY_mm = cachedLocalizerData.posY_mm
        destination.heading_rad = cachedLocalizerData.heading_rad
        destination.velX_mmS = cachedLocalizerData.velX_mmS
        destination.velY_mmS = cachedLocalizerData.velY_mmS
        destination.velHeading_radS = cachedLocalizerData.velHeading_radS
    }

    /** Terminates background sampling thread and releases device client handles. */
    override fun close() {
        running = false
        pollingThread.interrupt()
        if (Thread.currentThread() !== pollingThread) {
            try {
                pollingThread.join(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        deviceClient.close()
    }
}


/**
 * Wrapper for an individual encoder plugged into the OctoQuad.
 */
class OctoQuadEncoderIO(private val octoQuad: OctoQuadFWv3, private val channel: Int) : MotorIO {
    init {
        require(channel in 0..7) { "OctoQuad encoder channel must be in 0..7" }
    }
    override var power: Double
        get() = 0.0 // Encoders are read-only
        set(@Suppress("UNUSED_PARAMETER") value) {
            // Encoders are read-only, cannot set power
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

    init {
        require(channel in 0..7) { "OctoQuad PWM channel must be in 0..7" }
        require(ticksPerRev.isFinite() && ticksPerRev > 0.0) { "ticksPerRev must be finite and positive" }
    }

    override var power: Double
        get() = 0.0
        set(@Suppress("UNUSED_PARAMETER") value) {}

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
    private val rawData = OctoQuadFWv3.LocalizerDataBlock()
    private val frameTransform = OctoQuadLocalizerFrameTransform()
    private var requestedStartPose = Pose2d()
    private var frameInitialized = false

    /**
     * Seeds a software rigid transform from the localizer's current native frame to [startPose].
     * The OctoQuad FWv3 register surface does not expose a field-pose write, so no unrelated encoder
     * channel is reset. Subsequent positions and velocities are rotated into the seeded field frame.
     */
    override fun initialize(startPose: Pose2d) {
        require(startPose.x.isFinite() && startPose.y.isFinite() && startPose.heading.radians.isFinite()) {
            "OctoQuad start pose must be finite"
        }
        requestedStartPose = startPose
        frameInitialized = false
        initializeFrameFromCacheIfFresh()
    }

    /** Copies the latest cached localizer block into meter/radian odometry fields. */
    override fun updateInputs(inputs: com.areslib.hardware.drive.OdometryInputs) {
        val sampleTimestampMs = octoQuad.lastLocalizerUpdateTimestampMs
        if (sampleTimestampMs <= 0L) {
            inputs.posX = requestedStartPose.x
            inputs.posY = requestedStartPose.y
            inputs.heading = requestedStartPose.heading.radians
            inputs.velX = 0.0
            inputs.velY = 0.0
            inputs.headingVelocity = 0.0
            inputs.timestampMs = 0L
            return
        }
        octoQuad.copyLocalizerDataInto(rawData)
        initializeFrameFromCacheIfFresh()
        frameTransform.apply(rawData, inputs)
        inputs.timestampMs = sampleTimestampMs
    }

    private fun initializeFrameFromCacheIfFresh() {
        if (frameInitialized || octoQuad.lastLocalizerUpdateTimestampMs <= 0L) return
        octoQuad.copyLocalizerDataInto(rawData)
        frameTransform.initialize(requestedStartPose, rawData)
        frameInitialized = true
    }
}

/** Allocation-free rigid transform from the OctoQuad native localizer frame to the field frame. */
internal class OctoQuadLocalizerFrameTransform {
    private var rawOriginXMeters = 0.0
    private var rawOriginYMeters = 0.0
    private var fieldOriginXMeters = 0.0
    private var fieldOriginYMeters = 0.0
    private var headingOffsetRadians = 0.0
    private var offsetCos = 1.0
    private var offsetSin = 0.0

    fun initialize(startPose: Pose2d, raw: OctoQuadFWv3.LocalizerDataBlock) {
        require(startPose.x.isFinite() && startPose.y.isFinite() && startPose.heading.radians.isFinite()) {
            "OctoQuad start pose must be finite"
        }
        rawOriginXMeters = raw.posX_mm / 1000.0
        rawOriginYMeters = raw.posY_mm / 1000.0
        fieldOriginXMeters = startPose.x
        fieldOriginYMeters = startPose.y
        headingOffsetRadians = wrapAngle(startPose.heading.radians - raw.heading_rad.toDouble())
        offsetCos = kotlin.math.cos(headingOffsetRadians)
        offsetSin = kotlin.math.sin(headingOffsetRadians)
    }

    fun apply(raw: OctoQuadFWv3.LocalizerDataBlock, inputs: com.areslib.hardware.drive.OdometryInputs) {
        val deltaX = raw.posX_mm / 1000.0 - rawOriginXMeters
        val deltaY = raw.posY_mm / 1000.0 - rawOriginYMeters
        inputs.posX = fieldOriginXMeters + offsetCos * deltaX - offsetSin * deltaY
        inputs.posY = fieldOriginYMeters + offsetSin * deltaX + offsetCos * deltaY
        inputs.heading = wrapAngle(raw.heading_rad.toDouble() + headingOffsetRadians)

        val rawVelocityX = raw.velX_mmS / 1000.0
        val rawVelocityY = raw.velY_mmS / 1000.0
        inputs.velX = offsetCos * rawVelocityX - offsetSin * rawVelocityY
        inputs.velY = offsetSin * rawVelocityX + offsetCos * rawVelocityY
        inputs.headingVelocity = raw.velHeading_radS.toDouble()
    }
}
