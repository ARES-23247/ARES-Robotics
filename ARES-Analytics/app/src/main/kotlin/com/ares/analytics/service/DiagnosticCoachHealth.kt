package com.ares.analytics.service

import java.util.Locale

/** Screening thresholds apply to recorded observations, not a physical safety assessment. */
internal fun coachHealth(snapshot: CoachSnapshot): PitDiagnosticSummary {
    val battery = snapshot.first("battery")
    val loop = snapshot.first("loop")
    val findings = buildList {
        if (battery != null && battery.value < DiagnosticCoachService.BATTERY_REVIEW_VOLTS) add(DiagnosticFinding(
            id = "battery-low",
            title = "Battery voltage crossed the review threshold",
            severity = if (battery.value < DiagnosticCoachService.BATTERY_URGENT_VOLTS) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
            timestampSeconds = battery.startUs / 1_000_000.0,
            observation = "Minimum recorded battery voltage was ${coachNumber(battery.value, 2)} V.",
            thresholdContext = "ARES screens recorded voltage below ${DiagnosticCoachService.BATTERY_REVIEW_VOLTS} V; this is not a battery diagnosis. Check measurement validity, including recorded zero values.",
            possibleCauses = listOf("A discharged or high-resistance battery", "High simultaneous mechanism load", "Loose or resistive power wiring", "A telemetry or calibration problem"),
            verificationSteps = listOf("Check the timestamp against total current and driver actions", "Verify voltage telemetry against approved pit equipment", "Inspect power connections using the team's electrical checklist"),
            topic = battery.topic,
        ))
        for (span in snapshot.all("current")) add(DiagnosticFinding(
            id = "sustained-current-${span.topic}",
            title = "Recorded motor current crossed the duration threshold",
            severity = DiagnosticSeverity.REVIEW,
            timestampSeconds = span.startUs / 1_000_000.0,
            observation = "${span.topic}: ${span.samples} recorded samples at or above ${DiagnosticCoachService.CURRENT_REVIEW_AMPS} A span ${coachNumber(span.durationSeconds, 3)} s; interval peak ${coachNumber(span.value, 1)} A.",
            thresholdContext = "Adjacent recorded samples are at most ${DiagnosticCoachService.MAX_SAMPLE_GAP_US / 1_000} ms apart. Values between samples are unknown. This generic threshold is not the mechanism's configured current limit and does not establish a stall.",
            possibleCauses = listOf("Expected heavy mechanism load", "Binding or obstruction", "Aggressive control demand", "Incorrect current telemetry"),
            verificationSteps = listOf("Compare the interval with target, velocity, position and operator intent", "Check the mechanism-specific current limit and duty cycle", "Inspect the mechanism while disabled before a restrained test"),
            topic = span.topic,
        ))
        if (loop != null && loop.value >= DiagnosticCoachService.LOOP_TIME_REVIEW_MS) add(DiagnosticFinding(
            id = "loop-time-overrun",
            title = "Recorded control loop period crossed the review threshold",
            severity = if (loop.value >= DiagnosticCoachService.LOOP_TIME_URGENT_MS) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
            timestampSeconds = loop.startUs / 1_000_000.0,
            observation = "Peak recorded control loop period was ${coachNumber(loop.value, 1)} ms.",
            thresholdContext = "ARES screens recorded periods at or above ${DiagnosticCoachService.LOOP_TIME_REVIEW_MS.toInt()} ms. This is a generic review threshold; telemetry does not establish the cause or current real-time performance.",
            possibleCauses = listOf("Blocking work or synchronous I/O", "Allocation or garbage collection pauses", "Logging or serialization overhead", "Host contention or timing telemetry errors"),
            verificationSteps = listOf("Correlate the recorded peak with loop-profiler stage measurements", "Check hardware read caching, blocking calls and allocations in the periodic path"),
            topic = loop.topic,
        ))
        val trips = snapshot.first("brownout")
        val scale = snapshot.first("scale")?.takeIf { it.value < 1.0 }
        if (trips != null || scale != null) {
            val anchor = scale ?: requireNotNull(trips)
            add(DiagnosticFinding(
                id = "brownout-guard-tripped",
                title = "Recorded brownout guard activity needs review",
                severity = if (scale?.value == 0.0) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
                timestampSeconds = anchor.startUs / 1_000_000.0,
                observation = listOfNotNull(
                    trips?.let { "${coachNumber(it.value, 0)} guard counter increment(s) across recorded intervals." },
                    scale?.let { "Minimum recorded guard power scale was ${java.math.BigDecimal.valueOf(it.value).movePointRight(2).stripTrailingZeros().toPlainString()}%." },
                ).joinToString(" "),
                thresholdContext = "Guard trips include warning/critical transitions and responses to invalid voltage; these signals do not establish physical brownouts. Counter changes occur between recorded updates. A zero scale reports guard-requested neutralization.",
                possibleCauses = listOf("Low or sagging measured voltage", "An invalid battery-voltage reading", "Guard thresholds or hysteresis configuration"),
                verificationSteps = listOf("Inspect synchronized voltage, guard state and power-scale records", "Verify measurement validity and configured guard thresholds before changing hardware or tuning"),
                topic = anchor.topic,
            ))
        }
    }
    val missing = buildList {
        if (battery == null) add("Battery voltage")
        if (snapshot.first("current_present") == null) add("Per-motor current")
        if (loop == null) add("Control loop period")
    }
    return PitDiagnosticSummary(findings, missing)
}

private fun coachNumber(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value)
