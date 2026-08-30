package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DiagnosticSeverity { INFORMATION, REVIEW, URGENT }

data class DiagnosticFinding(
    val id: String,
    val title: String,
    val severity: DiagnosticSeverity,
    val timestampSeconds: Double,
    val observation: String,
    val thresholdContext: String,
    val possibleCauses: List<String>,
    val verificationSteps: List<String>,
    val topic: String
)

data class PitDiagnosticSummary(
    val findings: List<DiagnosticFinding>,
    val missingSignals: List<String>,
    val evidenceNotice: String = "These are telemetry screening observations, not root-cause diagnoses or proof that the robot is safe."
) {
    val urgentCount: Int get() = findings.count { it.severity == DiagnosticSeverity.URGENT }
    val reviewCount: Int get() = findings.count { it.severity == DiagnosticSeverity.REVIEW }
}

/**
 * Produces an evidence-limited pit checklist from imported telemetry.
 *
 * It reports only thresholds directly supported by the selected signal. Possible causes are
 * hypotheses to verify; they are never presented as diagnoses.
 */
class DiagnosticCoachService(private val databaseService: DatabaseService) {
    suspend fun analyze(sessionId: String): PitDiagnosticSummary = withContext(Dispatchers.Default) {
        require(sessionId.isNotBlank()) { "Select a recorded session before running the checklist" }
        var rawBatteryFrames: List<TelemetryFrame> = emptyList()
        for (key in TelemetryMetricCatalog.BATTERY_VOLTAGE.keys) {
            rawBatteryFrames = databaseService.getTelemetryForKey(sessionId, key)
            if (rawBatteryFrames.isNotEmpty()) break
        }
        val batteryFrames = rawBatteryFrames.filter { it.value.isFinite() }.sortedBy { it.timestampUs }
        val currentFrames = databaseService.getTelemetryForKeyPatterns(
            sessionId,
            listOf("Hardware/Motors/%/CurrentAmps", "Hardware/Motors/%/Current")
        ).filter { it.value.isFinite() }.sortedBy { it.timestampUs }

        var rawLoopFrames: List<TelemetryFrame> = emptyList()
        for (key in TelemetryMetricCatalog.LOOP_TIME.keys) {
            rawLoopFrames = databaseService.getTelemetryForKey(sessionId, key)
            if (rawLoopFrames.isNotEmpty()) break
        }
        val loopFrames = rawLoopFrames.filter { it.value.isFinite() }.sortedBy { it.timestampUs }

        val brownoutFrames = databaseService.getTelemetryForKeyPatterns(
            sessionId,
            listOf("Diagnostics/Power/BrownoutCount", "Robot/BrownoutCount", "Robot/BrownoutPowerScale")
        ).filter { it.value.isFinite() }.sortedBy { it.timestampUs }

        val ekfFrames = databaseService.getTelemetryForKeyPatterns(
            sessionId,
            listOf("Diagnostics/EKF/%", "Vision/EKF_NIS")
        ).filter { it.value.isFinite() }.sortedBy { it.timestampUs }

        val autoFrames = databaseService.getTelemetryForKeyPatterns(
            sessionId,
            listOf("Diagnostics/Auto/%", "Path/CrossTrackError", "Drive/CrossTrackError")
        ).filter { it.value.isFinite() }.sortedBy { it.timestampUs }

        val findings = buildList {
            batteryFrames.minByOrNull(TelemetryFrame::value)?.takeIf { it.value < BATTERY_REVIEW_VOLTS }?.let { frame ->
                add(
                    DiagnosticFinding(
                        id = "battery-low",
                        title = "Battery voltage crossed the review threshold",
                        severity = if (frame.value < BATTERY_URGENT_VOLTS) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
                        timestampSeconds = frame.timestampUs / 1_000_000.0,
                        observation = "Minimum observed battery voltage was ${"%.2f".format(frame.value)} V.",
                        thresholdContext = "ARES screens imported voltage below $BATTERY_REVIEW_VOLTS V for review; this is not a battery diagnosis.",
                        possibleCauses = listOf("A discharged or high-resistance battery", "High simultaneous mechanism load", "Loose or resistive power wiring", "A telemetry or calibration problem"),
                        verificationSteps = listOf("Check the timestamp against total current and driver actions", "Measure the battery with approved pit equipment", "Inspect and torque power connections using the team's electrical checklist"),
                        topic = frame.key
                    )
                )
            }
            sustainedCurrentFinding(currentFrames)?.let(::add)
            loopOverrunFinding(loopFrames)?.let(::add)
            brownoutFinding(brownoutFrames)?.let(::add)
            ekfDiagnosticFinding(ekfFrames)?.let(::add)
            autoTrackingFinding(autoFrames)?.let(::add)
        }
        val missing = buildList {
            if (batteryFrames.isEmpty()) add("Battery voltage")
            if (currentFrames.isEmpty()) add("Per-motor current")
            if (loopFrames.isEmpty()) add("Control loop period")
        }
        PitDiagnosticSummary(findings, missing)
    }

