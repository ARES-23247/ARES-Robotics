package com.areslib.simulation

/** Stable health classification shared by physical-parity device models. */
enum class SimulationDeviceHealth {
    HEALTHY,
    STALE,
    INVALID,
    DISCONNECTED,
    BUS_FAULT,
    WRITE_FAULT,
}

/**
 * Preallocated mutable observation populated once per simulation tick.
 *
 * Device models own one instance and consumers read it after [SimulationDeviceModel.sampleInto].
 * This mirrors the physical cached-read contract without allocating boxed maps on the hot path.
 */
class SimulationDeviceObservation {
    var value: Double = 0.0
    var sampleTimestampNanos: Long = -1L
    var sequence: Long = 0L
    var health: SimulationDeviceHealth = SimulationDeviceHealth.DISCONNECTED
    var configurationHealthy: Boolean = false
    var homed: Boolean = false
    var targetVisible: Boolean = false

    val valid: Boolean get() = health == SimulationDeviceHealth.HEALTHY

    fun ageNanos(nowNanos: Long): Long {
        if (sampleTimestampNanos < 0L || nowNanos < sampleTimestampNanos) return Long.MAX_VALUE
        return nowNanos - sampleTimestampNanos
    }
}

/** Target-specific simulated device with cached reads and fail-closed writes. */
interface SimulationDeviceModel {
    val deviceId: String

    /** Samples this device once for the current loop into caller-owned storage. */
    fun sampleInto(timestampNanos: Long, observation: SimulationDeviceObservation)

    /** Returns false when the adapter rejects a command and has already applied safe neutral. */
    fun write(command: Double, timestampNanos: Long): Boolean

    /** Applies safe neutral regardless of prior command state. */
    fun neutralize(timestampNanos: Long): Boolean

    fun close()
}
