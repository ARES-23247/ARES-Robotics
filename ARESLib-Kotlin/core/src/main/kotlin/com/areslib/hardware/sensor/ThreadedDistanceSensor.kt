package com.areslib.hardware.sensor

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.areslib.hardware.HardwareRegistry

/**
 * Thread-safe wrapper that polls a slow DistanceSensorIO on a background thread
 * at a fixed rate, completely eliminating synchronous I2C blocking delays from the main control loop.
 */
class ThreadedDistanceSensor(
    private val physicalSensor: DistanceSensorIO,
    pollIntervalMs: Long = 20 // 50 Hz poll rate by default
) : DistanceSensorIO, AutoCloseable {

    @Volatile
    private var cachedDistance: Double = Double.NaN
    
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { thread ->
        Thread(thread, "ARES-ToF-Polling-Thread").apply { isDaemon = true }
    }

    init {
        HardwareRegistry.registerCloseable(this)
        scheduler.scheduleAtFixedRate({
            try {
                cachedDistance = physicalSensor.distanceMeters
            } catch (e: Exception) {
                cachedDistance = Double.NaN
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Instantly returns the latest cached reading from memory (0.0 ms execution time).
     */
    override val distanceMeters: Double
        get() = cachedDistance

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

    override fun close() {
        shutdown()
    }
}
