package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/** Descriptive statistics of supplied source observations, not independent camera events. */
internal class SummaryLocalizationDiagnostics(frames: List<TelemetryFrame>, private val sessionId: String) {
    private val topics = frames.filter { it.sessionId == sessionId }.groupBy { it.key.trimStart('/') }
    private val numeric = mutableMapOf<String, List<TelemetryFrame>>()
    private fun series(key: String) = numeric.getOrPut(key) {
        numericAnalyticsSeries(topics[key].orEmpty(), sessionId, key)
    }
    private fun selected(keys: List<String>): List<TelemetryFrame> =
        keys.firstOrNull { it in topics }?.let(::series).orEmpty()

    private fun active(key: String): Set<Long>? = if (key in topics) {
        series(key).filter { it.value == 1.0 }.mapTo(HashSet()) { it.timestampUs }
    } else null

    fun calculate(): Map<String, Double> = buildMap {
        val nis = selected(NIS_KEYS).filter { it.value >= 0.0 }.map { it.value }
        if (nis.isNotEmpty()) {
            put("Diagnostics/EKF/AvgNIS", scaledMean(nis))
            put("Diagnostics/EKF/NISSamples", nis.size.toDouble())
        }

        // Compare published poses at exactly matching source microseconds. These are not the
        // filter's capture-time innovations, and do not isolate camera extrinsic calibration.
        val estimator = ESTIMATOR_PAIRS.firstOrNull { pair -> pair.all { it in topics } }
        if (estimator != null) {
            val visionY = series("Vision/Pose_Y").associateBy { it.timestampUs }
            val estimateX = series(estimator[0]).associateBy { it.timestampUs }
            val estimateY = series(estimator[1]).associateBy { it.timestampUs }
            val visible = active("Vision/HasTarget")
            val offsetsX = ArrayList<Double>(); val offsetsY = ArrayList<Double>()
            val magnitudes = ArrayList<Double>()
            for (x in series("Vision/Pose_X")) {
                val t = x.timestampUs
                if (visible != null && t !in visible) continue
                val y = visionY[t] ?: continue
                val dx = x.value - (estimateX[t]?.value ?: continue)
                val dy = y.value - (estimateY[t]?.value ?: continue)
                val magnitude = hypot(dx, dy)
                if (!magnitude.isFinite()) continue
                offsetsX.add(dx); offsetsY.add(dy); magnitudes.add(magnitude)
            }
            if (magnitudes.isNotEmpty()) {
                put("Diagnostics/EKF/PoseDisagreementMeanM", scaledMean(magnitudes))
                put("Diagnostics/EKF/PoseDisagreementBiasM", hypot(scaledMean(offsetsX), scaledMean(offsetsY)))
                put("Diagnostics/EKF/PoseDisagreementSamples", magnitudes.size.toDouble())
            }
        }

        val following = active("Path/Active")
        val errors = selected(PATH_KEYS).filter { following == null || it.timestampUs in following }.map { abs(it.value) }
        if (errors.isNotEmpty()) {
            val scale = errors.max()
            val rms = if (scale == 0.0) 0.0 else {
                sqrt(errors.sumOf { val normalized = it / scale; normalized * normalized } / errors.size).coerceAtMost(1.0) * scale
            }
            // Retain persisted key compatibility; the samples alone do not establish autonomous mode.
            put("Diagnostics/Auto/CrossTrackRMSE", rms)
            put("Diagnostics/Auto/MaxCrossTrackM", scale)
            put("Diagnostics/Auto/CrossTrackSamples", errors.size.toDouble())
        }
    }

    private fun scaledMean(values: List<Double>): Double {
        val scale = values.maxOf { abs(it) }
        return if (scale == 0.0) 0.0 else (values.sumOf { it / scale } / values.size).coerceIn(-1.0, 1.0) * scale
    }

    companion object {
        private val NIS_KEYS = listOf("Vision/EKF_NIS", "EKF/NIS")
        private val PATH_KEYS = listOf("Path/Error_CrossTrack", "Path/CrossTrackError", "Drive/CrossTrackError", "Drive/Cross_Track")
        private val ESTIMATOR_PAIRS = listOf(
            listOf("ARES/SimulatorPoseFrame/3", "ARES/SimulatorPoseFrame/4"),
            listOf("ARES/EstimatedPose/0", "ARES/EstimatedPose/1"),
            listOf("Drive/Pose_X", "Drive/Pose_Y"),
        )
        val ekfInputKeys = NIS_KEYS + ESTIMATOR_PAIRS.flatten() + listOf("Vision/Pose_X", "Vision/Pose_Y", "Vision/HasTarget")
        val pathInputKeys = PATH_KEYS + "Path/Active"
        val inputKeys: List<String> = (ekfInputKeys + pathInputKeys).flatMap { listOf(it, "/$it") }
    }
}
