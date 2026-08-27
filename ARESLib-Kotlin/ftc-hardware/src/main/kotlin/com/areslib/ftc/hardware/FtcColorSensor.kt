package com.areslib.ftc.hardware

import com.areslib.hardware.sensor.ColorSensorIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.ColorSensor
import com.qualcomm.robotcore.hardware.NormalizedColorSensor

/**
 * Asynchronous hardware IO wrapper for generic FTC Color Sensors (e.g. REV Color Sensor v2/v3, Adafruit TCS34725).
 *
 * Automatically detects whether the hardware implements [NormalizedColorSensor] to provide lighting-invariant,
 * normalized RGB color channels $[0.0, 1.0]$. Uses a dedicated 50Hz background thread (`ARES-GenericColorSensor-Thread`)
 * and double-buffered thread-safe memory reads to prevent main control loop blocking on slow I2C sensor reads.
 *
 * ### Physical Units & Range Boundaries:
 * - Raw Channels ($R, G, B, \alpha$): Unscaled 16-bit integer ADC counts $[0, 65535]$.
 * - Normalized Channels: Double-precision floating point ratios $[0.0, 1.0]$ where:
 *   $$R_{norm} = \frac{R}{R + G + B + \alpha}$$
 *
 * ### Zero-GC Execution Compliance:
 * Background sampling thread writes into pre-allocated double-buffers (`normalizedBuffers`, `threadBuffer`).
 * Properties access cached primitive values inside `synchronized` blocks without heap object instantiations.
 *
 * @param sensor Underlying FTC SDK [ColorSensor] instance.
 *
 * @see ColorSensorIO
 * @see NormalizedColorSensor
 */
class FtcColorSensor(private val sensor: ColorSensor) : ColorSensorIO, AutoCloseable {
    
    private val normalizedSensor = sensor as? NormalizedColorSensor

    private val lock = Any()
    @Volatile private var running = true
    private val pollingThread: Thread

    private var cachedRed = 0
    private var cachedGreen = 0
    private var cachedBlue = 0
    private var cachedAlpha = 0
    private val normalizedBuffers = Array(2) { DoubleArray(4) }
    private var readBufferIndex = 0
    private val threadBuffer = DoubleArray(4)

    init {
        HardwareRegistry.registerCloseable(this)
        pollingThread = Thread {
            while (running) {
                var r = 0
                var g = 0
                var b = 0
                var a = 0
                try {
                    r = sensor.red()
                    g = sensor.green()
                    b = sensor.blue()
                    a = sensor.alpha()
                } catch (_: Exception) {}

                try {
                    val colors = normalizedSensor?.normalizedColors
                    if (colors != null) {
                        threadBuffer[0] = colors.red.toDouble()
                        threadBuffer[1] = colors.green.toDouble()
                        threadBuffer[2] = colors.blue.toDouble()
                        threadBuffer[3] = colors.alpha.toDouble()
                    } else {
                        val sum = (r + g + b + a).toDouble()
                        if (sum < 0.1) {
                            threadBuffer[0] = 0.0
                            threadBuffer[1] = 0.0
                            threadBuffer[2] = 0.0
                            threadBuffer[3] = 0.0
                        } else {
                            threadBuffer[0] = r / sum
                            threadBuffer[1] = g / sum
                            threadBuffer[2] = b / sum
                            threadBuffer[3] = a / sum
                        }
                    }
                } catch (_: Exception) {
                    val sum = (r + g + b + a).toDouble()
                    if (sum < 0.1) {
                        threadBuffer[0] = 0.0
                        threadBuffer[1] = 0.0
                        threadBuffer[2] = 0.0
                        threadBuffer[3] = 0.0
                    } else {
                        threadBuffer[0] = r / sum
                        threadBuffer[1] = g / sum
                        threadBuffer[2] = b / sum
                        threadBuffer[3] = a / sum
                    }
                }

                synchronized(lock) {
                    cachedRed = r
                    cachedGreen = g
                    cachedBlue = b
                    cachedAlpha = a
                    System.arraycopy(threadBuffer, 0, normalizedBuffers[1 - readBufferIndex], 0, 4)
                    readBufferIndex = 1 - readBufferIndex
                }

                try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        pollingThread.isDaemon = true
        pollingThread.name = "ARES-GenericColorSensor-Thread"
        pollingThread.start()
    }

    /** Cached raw red channel reading $[0, 65535]$. */
    override val red: Int
        get() = synchronized(lock) { cachedRed }

    /** Cached raw green channel reading $[0, 65535]$. */
    override val green: Int
        get() = synchronized(lock) { cachedGreen }

    /** Cached raw blue channel reading $[0, 65535]$. */
    override val blue: Int
        get() = synchronized(lock) { cachedBlue }

    /** Cached raw alpha/luminance channel reading $[0, 65535]$. */
    override val alpha: Int
        get() = synchronized(lock) { cachedAlpha }

    /** Cached double-buffered normalized $[R, G, B, \alpha]$ array $[0.0, 1.0]$. */
    override val normalizedRgb: DoubleArray
        get() = DoubleArray(4).also(::copyNormalizedRgbInto)

    override fun copyNormalizedRgbInto(destination: DoubleArray) {
        require(destination.size >= 4) { "Normalized RGBA destination must contain at least four elements" }
        synchronized(lock) {
            normalizedBuffers[readBufferIndex].copyInto(destination, endIndex = 4)
        }
    }

    /**
     * Terminates background thread execution and releases hardware resources.
     */
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
    }
}

