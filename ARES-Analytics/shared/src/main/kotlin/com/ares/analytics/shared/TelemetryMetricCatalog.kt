package com.ares.analytics.shared

import com.areslib.telemetry.TelemetryTopicNormalizer

/** Physical dimension attached to a canonical telemetry metric. */
enum class TelemetryUnit {
    VOLT,
    AMPERE,
    MILLISECOND,
    METER,
    RADIAN,
    UNITLESS
}

/**
 * Semantic definition for a signal that may be published under multiple transport keys.
 *
 * The catalog deliberately does not rewrite aliases into one storage key: two sources can
 * represent the same physical quantity while still carrying different provenance. Callers
 * use [keys] when querying and retain the actual source key on every stored sample.
 */
data class TelemetryMetric(
    val id: String,
    val canonicalKey: String,
    val aliases: Set<String> = emptySet(),
    val unit: TelemetryUnit,
    val expectedRateHz: ClosedFloatingPointRange<Double>? = null
) {
    val keys: Set<String> = buildSet {
        add(TelemetryMetricCatalog.normalizeTopic(canonicalKey))
        aliases.mapTo(this, TelemetryMetricCatalog::normalizeTopic)
    }
}

/** Single source of truth for dashboard analytics topic names and aliases. */
object TelemetryMetricCatalog {
    val BATTERY_VOLTAGE = TelemetryMetric(
        id = "battery_voltage",
        canonicalKey = "Robot/BatteryVoltage",
        aliases = setOf("Battery/Voltage", "DSLog/BatteryVoltage"),
        unit = TelemetryUnit.VOLT,
        expectedRateHz = 1.0..100.0
    )

    val LOOP_TIME = TelemetryMetric(
        id = "robot_loop_time",
        canonicalKey = "Robot/LoopTimeMs",
        aliases = setOf("Profiling/LoopTime_ms", "System/LoopTimeMs", "LoopTimeMs"),
        unit = TelemetryUnit.MILLISECOND,
        expectedRateHz = 20.0..200.0
    )

    val DRIVE_VOLTAGE = TelemetryMetric(
        id = "drive_applied_voltage",
        canonicalKey = "Drive/Voltage",
        unit = TelemetryUnit.VOLT,
        expectedRateHz = 20.0..200.0
    )

    val DRIVE_VELOCITY = TelemetryMetric(
        id = "drive_velocity",
        canonicalKey = "Drive/Velocity",
        unit = TelemetryUnit.UNITLESS,
        expectedRateHz = 20.0..200.0
    )

    val DRIVE_ACCELERATION = TelemetryMetric(
        id = "drive_acceleration",
        canonicalKey = "Drive/Acceleration",
        unit = TelemetryUnit.UNITLESS,
        expectedRateHz = 20.0..200.0
    )

    val GAMEPAD_LEFT_X = TelemetryMetric(
        id = "gamepad_left_x",
        canonicalKey = "Gamepad1/LeftX",
        unit = TelemetryUnit.UNITLESS,
        expectedRateHz = 20.0..100.0
    )

    val GAMEPAD_LEFT_Y = TelemetryMetric(
        id = "gamepad_left_y",
        canonicalKey = "Gamepad1/LeftY",
        unit = TelemetryUnit.UNITLESS,
        expectedRateHz = 20.0..100.0
    )

    val definitions: List<TelemetryMetric> = listOf(
        BATTERY_VOLTAGE,
        LOOP_TIME,
        DRIVE_VOLTAGE,
        DRIVE_VELOCITY,
        DRIVE_ACCELERATION,
        GAMEPAD_LEFT_X,
        GAMEPAD_LEFT_Y
    )

    private val byKey: Map<String, TelemetryMetric> = buildMap {
        definitions.forEach { metric -> metric.keys.forEach { key -> putIfAbsent(key, metric) } }
    }

    fun normalizeTopic(key: String): String =
        TelemetryTopicNormalizer.normalizeTopic(key.trim())

    fun metricFor(key: String): TelemetryMetric? = byKey[normalizeTopic(key)]
}
