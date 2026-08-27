package com.ares.analytics.ui.components.pathplanner

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

/** 2D Point with derivatives along a parametric trajectory. */
data class SplineSample(
    val u: Double,
    val x: Double,
    val y: Double,
    val dx: Double,
    val dy: Double,
    val ddx: Double,
    val ddy: Double,
    val curvature: Double,
    val maxVelocity: Double
)

/** Motion profile point at time t. */
data class MotionProfilePoint(
    val t: Double,
    val pos: Double,
    val vel: Double,
    val accel: Double,
    val jerk: Double
)

object SplineMath {

    /** Evaluates a 2D Quintic Hermite Spline between (x0, y0, th0) and (x1, y1, th1). */
    fun sampleQuinticSpline(
        x0: Double, y0: Double, th0: Double,
        x1: Double, y1: Double, th1: Double,
        scale: Double = 1.5,
        maxCentripetalAccel: Double = 3.0,
        maxVelocity: Double = 3.5,
        samples: Int = 100
    ): List<SplineSample> {
        require(listOf(x0, y0, th0, x1, y1, th1, scale, maxCentripetalAccel, maxVelocity).all(Double::isFinite)) {
            "Spline inputs must be finite"
        }
        require(scale > 0.0 && maxCentripetalAccel > 0.0 && maxVelocity > 0.0) {
            "Spline scale and limits must be positive"
        }
        require(samples in 2..5_000) { "Spline sample count must be between 2 and 5000" }
        val vx0 = cos(th0) * scale
        val vy0 = sin(th0) * scale
        val vx1 = cos(th1) * scale
        val vy1 = sin(th1) * scale

        val result = ArrayList<SplineSample>(samples + 1)
        for (i in 0..samples) {
            val u = i.toDouble() / samples
            val u2 = u * u
            val u3 = u2 * u
            val u4 = u3 * u
            val u5 = u4 * u

            // Quintic basis functions with zero 2nd derivative at endpoints
            val h0 = 1.0 - 10.0 * u3 + 15.0 * u4 - 6.0 * u5
            val h1 = 10.0 * u3 - 15.0 * u4 + 6.0 * u5
            val h2 = u - 6.0 * u3 + 8.0 * u4 - 3.0 * u5
            val h3 = -4.0 * u3 + 7.0 * u4 - 3.0 * u5

            val dh0 = -30.0 * u2 + 60.0 * u3 - 30.0 * u4
            val dh1 = 30.0 * u2 - 60.0 * u3 + 30.0 * u4
            val dh2 = 1.0 - 18.0 * u2 + 32.0 * u3 - 15.0 * u4
            val dh3 = -12.0 * u2 + 28.0 * u3 - 15.0 * u4

            val ddh0 = -60.0 * u + 180.0 * u2 - 120.0 * u3
            val ddh1 = 60.0 * u - 180.0 * u2 + 120.0 * u3
            val ddh2 = -36.0 * u + 96.0 * u2 - 60.0 * u3
            val ddh3 = -24.0 * u + 84.0 * u2 - 60.0 * u3

            val x = h0 * x0 + h1 * x1 + h2 * vx0 + h3 * vx1
            val y = h0 * y0 + h1 * y1 + h2 * vy0 + h3 * vy1

            val dx = dh0 * x0 + dh1 * x1 + dh2 * vx0 + dh3 * vx1
            val dy = dh0 * y0 + dh1 * y1 + dh2 * vy0 + dh3 * vy1

            val ddx = ddh0 * x0 + ddh1 * x1 + ddh2 * vx0 + ddh3 * vx1
            val ddy = ddh0 * y0 + ddh1 * y1 + ddh2 * vy0 + ddh3 * vy1

            val speedSq = dx * dx + dy * dy
            val curvature = if (speedSq > 1e-6) {
                (dx * ddy - dy * ddx) / (speedSq * sqrt(speedSq))
            } else {
                0.0
            }

            val absCurv = abs(curvature)
            val vCurv = if (absCurv > 1e-4) sqrt(maxCentripetalAccel / absCurv) else maxVelocity
            val vMax = min(maxVelocity, vCurv)

            result.add(SplineSample(u, x, y, dx, dy, ddx, ddy, curvature, vMax))
        }
        return result
    }

