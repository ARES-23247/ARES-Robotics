package com.areslib.ftc.hardware

import com.areslib.util.RobotClock

import com.areslib.hardware.sensor.ColorSensorIO
import com.areslib.hardware.sensor.DistanceSensorIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.ColorSensor
import com.qualcomm.robotcore.hardware.DistanceSensor
import com.qualcomm.robotcore.hardware.NormalizedColorSensor
import org.firstinspires.ftc.robotcore.external.navigation.Distance

/**
 * Asynchronous hardware IO wrapper for the REV Color Sensor V3 (SKU REV-31-1557).
 *
 * Integrates both the multi-spectral color channel sensor and the IR proximity/rangefinder into a single device interface,
 * implementing both [ColorSensorIO] and [DistanceSensorIO]. Uses a dedicated 50Hz background thread (`ARES-ColorSensorV3-Thread`)
 * and double-buffered thread-safe memory transfers to eliminate main control loop I2C stalls.
 *
 * ### Physical Units & Range Boundaries:
 * - Raw Color Channels ($R, G, B, \alpha$): 16-bit unscaled ADC counts $[0, 65535]$.
 * - Normalized Color Channels: Double-precision floating point ratios $[0.0, 1.0]$.
 * - Integrated IR Proximity Distance: Distance in meters ($m$). Returns [Double.NaN] when out of range.
 *
 * ### Zero-GC Execution Compliance:
 * Background thread writes into pre-allocated double-buffers (`threadBuffer`, `cachedNormalized`).
 * Property getters access primitives inside `synchronized` blocks without heap object instantiations.
 *
 * @param device Underlying FTC SDK [ColorSensor] hardware instance.
 *
 * @see ColorSensorIO
 * @see DistanceSensorIO
 * @see NormalizedColorSensor
 */
class FtcRevColorSensorV3(private val device: ColorSensor) : ColorSensorIO, DistanceSensorIO, AutoCloseable {
    
    private val normalizedSensor = device as? NormalizedColorSensor
    private val distanceSensor = device as? DistanceSensor

    private val lock = Any()
    private var running = true

    private var cachedRed = 0
    private var cachedGreen = 0
    private var cachedBlue = 0
    private var cachedAlpha = 0
    private val cachedNormalized = DoubleArray(4)
    private var cachedDistance = Double.NaN
    private val threadBuffer = DoubleArray(4)

    init {
        HardwareRegistry.registerCloseable(this)
        val thread = Thread {
            while (running) {
                var red = 0
                var green = 0
                var blue = 0
                var alpha = 0
                try {
                    red = device.red()
                    green = device.green()
                    blue = device.blue()
                    alpha = device.alpha()
                } catch (_: Exception) {}

                try {
                    val colors = normalizedSensor?.normalizedColors
                    if (colors != null) {
                        threadBuffer[0] = colors.red.toDouble()
                        threadBuffer[1] = colors.green.toDouble()
                        threadBuffer[2] = colors.blue.toDouble()
                        threadBuffer[3] = colors.alpha.toDouble()
                    } else {
                        val sum = (red + green + blue + alpha).toDouble()
                        if (sum < 0.1) {
                            threadBuffer[0] = 0.0
                            threadBuffer[1] = 0.0
                            threadBuffer[2] = 0.0
                            threadBuffer[3] = 0.0
                        } else {
                            threadBuffer[0] = red / sum
                            threadBuffer[1] = green / sum
                            threadBuffer[2] = blue / sum
                            threadBuffer[3] = alpha / sum
                        }
                    }
                } catch (_: Exception) {
                    val sum = (red + green + blue + alpha).toDouble()
                    if (sum < 0.1) {
                        threadBuffer[0] = 0.0
                        threadBuffer[1] = 0.0
                        threadBuffer[2] = 0.0
                        threadBuffer[3] = 0.0
                    } else {
                        threadBuffer[0] = red / sum
                        threadBuffer[1] = green / sum
                        threadBuffer[2] = blue / sum
                        threadBuffer[3] = alpha / sum
                    }
                }

                var distance = Double.NaN
                try {
                    distance = distanceSensor?.getDistance(Distance.METER) ?: Double.NaN
                } catch (_: Exception) {}

                synchronized(lock) {
                    cachedRed = red
                    cachedGreen = green
                    cachedBlue = blue
                    cachedAlpha = alpha
                    System.arraycopy(threadBuffer, 0, cachedNormalized, 0, 4)
                    cachedDistance = distance
                }

                try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        thread.isDaemon = true
        thread.name = "ARES-ColorSensorV3-Thread"
        thread.start()
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
        get() = synchronized(lock) { cachedNormalized }

    /** Cached IR proximity rangefinder reading in meters ($m$). Returns [Double.NaN] if out of range. */
    override val distanceMeters: Double
        get() = synchronized(lock) { cachedDistance }

    /**
     * Terminates background thread execution and releases hardware resources.
     */
    override fun close() {
        running = false
    }
}


