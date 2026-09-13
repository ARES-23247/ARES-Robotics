package com.areslib.subsystem

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.action.RobotAction
import com.areslib.reducer.rootReducer
import com.areslib.hardware.HardwareRegistry
import java.util.Collections

/**
 * Platform-independent Redux store and subsystem lifecycle owner.
 *
 * Register during initialization, then call read/write from one robot-loop owner. Platform
 * owners must catch callback failures and neutralize the robot, and quiesce concurrent IO before
 * closing. This class does not supply actuator enable, configuration or feedback-validity policy;
 * each [Subsystem] enforces those requirements and treats output scale zero as safe neutral.
 */
open class AresRobot(
    initialState: RobotState = RobotState(),
    reducer: (RobotState, RobotAction) -> RobotState = ::rootReducer,
    val hardwareRegistry: HardwareRegistry = HardwareRegistry(),
) {
    val store = Store(initialState, reducer)

    // Copy only during initialization; indexed reads and registry inspection allocate nothing.
    private var subsystems: List<Subsystem> = emptyList()
    private var lifecycleDepth = 0
    private var safing = false
    private var subsystemsClosed = false

    /**
     * Registers once by object identity in initialization order. Equal distinct instances remain
     * independent. Registration from lifecycle callbacks or after closure is rejected.
     */
    fun registerSubsystem(subsystem: Subsystem) {
        check(!subsystemsClosed) { "Subsystem lifecycle is closed" }
        check(lifecycleDepth == 0) { "Register subsystems during initialization, outside lifecycle callbacks" }
        for (i in subsystems.indices) if (subsystems[i] === subsystem) return
        val updated = ArrayList<Subsystem>(subsystems.size + 1)
        updated.addAll(subsystems)
        updated.add(subsystem)
        subsystems = Collections.unmodifiableList(updated)
    }

    /** Stable read-only snapshot; later registration/closure does not change a retained list. */
    fun getRegisteredSubsystems(): List<Subsystem> = subsystems

    /**
     * Reads each registered subsystem once, in order, with the supplied RobotClock timestamp.
     * Callback failures propagate to the platform loop owner. Calls after closure are rejected.
     */
    fun readAllSensors(timestampMs: Long) {
        check(!subsystemsClosed) { "Subsystem lifecycle is closed" }
        val snapshot = subsystems
        lifecycleDepth++
        try {
            for (i in snapshot.indices) {
                if (subsystemsClosed) return
                snapshot[i].readSensors(store, timestampMs)
            }
        } finally { lifecycleDepth-- }
    }

    /**
     * Writes one coherent Redux snapshot to every registered subsystem. Finite power scale is
     * clamped to [0, 1]; nonfinite scale commands neutral. A callback that closes this lifecycle
     * prevents later active writes in the batch. Calls after closure are rejected.
     */
    fun writeAllOutputs(powerScale: Double) {
        check(!subsystemsClosed) { "Subsystem lifecycle is closed" }
        val scale = if (powerScale.isFinite()) powerScale.coerceIn(0.0, 1.0) else 0.0
        val state = store.state
        val snapshot = subsystems
        lifecycleDepth++
        try {
            for (i in snapshot.indices) {
                if (subsystemsClosed) return
                snapshot[i].writeOutputs(state, scale)
            }
        } finally { lifecycleDepth-- }
    }

    /**
     * Attempts neutral for every subsystem, then [HardwareRegistry.safeAll]. Reentrant safety
     * calls leave the current safety traversal in charge. Ordinary exceptions retain legacy
     * best-effort behavior; other throwables are rethrown only after remaining safety attempts.
     */
    open fun safeAll() {
        if (safing) return
        safing = true
        lifecycleDepth++
        var failure: Throwable? = null
        try {
            val state = store.state
            val snapshot = subsystems
            for (i in snapshot.indices) {
                if (subsystemsClosed) break
                try { snapshot[i].writeOutputs(state, 0.0) }
                catch (next: Throwable) { failure = retainFatalFailure(failure, next) }
            }
            try { hardwareRegistry.safeAll() }
            catch (next: Throwable) { failure = retainFatalFailure(failure, next) }
        } finally {
            lifecycleDepth--
            safing = false
        }
        failure?.let { throw it }
    }

    /**
     * Terminal, once-only subsystem teardown. Attempts neutral on every subsystem before closing
     * any, then attempts every close even after failures. Registry resources remain owned by the
     * platform's separate registry shutdown. Concurrent foreground callbacks must be quiesced first.
     * Ordinary exceptions are suppressed; other throwables are aggregated by identity and rethrown.
     */
    open fun closeSubsystems() {
        if (subsystemsClosed) return
        subsystemsClosed = true
        val snapshot = subsystems
        subsystems = emptyList()
        val state = store.state
        var failure: Throwable? = null
        for (i in snapshot.indices) {
            try { snapshot[i].writeOutputs(state, 0.0) }
            catch (next: Throwable) { failure = retainFatalFailure(failure, next) }
        }
        for (i in snapshot.indices) {
            try { snapshot[i].close() }
            catch (next: Throwable) { failure = retainFatalFailure(failure, next) }
        }
        failure?.let { throw it }
    }

    private fun retainFatalFailure(first: Throwable?, next: Throwable): Throwable? {
        if (next is Exception) return first
        if (first == null) return next
        if (first !== next && first.suppressed.none { it === next }) first.addSuppressed(next)
        return first
    }
}
