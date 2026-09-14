package com.ares.analytics.ui.components.history

import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.ui.screens.RowDefinition

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

        ) + sysIdDiagnosticRows() + listOf(
            // Driver Jitter
            RowDefinition("Driver Rec. Exponent", "Driver Profiles", { _, _, diag -> diag["Diagnostics/Driver/RecommendedExponent"]?.let { String.format("%.2f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/Driver/RecommendedExponent"] }),
            RowDefinition("Driver Rec. Slew Rate", "Driver Profiles", { _, _, diag -> diag["Diagnostics/Driver/RecommendedSlewRate"]?.let { if (it >= 999.0) "None" else String.format("%.1f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/Driver/RecommendedSlewRate"] }),
            RowDefinition("Jitter Present", "Driver Profiles", { _, _, diag -> diag["Diagnostics/Driver/JitterPresent"]?.let { if (it > 0.5) "Yes" else "No" } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/Driver/JitterPresent"] }, { it > 0.5 }),
            RowDefinition("Peak Jitter Freq (Hz)", "Driver Profiles", { _, _, diag -> diag["Diagnostics/Driver/PeakJitterFrequency"]?.let { String.format("%.1f Hz", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/Driver/PeakJitterFrequency"] })
        )
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
