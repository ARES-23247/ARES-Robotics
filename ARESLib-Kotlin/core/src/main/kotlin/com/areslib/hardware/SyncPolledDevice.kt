package com.areslib.hardware

/**
 * Hardware component whose blocking read is scheduled on the registry polling thread.
 *
 * Devices implementing this interface can be registered with [HardwareRegistry.registerSyncPolledDevice]
 * to be included in the centralized hardware polling loop. The registry polls at most one device
 * from each polling list per interval; the interval is therefore not a per-device sampling period.
 * Implementations must publish results through their own thread-safe cached fields so robot-loop
 * getters never touch hardware directly.
 */
interface SyncPolledDevice {
    /**
     * Performs one synchronous hardware transaction and updates cached state.
     *
     * Called serially by the registry-owned daemon thread. Implementations must not retain caller
     * state, block indefinitely, or throw; an uncaught exception terminates the shared polling loop.
     */
    fun pollSync()
}
