package com.areslib.pathing

import com.areslib.sequencer.Task

/**
 * Autonomous Marker Event Named Command Registry.
 *
 * Maps named string keys embedded within PathPlanner `.path` and `.auto` files
 * to executable subsystem [Task] factories or lambda actions.
 */
object NamedCommands {
    private val commands = mutableMapOf<String, (Long) -> Task>()

    /**
     * Registers a named command key with a timestamp-parameterized [Task] generator function.
     *
     * @param name Unique event trigger string key (e.g., `"intake_deploy"`).
     * @param taskFactory Lambda taking system timestamp ($ms$) and returning executable [Task].
     */
    fun registerCommand(name: String, taskFactory: (Long) -> Task) {
        commands[name] = taskFactory
    }

    /**
     * Registers a named command key with a static non-timestamped [Task].
     *
     * @param name Unique event trigger string key.
     * @param task Executable [Task] instance.
     */
    fun registerCommand(name: String, task: Task) {
        commands[name] = { task }
    }

    /**
     * Resolves a registered command by name. Returns null if not found.
     * Supports dynamic resolution for indicator light commands:
     * - `SetIndicatorColor_<color>` (Light 1 / "indicator")
     * - `SetSecondIndicatorColor_<color>` (Light 2 / "indicator2")
     * - `SetThirdIndicatorColor_<color>` (Light 3 / "indicator3")
     * - `SetFourthIndicatorColor_<color>` (Light 4 / "indicator4")
     * - `SetIndicatorColor_<lightName>_<color>` (Custom target light name)
     *
     * @param name Target command name.
     * @param timestampMs Execution timestamp in milliseconds ($ms$).
     * @return Resolved [Task], or `null` if unregistered.
     */
    fun getCommand(name: String, timestampMs: Long): Task? {
        val builder = commands[name]
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
        commands.clear()
    }
}

