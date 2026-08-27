package com.ares.analytics.ui.components.history

import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
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

    fun getDiagnosticValue(diag: Map<String, Double>, canonicalMotor: String, param: String): Double? {
        val namesToCheck = when (canonicalMotor) {
            "rl" -> listOf("rl", "bl")
            "rr" -> listOf("rr", "br")
            "fl" -> listOf("fl", "lf")
            "fr" -> listOf("fr", "rf")
            else -> listOf(canonicalMotor)
        }
        for (name in namesToCheck) {
            val value = diag["Diagnostics/SysId/Motors/$name/$param"]
            if (value != null) return value
        }
        return null
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
            RowDefinition("Battery Resistance (Ω)", "System Health", { _, summary, _ -> summary?.let { String.format("%.3f Ω", it.avgBatteryResistance) } ?: "N/A" }, { _, summary, _ -> summary?.avgBatteryResistance }, { it > 0.15 }),
            RowDefinition("Avg Loop Time (ms)", "System Health", { _, summary, _ -> summary?.let { String.format("%.2f ms", it.avgLoopTimeMs) } ?: "N/A" }, { _, summary, _ -> summary?.avgLoopTimeMs }, { it > 15.0 }),
            RowDefinition("P95 Loop Time (ms)", "System Health", { _, summary, _ -> summary?.let { String.format("%.2f ms", it.p95LoopTimeMs) } ?: "N/A" }, { _, summary, _ -> summary?.p95LoopTimeMs }, { it > 25.0 }),
            RowDefinition("Traction Loss (%)", "System Health", { _, _, diag -> diag["Diagnostics/Drive/TractionLoss"]?.let { String.format("%.1f%%", it * 100.0) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/Drive/TractionLoss"] }, { it > 0.20 }),
            RowDefinition("Comms Loss Count", "System Health", { _, _, diag -> diag["Diagnostics/System/CommsLosses"]?.toInt()?.toString() ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/CommsLosses"] }, { it > 0.0 }),
            RowDefinition("Loop Overrun Count", "System Health", { _, _, diag -> diag["Diagnostics/System/LoopOverruns"]?.toInt()?.toString() ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/LoopOverruns"] }, { it > 5.0 }),
            RowDefinition("Max CANbus Util (%)", "System Health", { _, _, diag -> diag["Diagnostics/System/MaxCANBusUtilization"]?.let { String.format("%.1f%%", it * 100.0) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/MaxCANBusUtilization"] }, { it > 0.90 }),
            RowDefinition("Total CANbus Errors", "System Health", { _, _, diag -> diag["Diagnostics/System/TotalCANBusErrors"]?.toInt()?.toString() ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/TotalCANBusErrors"] }, { it > 0.0 }),
            RowDefinition("CANbus Offs", "System Health", { _, _, diag -> diag["Diagnostics/System/CANBusOffs"]?.toInt()?.toString() ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/CANBusOffs"] }, { it > 0.0 }),
            RowDefinition("Max CANbus Latency (ms)", "System Health", { _, _, diag -> diag["Diagnostics/System/MaxCANBusLatencyMs"]?.let { String.format("%.1f ms", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/MaxCANBusLatencyMs"] }, { it > 20.0 }),
            RowDefinition("Power Brownouts", "System Health", { _, _, diag -> diag["Diagnostics/System/BrownoutCount"]?.toInt()?.toString() ?: "N/A" }, { _, _, diag -> diag["Diagnostics/System/BrownoutCount"] }, { it > 0.0 }),

            // Vision
            RowDefinition("Max EKF Drift (m)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.3fm", it.maxEkfDrift) } ?: "N/A" }, { _, summary, _ -> summary?.maxEkfDrift }, { it > 0.10 }),
            RowDefinition("Avg Cross-Track Error (m)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.3fm", it.avgCrossTrackError) } ?: "N/A" }, { _, summary, _ -> summary?.avgCrossTrackError }, { it > 0.10 }),
            RowDefinition("Vision Latency (ms)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.1f ms", it.avgVisionLatencyMs) } ?: "N/A" }, { _, summary, _ -> summary?.avgVisionLatencyMs }, { it > 100.0 }),
            RowDefinition("Vision Acceptance (%)", "Vision & Localization", { _, summary, _ -> summary?.let { String.format("%.1f%%", it.visionAcceptanceRate * 100.0) } ?: "N/A" }, { _, summary, _ -> summary?.visionAcceptanceRate }, { it < 0.60 }),

            // Linear SysId
            RowDefinition("Linear kS (V)", "Drivetrain SysId (Linear)", { _, _, diag -> diag["Diagnostics/SysId/kS"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/kS"] }),
            RowDefinition("Linear kV (V/m/s)", "Drivetrain SysId (Linear)", { _, _, diag -> diag["Diagnostics/SysId/kV"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/kV"] }),
            RowDefinition("Linear kA (V/m/s²)", "Drivetrain SysId (Linear)", { _, _, diag -> diag["Diagnostics/SysId/kA"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/kA"] }),
            RowDefinition("Linear R²", "Drivetrain SysId (Linear)", { _, _, diag -> diag["Diagnostics/SysId/R2"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/R2"] }, { it < 0.70 }),
            RowDefinition("Linear ADRC b0", "Drivetrain SysId (Linear)", { _, _, diag -> diag["Diagnostics/SysId/ADRC_b0"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/ADRC_b0"] }),

            // Angular SysId
            RowDefinition("Angular kS (V)", "Drivetrain SysId (Angular)", { _, _, diag -> diag["Diagnostics/SysId/Angular/kS"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/Angular/kS"] }),
            RowDefinition("Angular kV (V/rad/s)", "Drivetrain SysId (Angular)", { _, _, diag -> diag["Diagnostics/SysId/Angular/kV"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/Angular/kV"] }),
            RowDefinition("Angular kA (V/rad/s²)", "Drivetrain SysId (Angular)", { _, _, diag -> diag["Diagnostics/SysId/Angular/kA"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/Angular/kA"] }),
            RowDefinition("Angular R²", "Drivetrain SysId (Angular)", { _, _, diag -> diag["Diagnostics/SysId/Angular/R2"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/Angular/R2"] }, { it < 0.70 }),
            RowDefinition("Angular ADRC b0", "Drivetrain SysId (Angular)", { _, _, diag -> diag["Diagnostics/SysId/Angular/ADRC_b0"]?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> diag["Diagnostics/SysId/Angular/ADRC_b0"] }),

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

    fun buildMotorSysIdRows(allMotorNames: List<String>): List<RowDefinition> {
        return allMotorNames.flatMap { motor ->
            listOf(
                RowDefinition("Motor [$motor] kS", "Subsystem Motors ($motor)", { _, _, diag -> getDiagnosticValue(diag, motor, "kS")?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> getDiagnosticValue(diag, motor, "kS") }),
                RowDefinition("Motor [$motor] kV", "Subsystem Motors ($motor)", { _, _, diag -> getDiagnosticValue(diag, motor, "kV")?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> getDiagnosticValue(diag, motor, "kV") }),
                RowDefinition("Motor [$motor] kA", "Subsystem Motors ($motor)", { _, _, diag -> getDiagnosticValue(diag, motor, "kA")?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> getDiagnosticValue(diag, motor, "kA") }),
                RowDefinition("Motor [$motor] kG (Gravity)", "Subsystem Motors ($motor)", { _, _, diag -> getDiagnosticValue(diag, motor, "kG")?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> getDiagnosticValue(diag, motor, "kG") }),
                RowDefinition("Motor [$motor] ADRC b0", "Subsystem Motors ($motor)", { _, _, diag -> getDiagnosticValue(diag, motor, "ADRC_b0")?.let { String.format("%.3f", it) } ?: "N/A" }, { _, _, diag -> getDiagnosticValue(diag, motor, "ADRC_b0") })
            )
        }
    }
}
