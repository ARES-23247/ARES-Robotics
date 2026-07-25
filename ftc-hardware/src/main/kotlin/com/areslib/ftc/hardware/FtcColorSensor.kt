package com.areslib.ftc.hardware

import com.areslib.hardware.sensor.ColorSensorIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.ColorSensor
import com.qualcomm.robotcore.hardware.NormalizedColorSensor

/**
 * Wraps a generic FTC Color Sensor.
 * Automatically attempts to use NormalizedColorSensor telemetry for accurate, lighting-invariant detection.
 */
class FtcColorSensor(private val sensor: ColorSensor) : ColorSensorIO, AutoCloseable {
    
    private val normalizedSensor = sensor as? NormalizedColorSensor

    private val lock = Any()
    private var running = true

    private var cachedRed = 0
    private var cachedGreen = 0
    private var cachedBlue = 0
    private var cachedAlpha = 0
    private val normalizedBuffers = Array(2) { DoubleArray(4) }
    private var readBufferIndex = 0
    private val threadBuffer = DoubleArray(4)

    init {
        HardwareRegistry.registerCloseable(this)
        val thread = Thread {
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
        thread.isDaemon = true
        thread.name = "ARES-GenericColorSensor-Thread"
        thread.start()
    }

    override val red: Int
        get() = synchronized(lock) { cachedRed }
    override val green: Int
        get() = synchronized(lock) { cachedGreen }
    override val blue: Int
        get() = synchronized(lock) { cachedBlue }
    override val alpha: Int
        get() = synchronized(lock) { cachedAlpha }

    override val normalizedRgb: DoubleArray
        get() = synchronized(lock) { normalizedBuffers[readBufferIndex] }

    /**
     * close declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun close() {
        running = false
    }
}

