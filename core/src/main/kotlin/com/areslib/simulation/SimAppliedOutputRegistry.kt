package com.areslib.simulation

import java.util.concurrent.ConcurrentHashMap

/** Pre-registered, allocation-free value cell for one simulated actuator's accepted output. */
class SimAppliedOutputSignal internal constructor() {
    @Volatile
    var value: Double = 0.0
        private set

    fun publish(appliedOutput: Double) {
        value = appliedOutput.takeIf(Double::isFinite) ?: 0.0
    }
}

/**
 * Process-local bridge from generated mock IO to descriptor-driven mechanism/field simulation.
 * Handles are registered at construction and updated without map lookup in the periodic path.
 */
object SimAppliedOutputRegistry {
    private val signals = ConcurrentHashMap<String, SimAppliedOutputSignal>()

    fun register(subsystemUid: String, actuatorId: String): SimAppliedOutputSignal {
        require(subsystemUid.isNotBlank()) { "Subsystem UID is required" }
        require(actuatorId.isNotBlank()) { "Actuator ID is required" }
        return signals.computeIfAbsent(key(subsystemUid, actuatorId)) { SimAppliedOutputSignal() }
    }

    fun find(subsystemUid: String, actuatorId: String): SimAppliedOutputSignal? =
        signals[key(subsystemUid, actuatorId)]

    fun reset() {
        signals.values.forEach { it.publish(0.0) }
    }

    private fun key(subsystemUid: String, actuatorId: String): String = "$subsystemUid/$actuatorId"
}
