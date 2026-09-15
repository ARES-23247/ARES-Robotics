package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.CalculatedSummary
import com.ares.analytics.shared.models.TelemetryFrame

/** Three-term recorded-data fits, with explicit source provenance; no inferred mechanism geometry. */
internal class SummarySysIdDiagnostics(
    frames: List<TelemetryFrame>, private val sessionId: String,
    private val analyze: (List<AlignedDataRow>) -> CalculatedSummary,
) {
    private val sources = frames.asSequence().filter { it.sessionId == sessionId }.groupBy { it.key.trimStart('/') }
    private fun source(keys: Iterable<String>) = keys.firstNotNullOfOrNull { sources[it] }

    fun calculate(): List<AnalysisDiagnostic> = buildList {
        fun fit(prefix: String, voltage: List<TelemetryFrame>?, velocity: List<TelemetryFrame>?, acceleration: List<TelemetryFrame>?, minimumR2: Double) {
            if (voltage == null || velocity == null) return
            val rows = RecordedSysIdInputs.align(voltage, velocity, acceleration)
            if (rows.size < 10) return
            val result = analyze(rows)
            if (!result.rSquared.isFinite() || result.rSquared <= minimumR2 || result.rSquared > 1.0 ||
                listOf(result.kS, result.kV, result.kA).any { !it.isFinite() }) return
            fun number(key: String, value: Double) { add(AnalysisDiagnostic(sessionId, "$prefix/$key", value)) }
            fun text(key: String, value: String) { add(AnalysisDiagnostic(sessionId, "$prefix/$key", 0.0, value)) }
            number("kS", result.kS); number("kV", result.kV); number("kA", result.kA); number("R2", result.rSquared)
            if (result.kA > 1e-6) {
                val inverse = 1.0 / result.kA
                if (inverse.isFinite()) number("InverseKA", inverse)
            }
            number("FitSamples", rows.size.toDouble())
            text("VoltageSource", voltage.first().key.trimStart('/'))
            text("VelocitySource", velocity.first().key.trimStart('/'))
            text("AccelerationSource", acceleration?.first()?.key?.trimStart('/') ?: "Backward difference of selected velocity; maximum gap 50 ms")
            text("Model", "V = kS*sign(v) + kV*v + kA*a; recorded source units and polarity; no gravity model or tuning approval")
        }
        fit("Diagnostics/SysId", source(TelemetryMetricCatalog.DRIVE_VOLTAGE.keys), source(TelemetryMetricCatalog.DRIVE_VELOCITY.keys),
            source(TelemetryMetricCatalog.DRIVE_ACCELERATION.keys), 0.1)
        val motors = sources.keys.mapNotNull { MOTOR_TOPIC.matchEntire(it)?.groupValues?.get(1) }.toSortedSet()
        for (motor in motors) {
            val base = "Hardware/Motors/$motor/"
            val velocity = source(listOf("Velocity", "VelocityRps", "VelocityRpm").map { base + it })
            // An acceleration channel must use the same source basis, including RPM/RPS suffixes.
            val suffix = velocity?.first()?.key?.substringAfterLast('/')?.removePrefix("Velocity") ?: ""
            fit("Diagnostics/SysId/Motors/$motor", source(listOf(base+"AppliedVoltage", base+"Voltage")), velocity,
                source(listOf(base+"Acceleration"+suffix)), 0.5)
        }
        // Wheel names do not establish motor inversion, topology or a common signed angular effort.
        fit("Diagnostics/SysId/Angular", source(listOf("Drive/AngularVoltage")), source(listOf("Drive/Velocity_Omega")),
            source(listOf("Drive/AngularAcceleration")), 0.1)
    }

    companion object {
        val extraInputKeys = listOf("Drive/AngularVoltage", "Drive/Velocity_Omega", "Drive/AngularAcceleration")
        val motorTopicPattern = "^Hardware/Motors/([^/]+)/(AppliedVoltage|Voltage|Velocity|VelocityRps|VelocityRpm|Acceleration|AccelerationRps|AccelerationRpm)$"
        private val MOTOR_TOPIC = Regex(motorTopicPattern)
    }
}
