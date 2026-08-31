package com.areslib.simulation

import com.areslib.util.RobotClock

/** One pre-registered phase in a deterministic simulator tick. */
public fun interface SimulationTickParticipant {
    /** Invoked in registration order with the same immutable tick timestamp. */
    public fun tick(timestampNanos: Long)
}

/**
 * Fixed-step scheduler shared by league-specific simulator products.
 *
 * Participants are frozen at construction so the periodic path performs no collection mutation.
 * The scheduler advances [RobotClock] before each tick, which makes reducers, replay, fault
 * injection, and telemetry observe one deterministic timeline.
 */
public class DeterministicSimulationScheduler(
    public val stepNanos: Long,
    participants: Collection<SimulationTickParticipant>,
    startTimeNanos: Long = 0L,
) {
    private val orderedParticipants = participants.toTypedArray()

    public var timestampNanos: Long = startTimeNanos
        private set

    public var tickSequence: Long = 0L
        private set

    init {
        require(stepNanos > 0L) { "Simulation step must be positive" }
        require(startTimeNanos >= 0L) { "Simulation start time must be non-negative" }
        require(startTimeNanos % 1_000_000L == 0L) {
            "Simulation start time must align to RobotClock millisecond precision"
        }
        require(stepNanos % 1_000_000L == 0L) {
            "Simulation step must align to RobotClock millisecond precision"
        }
    }

    public fun step(): Unit {
        timestampNanos = Math.addExact(timestampNanos, stepNanos)
        tickSequence = Math.addExact(tickSequence, 1L)
        RobotClock.useMockTime(timestampNanos / 1_000_000L)
        var index = 0
        while (index < orderedParticipants.size) {
            orderedParticipants[index].tick(timestampNanos)
            index++
        }
    }

    public fun step(count: Int): Unit {
        require(count >= 0) { "Simulation step count must be non-negative" }
        repeat(count) { step() }
    }
}
