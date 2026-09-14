package com.areslib.simulation

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/** Pre-registered, allocation-free value cell for one simulated actuator's accepted output. */
class SimAppliedOutputSignal internal constructor() {
    @Volatile
    var value: Double = 0.0
        private set

    fun publish(appliedOutput: Double) {
        value = if (appliedOutput.isFinite()) appliedOutput else 0.0
    }
}

/**
 * Process-local bridge from generated mock IO to descriptor-driven mechanism/field simulation.
 * Handles are registered at construction and updated without map lookup in the periodic path.
 * Models and IO own their handles; this registry does not retain abandoned simulation objects.
 */
object SimAppliedOutputRegistry {
    private data class SignalKey(val subsystemUid: String, val actuatorId: String)
    private class SignalReference(
        val key: SignalKey,
        signal: SimAppliedOutputSignal,
        queue: ReferenceQueue<SimAppliedOutputSignal>,
    ) : WeakReference<SimAppliedOutputSignal>(signal, queue)

    private val signals = ConcurrentHashMap<SignalKey, SignalReference>()
    private val collected = ReferenceQueue<SimAppliedOutputSignal>()

    fun register(subsystemUid: String, actuatorId: String): SimAppliedOutputSignal {
        require(subsystemUid.isNotBlank()) { "Subsystem UID is required" }
        require(actuatorId.isNotBlank()) { "Actuator ID is required" }
        drainCollectedSignals()
        val key = SignalKey(subsystemUid, actuatorId)
        while (true) {
            val previous = signals[key]
            previous?.get()?.let { return it }
            // Hold the new cell strongly until ownership transfers to the registering caller.
            val signal = SimAppliedOutputSignal()
            val reference = SignalReference(key, signal, collected)
            if (previous == null) {
                if (signals.putIfAbsent(key, reference) == null) return signal
            } else if (signals.replace(key, previous, reference)) return signal
        }
    }

    fun find(subsystemUid: String, actuatorId: String): SimAppliedOutputSignal? {
        drainCollectedSignals()
        return signals[SignalKey(subsystemUid, actuatorId)]?.get()
    }

    /**
     * Neutralizes live handles with publishers stopped, retaining model bindings created before IO.
     * This resets values, not handle identity; later writes through a live handle remain valid.
     */
    fun reset() {
        drainCollectedSignals()
        for ((key, reference) in signals) {
            val signal = reference.get()
            if (signal != null) signal.publish(0.0) else signals.remove(key, reference)
        }
    }

    private fun drainCollectedSignals() {
        while (true) {
            val reference = collected.poll() as? SignalReference ?: return
            // A late collection notification must not evict a newer signal for the same key.
            signals.remove(reference.key, reference)
        }
    }
}
