package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import kotlin.math.*

data class EkfSimState(
    val trueX: Double,
    val trueY: Double,
    val odomX: Double,
    val odomY: Double,
    val ekfX: Double,
    val ekfY: Double,
    val sigmaX: Double,
    val sigmaY: Double,
    val acceptedVisionCount: Int,
    val rejectedVisionCount: Int
)

/** A deliberately simplified diagonal 2-D Kalman teaching model, not the production ARES EKF. */
object EkfMath {

    fun simulateEkfFusion(
        processNoiseQ: Double = 0.05,
        measurementNoiseR: Double = 0.02,
        mahalanobisGate: Double = 9.0, // 3-sigma gate
        visionIntervalSteps: Int = 10,
        injectVisionOutlier: Boolean = true,
        steps: Int = 100,
        dt: Double = 0.02
    ): List<EkfSimState> {
        require(processNoiseQ.isFinite() && processNoiseQ > 0.0) { "Process noise Q must be finite and positive" }
        require(measurementNoiseR.isFinite() && measurementNoiseR > 0.0) { "Measurement noise R must be finite and positive" }
        require(mahalanobisGate.isFinite() && mahalanobisGate > 0.0) { "Gate must be finite and positive" }
        require(visionIntervalSteps in 1..5_000) { "Vision interval must be between 1 and 5000 steps" }
        require(steps in 1..5_000) { "Step count must be between 1 and 5000" }
        require(dt.isFinite() && dt in 0.001..0.050) { "Time step must be between 1 ms and 50 ms" }
        val history = ArrayList<EkfSimState>(steps + 1)

        var trueX = 0.0
        var trueY = 0.0
        var odomX = 0.0
        var odomY = 0.0
        var ekfX = 0.0
        var ekfY = 0.0

        var pXx = 0.001
        var pYy = 0.001

        var acceptedCount = 0
        var rejectedCount = 0

        // Landmark tag at (2.0, 1.5)
        val tagX = 2.0
        val tagY = 1.5

        for (step in 0..steps) {
            val t = step * dt
            val vx = 1.0 * cos(t * 1.5)
            val vy = 0.8 * sin(t * 1.5)

            // Ground truth motion
            trueX += vx * dt
            trueY += vy * dt

            // Odometry with systematic drift + noise
            val odomNoiseX = (sin(step * 0.7) * 0.03 + 0.01) * processNoiseQ * 10.0
            val odomNoiseY = (cos(step * 0.5) * 0.03 + 0.01) * processNoiseQ * 10.0
            odomX += (vx + odomNoiseX) * dt
            odomY += (vy + odomNoiseY) * dt

            // 1. Diagonal Kalman predict (this teaching model has no nonlinear Jacobian).
            ekfX += vx * dt
            ekfY += vy * dt
            pXx += processNoiseQ * dt
            pYy += processNoiseQ * dt

            // 2. Periodic Vision Update
            if (step > 0 && step % visionIntervalSteps == 0) {
                val isOutlier = injectVisionOutlier && step == 50
                val rawVisionX = trueX + (if (isOutlier) 0.6 else (sin(step.toDouble()) * 0.02 * measurementNoiseR * 10.0))
                val rawVisionY = trueY + (if (isOutlier) -0.5 else (cos(step.toDouble()) * 0.02 * measurementNoiseR * 10.0))

                // Residual innovation
                val resX = rawVisionX - ekfX
                val resY = rawVisionY - ekfY

                // Innovation covariance S = P + R
                val sXx = pXx + measurementNoiseR
                val sYy = pYy + measurementNoiseR

                // Mahalanobis distance D_M^2 = r^T * S^-1 * r
                val dMahalanobisSq = (resX * resX) / sXx + (resY * resY) / sYy

                if (dMahalanobisSq <= mahalanobisGate) {
                    // Accept observation & compute Kalman Gain K = P * S^-1
                    val kXx = pXx / sXx
                    val kYy = pYy / sYy

                    ekfX += kXx * resX
                    ekfY += kYy * resY

                    // Update covariance P = (I - K) * P
                    pXx = (1.0 - kXx) * pXx
                    pYy = (1.0 - kYy) * pYy
                    acceptedCount++
                } else {
                    rejectedCount++
                }
            }

            history.add(
                EkfSimState(
                    trueX = trueX,
                    trueY = trueY,
                    odomX = odomX,
                    odomY = odomY,
                    ekfX = ekfX,
                    ekfY = ekfY,
                    sigmaX = sqrt(pXx.coerceAtLeast(1e-6)),
                    sigmaY = sqrt(pYy.coerceAtLeast(1e-6)),
                    acceptedVisionCount = acceptedCount,
                    rejectedVisionCount = rejectedCount
                )
            )
        }

        return history
    }
}

