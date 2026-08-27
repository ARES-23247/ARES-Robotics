package com.areslib.ftc.hardware.rev

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Asynchronous current sampling manager for REV Expansion Hub motor ports.
 *
 * Implements a round-robin background thread (`ARES-MotorCurrent-Thread`) polling motor current draw at 20Hz ($50\text{ms}$ interval).
 * Prevents high-frequency main control loop thread contention and eliminates slow I2C current query stalls.
 *
 * ### Performance & Physical Units:
 * - Current Draw: Amperes ($A$).
 * - Round-Robin Rate: 1 motor queried per $50\text{ms}$ step to eliminate REV I2C bus saturation.
 *
 * @see RevMotorController
 */
object RevBulkDataReader {
    private val motorsList = CopyOnWriteArrayList<RevMotorController>()
    @Volatile private var pollingRunning = false
    private var pollingThread: Thread? = null

    /**
     * Registers a [RevMotorController] instance to be included in round-robin background current polling.
     *
     * @param motor Motor controller instance.
     */
    fun registerMotor(motor: RevMotorController) {
        synchronized(this) {
            motorsList.add(motor)
            startPollingThreadIfNeeded()
        }
    }

    /**
     * Unregisters a [RevMotorController] instance from background current polling.
     *
     * @param motor Motor controller instance.
     */
    fun unregisterMotor(motor: RevMotorController) {
        synchronized(this) {
            motorsList.remove(motor)
        }
    }

    private var rrIndex = 0

    private fun startPollingThreadIfNeeded() {
        if (pollingRunning) return
        pollingRunning = true
        pollingThread = Thread {
            while (pollingRunning) {
                try {
                    val list = motorsList
                    if (list.isNotEmpty()) {
                        val index = Math.abs(rrIndex) % list.size
                        val motorInstance = list.getOrNull(index)
                        motorInstance?.pollCurrentSync()
                        rrIndex++
                    }
                    Thread.sleep(50) // 20Hz round-robin (only 1 I2C current read per 50ms)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (_: Exception) {
                    // Ignore concurrent modification or polling errors
                }
            }
        }.apply {
            isDaemon = true
            name = "ARES-MotorCurrent-Thread"
            start()
        }
    }

    /**
     * Stops the background polling thread and unregisters all motor controller instances.
     */
    fun unregisterAll() {
        synchronized(this) {
            pollingRunning = false
            pollingThread?.interrupt()
            pollingThread = null
            motorsList.clear()
        }
    }
}

