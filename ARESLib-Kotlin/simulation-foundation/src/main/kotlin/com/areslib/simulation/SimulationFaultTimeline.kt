package com.areslib.simulation

import com.areslib.util.RobotClock

/** Fault classes shared by fault-injection labs without prescribing a league-specific device API. */
enum class SimulationFaultKind {
    STALE_INPUT,
    INVALID_INPUT,
    FROZEN_INPUT,
    DEVICE_DISCONNECTED,
    BUS_DISCONNECTED,
    WRITE_REJECTED,
    BROWNOUT,
}

/**
 * One deterministic fault interval. [targetId] is a canonical drivetrain component, subsystem
 * hardware, bus, or power-source ID; adapters decide which IDs they own and must reject unknowns.
 */
data class SimulationFaultCommand(
    val commandId: String,
    val targetId: String,
    val kind: SimulationFaultKind,
    val startsAtMillis: Long,
    val endsAtMillis: Long? = null,
) {
    init {
        require(commandId.isNotBlank()) { "Fault command ID is required" }
        require(targetId.isNotBlank()) { "Fault target ID is required" }
        require(startsAtMillis >= 0L) { "Fault start must be non-negative" }
        require(endsAtMillis == null || endsAtMillis > startsAtMillis) {
            "Fault end must be later than its start"
        }
    }

    fun isActiveAt(timeMillis: Long): Boolean =
        timeMillis >= startsAtMillis && (endsAtMillis == null || timeMillis < endsAtMillis)
}

/**
 * Pre-sorted, immutable fault schedule. The indexed hot-path lookup creates no collections and uses
 * [RobotClock], keeping live simulation and deterministic replay on the same time source.
 */
class SimulationFaultTimeline(commands: Collection<SimulationFaultCommand>) {
    private val scheduled = commands.sortedWith(
        compareBy<SimulationFaultCommand> { it.startsAtMillis }
            .thenBy { it.targetId }
            .thenBy { it.kind.ordinal }
            .thenBy { it.commandId },
    ).toTypedArray()

    init {
        require(scheduled.map { it.commandId }.distinct().size == scheduled.size) {
            "Fault command IDs must be unique"
        }
    }

    val size: Int get() = scheduled.size

    /** First active fault in explicit stable priority order, or null when this target is healthy. */
    fun activeFault(targetId: String, timeMillis: Long = RobotClock.currentTimeMillis()): SimulationFaultCommand? {
        var index = 0
        while (index < scheduled.size) {
            val command = scheduled[index]
            if (command.startsAtMillis > timeMillis) return null
            if (command.targetId == targetId && command.isActiveAt(timeMillis)) return command
            index++
        }
        return null
    }
}
