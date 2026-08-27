package com.areslib.ftc.hardware

import com.areslib.hardware.sensor.MultizoneDistanceSensorIO

/**
 * Hardware driver abstraction interface for community-developed VL53L5CX multizone Time-of-Flight (ToF) sensors.
 *
 * Exposes multi-zone grid array dimensions ($4 \times 4$ or $8 \times 8$) and raw per-zone distance telemetry in millimeters ($mm$).
 *
 * @see MultizoneDistanceSensorIO
 */
interface VL53L5CXDriverProxy {
    /**
     * Polls the physical I2C sensor for the latest frame of multi-zone distance readings.
     */
    fun update()

    /** Active grid row count (typically 4 or 8). */
    val rows: Int

    /** Active grid column count (typically 4 or 8). */
    val columns: Int

    /** Raw multizone distance measurements array in millimeters ($mm$). */
    val distancesMillimeters: IntArray
}

/**
 * Concrete hardware IO adapter for the STMicroelectronics VL53L5CX $8 \times 8$ multizone ToF distance sensor array.
 *
 * Runs a 50Hz background thread (`ARES-VL53L5CX-Thread`) to poll I2C zone arrays asynchronously,
 * scaling millimeter integer readings into meters ($m$) in-place.
 *
 * ### Physical Units & Specifications:
 * - Zone Resolution: $4 \times 4$ (16 zones) or $8 \times 8$ (64 zones).
 * - Measured Zone Distance: Meters ($m$).
 *
 * ### Zero-GC Execution Compliance:
 * Property getter accesses [lastDistances] double-buffer inside `synchronized` lock without dynamic heap allocations.
 *
 * @param driver Underlying [VL53L5CXDriverProxy] physical driver handle.
 *
 * @see MultizoneDistanceSensorIO
 * @see VL53L5CXDriverProxy
 */
class FtcVL53L5CX(private val driver: VL53L5CXDriverProxy) : MultizoneDistanceSensorIO, AutoCloseable {
    /** Measured grid row count. Returns 0 if communication fails. */
    override val rows: Int
        get() = try { driver.rows } catch (_: Exception) { 0 }

    /** Measured grid column count. Returns 0 if communication fails. */
    override val columns: Int
        get() = try { driver.columns } catch (_: Exception) { 0 }

    private val lock = Any()
    @Volatile private var running = true
    private var readDistances = DoubleArray(0)
    private var writeDistances = DoubleArray(0)
    private val pollingThread: Thread

    init {
        com.areslib.hardware.HardwareRegistry.registerCloseable(this)
        pollingThread = Thread {
            while (running) {
                try {
                    driver.update()
                    val raw = driver.distancesMillimeters
                    if (writeDistances.size != raw.size) {
                        writeDistances = DoubleArray(raw.size)
                    }
                    for (i in raw.indices) {
                        writeDistances[i] = raw[i] / 1000.0
                    }
                    synchronized(lock) {
                        val oldRead = readDistances
                        readDistances = writeDistances
                        writeDistances = if (oldRead.size == raw.size) oldRead else DoubleArray(raw.size)
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        pollingThread.isDaemon = true
        pollingThread.name = "ARES-VL53L5CX-Thread"
        pollingThread.start()
    }

    /** Latest multizone distance readings in meters ($m$). Access is thread-safe. */
    override val distancesMeters: DoubleArray
        get() = synchronized(lock) { readDistances.copyOf() }

    override fun copyDistancesMetersInto(destination: DoubleArray): Int = synchronized(lock) {
        val count = minOf(readDistances.size, destination.size)
        readDistances.copyInto(destination, endIndex = count)
        count
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

