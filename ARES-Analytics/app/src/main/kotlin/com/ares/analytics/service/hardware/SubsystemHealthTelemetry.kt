package com.ares.analytics.service.hardware

import com.ares.analytics.shared.models.TelemetryFrame

/** User-facing health states derived only from generated `Subsystems/<id>/<signal>` telemetry. */
enum class SubsystemHealthStatus(val label: String) {
    HEALTHY("Ready"),
    INCOMPLETE("Waiting for health signals"),
    STALE("Telemetry stale"),
    OUTPUT_FAULT("Output fault latched"),
    HOMING_FAULT("Homing fault latched"),
    CONFIGURATION_FAULT("Configuration unhealthy"),
    FEEDBACK_INVALID("Feedback invalid"),
    CURRENT_INVALID("Current reading invalid"),
    NEEDS_HOMING("Homing required"),
    NEEDS_CALIBRATION("Calibration required"),
}

data class SubsystemHealthSnapshot(
    val subsystemId: String,
    val status: SubsystemHealthStatus,
    val issues: List<String>,
    val measurements: Map<String, Double>,
    val ageMs: Long,
)

/**
 * Accumulates generated subsystem telemetry without coupling the dashboard to a particular robot.
 * Receipt time is supplied by the caller because robot timestamps and desktop clocks are not
 * guaranteed to share an epoch.
 */
class SubsystemHealthAccumulator(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    private data class MutableSubsystemHealth(
        val signals: MutableMap<String, Double> = linkedMapOf(),
        var lastReceiptNs: Long = 0L,
    )

    private val subsystems = linkedMapOf<String, MutableSubsystemHealth>()

    /** Returns true when [frame] belongs to generated subsystem telemetry. */
    fun accept(frame: TelemetryFrame, receiptTimeNs: Long): Boolean {
        val normalized = frame.key.removePrefix("/")
        if (!normalized.startsWith(SUBSYSTEM_PREFIX)) return false
        val remainder = normalized.removePrefix(SUBSYSTEM_PREFIX)
        val separator = remainder.indexOf('/')
        if (separator <= 0 || separator == remainder.lastIndex) return false

        val subsystemId = remainder.substring(0, separator)
        val signal = remainder.substring(separator + 1)
        if ('/' in signal) return false
        val health = subsystems.getOrPut(subsystemId) { MutableSubsystemHealth() }
        health.signals[signal] = frame.value
        health.lastReceiptNs = receiptTimeNs
        return true
    }

    fun snapshots(nowNs: Long): List<SubsystemHealthSnapshot> = subsystems.map { (id, health) ->
        val ageMs = ((nowNs - health.lastReceiptNs).coerceAtLeast(0L) / NANOS_PER_MILLISECOND)
        val issues = buildList {
            if (health.isFalse("ConfigurationHealthy")) add("Check device names, ports, and configuration.")
            if (health.isFalse("FeedbackValid")) add("Sensor feedback is invalid or unavailable.")
            if (health.isFalse("CurrentReadingValid")) add("Current monitoring is unavailable or invalid.")
            if (health.isFalse("Homed")) add("Home the mechanism before commanding motion.")
            if (health.isFalse("Calibrated")) add("Complete the required calibration.")
            if (health.isTrue("HomingFaultLatched")) add("Homing failed; inspect the mechanism and recover through neutral.")
            if (health.isTrue("OutputFaultLatched")) add("An output write failed; motion remains latched off until neutral recovery.")
            if (ageMs > staleAfterMs) add("No subsystem telemetry received for ${ageMs} ms.")
            val missingSignals = REQUIRED_HEALTH_SIGNAL_NAMES - health.signals.keys
            if (missingSignals.isNotEmpty()) {
                add("Waiting for generated health signals: ${missingSignals.sorted().joinToString()}.")
            }
        }
        val status = when {
            ageMs > staleAfterMs -> SubsystemHealthStatus.STALE
            health.isTrue("OutputFaultLatched") -> SubsystemHealthStatus.OUTPUT_FAULT
            health.isTrue("HomingFaultLatched") -> SubsystemHealthStatus.HOMING_FAULT
            health.isFalse("ConfigurationHealthy") -> SubsystemHealthStatus.CONFIGURATION_FAULT
            health.isFalse("FeedbackValid") -> SubsystemHealthStatus.FEEDBACK_INVALID
            health.isFalse("CurrentReadingValid") -> SubsystemHealthStatus.CURRENT_INVALID
            health.isFalse("Homed") -> SubsystemHealthStatus.NEEDS_HOMING
            health.isFalse("Calibrated") -> SubsystemHealthStatus.NEEDS_CALIBRATION
            !health.signals.keys.containsAll(REQUIRED_HEALTH_SIGNAL_NAMES) -> SubsystemHealthStatus.INCOMPLETE
            else -> SubsystemHealthStatus.HEALTHY
        }
        SubsystemHealthSnapshot(
            subsystemId = id,
            status = status,
            issues = issues,
            measurements = health.signals.filterKeys { it !in HEALTH_SIGNAL_NAMES },
            ageMs = ageMs,
        )
    }.sortedWith(compareBy<SubsystemHealthSnapshot> { it.status == SubsystemHealthStatus.HEALTHY }.thenBy { it.subsystemId })

    private fun MutableSubsystemHealth.isTrue(key: String): Boolean = signals[key]?.let { it >= 0.5 } == true
    private fun MutableSubsystemHealth.isFalse(key: String): Boolean = signals[key]?.let { it < 0.5 } == true

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val SUBSYSTEM_PREFIX = "Subsystems/"
        private val HEALTH_SIGNAL_NAMES = setOf(
            "TelemetryHeartbeat",
            "FeedbackValid",
            "ConfigurationHealthy",
            "Homed",
            "HomingConditionMet",
            "HomingFaultLatched",
            "Calibrated",
            "CurrentReadingValid",
            "OutputFaultLatched",
        )
        private val REQUIRED_HEALTH_SIGNAL_NAMES = HEALTH_SIGNAL_NAMES - "HomingConditionMet"
    }
}
