package com.areslib.simulation

/** Stable health classification shared by physical-parity device models. */
public enum class SimulationDeviceHealth {
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
public class SimulationDeviceObservation {
    public var value: Double = 0.0
    public var sampleTimestampNanos: Long = -1L
    public var sequence: Long = 0L
    public var health: SimulationDeviceHealth = SimulationDeviceHealth.DISCONNECTED
    public var configurationHealthy: Boolean = false
    public var homed: Boolean = false
    public var targetVisible: Boolean = false

    public val valid: Boolean get() = health == SimulationDeviceHealth.HEALTHY

    public fun ageNanos(nowNanos: Long): Long {
        if (sampleTimestampNanos < 0L || nowNanos < sampleTimestampNanos) return Long.MAX_VALUE
        return nowNanos - sampleTimestampNanos
    }
}

/** Target-specific simulated device with cached reads and fail-closed writes. */
public interface SimulationDeviceModel {
    public val deviceId: String

    /** Samples this device once for the current loop into caller-owned storage. */
    public fun sampleInto(timestampNanos: Long, observation: SimulationDeviceObservation)

    /** Returns false when the adapter rejects a command and has already applied safe neutral. */
    public fun write(command: Double, timestampNanos: Long): Boolean

    /** Applies safe neutral regardless of prior command state. */
    public fun neutralize(timestampNanos: Long): Boolean

    public fun close()
}