@Composable
fun EkfSensorFusionLabCard(
    modifier: Modifier = Modifier
) {
    var processNoiseQ by remember { mutableFloatStateOf(0.05f) }
    var measurementNoiseR by remember { mutableFloatStateOf(0.02f) }
    var mahalanobisGate by remember { mutableFloatStateOf(9.0f) }
    var visionIntervalSteps by remember { mutableIntStateOf(10) }
    var showTheory by remember { mutableStateOf(false) }

    val simResults = remember(processNoiseQ, measurementNoiseR, mahalanobisGate, visionIntervalSteps) {
        EkfMath.simulateEkfFusion(
            processNoiseQ = processNoiseQ.toDouble(),
            measurementNoiseR = measurementNoiseR.toDouble(),
            mahalanobisGate = mahalanobisGate.toDouble(),
            visionIntervalSteps = visionIntervalSteps
        )
    }

    val latest = simResults.lastOrNull()

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("2-D Kalman Sensor-Fusion Teaching Lab", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text("Explore a simplified diagonal filter. It does not run the production ARES EKF, save calibration, command hardware, or validate a robot.", color = AresTextSecondary, fontSize = 12.sp)
                }
            }

            // 2D Field Pose & Uncertainty Ellipse Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresBackground)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "Teaching plot with simulated truth, drifting odometry, and a simplified fused estimate"
                }) {
                    val w = size.width
                    val h = size.height

                    val minX = -0.5f
                    val maxX = 2.5f
                    val minY = -0.5f
                    val maxY = 2.0f

                    fun toScreen(x: Double, y: Double): Offset {
                        val px = ((x.toFloat() - minX) / (maxX - minX)) * w
                        val py = h - ((y.toFloat() - minY) / (maxY - minY)) * h
                        return Offset(px, py)
                    }

                    if (simResults.isNotEmpty()) {
                        val truePath = Path()
                        val odomPath = Path()
                        val ekfPath = Path()

                        simResults.forEachIndexed { index, s ->
                            val ptTrue = toScreen(s.trueX, s.trueY)
                            val ptOdom = toScreen(s.odomX, s.odomY)
                            val ptEkf = toScreen(s.ekfX, s.ekfY)

                            if (index == 0) {
                                truePath.moveTo(ptTrue.x, ptTrue.y)
                                odomPath.moveTo(ptOdom.x, ptOdom.y)
                                ekfPath.moveTo(ptEkf.x, ptEkf.y)
                            } else {
                                truePath.lineTo(ptTrue.x, ptTrue.y)
                                odomPath.lineTo(ptOdom.x, ptOdom.y)
                                ekfPath.lineTo(ptEkf.x, ptEkf.y)
                            }
                        }

                        // Draw Ground Truth in faint Green
                        drawPath(truePath, AresGreen.copy(alpha = 0.5f), style = Stroke(width = 1.5f))
                        // Draw Drifted Odometry in dashed Amber
                        drawPath(odomPath, AresAmber, style = Stroke(width = 1.5f))
                        // Draw EKF Estimate in solid Cyan
                        drawPath(ekfPath, AresCyan, style = Stroke(width = 2.5f))

                        // Draw 3-sigma Covariance Ellipse around current EKF estimate
                        latest?.let { l ->
                            val center = toScreen(l.ekfX, l.ekfY)
                            val rx = (l.sigmaX.toFloat() * 3.0f / (maxX - minX)) * w
                            val ry = (l.sigmaY.toFloat() * 3.0f / (maxY - minY)) * h
                            drawOval(
                                color = AresCyan.copy(alpha = 0.15f),
                                topLeft = Offset(center.x - rx, center.y - ry),
                                size = Size(rx * 2, ry * 2)
                            )
                            drawOval(
                                color = AresCyan,
                                topLeft = Offset(center.x - rx, center.y - ry),
                                size = Size(rx * 2, ry * 2),
                                style = Stroke(width = 1.2f)
                            )
                        }
                    }
                }

                // Overlay Legend
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Green: Truth", color = AresGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Amber: Raw Odom (Drift)", color = AresAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Cyan: Simplified fusion", color = AresCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Stat Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Vision Accepted", fontSize = 9.sp, color = AresTextTertiary)
                    Text("${latest?.acceptedVisionCount ?: 0}", fontSize = 13.sp, color = AresGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Outliers Gated", fontSize = 9.sp, color = AresTextTertiary)
                    Text("${latest?.rejectedVisionCount ?: 0}", fontSize = 13.sp, color = AresError, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Uncertainty 3σ", fontSize = 9.sp, color = AresTextTertiary)
                    Text("±${"%.2f".format((latest?.sigmaX ?: 0.0) * 3.0 * 100)} cm", fontSize = 13.sp, color = AresCyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            // Interactive Sliders
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Process Noise Q (Wheel Slip): ${"%.3f".format(processNoiseQ)}", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = processNoiseQ, onValueChange = { processNoiseQ = it }, valueRange = 0.01f..0.20f)

                Text("Measurement Noise R (Camera Jitter): ${"%.3f".format(measurementNoiseR)}", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = measurementNoiseR, onValueChange = { measurementNoiseR = it }, valueRange = 0.005f..0.10f)

                Text("Mahalanobis Gate χ² Threshold: ${"%.1f".format(mahalanobisGate)} (3σ = 9.0)", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = mahalanobisGate, onValueChange = { mahalanobisGate = it }, valueRange = 1.0f..25.0f)
            }

            // Educational Concept Toggle
            TextButton(onClick = { showTheory = !showTheory }) {
                Icon(if (showTheory) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (showTheory) "Hide teaching-model details" else "Learn how this simplified model works", fontSize = 11.sp, color = AresCyan)
            }

            if (showTheory) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AresSurface)
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("1. Prediction vs. Correction Steps", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("Each simulated time step predicts position from motion while uncertainty grows. Periodic synthetic vision samples may correct the estimate. Real sensor rates, latency, correlations, and robot dynamics are not modeled here.", color = AresTextTertiary, fontSize = 10.sp)

                    Text("2. Kalman Gain K = P / (P + R)", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("Kalman Gain dynamically balances confidence. When odometry is uncertain (P is high) or vision is pristine (R is low), K approaches 1.0 (trusting vision). When odometry is sharp and vision is noisy, K approaches 0.0.", color = AresTextTertiary, fontSize = 10.sp)

                    Text("3. Mahalanobis Distance Outlier Rejection", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("The model compares each synthetic observation with its prediction using D_M² = rᵀ S⁻¹ r. Samples above the selected teaching threshold are rejected; this is not proof that a real camera measurement is safe or unsafe.", color = AresTextTertiary, fontSize = 10.sp)
                }
            }
        }
    }
}
