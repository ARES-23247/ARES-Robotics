package com.areslib.ftc.hardware

import com.areslib.hardware.sensor.DistanceSensorIO
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.DistanceSensor
import org.firstinspires.ftc.robotcore.external.navigation.Distance

/**
 * Asynchronous hardware IO wrapper for FTC I2C, Time-of-Flight (ToF), and LiDAR distance sensors.
 *
 * Compatible with REV 2m Distance Sensors (VL53L0X), goBILDA ToF sensors, and STMicroelectronics rangefinders.
 * Utilizes a dedicated 50Hz background thread (`ARES-DistanceSensor-Thread`) to poll distance measurements asynchronously,
 * preventing slow I2C bus transactions from stalling high-frequency 50Hz–100Hz control loops.
 *
 * ### Physical Units & Range Boundaries:
 * - Distance: Meters ($m$).
 * - Out-of-range / Error state: Returns [Double.NaN] when target object is beyond maximum sensor range.
 *
 * ### Zero-GC Execution Compliance:
 * Reads and updates [cachedDistance] in-place inside `synchronized` blocks without allocating heap memory objects.
 *
 * @param sensor Underlying FTC SDK [DistanceSensor] instance.
 *
 * @see DistanceSensorIO
 * @see DistanceSensor
 */
class FtcDistanceSensor(private val sensor: DistanceSensor) : DistanceSensorIO, AutoCloseable {
    private val lock = Any()
    private var running = true
    private var cachedDistance = Double.NaN
    private var pollingThread: Thread? = null

    init {
        HardwareRegistry.registerCloseable(this)
        pollingThread = Thread {
            while (running) {
                val d = try {
                    sensor.getDistance(Distance.METER)
                } catch (_: Exception) {
                    Double.NaN
                }
                synchronized(lock) {
                    cachedDistance = d
                }
                try { Thread.sleep(20) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        pollingThread?.isDaemon = true
        pollingThread?.name = "ARES-DistanceSensor-Thread"
        pollingThread?.start()
    }

    /** Measured distance reading in meters ($m$). Returns [Double.NaN] if out of range or uninitialized. */
    override val distanceMeters: Double
        get() = synchronized(lock) { cachedDistance }

    /**
     * Terminates background sampling thread and unregisters hardware resources.
     */
    override fun close() {
        running = false
        pollingThread?.interrupt()
    }
}


