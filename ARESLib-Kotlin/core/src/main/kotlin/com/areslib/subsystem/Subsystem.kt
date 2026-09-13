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
     * Scale is finite and bounded to [0, 1]. Scale zero must command declared safe neutral,
     * including for actuators whose neutral is not a numeric zero. Implementations own
     * configuration, fresh-feedback and explicit enable/arm checks before nonzero output.
     */
    fun writeOutputs(state: RobotState, scale: Double)

    /**
     * Closes background threads or open resources cleanly.
     * AresRobot attempts neutral on all registered subsystems before closing any of them.
     */
    override fun close() {}
}
