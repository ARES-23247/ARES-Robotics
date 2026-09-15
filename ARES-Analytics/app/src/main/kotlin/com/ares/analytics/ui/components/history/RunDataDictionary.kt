package com.ares.analytics.ui.components.history

import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.screens.RowDefinition
import java.util.Locale
import kotlin.math.floor

object RunDataDictionary {

    fun canonicalizeMotorName(name: String): String {
        return when (name.lowercase()) {
            "bl" -> "rl"
            "br" -> "rr"
            "lf" -> "fl"
            "rf" -> "fr"
            else -> name
        }
    }

    fun getMotorCurrentAverage(summary: SessionSummary?, canonicalMotor: String): Double? {
        if (summary == null) return null
        val namesToCheck = when (canonicalMotor) {
            "rl" -> listOf("rl", "bl")
            "rr" -> listOf("rr", "br")
            "fl" -> listOf("fl", "lf")
            "fr" -> listOf("fr", "rf")
            else -> listOf(canonicalMotor)
        }
        for (name in namesToCheck) {
            val value = summary.motorCurrentAverages[name]
            if (value != null) return value
        }
        return null
    }

    fun buildBaseRowDefinitions(): List<RowDefinition> {
        return listOf(
            RowDefinition("Match Number", "Session Info", { session, _, _ -> session.matchNumber?.toString() ?: "N/A" }),
            RowDefinition("Alliance", "Session Info", { session, _, _ -> session.allianceColor ?: "N/A" }),
            RowDefinition("Tags", "Session Info", { session, _, _ -> session.tags.joinToString(", ") }),
            RowDefinition("Duration (s)", "Session Info", { _, summary, _ -> summary?.let { String.format("%.1fs", it.durationMs / 1000.0) } ?: "N/A" }, { _, summary, _ -> summary?.durationMs?.toDouble()?.div(1000.0) }),

            // Health
            RowDefinition("Min Battery Voltage (V)", "System Health", { _, summary, _ -> summary?.let { String.format("%.2fV", it.minBatteryVoltage) } ?: "N/A" }, { _, summary, _ -> summary?.minBatteryVoltage }, { it < 9.5 }),
            RowDefinition("Battery Resistance (Î©)", "System Health", { _, summary, _ -> summary?.let { String.format("%.3f Î©", it.avgBatteryResistance) } ?: "N/A" }, { _, summary, _ -> summary?.avgBatteryResistance }, { it > 0.15 }),
            RowDefinition("Avg Loop Time (ms)", "System Health", { _, summary, _ -> summary?.let { String.format("%.2f ms", it.avgLoopTimeMs) } ?: "N/A" }, { _, summary, _ -> summary?.avgLoopTimeMs }, { it > 15.0 }),
            RowDefinition("P95 Loop Time (ms)", "System Health", { _, summary, _ -> summary?.let { String.format("%.2f ms", it.p95LoopTimeMs) } ?: "N/A" }, { _, summary, _ -> summary?.p95LoopTimeMs }, { it > 25.0 }),
        ) + healthDiagnosticRows() + listOf(
            // Vision
            RowDefinition("Max EKF Drift (m)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.3fm", it.maxEkfDrift) } ?: "N/A" }, { _, summary, _ -> summary?.maxEkfDrift }, { it > 0.10 }),
            RowDefinition("Avg Cross-Track Error (m)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.3fm", it.avgCrossTrackError) } ?: "N/A" }, { _, summary, _ -> summary?.avgCrossTrackError }, { it > 0.10 }),
            RowDefinition("Vision Latency (ms)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.1f ms", it.avgVisionLatencyMs) } ?: "N/A" }, { _, summary, _ -> summary?.avgVisionLatencyMs }, { it > 100.0 }),
            RowDefinition("Vision Acceptance (%)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.1f%%", it.visionAcceptanceRate * 100.0) } ?: "N/A" }, { _, summary, _ -> summary?.visionAcceptanceRate }, { it < 0.60 }),

        ) + sysIdDiagnosticRows() + driverDiagnosticRows()
    }

    fun buildMotorCurrentRows(allMotorNames: List<String>): List<RowDefinition> {
        return allMotorNames.map { motor ->
            RowDefinition(
                label = "Motor [$motor] Avg Current",
                category = "Motor Current Draw",
                getValue = { _, summary, _ ->
                    getMotorCurrentAverage(summary, motor)?.let { String.format("%.2f A", it) } ?: "N/A"
                },
                getNumericValue = { _, summary, _ ->
                    getMotorCurrentAverage(summary, motor)
                }
            )
        }
    }

    fun buildMotorSysIdRows(allMotorNames: List<String>): List<RowDefinition> = motorSysIdRows(allMotorNames)
}

