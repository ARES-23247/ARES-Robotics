package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import com.areslib.math.wrapAngle
import kotlin.math.*

/** Fits geometric calibration and stationary vision noise from complete, chronological samples. */
class SysIdRegressionSolver(
    private val _state: MutableStateFlow<SysIdState>
) {
    fun runCalibrationAnalysis(calibrationType: String, data: List<DoubleArray>) {
        // A failed/new analysis must not leave an earlier result available for promotion.
        _state.update { it.copy(recommendedPinpointXOffsetMm = null, recommendedPinpointYOffsetMm = null,
            recommendedTrackWidthMeters = null, recommendedVisionStdDevsX = null,
            recommendedVisionStdDevsY = null, recommendedVisionStdDevsHeading = null,
            recommendedTicksPerMeter = null, errorMessage = null) }
        if (data.size < 10) {
            _state.update { it.copy(errorMessage = "Not enough calibration data collected (minimum 10 points)") }
            return
        }

        try {
            val requiredColumns = when (calibrationType) {
                "TRACK_WIDTH_SPIN" -> 7
                "LINEAR_DRIVE" -> 3
                "PINPOINT_SPIN", "VISION_CALIBRATION" -> 4
                else -> error("Unknown calibration type: $calibrationType")
            }
            require(data.all { row -> row.size >= requiredColumns && (0 until requiredColumns).all { row[it].isFinite() } }) {
                "Calibration samples are incomplete or non-finite; collect a new run with the current robot runtime"
            }
            var previousTime = -1.0
            for (row in data) {
                require(row[0] >= 0.0 && row[0] > previousTime) { "Calibration timestamps must be nonnegative and strictly increasing" }
                previousTime = row[0]
            }
            when (calibrationType) {
                "PINPOINT_SPIN" -> {
                    val n = data.size
                    val meanX = scaledMean(data, 1)
                    val meanY = scaledMean(data, 2)
                    var meanCos = 0.0
                    var meanSin = 0.0
                    for (row in data) {
                        meanCos += cos(row[3]) / n
                        meanSin += sin(row[3]) / n
                    }
                    // Eliminate the fixed field origin by centering z = origin + rotation * offset.
                    // Complex least squares then needs three scalar sums, not a 2N by 4 matrix.
                    var excitation = 0.0
                    var covarianceX = 0.0
                    var covarianceY = 0.0
                    for (row in data) {
                        val dc = cos(row[3]) - meanCos
                        val ds = sin(row[3]) - meanSin
                        val dx = row[1] - meanX
                        val dy = row[2] - meanY
                        excitation += (dc * dc + ds * ds) / n
                        covarianceX += dc * (dx / n) + ds * (dy / n)
                        covarianceY += dc * (dy / n) - ds * (dx / n)
                    }
                    // Near-constant headings make the fixed origin and rotating offset indistinguishable.
                    require(excitation > 1e-6) { "Insufficient rotation to identify Pinpoint offsets" }
                    val deltaXOffsetMm = covarianceX / excitation * 1000.0
                    val deltaYOffsetMm = covarianceY / excitation * 1000.0
                    require(deltaXOffsetMm.isFinite() && deltaYOffsetMm.isFinite()) { "Calibration produced invalid offsets" }

                    _state.update {
                        it.copy(
                            recommendedPinpointXOffsetMm = deltaXOffsetMm,
                            recommendedPinpointYOffsetMm = deltaYOffsetMm,
                            errorMessage = null
                        )
                    }
                }
                "TRACK_WIDTH_SPIN" -> {
                    val n = data.size
                    var accumHeading = 0.0
                    var lastHeading = data[0][5]
                    var sumXY = 0.0
                    var sumX2 = 0.0
                    val fl0 = data[0][1]
                    val fr0 = data[0][2]
                    val rl0 = data[0][3]
                    val rr0 = data[0][4]

                    for (i in 0 until n) {
                        val currentHeading = data[i][5]
                        val rawDifference = currentHeading - lastHeading
                        require(rawDifference.isFinite()) { "Invalid heading difference" }
                        val diff = wrapAngle(rawDifference)
                        accumHeading += diff
                        lastHeading = currentHeading
                        val fl = data[i][1] - fl0
                        val fr = data[i][2] - fr0
                        val rl = data[i][3] - rl0
                        val rr = data[i][4] - rr0
                        val y = -fl + fr - rl + rr
                        val x = 4.0 * accumHeading

                        sumXY += x * y
                        sumX2 += x * x
                    }
                    require(sumXY.isFinite() && sumX2.isFinite() && sumX2 > 1e-6) { "Insufficient or unrepresentable rotation to estimate track width" }
                    val k = sumXY / sumX2
                    val wheelBase = data.first()[6]
                    require(wheelBase > 0.0 && data.all { it[6] == wheelBase }) {
                        "Wheelbase must be positive and unchanged throughout calibration"
                    }
                    val recTrackWidth = 2.0 * k - wheelBase
                    require(recTrackWidth.isFinite() && recTrackWidth > 0.0) { "Calibration produced an invalid track width" }

                    _state.update {
                        it.copy(
                            recommendedTrackWidthMeters = recTrackWidth,
                            errorMessage = null
                        )
                    }
                }
                "VISION_CALIBRATION" -> {
                    val n = data.size
                    val meanX = scaledMean(data, 1)
                    val meanY = scaledMean(data, 2)
                    var sumSin = 0.0
                    var sumCos = 0.0
                    for (row in data) {
                        sumSin += kotlin.math.sin(row[3])
                        sumCos += kotlin.math.cos(row[3])
                    }
                    require(kotlin.math.hypot(sumSin, sumCos) > 1e-6 * n) {
                        "Heading samples have no identifiable circular mean"
                    }
                    val meanHeading = kotlin.math.atan2(sumSin, sumCos)
                    var stdX = 0.0
                    var stdY = 0.0
                    var stdHeading = 0.0
                    val divisor = sqrt((n - 1).toDouble())

                    for (row in data) {
                        val dx = scaledDifference(row[1], meanX, divisor)
                        val dy = scaledDifference(row[2], meanY, divisor)
                        val dHeading = wrapAngle(row[3] - meanHeading)
                        // hypot scales internally, avoiding overflow while squaring finite residuals.
                        stdX = hypot(stdX, dx)
                        stdY = hypot(stdY, dy)
                        stdHeading = hypot(stdHeading, dHeading / divisor)
                    }
                    require(stdX.isFinite() && stdY.isFinite() && stdHeading.isFinite()) { "Calibration produced invalid vision deviations" }

                    _state.update {
                        it.copy(
                            recommendedVisionStdDevsX = stdX,
                            recommendedVisionStdDevsY = stdY,
                            recommendedVisionStdDevsHeading = stdHeading,
                            errorMessage = null
                        )
                    }
                }
                "LINEAR_DRIVE" -> {
                    val firstDisplacement = data.first()[1]
                    val lastDisplacement = data.last()[1]
                    val reportedDisplacement = lastDisplacement - firstDisplacement
                    val actualDistance = _state.value.linearDriveActualDistanceMeters

                    if (actualDistance.isFinite() && actualDistance > 0.1 && reportedDisplacement.isFinite() && reportedDisplacement > 0.05) {
                        val currentTicks = data.first()[2]
                        require(currentTicks > 0.0 && data.all { it[2] == currentTicks }) {
                            "Encoder scale must be positive and unchanged throughout calibration"
                        }
                        val recTicks = currentTicks * (reportedDisplacement / actualDistance)
                        require(recTicks.isFinite() && recTicks > 0.0) { "Calibration produced an invalid encoder scale" }

                        _state.update {
                            it.copy(
                                recommendedTicksPerMeter = recTicks,
                                errorMessage = null
                            )
                        }
                    } else {
                        _state.update { it.copy(errorMessage = "Insufficient linear displacement or invalid physical distance input.") }
                    }
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(errorMessage = "Calibration analysis failed: ${e.message}") }
        }
    }

    private fun scaledDifference(value: Double, mean: Double, divisor: Double): Double {
        val difference = value - mean
        return if (difference.isFinite()) difference / divisor else value / divisor - mean / divisor
    }

    private fun scaledMean(data: List<DoubleArray>, column: Int): Double {
        var scale = 0.0
        for (row in data) scale = max(scale, abs(row[column]))
        if (scale == 0.0) return 0.0
        var normalizedMean = 0.0
        for (row in data) normalizedMean += (row[column] / scale) / data.size
        // The exact mean of normalized finite values lies in [-1, 1]; contain summation roundoff.
        return normalizedMean.coerceIn(-1.0, 1.0) * scale
    }
}