    private fun sustainedCurrentFinding(frames: List<TelemetryFrame>): DiagnosticFinding? {
        for (group in frames.groupBy(TelemetryFrame::key).values) {
            var start: TelemetryFrame? = null
            var previous: TelemetryFrame? = null
            for (frame in group) {
                val gapUs = previous?.let { frame.timestampUs - it.timestampUs } ?: 0L
                if (frame.value >= CURRENT_REVIEW_AMPS && (previous == null || gapUs in 0..MAX_SAMPLE_GAP_US)) {
                    if (start == null) start = frame
                    if (frame.timestampUs - start.timestampUs >= CURRENT_REVIEW_DURATION_US) {
                        return DiagnosticFinding(
                            id = "sustained-current-${frame.key}",
                            title = "Sustained motor current crossed the review threshold",
                            severity = DiagnosticSeverity.REVIEW,
                            timestampSeconds = start.timestampUs / 1_000_000.0,
                            observation = "${frame.key} stayed at or above $CURRENT_REVIEW_AMPS A for at least 0.5 s; peak ${"%.1f".format(group.maxOf { it.value })} A.",
                            thresholdContext = "This generic screening threshold is not the mechanism's configured current limit and does not establish a stall.",
                            possibleCauses = listOf("Expected heavy mechanism load", "Binding or obstruction", "Aggressive control demand", "Incorrect current telemetry"),
                            verificationSteps = listOf("Compare the interval with target, velocity, position, and operator intent", "Check the mechanism-specific current limit and duty cycle", "Inspect the mechanism while disabled before repeating a restrained test"),
                            topic = frame.key
                        )
                    }
                } else {
                    start = null
                }
                previous = frame
            }
        }
        return null
    }

    private fun loopOverrunFinding(frames: List<TelemetryFrame>): DiagnosticFinding? {
        val peak = frames.maxByOrNull(TelemetryFrame::value) ?: return null
        if (peak.value < LOOP_TIME_REVIEW_MS) return null
        val isUrgent = peak.value >= LOOP_TIME_URGENT_MS
        return DiagnosticFinding(
            id = "loop-time-overrun",
            title = "Control loop period exceeded review threshold",
            severity = if (isUrgent) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
            timestampSeconds = peak.timestampUs / 1_000_000.0,
            observation = "Peak control loop period reached ${"%.1f".format(peak.value)} ms (${"%.0f".format(1000.0 / peak.value)} Hz).",
            thresholdContext = "ARES screens loop times above ${LOOP_TIME_REVIEW_MS.toInt()} ms for potential cycle jitter and controller latency.",
            possibleCauses = listOf("Synchronous I/O or blocking operations in control loop", "Garbage collection pauses from dynamic allocations in hot path", "Excessive logging bandwidth or serialization overhead", "Host CPU contention"),
            verificationSteps = listOf("Review ControlLoopProfilerCard breakdown for pipeline stages", "Ensure zero-GC memory compliance in periodic robot loop", "Verify sensor and vision read caching"),
            topic = peak.key
        )
    }

    private fun brownoutFinding(frames: List<TelemetryFrame>): DiagnosticFinding? {
        val countFrame = frames.firstOrNull { it.key.contains("count", ignoreCase = true) && it.value > 0.0 }
        val scaleFrame = frames.firstOrNull { it.key.contains("scale", ignoreCase = true) && it.value in 0.0..0.95 }
        val trigger = countFrame ?: scaleFrame ?: return null
        return DiagnosticFinding(
            id = "brownout-guard-tripped",
            title = "Brownout protection event detected",
            severity = DiagnosticSeverity.URGENT,
            timestampSeconds = trigger.timestampUs / 1_000_000.0,
            observation = if (countFrame != null) {
                "Brownout guard recorded ${countFrame.value.toInt()} brownout event(s)."
            } else {
                "Brownout guard throttled drive power to ${"%.0f".format((scaleFrame?.value ?: 1.0) * 100)}% to protect system bus voltage."
            },
            thresholdContext = "Brownout protection triggers automatically when bus voltage drops below critical operating thresholds.",
            possibleCauses = listOf("Depleted or high internal resistance battery", "Simultaneous peak acceleration across multiple high-draw mechanisms", "Low starting voltage before match run", "Undersized main power wiring or loose battery terminals"),
            verificationSteps = listOf("Correlate timestamp with motor current and battery voltage curves", "Perform battery load and internal resistance test in pit", "Verify CurrentBudgetManager configuration and slew rate limits"),
            topic = trigger.key
        )
    }

