package com.areslib.sequencer.tasks

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.sequencer.TaskCallbacks
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.RobotState

/**
 * Sequencer task that blinks a named indicator light between two colors
 * for a specified duration. The blink rate is configurable.
 *
 * Dispatches immutable, freshly timestamped actions on initialization, color changes and end.
 * Unchanged phases allocate no actions; phase transitions still allocate their action and list.
 * Both phases need at least one millisecond. Odd periods give the first color the extra
 * millisecond while preserving the full period. Negative elapsed time fails the task.
 *
 * Usage in an ARES auto sequence:
 * ```
 * robotSequence {
 *     blinkIndicator("indicator", GREEN, OFF, duration = 2.seconds, period = 400.milliseconds)
 * }
 * ```
 *
 * @param lightName Nonblank hardware map name of the indicator light.
 * @param colorA First blink color (shown initially).
 * @param colorB Second blink color (alternates with colorA).
 * @param durationMs Nonnegative total blink duration in milliseconds.
 * @param periodMs Full blink cycle period, at least 2 milliseconds (default 500ms = 2Hz).
 */
class BlinkIndicatorTask(
    private val lightName: String,
    private val colorA: IndicatorLightColor,
    private val colorB: IndicatorLightColor,
    private val durationMs: Long,
    private val periodMs: Long = 500L
) : Task {
    init {
        require(lightName.isNotBlank()) { "Indicator name must not be blank" }
        require(durationMs >= 0L) { "Blink duration must be non-negative" }
        require(periodMs >= 2L) { "Blink period must be at least two milliseconds" }
    }
    override val name = "BlinkIndicator($lightName, ${colorA.name}↔${colorB.name}, ${durationMs}ms)"
    override val requiredResources: Long = TaskResources.LIGHTING
    private val firstPhaseDurationMs = periodMs / 2L + periodMs % 2L
    private var lastPhase = 0

    override fun initialize(state: RobotState): List<RobotAction> {
        super.initialize(state)
        lastPhase = 0
        return listOf(RobotAction.SetIndicatorLight(lightName, colorA.position))
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING) return emptyList()
        super.execute(state, elapsedMs)
        if (TaskStateMachine.getStatus(this) != TaskStatus.RUNNING || !validElapsed(elapsedMs)) return emptyList()
        // Named aliases may map to the same hardware command (e.g. PURPLE and VIOLET).
        if (colorA.position == colorB.position) return emptyList()
        val phase = if (elapsedMs % periodMs < firstPhaseDurationMs) 0 else 1
        if (phase != lastPhase) {
            lastPhase = phase
            val color = if (phase == 0) colorA else colorB
            return listOf(RobotAction.SetIndicatorLight(lightName, color.position))
        }
        return emptyList()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean =
        validElapsed(elapsedMs) && elapsedMs >= durationMs

    private fun validElapsed(elapsedMs: Long): Boolean {
        if (elapsedMs >= 0L) return true
        if (TaskStateMachine.markFailed(this)) TaskCallbacks.invokeFail(this)
        return false
    }

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        super.end(state, interrupted)
        // Preserve the authored terminal color on normal and interrupted endings.
        return listOf(RobotAction.SetIndicatorLight(lightName, colorA.position))
    }
}
