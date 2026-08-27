package com.areslib.subsystem

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer

/**
 * Platform-independent base class for all ARES robots (FTC and FRC).
 *
 * Provides:
 * - A central Redux [Store] for immutable state management
 * - A pluggable [Subsystem] registry for modular hardware lifecycle orchestration
 * - Shared lifecycle methods ([readAllSensors], [writeAllOutputs], [safeAll])
 *
 * Platform-specific subclasses (e.g., `FtcBaseRobot`, `FrcBaseRobot`) extend this
 * class to wire SDK-specific hardware initialization, telemetry backends, and
 * sensor polling. Team-specific code registers custom subsystems via [registerSubsystem].
 *
 * @param initialState The initial immutable robot state snapshot.
 * @param reducer The root reducer function composing all domain-specific sub-reducers.
 */
/**
 * Class implementation for Ares Robot.
 *
 * Robotics framework control component.
 */
open class AresRobot(
    initialState: RobotState = RobotState(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer
) {
    val store = Store(initialState, reducer)

    // ── Subsystem Registry ──
    // Uses ArrayList with indexed access for zero-allocation iteration in hot paths.
    private val subsystems = ArrayList<Subsystem>(8)

    /**
     * Registers a [Subsystem] for lifecycle management.
     * Registered subsystems are polled in [readAllSensors] and commanded in [writeAllOutputs].
     *
     * Call this during robot initialization (e.g., in `robotInit()` or constructor),
     * **not** inside update loops.
     */
    fun registerSubsystem(subsystem: Subsystem) {
        subsystems.add(subsystem)
    }

    /**
     * Returns the list of registered subsystems.
     * Useful for platform subclasses that need to iterate or inspect registered subsystems.
     */
    fun getRegisteredSubsystems(): List<Subsystem> = subsystems

    /**
     * Reads all registered subsystem sensors and dispatches observations to the store.
     * Called once per update cycle, before [writeAllOutputs].
     *
     * @param timestampMs Current timestamp from [com.areslib.util.RobotClock].
     */
    fun readAllSensors(timestampMs: Long) {
        for (i in 0 until subsystems.size) {
            subsystems[i].readSensors(store, timestampMs)
        }
    }

    /**
     * Writes store state to all registered subsystem hardware outputs.
     * Called once per update cycle, after [readAllSensors].
     *
     * @param powerScale Global power scaling factor (0.0 to 1.0) from brownout protection.
     */
    fun writeAllOutputs(powerScale: Double) {
        val state = store.state
        for (i in 0 until subsystems.size) {
            subsystems[i].writeOutputs(state, powerScale)
        }
    }

    /**
     * Emergency-stops all registered subsystems by writing zero-power outputs.
     * Also invokes [com.areslib.hardware.HardwareRegistry.safeAll] for any
     * hardware registered outside the subsystem lifecycle.
     */
    open fun safeAll() {
        val state = store.state
        for (i in 0 until subsystems.size) {
            try {
                subsystems[i].writeOutputs(state, 0.0)
            } catch (_: Throwable) {}
        }
        com.areslib.hardware.HardwareRegistry.safeAll()
    }

    /**
     * Closes all registered subsystems and releases their resources.
     */
    open fun closeSubsystems() {
        for (i in 0 until subsystems.size) {
            try {
                subsystems[i].close()
            } catch (_: Throwable) {}
        }
    }
}
