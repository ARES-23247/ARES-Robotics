package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.viewmodel.SysIdState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.ejml.simple.SimpleMatrix

/** Runs feedforward regression and transient classification for the selected mechanism. */
class SysIdRegressionSolver(
    private val nt4ClientService: Nt4ClientService,
    private val _state: MutableStateFlow<SysIdState>
) {
    fun runCalibrationAnalysis(calibrationType: String, data: List<DoubleArray>) {
        if (data.size < 10) {
            _state.update { it.copy(errorMessage = "Not enough calibration data collected (minimum 10 points)") }
            return
        }

        try {
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
                        var diff = currentHeading - lastHeading
                        while (diff < -kotlin.math.PI) diff += 2 * kotlin.math.PI
                        while (diff > kotlin.math.PI) diff -= 2 * kotlin.math.PI
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
                    val k = if (sumX2 > 1e-6) sumXY / sumX2 else 0.45
                    // This analysis cannot infer a schema-v3 declaration UID from a display key.
                    // Geometry is reviewed on the Drivebase Builder before promotion.
                    val wheelBase = 0.45
                    val recTrackWidth = 2.0 * k - wheelBase

                    _state.update {
                        it.copy(
                            recommendedTrackWidthMeters = recTrackWidth,
                            errorMessage = null
                        )
                    }
                }
                "VISION_CALIBRATION" -> {
                    val n = data.size
                    val meanX = data.map { it[1] }.average()
                    val meanY = data.map { it[2] }.average()
                    val meanHeading = data.map { it[3] }.average()
                    var varX = 0.0
                    var varY = 0.0
                    var varHeading = 0.0

                    for (row in data) {
                        val dx = row[1] - meanX
                        val dy = row[2] - meanY
                        var dHeading = row[3] - meanHeading
                        while (dHeading < -kotlin.math.PI) dHeading += 2 * kotlin.math.PI
                        while (dHeading > kotlin.math.PI) dHeading -= 2 * kotlin.math.PI

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

                    if (actualDistance > 0.1 && reportedDisplacement > 0.05) {
                        val currentTicks = 2000.0
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
