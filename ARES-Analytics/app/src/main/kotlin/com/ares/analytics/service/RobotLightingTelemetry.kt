package com.ares.analytics.service

/** Typed lighting kinds emitted by generated IO telemetry. */
enum class RobotLightingKind { INDICATOR, PRISM }

/** One accepted output value after the robot or simulator IO safety boundary. */
data class RobotLightingReading(
    val stableName: String,
    val kind: RobotLightingKind,
    val value: Double,
)

data class RobotLightingTelemetryState(
    val outputs: Map<String, RobotLightingReading> = emptyMap(),
) {
    val indicatorOutputs: Map<String, Double>
        get() = outputs.values.filter { it.kind == RobotLightingKind.INDICATOR }
            .associate { it.stableName to it.value }
    val prismOutputs: Map<String, Double>
        get() = outputs.values.filter { it.kind == RobotLightingKind.PRISM }
            .associate { it.stableName to it.value }
}

/** Decodes the canonical descriptor-owned lighting telemetry contract. */
fun robotLightingReading(topic: String, value: Double): RobotLightingReading? {
    if (!value.isFinite()) return null
    val segments = topic.split('/')
    if (segments.size != 5 || segments[0] != "Subsystems" || segments[2] != "AppliedOutputs") return null
    val kind = when (segments[4]) {
        "INDICATOR_LIGHT" -> RobotLightingKind.INDICATOR
        "PRISM_DRIVER" -> RobotLightingKind.PRISM
        else -> return null
    }
    val subsystemId = segments[1].takeIf(String::isNotBlank) ?: return null
    val hardwareId = segments[3].takeIf(String::isNotBlank) ?: return null
    val name = "$subsystemId/$hardwareId"
    return RobotLightingReading(name, kind, value)
}

fun robotLightingTelemetry(values: Map<String, Double>): RobotLightingTelemetryState =
    RobotLightingTelemetryState(
        values.entries.mapNotNull { (topic, value) -> robotLightingReading(topic, value) }
            .associateBy(RobotLightingReading::stableName),
    )
