package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MecanumVisualizer(
    currentFrame: ReplayFrame? = null,
    nt4ClientService: Nt4ClientService? = null,
    modifier: Modifier = Modifier
) {
    // FL = 0, FR = 1, RL = 2, RR = 3
    val powers = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    val velocities = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    val currents = remember { mutableStateListOf(0.0, 0.0, 0.0, 0.0) }
    var lastMotorTelemetryAtMs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    when {
        currentFrame != null -> {
            SideEffect {
                powers[0] = currentFrame.values["Drive/MotorPower_fl"] ?: currentFrame.values["Hardware/Motors/fl/Power"] ?: 0.0
                powers[1] = currentFrame.values["Drive/MotorPower_fr"] ?: currentFrame.values["Hardware/Motors/fr/Power"] ?: 0.0
                powers[2] = currentFrame.values["Drive/MotorPower_bl"] ?: currentFrame.values["Drive/MotorPower_rl"] ?: currentFrame.values["Hardware/Motors/bl/Power"] ?: currentFrame.values["Hardware/Motors/rl/Power"] ?: 0.0
                powers[3] = currentFrame.values["Drive/MotorPower_br"] ?: currentFrame.values["Drive/MotorPower_rr"] ?: currentFrame.values["Hardware/Motors/br/Power"] ?: currentFrame.values["Hardware/Motors/rr/Power"] ?: 0.0

                velocities[0] = currentFrame.values["Drive/MotorVelocity_fl"] ?: currentFrame.values["Hardware/Motors/fl/Velocity"] ?: 0.0
                velocities[1] = currentFrame.values["Drive/MotorVelocity_fr"] ?: currentFrame.values["Hardware/Motors/fr/Velocity"] ?: 0.0
                velocities[2] = currentFrame.values["Drive/MotorVelocity_bl"] ?: currentFrame.values["Drive/MotorVelocity_rl"] ?: currentFrame.values["Hardware/Motors/bl/Velocity"] ?: currentFrame.values["Hardware/Motors/rl/Velocity"] ?: 0.0
                velocities[3] = currentFrame.values["Drive/MotorVelocity_br"] ?: currentFrame.values["Drive/MotorVelocity_rr"] ?: currentFrame.values["Hardware/Motors/br/Velocity"] ?: currentFrame.values["Hardware/Motors/rr/Velocity"] ?: 0.0

                currents[0] = currentFrame.values["Drive/MotorCurrent_fl"] ?: currentFrame.values["Hardware/Motors/fl/CurrentAmps"] ?: 0.0
                currents[1] = currentFrame.values["Drive/MotorCurrent_fr"] ?: currentFrame.values["Hardware/Motors/fr/CurrentAmps"] ?: 0.0
                currents[2] = currentFrame.values["Drive/MotorCurrent_bl"] ?: currentFrame.values["Hardware/Motors/bl/CurrentAmps"] ?: currentFrame.values["Hardware/Motors/rl/CurrentAmps"] ?: 0.0
                currents[3] = currentFrame.values["Drive/MotorCurrent_br"] ?: currentFrame.values["Hardware/Motors/br/CurrentAmps"] ?: currentFrame.values["Hardware/Motors/rr/CurrentAmps"] ?: 0.0
            }
        }
        nt4ClientService != null -> {
            LaunchedEffect(nt4ClientService) {
                nt4ClientService.mecanumMotorFrame.collect { frame ->
                    if (frame == null) return@collect
                    powers[0] = frame.flPower
                    powers[1] = frame.frPower
                    powers[2] = frame.rlPower
                    powers[3] = frame.rrPower
                    velocities[0] = frame.flVelocity
                    velocities[1] = frame.frVelocity
                    velocities[2] = frame.rlVelocity
                    velocities[3] = frame.rrVelocity
                    currents[0] = frame.flCurrentAmps
                    currents[1] = frame.frCurrentAmps
                    currents[2] = frame.rlCurrentAmps
                    currents[3] = frame.rrCurrentAmps
                    lastMotorTelemetryAtMs = System.currentTimeMillis()
                }
            }
            LaunchedEffect(nt4ClientService) {
                nt4ClientService.uiTelemetryFlow.collect { frame ->
                    val key = frame.key.trimStart('/')
                    val value = frame.value
                    val accepted = when (key) {
                        "Drive/MotorPower_fl", "Hardware/Motors/fl/Power" -> true.also { powers[0] = value }
                        "Drive/MotorPower_fr", "Hardware/Motors/fr/Power" -> true.also { powers[1] = value }
                        "Drive/MotorPower_bl", "Drive/MotorPower_rl", "Hardware/Motors/bl/Power", "Hardware/Motors/rl/Power" -> true.also { powers[2] = value }
                        "Drive/MotorPower_br", "Drive/MotorPower_rr", "Hardware/Motors/br/Power", "Hardware/Motors/rr/Power" -> true.also { powers[3] = value }

                        "Drive/MotorVelocity_fl", "Hardware/Motors/fl/Velocity" -> true.also { velocities[0] = value }
                        "Drive/MotorVelocity_fr", "Hardware/Motors/fr/Velocity" -> true.also { velocities[1] = value }
                        "Drive/MotorVelocity_bl", "Drive/MotorVelocity_rl", "Hardware/Motors/bl/Velocity", "Hardware/Motors/rl/Velocity" -> true.also { velocities[2] = value }
                        "Drive/MotorVelocity_br", "Drive/MotorVelocity_rr", "Hardware/Motors/br/Velocity", "Hardware/Motors/rr/Velocity" -> true.also { velocities[3] = value }

                        "Drive/MotorCurrent_fl", "Hardware/Motors/fl/CurrentAmps" -> true.also { currents[0] = value }
                        "Drive/MotorCurrent_fr", "Hardware/Motors/fr/CurrentAmps" -> true.also { currents[1] = value }
                        "Drive/MotorCurrent_bl", "Drive/MotorCurrent_rl", "Hardware/Motors/bl/CurrentAmps", "Hardware/Motors/rl/CurrentAmps" -> true.also { currents[2] = value }
                        "Drive/MotorCurrent_br", "Drive/MotorCurrent_rr", "Hardware/Motors/br/CurrentAmps", "Hardware/Motors/rr/CurrentAmps" -> true.also { currents[3] = value }
                        else -> false
                    }
                    if (accepted) lastMotorTelemetryAtMs = System.currentTimeMillis()
                }
            }
        }
    }
    val isConnected by nt4ClientService?.isConnected?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }
    LaunchedEffect(nt4ClientService) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val replayHasMotorTelemetry = currentFrame?.values?.keys?.any(::isMecanumReplayTopic) ?: false
    val hasFreshMotorTelemetry = (currentFrame != null && replayHasMotorTelemetry) ||
        (lastMotorTelemetryAtMs != Long.MIN_VALUE && nowMs - lastMotorTelemetryAtMs <= MOTOR_TELEMETRY_FRESH_MS)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AresSurface, RoundedCornerShape(12.dp))
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Mecanum Drive Forces",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            val telemetryStatus = when {
                    currentFrame != null && replayHasMotorTelemetry -> "Replay"
                    currentFrame != null -> "Replay · no motor topics"
                    !isConnected -> "Offline"
                    hasFreshMotorTelemetry -> "Live"
                    else -> "Waiting"
                }
            Text(
                "Front ↑ · $telemetryStatus",
                color = if (hasFreshMotorTelemetry) AresGreen else if (currentFrame != null) AresAmber else AresTextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(AresBackground, RoundedCornerShape(8.dp))
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f

                val wheels = listOf(
                    WheelData("FL", -1f, -1f, Math.toRadians(45.0), powers[0], currents[0]),
                    WheelData("FR", 1f, -1f, Math.toRadians(-45.0), powers[1], currents[1]),
                    WheelData("BL", -1f, 1f, Math.toRadians(-45.0), powers[2], currents[2]),
                    WheelData("BR", 1f, 1f, Math.toRadians(45.0), powers[3], currents[3])
                )

                // Draw robot outline (dashed)
                val robotW = 160f
                val robotH = 220f
                drawRect(
                    color = AresBorder,
                    topLeft = Offset(cx - robotW / 2f, cy - robotH / 2f),
                    size = Size(robotW, robotH),
                    style = Stroke(width = 2f, pathEffect = dashEffect)
                )

                val speedScale = 1.0f

                for (w in wheels) {
                    val center = Offset(cx + w.offsetXSign * robotW / 2f, cy + w.offsetYSign * robotH / 2f)
                        val wWidth = 32f
                        val wHeight = 64f

                        // Draw wheel body
                        drawRoundRect(
                            color = AresSurfaceElevated,
                            topLeft = Offset(center.x - wWidth / 2f, center.y - wHeight / 2f),
                            size = Size(wWidth, wHeight),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                        drawRoundRect(
                            color = AresBorder,
                            topLeft = Offset(center.x - wWidth / 2f, center.y - wHeight / 2f),
                            size = Size(wWidth, wHeight),
                            cornerRadius = CornerRadius(8f, 8f),
                            style = Stroke(width = 1.5f)
                        )

                        // Draw wheel rollers (diagonal lines)
                        val spacing = 12f
                        var offset = -wHeight / 2f + 4f
                        while (offset < wHeight / 2f) {
                            val rx1 = center.x - wWidth / 2f + 2f
                            val ry1 = center.y + offset
                            val rx2 = center.x + wWidth / 2f - 2f
                            val ry2 = ry1 + wWidth * tan(w.rollerAngle).toFloat()

                            if (ry2 >= center.y - wHeight / 2f && ry2 <= center.y + wHeight / 2f) {
                                drawLine(
                                    color = AresBorder.copy(alpha = 0.6f),
                                    start = Offset(rx1, ry1),
                                    end = Offset(rx2, ry2),
                                    strokeWidth = 2.5f,
                                    cap = StrokeCap.Round
                                )
                            }
                            offset += spacing
                        }

                        // Draw spin vector arrow (along wheel axis, vertically)
                        val normalizedSpeed = (w.speed / speedScale).toFloat()
                        if (Math.abs(normalizedSpeed) > 0.05f) {
                            val maxArrowLen = 40f
                            val arrowLen = normalizedSpeed * maxArrowLen
                            val spinEnd = Offset(center.x, center.y - arrowLen)

                            drawLine(
                                color = AresGreen,
                                start = center,
                                end = spinEnd,
                                strokeWidth = 3.5f,
                                cap = StrokeCap.Round
                            )

                            // Draw traction force vector arrow (at 45 degrees, matching roller slide reaction)
                            // The traction force vector points along the roller's perpendicular axis (direction of push)
                            val forceAngle = getForceAngle(w.name, w.speed)
                            val forceLen = Math.abs(normalizedSpeed * maxArrowLen)
                            val forceEnd = Offset(
                                center.x + forceLen * cos(forceAngle).toFloat(),
                                center.y + forceLen * sin(forceAngle).toFloat()
                            )

                            drawLine(
                                color = AresCyan,
                                start = center,
                                end = forceEnd,
                                strokeWidth = 3.5f,
                                cap = StrokeCap.Round
                            )

                            // Draw force arrowhead
                            val headSize = 8f
                            val leftWing = Offset(
                                forceEnd.x - headSize * cos(forceAngle - Math.PI / 6).toFloat(),
                                forceEnd.y - headSize * sin(forceAngle - Math.PI / 6).toFloat()
                            )
                            val rightWing = Offset(
                                forceEnd.x - headSize * cos(forceAngle + Math.PI / 6).toFloat(),
                                forceEnd.y - headSize * sin(forceAngle + Math.PI / 6).toFloat()
                            )
                            drawLine(color = AresCyan, start = forceEnd, end = leftWing, strokeWidth = 2.5f)
                            drawLine(color = AresCyan, start = forceEnd, end = rightWing, strokeWidth = 2.5f)
                        }
                    }

                    // Calculate and draw net force vector in the center using Mecanum forward kinematics
                    val fl = (wheels.getOrNull(0)?.speed ?: 0.0) / speedScale
                    val fr = (wheels.getOrNull(1)?.speed ?: 0.0) / speedScale
                    val bl = (wheels.getOrNull(2)?.speed ?: 0.0) / speedScale
                    val br = (wheels.getOrNull(3)?.speed ?: 0.0) / speedScale

                    val netForce = mecanumNetScreenVector(fl, fr, bl, br)

                    val netMagnitude = Math.sqrt(
                        netForce.x * netForce.x + netForce.y * netForce.y
                    ).toFloat()
                    if (netMagnitude > 0.05f) {
                        val maxNetArrowLen = 100f
                        val arrowLen = (netMagnitude * maxNetArrowLen).coerceAtMost(maxNetArrowLen)
                        val netAngle = Math.atan2(netForce.y, netForce.x)
                        val netStart = Offset(cx, cy)
                        val netEnd = Offset(
                            cx + arrowLen * cos(netAngle).toFloat(),
                            cy + arrowLen * sin(netAngle).toFloat()
                        )

                        drawLine(
                            color = AresAmber, // use Amber to stand out from Cyan wheel vectors
                            start = netStart,
                            end = netEnd,
                            strokeWidth = 6f, // thicker line for net vector
                            cap = StrokeCap.Round
                        )

                        // Draw net force arrowhead
                        val headSize = 14f
                        val leftWing = Offset(
                            netEnd.x - headSize * cos(netAngle - Math.PI / 6).toFloat(),
                            netEnd.y - headSize * sin(netAngle - Math.PI / 6).toFloat()
                        )
                        val rightWing = Offset(
                            netEnd.x - headSize * cos(netAngle + Math.PI / 6).toFloat(),
                            netEnd.y - headSize * sin(netAngle + Math.PI / 6).toFloat()
                        )
                        drawLine(color = AresAmber, start = netEnd, end = leftWing, strokeWidth = 4f, cap = StrokeCap.Round)
                        drawLine(color = AresAmber, start = netEnd, end = rightWing, strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                }

            if (currentFrame != null && !replayHasMotorTelemetry) {
                Text(
                    text = "This recording has no drivetrain motor topics.\nZero output is not being inferred.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        MecanumDetailsPanel(powers = powers, velocities = velocities, currents = currents)
    }
}

internal fun isMecanumReplayTopic(key: String): Boolean =
    key.startsWith("Drive/MotorPower_") ||
        key.startsWith("Drive/MotorVelocity_") ||
        key.startsWith("Drive/MotorCurrent_") ||
        (key.startsWith("Hardware/Motors/") &&
            (key.endsWith("/Power") || key.endsWith("/Velocity") || key.endsWith("/CurrentAmps")))

@Composable
fun MecanumDetailsPanel(powers: List<Double>, velocities: List<Double>, currents: List<Double>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(mecanumDetail("FL", powers[0], velocities[0], currents[0]), color = AresTextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(mecanumDetail("RL", powers[2], velocities[2], currents[2]), color = AresTextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(mecanumDetail("FR", powers[1], velocities[1], currents[1]), color = AresTextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(mecanumDetail("RR", powers[3], velocities[3], currents[3]), color = AresTextSecondary, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
    }
}

internal fun mecanumDetail(name: String, power: Double, velocityTicksPerSecond: Double, currentAmps: Double): String =
    "$name: out ${"%+.2f".format(power)} | ${"%.0f".format(velocityTicksPerSecond)} ticks/s | ${"%.2f".format(currentAmps)} A"

internal data class MecanumScreenVector(val x: Double, val y: Double)

/**
 * Converts robot-relative mecanum motion into this card's screen coordinates.
 *
 * Robot +X is forward and +Y is left. The chassis drawing faces the top of the card,
 * while screen +X points right and +Y points down; therefore screen X = -robot Y and
 * screen Y = -robot X.
 */
internal fun mecanumNetScreenVector(
    fl: Double,
    fr: Double,
    rl: Double,
    rr: Double,
): MecanumScreenVector {
    val robotForward = (fl + fr + rl + rr) / 4.0
    val robotLeft = (-fl + fr + rl - rr) / 4.0
    return MecanumScreenVector(x = -robotLeft, y = -robotForward)
}

private data class WheelData(
    val name: String,
    val offsetXSign: Float,
    val offsetYSign: Float,
    val rollerAngle: Double,
    val speed: Double,
    val current: Double
)

private fun getForceAngle(name: String, speed: Double): Double {
    val isForward = speed >= 0
    return when {
        name == "FL" && isForward -> Math.toRadians(-45.0)
        name == "FL" && !isForward -> Math.toRadians(135.0)
        name == "FR" && isForward -> Math.toRadians(-135.0)
        name == "FR" && !isForward -> Math.toRadians(45.0)
        (name == "BL" || name == "RL") && isForward -> Math.toRadians(-135.0)
        (name == "BL" || name == "RL") && !isForward -> Math.toRadians(45.0)
        (name == "BR" || name == "RR") && isForward -> Math.toRadians(-45.0)
        (name == "BR" || name == "RR") && !isForward -> Math.toRadians(135.0)
        else -> 0.0
    }
}

private fun tan(radians: Double): Double = kotlin.math.tan(radians)

private const val MOTOR_TELEMETRY_FRESH_MS = 750L
