package com.areslib.subsystem

import com.areslib.Store
import com.areslib.state.RobotState

/**
 * Standard lifecycle interface for modular, season-independent robot mechanisms.
 */
interface Subsystem : AutoCloseable {
    /**
     * Reads sensors, processes telemetry signals, and dispatches actions to the store.
     */
    fun readSensors(store: Store, timestampMs: Long)

    /**
     * Applies outputs/voltages to physical motor controllers or actuator loops.
     */
    fun writeOutputs(state: RobotState, scale: Double)

    /**
     * Closes background threads or open resources cleanly.
     */
    override fun close() {}
}
