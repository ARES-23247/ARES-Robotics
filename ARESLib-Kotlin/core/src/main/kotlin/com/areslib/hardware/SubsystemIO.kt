package com.areslib.hardware

/**
 * Common lifecycle interface for hardware wrappers supporting batch status updates and safety configurations.
 */
interface SubsystemIO : LoggableDevice {
    /**
     * Refreshes cached status signals or bulk registers from physical hardware.
     */
    fun refresh() {}

    /**
     * Commands this subsystem's actuator outputs to safe, zero-effort settings.
     */
    fun safe() {}
}