    private fun ekfDiagnosticFinding(frames: List<TelemetryFrame>): DiagnosticFinding? {
        val avgNisFrame = frames.firstOrNull { it.key.endsWith("AvgNIS") }
        val biasFrame = frames.firstOrNull { it.key.endsWith("ResidualBiasM") }
        val outlierFrame = frames.firstOrNull { it.key.endsWith("NISOutlierRatio") }

        if (avgNisFrame != null) {
            val avgNis = avgNisFrame.value
            val bias = biasFrame?.value ?: 0.0
            val outlier = outlierFrame?.value ?: 0.0
            when {
                bias >= 0.04 -> {
                    return DiagnosticFinding(
                        id = "ekf-extrinsic-skew",
                        title = "Camera Extrinsic or Odometry Calibration Skew Detected",
                        severity = DiagnosticSeverity.REVIEW,
                        timestampSeconds = avgNisFrame.timestampUs / 1_000_000.0,
                        observation = "Systematic vision residual bias of ${"%.1f".format(bias * 100)} cm between camera tag detections and EKF pose.",
                        thresholdContext = "ARES screens systematic residual offsets >= 4 cm as potential camera mounting angle or wheel scaling error.",
                        possibleCauses = listOf("Camera mounting pitch/yaw misalignment", "Incorrect Limelight 3D transform constants", "Odometry track width or wheel radius scaling error"),
                        verificationSteps = listOf("Run Camera Extrinsic Calibration Wizard in pit", "Verify Limelight robot-to-camera offset in hardware config", "Check wheel diameter and track width calibration"),
                        topic = biasFrame?.key ?: avgNisFrame.key
                    )
                }
                avgNis < 0.6 -> {
                    return DiagnosticFinding(
                        id = "ekf-vision-underweighted",
                        title = "EKF Vision Measurement Noise (R) is Under-Weighted",
                        severity = DiagnosticSeverity.INFORMATION,
                        timestampSeconds = avgNisFrame.timestampUs / 1_000_000.0,
                        observation = "Average Normalized Innovation Squared (NIS) is ${"%.2f".format(avgNis)} (target ~2.0).",
                        thresholdContext = "NIS < 0.6 indicates the Kalman filter assumes the camera is much noisier than it actually is, under-utilizing vision.",
                        possibleCauses = listOf("Vision measurement noise R set too high in PoseEstimator", "Process noise Q set too low"),
                        verificationSteps = listOf("Decrease R_vision in PoseEstimator or ares_tuning.json by ~30-50%", "Observe NIS convergence towards 2.0 in practice runs"),
                        topic = avgNisFrame.key
                    )
                }
                avgNis > 4.5 || outlier > 0.10 -> {
                    return DiagnosticFinding(
                        id = "ekf-vision-jitter",
                        title = "EKF Vision Jitter / Over-Confidence Detected",
                        severity = DiagnosticSeverity.REVIEW,
                        timestampSeconds = avgNisFrame.timestampUs / 1_000_000.0,
                        observation = "Average NIS is ${"%.2f".format(avgNis)} with ${"%.1f".format(outlier * 100)}% 3-sigma outliers.",
                        thresholdContext = "NIS > 4.5 indicates the Kalman filter is over-trusting noisy camera frames, leading to pose jitter.",
                        possibleCauses = listOf("Vision measurement noise R set too low", "Loose or flickering AprilTag detection at high distance", "Mahalanobis outlier gate too loose"),
                        verificationSteps = listOf("Increase R_vision in PoseEstimator to smooth out vision updates", "Tighten AprilTag max distance or ambiguity thresholds"),
                        topic = avgNisFrame.key
                    )
                }
                else -> return null
            }
        }
        return null
    }

    private fun autoTrackingFinding(frames: List<TelemetryFrame>): DiagnosticFinding? {
        val rmseFrame = frames.firstOrNull { it.key.endsWith("CrossTrackRMSE") }
        val maxFrame = frames.firstOrNull { it.key.endsWith("MaxCrossTrackM") }
        if (rmseFrame != null) {
            val rmse = rmseFrame.value
            val max = maxFrame?.value ?: 0.0
            if (rmse > 0.06 || max > 0.15) {
                return DiagnosticFinding(
                    id = "auto-path-deviation",
                    title = "Autonomous Path Tracking Deviation Exceeded Threshold",
                    severity = if (rmse > 0.10 || max > 0.25) DiagnosticSeverity.URGENT else DiagnosticSeverity.REVIEW,
                    timestampSeconds = rmseFrame.timestampUs / 1_000_000.0,
                    observation = "Cross-track error RMSE was ${"%.1f".format(rmse * 100)} cm with peak deviation ${"%.1f".format(max * 100)} cm.",
                    thresholdContext = "ARES screens autonomous tracking RMSE > 6 cm for potential feedforward mistuning or traction loss.",
                    possibleCauses = listOf("Holonomic drive PID / feedforward (kV, kA) under-tuned", "Wheel slip or carpet friction variation", "Trajectory path parameterizer acceleration limit too high for robot physics"),
                    verificationSteps = listOf("Run automated SysId characterization to refresh kV and kA gains", "Check HolonomicPathFollower proportional gains", "Verify wheel tread wear and drive motor current limits"),
                    topic = rmseFrame.key
                )
            }
        }
        return null
    }

    companion object {
        const val BATTERY_REVIEW_VOLTS = 10.5
        const val BATTERY_URGENT_VOLTS = 9.5
        const val CURRENT_REVIEW_AMPS = 40.0
        const val CURRENT_REVIEW_DURATION_US = 500_000L
        const val MAX_SAMPLE_GAP_US = 200_000L
        const val LOOP_TIME_REVIEW_MS = 35.0
        const val LOOP_TIME_URGENT_MS = 50.0
    }
}
