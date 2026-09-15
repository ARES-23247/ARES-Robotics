package com.areslib.sequencer

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Stable primitive resource bits used to prevent conflicting autonomous tasks from running in
 * parallel. Generated subsystems use bits 16..47; season code may reserve bits 48..62.
 */
object TaskResources {
    const val NONE: Long = 0L
    const val DRIVE: Long = 1L shl 0
    const val INTAKE: Long = 1L shl 1
    const val FLYWHEEL: Long = 1L shl 2
    const val FEEDER: Long = 1L shl 3
    const val FLOOR: Long = 1L shl 4
    const val ELEVATOR: Long = 1L shl 5
    const val ARM: Long = 1L shl 6
    const val WRIST: Long = 1L shl 7
    const val CLIMBER: Long = 1L shl 8
    const val LIGHTING: Long = 1L shl 9
    const val SUPERSTRUCTURE_SHARED: Long = 1L shl 10

    fun generatedSubsystem(index: Int): Long {
        require(index in 0..31) { "Generated subsystem resource index must be in 0..31: $index" }
        return 1L shl (16 + index)
    }

    fun season(index: Int): Long {
        require(index in 0..14) { "Season resource index must be in 0..14: $index" }
        return 1L shl (48 + index)
    }

    internal fun describe(mask: Long): String {
        if (mask == NONE) return "none"
        val names = ArrayList<String>(4)
        appendName(names, mask, DRIVE, "drive")
        appendName(names, mask, INTAKE, "intake")
        appendName(names, mask, FLYWHEEL, "flywheel")
        appendName(names, mask, FEEDER, "feeder")
        appendName(names, mask, FLOOR, "floor")
        appendName(names, mask, ELEVATOR, "elevator")
        appendName(names, mask, ARM, "arm")
        appendName(names, mask, WRIST, "wrist")
        appendName(names, mask, CLIMBER, "climber")
        appendName(names, mask, LIGHTING, "lighting")
        appendName(names, mask, SUPERSTRUCTURE_SHARED, "superstructure-shared")
        val knownMask = (1L shl 11) - 1L
        val customMask = mask and knownMask.inv()
        if (customMask != 0L) names += "custom(0x${java.lang.Long.toUnsignedString(customMask, 16)})"
        return names.joinToString()
    }

    private fun appendName(names: MutableList<String>, mask: Long, bit: Long, name: String) {
        if (mask and bit != 0L) names += name
    }
}

/** Owned random-access membership and its construction-time resource union. */
internal class TaskGroupMembers(val tasks: List<Task>, val requiredResources: Long)

/** Construction-time resource and identity validation. Group updates do not repeat this traversal. */
internal object TaskResourceValidator {
    fun snapshot(groupName: String, tasks: List<Task>, parallel: Boolean): TaskGroupMembers {
        val owned = Collections.unmodifiableList(ArrayList(tasks))
        var claimed = TaskResources.NONE
        for (task in owned) {
            // The declared mask must remain stable for this task's lifetime. Read it once so
            // validation and the group's advertised mask use the same construction snapshot.
            val resources = task.requiredResources
            val overlap = claimed and resources
            require(!parallel || overlap == TaskResources.NONE) {
                "$groupName contains conflicting task '${task.name}' for resources: " +
                    TaskResources.describe(overlap)
            }
            claimed = claimed or resources
        }
        val seen = Collections.newSetFromMap(IdentityHashMap<Task, Boolean>())
        val pending = ArrayDeque<Task>(owned)
        while (pending.isNotEmpty()) {
            val task = pending.removeLast()
            require(seen.add(task)) { "$groupName repeats task instance '${task.name}' in its task tree" }
            // Built-in groups expose only their already-owned snapshots. Arbitrary custom tasks
            // remain responsible for their own internal children and ownership contracts.
            when (task) {
                is SequentialTaskGroup -> pending.addAll(task.tasks)
                is ParallelTaskGroup -> pending.addAll(task.tasks)
                is ParallelRaceGroup -> pending.addAll(task.tasks)
                is ParallelDeadlineGroup -> pending.addAll(task.tasks)
            }
        }
        return TaskGroupMembers(owned, claimed)
    }
}
