package com.ares.analytics.service

import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.TelemetryFrame

/** Aggregate timestamps are display anchors, not the time of a diagnosed robot event. */
internal class DiagnosticCoachLocalization(
    frames: List<TelemetryFrame>, generated: List<AnalysisDiagnostic>, sessionId: String,
) {
    private val metrics = buildMap<String, TelemetryFrame> {
        val generatedFamilies = generated.filter { it.sessionId == sessionId }
            .map { it.key.trimStart('/').substringBeforeLast('/') }.toSet()
        for (frame in frames) {
            val key = frame.key.trimStart('/')
            if (frame.sessionId != sessionId || key !in KEYS || key.substringBeforeLast('/') in generatedFamilies) continue
            val previous = get(key)
            if (previous == null || frame.timestampUs > previous.timestampUs ||
                (frame.timestampUs == previous.timestampUs && frame.sampleOrder >= previous.sampleOrder)) put(key, frame)
        }
        // Prefer the current analysis result, including invalid values that supersede old data.
        for (metric in generated) {
            val key = metric.key.trimStart('/')
            if (metric.sessionId == sessionId && key in KEYS) {
                put(key, TelemetryFrame(0, sessionId, key, metric.value, metric.stringValue))
            }
        }
    }

    private fun metric(key: String) = metrics[key]?.takeIf {
        it.stringValue == null && it.value.isFinite() && it.value >= 0.0
    }

    fun ekfFinding(): DiagnosticFinding? {
        val poseKey = POSE_KEYS.firstOrNull { it in metrics }
        val pose = poseKey?.let(::metric)
        if (pose != null && pose.value >= 0.04) {
            val label = when (poseKey) {
                "Diagnostics/EKF/PoseDisagreementMeanM" -> "Mean published-pose disagreement"
                "Diagnostics/EKF/PoseDisagreementBiasM" -> "Magnitude of the mean published-pose offset"
                else -> "Legacy recorded residual offset statistic"
            }
            return DiagnosticFinding(
                id = "ekf-pose-disagreement",
                title = "Recorded pose disagreement crossed the review threshold",
                severity = DiagnosticSeverity.REVIEW,
                timestampSeconds = pose.timestampUs / 1_000_000.0,
                observation = "$label was ${centimeters(pose.value)} cm.",
                thresholdContext = "ARES screens recorded offsets at or above 4 cm. Published pose differences do not establish capture-time innovation, systematic bias or a calibration fault. $AGGREGATE_NOTICE",
                possibleCauses = listOf("Capture/publication latency or mismatched coordinate frames", "Camera or odometry measurement noise", "Estimator dynamics or calibration differences"),
                verificationSteps = listOf("Verify source timestamps, target validity and coordinate conventions", "Compare synchronized camera and estimator traces with independent pose evidence before changing calibration"),
                topic = pose.key,
            )
        }
        val nis = metric("Diagnostics/EKF/AvgNIS") ?: return null
        return DiagnosticFinding(
            id = "ekf-recorded-nis",
            title = "Recorded NIS summary needs measurement context",
            severity = DiagnosticSeverity.INFORMATION,
            timestampSeconds = nis.timestampUs / 1_000_000.0,
            observation = "Mean recorded Normalized Innovation Squared (NIS) was ${"%.2f".format(nis.value)}.",
            thresholdContext = "A consistency threshold requires measurement dimension, covariance assumptions and distinct observation identity. Repeated telemetry values cannot establish whiteness or optimal tuning. $AGGREGATE_NOTICE",
            possibleCauses = listOf("Measurement dimension and event sampling affect this statistic", "Model, noise, timing or calibration differences may affect innovations"),
            verificationSteps = listOf("Record per-observation NIS, degrees of freedom, source and capture time", "Check innovation independence and covariance assumptions before evaluating consistency or adjusting Q/R"),
            topic = nis.key,
        )
    }

    fun pathFinding(): DiagnosticFinding? {
        val rms = metric("Diagnostics/Auto/CrossTrackRMSE")
        val peak = metric("Diagnostics/Auto/MaxCrossTrackM")
        if ((rms?.value ?: 0.0) <= 0.06 && (peak?.value ?: 0.0) <= 0.15) return null
        val anchor = rms ?: peak ?: return null
        return DiagnosticFinding(
            id = "auto-path-deviation", // Preserve the existing consumer identifier.
            title = "Recorded path deviation exceeded the review threshold",
            severity = if ((rms?.value ?: 0.0) > 0.10 || (peak?.value ?: 0.0) > 0.25) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
            timestampSeconds = anchor.timestampUs / 1_000_000.0,
            observation = "Cross-track RMS: ${rms?.let { centimeters(it.value) + " cm" } ?: "unavailable"}; peak: ${peak?.let { centimeters(it.value) + " cm" } ?: "unavailable"}.",
            thresholdContext = "ARES screens recorded RMS above 6 cm or peaks above 15 cm. These generic thresholds do not establish autonomous mode or the cause of deviation. $AGGREGATE_NOTICE",
            possibleCauses = listOf("Pose or timing error", "Controller/path configuration", "Wheel slip or surface variation"),
            verificationSteps = listOf("Inspect active-path intervals and synchronized pose/target traces", "Verify metric units and sample coverage before deciding whether controller tuning or a restrained mechanical test is appropriate"),
            topic = anchor.key,
        )
    }

    private fun centimeters(meters: Double): String = if (meters <= Double.MAX_VALUE / 100.0) {
        "%.1f".format(meters * 100.0)
    } else "%.3e".format(java.math.BigDecimal.valueOf(meters).multiply(java.math.BigDecimal.valueOf(100)))

    companion object {
        private const val AGGREGATE_NOTICE = "Summary values describe recorded samples; the displayed time is a summary anchor (zero when unavailable), not an event time."
        private val POSE_KEYS = listOf("Diagnostics/EKF/PoseDisagreementMeanM", "Diagnostics/EKF/PoseDisagreementBiasM", "Diagnostics/EKF/ResidualBiasM")
        private val KEYS = (POSE_KEYS + listOf("Diagnostics/EKF/AvgNIS", "Diagnostics/Auto/CrossTrackRMSE", "Diagnostics/Auto/MaxCrossTrackM")).toSet()
    }
}