// --- Health Diagnostics Rows ---
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

// --- SysId Diagnostics Rows ---
/** Fits retain source units; neither a good R² nor reciprocal kA is approval to apply tuning. */
internal fun sysIdDiagnosticRows(): List<RowDefinition> =
    fitRows("Diagnostics/SysId", "Drive", "Recorded Drive Fit") +
        fitRows("Diagnostics/SysId/Angular", "Angular", "Recorded Angular Fit")

internal fun motorSysIdRows(motors: List<String>): List<RowDefinition> = motors.distinct().flatMap { motor ->
    fitRows("Diagnostics/SysId/Motors/$motor", "Motor [$motor]", "Recorded Motor Fit ($motor)")
}

internal fun sysIdMotorNames(diagnostics: Collection<Map<String, Double>>): List<String> = diagnostics.asSequence()
    .flatMap { it.keys.asSequence() }.mapNotNull { key -> MOTOR_FIT_KEY.matchEntire(key)?.groupValues?.get(1) }
    .distinct().sorted().toList()

private val MOTOR_FIT_KEY = Regex("^Diagnostics/SysId/Motors/([^/]+)/(kS|kV|kA|R2|InverseKA|FitSamples)$")

private fun fitRows(prefix: String, label: String, category: String): List<RowDefinition> {
    fun value(diagnostics: Map<String, Double>, metric: String): Double? {
        val samples = diagnostics["$prefix/FitSamples"] ?: return null
        if (!samples.isFinite() || samples < 10 || samples > 9_007_199_254_740_991.0 || samples != kotlin.math.floor(samples)) return null
        val result = diagnostics["$prefix/$metric"]?.takeIf { it.isFinite() } ?: return null
        if (metric == "R2" && result !in 0.0..1.0) return null
        if (metric == "InverseKA" && result <= 0.0) return null
        return result
    }
    return listOf("kS" to "kS (V)", "kV" to "kV (V/native speed)", "kA" to "kA (V/native acceleration)",
        "R2" to "R²", "InverseKA" to "Inverse kA", "FitSamples" to "Fit samples").map { (metric, title) ->
        RowDefinition("$label $title", category,
            getValue = { _, _, diagnostics -> value(diagnostics, metric)?.let {
                String.format(Locale.ROOT, if (metric == "FitSamples") "%.0f" else "%.3g", it)
            } ?: "N/A" },
            getNumericValue = { _, _, diagnostics -> value(diagnostics, metric) },
            isAnomaly = { metric == "R2" && it.isFinite() && it in 0.0..1.0 && it < 0.7 },
        )
    }
}

// --- Driver Diagnostics Rows ---
/** Current source-coverage evidence is required; old automatic tuning prescriptions are retired. */
internal fun driverDiagnosticRows(): List<RowDefinition> {
    fun axes(values: Map<String, Double>): Pair<Int, Int>? {
        val requested = values["Diagnostics/Driver/RequestedAxes"] ?: return null
        val analyzed = values["Diagnostics/Driver/AnalyzedAxes"] ?: return null
        if (requested !in 1.0..2.0 || requested % 1.0 != 0.0 || analyzed !in 0.0..requested || analyzed % 1.0 != 0.0) return null
        return analyzed.toInt() to requested.toInt()
    }
    fun crossed(values: Map<String, Double>): Double? {
        val (analyzed, requested) = axes(values) ?: return null
        if (analyzed == 0) return null
        return values["Diagnostics/Driver/JitterPresent"]?.takeIf { it == 1.0 || (it == 0.0 && analyzed == requested) }
    }
    fun peak(values: Map<String, Double>): Double? {
        if ((axes(values)?.first ?: 0) == 0) return null
        return values["Diagnostics/Driver/PeakJitterFrequency"]?.takeIf { it.isFinite() && it in 8.0..12.0 }
    }
    return listOf(
        RowDefinition("Analyzed joystick axes", "Driver Observations", { _, _, d -> axes(d)?.let { "${it.first} / ${it.second}" } ?: "N/A" }),
        RowDefinition("8-12 Hz threshold crossed", "Driver Observations", { _, _, d -> crossed(d)?.let { if (it == 1.0) "Yes" else "No" } ?: "N/A" },
            { _, _, d -> crossed(d) }, { it == 1.0 }),
        RowDefinition("Observed band peak (Hz)", "Driver Observations", { _, _, d -> peak(d)?.let { String.format(Locale.ROOT, "%.2f Hz", it) } ?: "N/A" },
            { _, _, d -> peak(d) }),
    )
}
