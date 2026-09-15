package com.areslib.superstructure

import com.areslib.action.RobotAction
import com.areslib.sequencer.Task
import com.areslib.state.RobotState
import com.areslib.state.SubsystemState
import com.areslib.subsystem.SubsystemValueType

/**
 * Observable immutable state for one generated superstructure state machine.
 *
 * The runtime's transition cursor and pending request are published through Redux so replay,
 * telemetry, and simulator snapshots never share hidden mutable transition state.
 */
data class SuperstructureRuntimeState(
    val currentStateId: String,
    val previousStateId: String = currentStateId,
    val stateEntryTimestampMs: Long = Long.MIN_VALUE,
    val pendingActionKey: String? = null,
    val pendingActionTimestampMs: Long = 0L,
    val requestSequence: Long = 0L,
    val handledRequestSequence: Long = 0L,
    val candidateTransitionId: String? = null,
    val candidateSinceMs: Long = 0L,
    val lastAppliedTargetHash: Long = Long.MIN_VALUE,
    val isFaulted: Boolean = false,
    val faultReason: String? = null,
    val lastRejectionReason: String? = null,
    /** True while the robot is disabled; generated subsystem controllers still own output neutral. */
    val isDisabled: Boolean = false,
    /** Monotonic event identity incremented for every accepted state transition. */
    val transitionSequence: Long = 0L,
    /** Last transition whose exit/entry action group was submitted to the lifecycle executor. */
    val lifecycleSequenceScheduled: Long = -1L,
    /** Last submitted transition whose lifecycle action group completed successfully. */
    val lifecycleSequenceCompleted: Long = -1L,
    val lastLifecycleError: String? = null,
) : SubsystemState

/**
 * Generated typed boundary between the generic state-machine evaluator and generated subsystem
 * state/action plumbing. Implementations must read only immutable cached Redux fields.
 */
interface SuperstructureRuntimeBinding {
    /** Cached lifecycle state supplied by the robot facade; no hardware access is permitted here. */
    fun isRobotEnabled(): Boolean
    /** Resolves stable descriptor UIDs once during construction; hot paths use only primitive slots. */
    fun resolvePort(subsystemUid: String, fieldUid: String): Int
    fun portType(port: Int): SubsystemValueType?
    fun readNumeric(port: Int, state: RobotState): Double
    fun readBoolean(port: Int, state: RobotState): Boolean?
    fun readString(port: Int, state: RobotState): String?
    /**
     * Returns [SuperstructurePortHealthBits] without allocating or reading hardware. [maximumAgeMs]
     * can tighten the descriptor feedback lease, never extend it; the default adds no restriction.
     */
    fun readHealthBits(port: Int, state: RobotState, nowMs: Long, maximumAgeMs: Long = Long.MAX_VALUE): Int
    /**
     * Target tasks emit only [RobotAction.UpdateNamedSubsystemState] actions. The runtime initializes
     * them against a projected snapshot in preset order before dispatching any target actions.
     */
    fun createDoubleTargetTask(port: Int, value: Double): Task?
    fun createIntTargetTask(port: Int, value: Int): Task?
    fun createBooleanTargetTask(port: Int, value: Boolean): Task?
    fun createStringTargetTask(port: Int, value: String): Task?
    /** Resolves a parameterless project-catalog action without executing it directly. */
    fun createLifecycleActionTask(actionKey: String, timestampMs: Long): Task?
}

/** Primitive cached-port health flags used by generated bindings. */
object SuperstructurePortHealthBits {
    const val VALID: Int = 1
    const val FRESH: Int = 1 shl 1
    const val CONFIGURED: Int = 1 shl 2
    const val HOMED: Int = 1 shl 3
    const val CALIBRATED: Int = 1 shl 4
    const val CURRENT_VALID: Int = 1 shl 5
    const val OUTPUT_HEALTHY: Int = 1 shl 6

    const val FRESH_VALID_MASK: Int = VALID or FRESH
    const val CONTROL_READY_MASK: Int = FRESH_VALID_MASK or CONFIGURED or HOMED or CALIBRATED or
        CURRENT_VALID or OUTPUT_HEALTHY

    fun requiredMask(requirement: SuperstructurePortHealthRequirement): Int = when (requirement) {
        SuperstructurePortHealthRequirement.VALUE_ONLY -> 0
        SuperstructurePortHealthRequirement.FRESH_VALID -> FRESH_VALID_MASK
        SuperstructurePortHealthRequirement.CONTROL_READY -> CONTROL_READY_MASK
    }
}
