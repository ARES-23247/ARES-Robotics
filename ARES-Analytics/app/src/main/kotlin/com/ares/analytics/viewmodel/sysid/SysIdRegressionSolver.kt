package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.ejml.simple.SimpleMatrix
import com.areslib.math.wrapAngle

/** Runs feedforward regression and transient classification for the selected mechanism. */
class SysIdRegressionSolver(
    private val _state: MutableStateFlow<SysIdState>
) {
    fun runCalibrationAnalysis(calibrationType: String, data: List<DoubleArray>) {
        if (data.size < 10) {
            _state.update { it.copy(errorMessage = "Not enough calibration data collected (minimum 10 points)") }
            return
        }

        try {
            val requiredColumns = when (calibrationType) {
                "TRACK_WIDTH_SPIN" -> 7
                "LINEAR_DRIVE" -> 3
                else -> 4
            }
            require(data.all { row -> row.size >= requiredColumns && (0 until requiredColumns).all { row[it].isFinite() } }) {
                "Calibration samples are incomplete or non-finite; collect a new run with the current robot runtime"
            }
            when (calibrationType) {
                "PINPOINT_SPIN" -> {
                    val n = data.size
                    val A = SimpleMatrix(2 * n, 4)
                    val b = SimpleMatrix(2 * n, 1)

                    for (i in 0 until n) {
                        val row = data[i]
                        val x = row[1]
                        val y = row[2]
                        val heading = row[3]
                        val cosT = kotlin.math.cos(heading)
                        val sinT = kotlin.math.sin(heading)

                        A.setRow(2 * i, 0, 1.0, 0.0, cosT, -sinT)
                        A.setRow(2 * i + 1, 0, 0.0, 1.0, sinT, cosT)

                        b.set(2 * i, 0, x)
                        b.set(2 * i + 1, 0, y)
                    }
                    val beta = A.solve(b)
                    val dxMeters = beta.get(2, 0)
                    val dyMeters = beta.get(3, 0)
                    val deltaXOffsetMm = dxMeters * 1000.0
                    val deltaYOffsetMm = dyMeters * 1000.0

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
                    val unwrappedHeadings = DoubleArray(n)
                    unwrappedHeadings[0] = 0.0

                    for (i in 1 until n) {
                        val currentHeading = data[i][5]
                        val diff = wrapAngle(currentHeading - lastHeading)
                        accumHeading += diff
                        unwrappedHeadings[i] = accumHeading
                        lastHeading = currentHeading
                    }
                    var sumXY = 0.0
                    var sumX2 = 0.0
                    val fl0 = data[0][1]
                    val fr0 = data[0][2]
                    val rl0 = data[0][3]
                    val rr0 = data[0][4]

                    for (i in 0 until n) {
                        val fl = data[i][1] - fl0
                        val fr = data[i][2] - fr0
                        val rl = data[i][3] - rl0
                        val rr = data[i][4] - rr0
                        val y = -fl + fr - rl + rr
                        val x = 4.0 * unwrappedHeadings[i]

                        sumXY += x * y
                        sumX2 += x * x
                    }
                    require(sumX2 > 1e-6) { "Insufficient rotation to estimate track width" }
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
                    var meanX = 0.0
                    var meanY = 0.0
                    var sumSin = 0.0
                    var sumCos = 0.0
                    for (row in data) {
                        meanX += row[1] / n
                        meanY += row[2] / n
                        sumSin += kotlin.math.sin(row[3])
                        sumCos += kotlin.math.cos(row[3])
                    }
                    require(kotlin.math.hypot(sumSin, sumCos) > 1e-6 * n) {
                        "Heading samples have no identifiable circular mean"
                    }
                    val meanHeading = kotlin.math.atan2(sumSin, sumCos)
                    var varX = 0.0
                    var varY = 0.0
                    var varHeading = 0.0

                    for (row in data) {
                        val dx = row[1] - meanX
                        val dy = row[2] - meanY
                        val dHeading = wrapAngle(row[3] - meanHeading)

                        varX += dx * dx
                        varY += dy * dy
                        varHeading += dHeading * dHeading
                    }
                    val stdX = kotlin.math.sqrt(varX / (n - 1))
                    val stdY = kotlin.math.sqrt(varY / (n - 1))
                    val stdHeading = kotlin.math.sqrt(varHeading / (n - 1))

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
                    val firstDisplacement = data.firstOrNull()?.get(1) ?: 0.0
                    val lastDisplacement = data.lastOrNull()?.get(1) ?: 0.0
                    val reportedDisplacement = lastDisplacement - firstDisplacement
                    val actualDistance = _state.value.linearDriveActualDistanceMeters

                    if (actualDistance.isFinite() && actualDistance > 0.1 && reportedDisplacement > 0.05) {
                        val currentTicks = data.first()[2]
                        require(currentTicks > 0.0 && data.all { it[2] == currentTicks }) {
                            "Encoder scale must be positive and unchanged throughout calibration"
                        }
                        val recTicks = currentTicks * (reportedDisplacement / actualDistance)

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
}
