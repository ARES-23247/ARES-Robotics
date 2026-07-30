package com.areslib.pathing

import com.areslib.sequencer.Task

/**
 * Object implementation for Named Commands.
 *
 * Autonomous path planning, trajectory generation, and obstacle avoidance module.
 *
 * ### Coordinate System:
 * Field-centric coordinates in meters ($m$) relative to field origin.
 */
object NamedCommands {
    private val registry = mutableMapOf<String, (Long) -> Task>()

    /**
     * Registers a command builder by name.
     * The builder lambda takes a base timestamp (reference timestamp) and returns a [Task].
     */
    fun registerCommand(name: String, builder: (Long) -> Task) {
        registry[name] = builder
    }

    /**
     * Registers a constant static command by name.
     */
    fun registerCommand(name: String, task: Task) {
        registry[name] = { task }
    }

    /**
     * Resolves a registered command by name. Returns null if not found.
     * Supports dynamic resolution for indicator light commands:
     * - `SetIndicatorColor_<color>` (Light 1 / "indicator")
     * - `SetSecondIndicatorColor_<color>` (Light 2 / "indicator2")
     * - `SetThirdIndicatorColor_<color>` (Light 3 / "indicator3")
     * - `SetFourthIndicatorColor_<color>` (Light 4 / "indicator4")
     * - `SetIndicatorColor_<lightName>_<color>` (Custom target light name)
     */
    fun getCommand(name: String, timestampMs: Long): Task? {
        val builder = registry[name]
        if (builder != null) return builder(timestampMs)

        if (name.contains("IndicatorColor_")) {
            val parts = name.split("_")
            if (parts.size == 3 && parts[0] == "SetIndicatorColor") {
                val targetLight = parts[1]
                val colorName = parts[2]
                val color = com.areslib.hardware.actuator.IndicatorLightColor.entries.firstOrNull { it.name.equals(colorName, ignoreCase = true) }
                if (color != null) {
                    return com.areslib.sequencer.tasks.SetIndicatorColorTask(targetLight, color)
                }
            } else if (parts.size == 2) {
                val prefix = parts[0]
                val colorName = parts[1]
                val color = com.areslib.hardware.actuator.IndicatorLightColor.entries.firstOrNull { it.name.equals(colorName, ignoreCase = true) }
                if (color != null) {
                    val targetLight = when (prefix) {
                        "SetIndicatorColor" -> "indicator"
                        "SetSecondIndicatorColor" -> "indicator2"
                        "SetThirdIndicatorColor" -> "indicator3"
                        "SetFourthIndicatorColor" -> "indicator4"
                        else -> null
                    }
                    if (targetLight != null) {
                        return com.areslib.sequencer.tasks.SetIndicatorColorTask(targetLight, color)
                    }
                }
            }
        }

        return null
    }

    /**
     * Clears all registered commands.
     */
    fun clear() {
        registry.clear()
    }
}
