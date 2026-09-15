package com.ares.analytics.ui.components.history

import com.ares.analytics.ui.screens.RowDefinition
import java.util.Locale

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
