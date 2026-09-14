package com.ares.analytics.ui.components.history

import com.ares.analytics.ui.screens.RowDefinition
import java.util.Locale
import kotlin.math.floor

/** Units and labels describe recorded observations; missing values never imply health. */
internal fun healthDiagnosticRows(): List<RowDefinition> = listOf(
    healthCountRow("Recording Gaps >1s", "RecordingGapsOver1s", anomaly = { it > 0 }),
    healthCountRow("Recorded Loop Samples >40ms", "LoopSamplesOver40Ms", anomaly = { it > 5 }),
    healthCountRow("Recorded Loop Samples", "LoopSamples"),
    healthRow("Max CANbus Util (%)", "MaxCANBusUtilization", { it <= 1 },
        { String.format(Locale.ROOT, "%.1f%%", it * 100) }, { it >= 0.90 }),
    healthCountRow("Peak CAN Error Counter", "PeakCANErrorCounter", anomaly = { it > 0 }),
    healthCountRow("CAN Bus-Off Increments", "CANBusOffIncrements", anomaly = { it > 0 }),
    healthRow("Max CANbus Latency (ms)", "MaxCANBusLatencyMs", { true },
        { String.format(Locale.ROOT, "%.1f ms", it) }, { it > 20 }),
    healthCountRow("Brownout Guard Trip Increments", "BrownoutGuardTripIncrements", anomaly = { it > 0 }),
    healthRow("Motor Fault Flag Observed", "MotorFaultObserved", { it == 0.0 || it == 1.0 },
        { if (it == 1.0) "Yes" else "No" }, { it == 1.0 }),
)

private fun healthCountRow(label: String, metric: String, anomaly: (Double) -> Boolean = { false }): RowDefinition =
    healthRow(label, metric, { it <= 9_007_199_254_740_991.0 && floor(it) == it },
        { String.format(Locale.ROOT, "%.0f", it) }, anomaly)

private fun healthRow(
    label: String, metric: String, accepts: (Double) -> Boolean,
    format: (Double) -> String, anomaly: (Double) -> Boolean,
): RowDefinition {
    val key = "Diagnostics/System/$metric"
    fun valid(value: Double) = value.isFinite() && value >= 0 && accepts(value)
    fun value(diagnostics: Map<String, Double>) = diagnostics[key]?.takeIf(::valid)
    return RowDefinition(
        label, "System Health",
        getValue = { _, _, diagnostics -> value(diagnostics)?.let(format) ?: "N/A" },
        getNumericValue = { _, _, diagnostics -> value(diagnostics) },
        isAnomaly = { valid(it) && anomaly(it) },
    )
}
