package com.ares.analytics.ui.components.history

import com.ares.analytics.ui.screens.RowDefinition
import java.util.Locale

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
