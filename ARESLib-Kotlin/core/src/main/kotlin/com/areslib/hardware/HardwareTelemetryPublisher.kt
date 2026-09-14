package com.areslib.hardware

import com.areslib.telemetry.ITelemetry
import java.util.concurrent.atomic.AtomicLong

/** Registration-owned telemetry snapshots, with allocation-free publication between registrations. */
internal class HardwareTelemetryPublisher {
    private class TelemetryEntry(val device: LoggableDevice, val prefix: String, val heartbeatTopic: String)
    // Written under the registration monitor; each publish pass retains one coherent array.
    private val telemetryEntries = ArrayList<TelemetryEntry>()
    @Volatile private var telemetrySnapshot = emptyArray<TelemetryEntry>()
    private val telemetryPublishSequence = AtomicLong(0L)

    // Stage and commit run under HardwareRegistry's registration monitor. Publication is lock-free.
    fun stage(index: Int?, device: LoggableDevice, telemetryPrefix: String) {
        val heartbeatTopic = if (telemetryPrefix.startsWith("Subsystems/")) {
            "$telemetryPrefix/TelemetryHeartbeat"
        } else {
            ""
        }
        val entry = TelemetryEntry(device, telemetryPrefix, heartbeatTopic)
        if (index == null) telemetryEntries.add(entry) else telemetryEntries[index] = entry
    }

    fun commitRegistration() {
        telemetrySnapshot = telemetryEntries.toTypedArray()
    }

    fun clearRegistrations() {
        telemetryEntries.clear()
        telemetrySnapshot = emptyArray()
    }

    fun resetSequence() {
        telemetryPublishSequence.set(0L)
    }

    fun publishAll(telemetry: ITelemetry) {
        val snapshot = telemetrySnapshot
        val publishSequence = nextTelemetrySequence()
        for (i in snapshot.indices) {
            val entry = snapshot[i]
            try {
                entry.device.logTelemetry(telemetry, entry.prefix)
                if (entry.heartbeatTopic.isNotEmpty()) {
                    telemetry.putNumber(entry.heartbeatTopic, publishSequence)
                }
            } catch (_: Throwable) {
                // Diagnostics are best effort; a failed producer must not hide healthy successors.
            }
        }
    }

    private fun nextTelemetrySequence(): Double {
        while (true) {
            val current = telemetryPublishSequence.get()
            val next = if (current >= 0L && current < 9_007_199_254_740_991L) current + 1L else 1L
            if (telemetryPublishSequence.compareAndSet(current, next)) return next.toDouble()
        }
    }
}
