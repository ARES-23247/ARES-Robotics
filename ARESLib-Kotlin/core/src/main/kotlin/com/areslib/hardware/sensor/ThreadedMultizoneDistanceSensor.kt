package com.areslib.hardware.sensor

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.areslib.hardware.HardwareRegistry

/**
 * Thread-safe wrapper that polls a slow MultizoneDistanceSensorIO (like the VL53L5CX) on a background thread
 * at a fixed rate, completely eliminating heavy synchronous I2C blocking delays from the main control loop.
 */
class ThreadedMultizoneDistanceSensor(
    private val physicalSensor: MultizoneDistanceSensorIO,
    pollIntervalMs: Long = 33 // ~30 Hz poll rate (ideal for VL53L5CX native framerates)
) : MultizoneDistanceSensorIO, AutoCloseable {

    override val rows: Int get() = physicalSensor.rows
    override val columns: Int get() = physicalSensor.columns

    private val bufferA = DoubleArray(rows * columns)
    private val bufferB = DoubleArray(rows * columns)

    @Volatile
    private var activeBuffer: DoubleArray = bufferA

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { thread ->
        Thread(thread, "ARES-Multizone-ToF-Polling-Thread").apply { isDaemon = true }
    }

    init {
        HardwareRegistry.registerCloseable(this)
        scheduler.scheduleAtFixedRate({
            try {
                val writeBuffer = if (activeBuffer === bufferA) bufferB else bufferA
                physicalSensor.copyDistancesMetersInto(writeBuffer)
                activeBuffer = writeBuffer
            } catch (e: Exception) {
                // Keep last cached values
            }
        }, 0, pollIntervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Returns a caller-owned copy of the latest cached zone readings.
     */
    override val distancesMeters: DoubleArray
        get() = activeBuffer.copyOf()

    override fun copyDistancesMetersInto(destination: DoubleArray): Int {
        val source = activeBuffer
        val count = minOf(source.size, destination.size)
        source.copyInto(destination, endIndex = count)
        return count
    }

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
