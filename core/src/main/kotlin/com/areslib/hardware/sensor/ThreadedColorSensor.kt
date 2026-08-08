package com.areslib.hardware.sensor

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.areslib.hardware.HardwareRegistry

/**
 * Thread-safe wrapper that polls a slow ColorSensorIO on a background thread
 * at a fixed rate, completely eliminating synchronous I2C blocking delays from the main control loop.
 */
class ThreadedColorSensor(
    private val physicalSensor: ColorSensorIO,
    pollIntervalMs: Long = 20 // 50 Hz poll rate by default
) : ColorSensorIO, AutoCloseable {

    @Volatile private var cachedRed: Int = 0
    @Volatile private var cachedGreen: Int = 0
    @Volatile private var cachedBlue: Int = 0
    @Volatile private var cachedAlpha: Int = 0
    
    private val bufferA = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    private val bufferB = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    @Volatile private var activeBuffer: DoubleArray = bufferA

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { thread ->
        Thread(thread, "ARES-Color-Polling-Thread").apply { isDaemon = true }
    }

    init {
        HardwareRegistry.registerCloseable(this)
        scheduler.scheduleAtFixedRate({
            try {
                cachedRed = physicalSensor.red
                cachedGreen = physicalSensor.green
                cachedBlue = physicalSensor.blue
                cachedAlpha = physicalSensor.alpha
                val newNorm = physicalSensor.normalizedRgb
                val writeBuffer = if (activeBuffer === bufferA) bufferB else bufferA
                newNorm.copyInto(writeBuffer)
                activeBuffer = writeBuffer
            } catch (e: Exception) {
                // Keep last cached values
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    override val red: Int get() = cachedRed
    override val green: Int get() = cachedGreen
    override val blue: Int get() = cachedBlue
    override val alpha: Int get() = cachedAlpha

    override val normalizedRgb: DoubleArray get() = activeBuffer

    /**
     * Safely shuts down the polling background thread and waits for termination.
     */
    fun shutdown() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (_: InterruptedException) {
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /**
     * close declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    override fun close() {
        shutdown()
    }
}
