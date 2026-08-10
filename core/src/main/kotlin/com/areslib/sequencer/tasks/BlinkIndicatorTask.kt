package com.areslib.sequencer.tasks

import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.sequencer.Task
import com.areslib.state.RobotState

/**
 * Sequencer task that blinks a named indicator light between two colors
 * for a specified duration. The blink rate is configurable.
 *
 * Only dispatches actions on phase transitions (not every tick)
 * to maintain zero-GC compliance in the hot loop.
 *
 * Usage in auto sequences:
 * ```
 * RobotSequence()
 *     .addTask(BlinkIndicatorTask("indicator", GREEN, OFF, durationMs = 2000, periodMs = 400))
 *     .build()
 * ```
 *
 * @param lightName Hardware map name of the indicator light.
 * @param colorA First blink color (shown initially).
 * @param colorB Second blink color (alternates with colorA).
 * @param durationMs Total blink duration in milliseconds.
 * @param periodMs Full blink cycle period in milliseconds (default 500ms = 1Hz blink).
 */
/**
 * Class implementation for Blink Indicator Task.
 *
 * Asynchronous superstructure task sequence execution unit.
 */
class BlinkIndicatorTask(
    private val lightName: String,
    private val colorA: IndicatorLightColor,
    private val colorB: IndicatorLightColor,
    private val durationMs: Long,
    private val periodMs: Long = 500L
) : Task {
    override val name = "BlinkIndicator($lightName, ${colorA.name}↔${colorB.name}, ${durationMs}ms)"
    private var lastPhase = -1L

    override fun initialize(state: RobotState): List<RobotAction> {
        lastPhase = 0L
        return listOf(RobotAction.SetIndicatorLight(lightName, colorA.position))
    }

    override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
        val halfPeriod = periodMs / 2
        val phase = if (halfPeriod > 0) (elapsedMs / halfPeriod) % 2 else 0L
        // Only dispatch when the phase actually changes (avoids per-tick allocation)
        if (phase != lastPhase) {
            lastPhase = phase
            val color = if (phase == 0L) colorA else colorB
            return listOf(RobotAction.SetIndicatorLight(lightName, color.position))
        }
        return emptyList()
    }

    override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = elapsedMs >= durationMs

    override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
        // Return to colorA when done
        return listOf(RobotAction.SetIndicatorLight(lightName, colorA.position))
    }
}