    /** Generates a 1D Jerk-Limited S-Curve profile for distance D. */
    fun generateSCurveProfile(
        distance: Double,
        maxVel: Double,
        maxAccel: Double,
        maxJerk: Double,
        dt: Double = 0.01
    ): List<MotionProfilePoint> {
        require(listOf(distance, maxVel, maxAccel, maxJerk, dt).all(Double::isFinite)) {
            "Motion-profile inputs must be finite"
        }
        require(distance > 0.0 && maxVel > 0.0 && maxAccel > 0.0 && maxJerk > 0.0) {
            "Distance and motion limits must be positive"
        }
        require(dt in 0.001..0.050) { "Time step must be between 1 ms and 50 ms" }
        val maxSamples = kotlin.math.ceil(10.0 / dt).toInt() + 2
        val points = ArrayList<MotionProfilePoint>(maxSamples)
        var t = 0.0
        var pos = 0.0
        var vel = 0.0
        var accel = 0.0

        // Time to ramp acceleration to maxAccel: t_j = a_max / j_max
        val tj = min(maxAccel / maxJerk, sqrt(maxVel / maxJerk))
        val aMaxActual = maxJerk * tj
        val vRamp = aMaxActual * tj

        // Simplified 7-phase integration
        while (t < 10.0) {
            val remainingDist = (distance - pos).coerceAtLeast(0.0)
            val stoppingDist = (vel * vel) / (2.0 * maxAccel.coerceAtLeast(0.1))

            if (remainingDist <= 0.01 && vel <= 0.05) {
                points.add(MotionProfilePoint(t, distance, 0.0, 0.0, 0.0))
                break
            }

            val targetJerk = when {
                remainingDist <= stoppingDist && accel > -aMaxActual -> -maxJerk
                remainingDist <= stoppingDist && accel <= -aMaxActual -> 0.0
                vel < maxVel && accel < aMaxActual -> maxJerk
                vel >= maxVel && accel > 0.0 -> -maxJerk
                else -> 0.0
            }

            accel = (accel + targetJerk * dt).coerceIn(-aMaxActual, aMaxActual)
            vel = (vel + accel * dt).coerceIn(0.0, maxVel)
            pos = (pos + vel * dt).coerceAtMost(distance)

            points.add(MotionProfilePoint(t, pos, vel, accel, targetJerk))
            t += dt
            if (pos >= distance && vel <= 0.05) break
        }
        return points
    }
}

