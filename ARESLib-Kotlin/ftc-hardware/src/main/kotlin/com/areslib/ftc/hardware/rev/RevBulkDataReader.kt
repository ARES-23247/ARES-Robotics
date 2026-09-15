package com.areslib.ftc.hardware.rev

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
    private val motorsList = ArrayList<RevMotorController>()
    private var pollingThread: Thread? = null
    private var rrIndex = 0

    /**
     * Registers a [RevMotorController] instance to be included in round-robin background current polling.
     *
     * @param motor Motor controller instance.
     */
    fun registerMotor(motor: RevMotorController) {
        synchronized(this) {
            if (motor.isClosed || motorsList.contains(motor)) return
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
        val stopped = synchronized(this) {
            motorsList.remove(motor)
            if (motorsList.isEmpty()) detachPollingThread() else null
        }
        stopWorker(stopped)
    }

    private fun startPollingThreadIfNeeded() {
        if (pollingThread != null) return
        val worker = Thread {
            val self = Thread.currentThread()
            try {
                while (!self.isInterrupted) {
                    // One coherent selection under the registry lock; hardware IO never holds it.
                    // Thread identity prevents a stopped worker from adopting a new registration.
                    val motor = synchronized(this) {
                        if (pollingThread !== self || motorsList.isEmpty()) return@Thread
                        if (rrIndex >= motorsList.size) rrIndex = 0
                        motorsList[rrIndex++]
                    }
                    try {
                        motor.pollCurrentSync()
                    } catch (_: Exception) {
                        // A failed device must not turn the reader into an unpaced retry loop.
                    }
                    if (synchronized(this) { pollingThread !== self }) return@Thread
                    try {
                        Thread.sleep(50) // One current read per 50 ms across registered motors.
                    } catch (_: InterruptedException) {
                        self.interrupt()
                        return@Thread
                    }
                }
            } finally {
                synchronized(this) {
                    if (pollingThread === self) pollingThread = null
                }
            }
        }.apply {
            isDaemon = true
            name = "ARES-MotorCurrent-Thread"
        }
        pollingThread = worker
        worker.start()
    }

    /**
     * Stops the background polling thread and unregisters all motor controller instances.
     * Waits at most one second for a blocked SDK read; a detached worker exits when that
     * read returns and cannot poll a later registration. This does not close actuators.
     */
    fun unregisterAll() {
        val stopped = synchronized(this) {
            motorsList.clear()
            detachPollingThread()
        }
        stopWorker(stopped)
    }

    /** Caller holds the registry lock. Only the returned worker belongs to this stop. */
    private fun detachPollingThread(): Thread? {
        rrIndex = 0
        return pollingThread.also { pollingThread = null }
    }

    private fun stopWorker(worker: Thread?) {
        if (worker == null) return
        worker.interrupt()
        if (worker === Thread.currentThread()) return
        try { worker.join(1000) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }
}

