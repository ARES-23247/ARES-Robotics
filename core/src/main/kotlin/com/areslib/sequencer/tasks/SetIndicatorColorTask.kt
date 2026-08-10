package com.areslib.sequencer.tasks

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.sequencer.Task
import com.areslib.state.RobotState

/**
 * Instant sequencer task that sets a named indicator light to a predefined color.
 * Completes immediately after dispatching the action.
 *
 * Usage in auto sequences:
 * ```
 * RobotSequence()
 *     .addTask(SetIndicatorColorTask("indicator", IndicatorLightColor.GREEN))
 *     .addTask(driveForwardTask)
 *     .addTask(SetIndicatorColorTask("indicator", IndicatorLightColor.RED))
 *     .build()
 * ```
 */
/**
 * Class implementation for Set Indicator Color Task.
 *
 * Asynchronous superstructure task sequence execution unit.
 */
class SetIndicatorColorTask(
    private val lightName: String,
    private val color: IndicatorLightColor
) : Task {
    override val name = "SetIndicator($lightName→${color.name})"
    private var dispatched = false

    override fun initialize(state: RobotState): List<RobotAction> {
        dispatched = true
        return listOf(RobotAction.SetIndicatorLight(lightName, color.position))
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = dispatched
}