@Composable
fun MotionProfileLabCard(
    modifier: Modifier = Modifier
) {
    var maxVelocity by remember { mutableFloatStateOf(3.0f) }
    var maxAcceleration by remember { mutableFloatStateOf(2.5f) }
    var maxJerk by remember { mutableFloatStateOf(15.0f) }
    var maxCentripetalAccel by remember { mutableFloatStateOf(2.0f) }
    var endHeadingDeg by remember { mutableFloatStateOf(90.0f) }
    var showTheory by remember { mutableStateOf(false) }

    val splineSamples = remember(maxVelocity, maxCentripetalAccel, endHeadingDeg) {
        SplineMath.sampleQuinticSpline(
            x0 = 0.0, y0 = 0.0, th0 = 0.0,
            x1 = 2.5, y1 = 1.5, th1 = Math.toRadians(endHeadingDeg.toDouble()),
            scale = 2.0,
            maxCentripetalAccel = maxCentripetalAccel.toDouble(),
            maxVelocity = maxVelocity.toDouble()
        )
    }

    val sCurvePoints = remember(maxVelocity, maxAcceleration, maxJerk) {
        SplineMath.generateSCurveProfile(
            distance = 3.0,
            maxVel = maxVelocity.toDouble(),
            maxAccel = maxAcceleration.toDouble(),
            maxJerk = maxJerk.toDouble()
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Spline & Motion-Profile Teaching Lab", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text("Explore a normalized spline and S-curve approximation. It does not save a robot path, model traction or temperature, or validate hardware limits.", color = AresTextSecondary, fontSize = 12.sp)
                }
            }

            // 2D Spline Curvature Canvas
            Text("2D Spline Curvature Heatmap (Color = Centripetal Stress):", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresBackground)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "Teaching plot of a spline colored by curvature, with start and goal labels"
                }) {
                    val w = size.width
                    val h = size.height

                    // Draw start and goal points
                    val minX = -0.2f
                    val maxX = 3.0f
                    val minY = -0.2f
                    val maxY = 2.0f

                    fun toScreen(x: Double, y: Double): Offset {
                        val px = ((x.toFloat() - minX) / (maxX - minX)) * w
                        val py = h - ((y.toFloat() - minY) / (maxY - minY)) * h
                        return Offset(px, py)
                    }

                    // Draw path segments color-coded by curvature
                    for (i in 0 until splineSamples.size - 1) {
                        val s1 = splineSamples[i]
                        val s2 = splineSamples[i + 1]
                        val p1 = toScreen(s1.x, s1.y)
                        val p2 = toScreen(s2.x, s2.y)

                        val absCurv = abs(s1.curvature)
                        val segColor = when {
                            absCurv > 2.0 -> AresError
                            absCurv > 0.8 -> AresAmber
                            else -> AresCyan
                        }

                        drawLine(
                            color = segColor,
                            start = p1,
                            end = p2,
                            strokeWidth = 3.5f
                        )
                    }

                    // Start node
                    drawCircle(color = AresGreen, radius = 6f, center = toScreen(0.0, 0.0))
                    // End node
                    drawCircle(color = AresGold, radius = 6f, center = toScreen(2.5, 1.5))
                }

                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Green: Start", color = AresGreen, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Gold: Goal", color = AresGold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("Red: High Curvature (Slow Zone)", color = AresError, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // S-Curve Velocity & Acceleration Plot
            Text("Jerk-Limited S-Curve (v(t) in Cyan, a(t) in Gold):", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresBackground)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "Teaching plot of approximate velocity and acceleration over time"
                }) {
                    val w = size.width
                    val h = size.height

                    if (sCurvePoints.isNotEmpty()) {
                        val maxT = sCurvePoints.last().t.coerceAtLeast(0.5).toFloat()
                        val vPath = Path()
                        val aPath = Path()

                        sCurvePoints.forEachIndexed { index, pt ->
                            val px = (pt.t.toFloat() / maxT) * w
                            val pyV = h - (pt.vel.toFloat() / (maxVelocity * 1.2f)) * (h * 0.85f)
                            val pyA = (h * 0.5f) - (pt.accel.toFloat() / (maxAcceleration * 1.5f)) * (h * 0.40f)

                            if (index == 0) {
                                vPath.moveTo(px, pyV)
                                aPath.moveTo(px, pyA)
                            } else {
                                vPath.lineTo(px, pyV)
                                aPath.lineTo(px, pyA)
                            }
                        }

                        // Zero acceleration line
                        drawLine(
                            color = AresTextTertiary.copy(alpha = 0.4f),
                            start = Offset(0f, h * 0.5f),
                            end = Offset(w, h * 0.5f),
                            strokeWidth = 1f
                        )

                        drawPath(vPath, AresCyan, style = Stroke(width = 2f))
                        drawPath(aPath, AresGold, style = Stroke(width = 2f))
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("v(t) Velocity (m/s)", color = AresCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("a(t) Accel (m/s²)", color = AresGold, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Interactive Sliders
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Max Velocity: ${"%.2f".format(maxVelocity)} m/s", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = maxVelocity, onValueChange = { maxVelocity = it }, valueRange = 0.5f..5.0f)

                Text("Max Acceleration: ${"%.2f".format(maxAcceleration)} m/s²", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = maxAcceleration, onValueChange = { maxAcceleration = it }, valueRange = 0.5f..6.0f)

                Text("Max Jerk (Smoothness): ${"%.1f".format(maxJerk)} m/s³", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = maxJerk, onValueChange = { maxJerk = it }, valueRange = 2.0f..40.0f)

                Text("Max Centripetal Accel (Cornering): ${"%.2f".format(maxCentripetalAccel)} m/s²", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = maxCentripetalAccel, onValueChange = { maxCentripetalAccel = it }, valueRange = 0.5f..4.0f)

                Text("End Heading Angle: ${endHeadingDeg.toInt()}°", color = AresTextPrimary, fontSize = 11.sp)
                Slider(value = endHeadingDeg, onValueChange = { endHeadingDeg = it }, valueRange = -180f..180f)
            }

            // Educational Concept Toggle
            TextButton(onClick = { showTheory = !showTheory }) {
                Icon(if (showTheory) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (showTheory) "Hide Path Theory" else "Learn How Splines & S-Curves Work", fontSize = 11.sp, color = AresCyan)
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
                    Text("1. Quintic Hermite Splines (C² Continuity)", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("This quintic construction illustrates continuous position, first derivative, and second derivative along one segment. A complete robot trajectory still needs module, timing, and controller validation.", color = AresTextTertiary, fontSize = 10.sp)

                    Text("2. Centripetal Acceleration Limit (v = √(ac / κ))", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("The teaching limit v = √(ac / |κ|) lowers displayed speed in tighter curves. Actual traction depends on mass, center of gravity, tires, surface, and control behavior, which this model does not measure.", color = AresTextTertiary, fontSize = 10.sp)

                    Text("3. Jerk-Limited S-Curves vs. Trapezoid Profiles", fontWeight = FontWeight.Bold, color = AresCyan, fontSize = 11.sp)
                    Text("This numerical approximation ramps acceleration using a jerk limit. It illustrates smoother commands than an ideal trapezoid corner, but does not predict gearbox shock, current, or battery response.", color = AresTextTertiary, fontSize = 10.sp)
                }
            }
        }
    }
}
