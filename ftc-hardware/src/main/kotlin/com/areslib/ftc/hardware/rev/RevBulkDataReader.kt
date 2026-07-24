package com.areslib.ftc.hardware.rev

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Object implementation for Rev Bulk Data Reader.
 *
 * Hardware IO abstraction layer bridging physical robot sensors and actuators into immutable Redux state representations.
 */
object RevBulkDataReader {
    private val motorsList = CopyOnWriteArrayList<RevMotorController>()
    @Volatile private var pollingRunning = false
    private var pollingThread: Thread? = null

    /**
     * registerMotor declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
     */
    fun registerMotor(motor: RevMotorController) {
        synchronized(this) {
            motorsList.add(motor)
            startPollingThreadIfNeeded()
        }
    }

    /**
     * unregisterMotor declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
     * unregisterAll declaration.
     *
     * @param args Standard arguments (if applicable).
     * @return Corresponding output value or Unit.
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
