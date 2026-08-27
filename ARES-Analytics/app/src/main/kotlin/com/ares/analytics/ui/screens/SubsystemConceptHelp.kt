package com.ares.analytics.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
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
import com.areslib.control.feedback.LinearADRC
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.SubsystemHomingComparison
import com.areslib.subsystem.SubsystemHomingDocument
import java.awt.Desktop
import java.net.URI
import kotlin.math.*

private const val SUBSYSTEM_GUIDE =
    "https://github.com/ARES-23247/ARES-Analytics/blob/master/docs/SUBSYSTEM_BUILDER.md"

/** Keyboard-focusable, hoverable help for a concept used by the subsystem form. */
// rememberPlainTooltipPositionProvider: the recommended
// rememberTooltipPositionProvider is not shipped by this Compose
// version; migrate at the next Compose bump.
@Suppress("DEPRECATION")
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ConceptHelp(
    title: String,
    explanation: String,
    anchor: String,
    compact: Boolean = false,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Column(
                    modifier = Modifier.padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(explanation)
                    Text("Press the help button for the full guide.", color = AresCyan)
                }
            }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = { openSubsystemGuide(anchor) },
            modifier = if (compact) Modifier.size(32.dp) else Modifier,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.HelpOutline,
                contentDescription = "Learn about $title",
                tint = AresCyan,
                modifier = if (compact) Modifier.size(17.dp) else Modifier,
            )
        }
    }
}

@Composable
internal fun HomingConceptLab(homing: SubsystemHomingDocument) {
    if (homing.evidence.isEmpty()) return
    val evidence = homing.evidence.first()
    var numericSignal by remember(evidence.fieldId, evidence.threshold) {
        mutableFloatStateOf((evidence.threshold ?: 1.0).toFloat())
    }
    var booleanSignal by remember(evidence.fieldId) { mutableStateOf(false) }
    var heldMs by remember(homing.method, homing.dwellMs) { mutableFloatStateOf(0f) }
    val conditionMet = when (evidence.comparison) {
        SubsystemHomingComparison.TRUE -> booleanSignal
        SubsystemHomingComparison.FALSE -> !booleanSignal
        SubsystemHomingComparison.AT_OR_ABOVE -> numericSignal.toDouble() >= (evidence.threshold ?: 0.0)
        SubsystemHomingComparison.AT_OR_BELOW -> numericSignal.toDouble() <= (evidence.threshold ?: 0.0)
        SubsystemHomingComparison.ABS_AT_OR_ABOVE -> abs(numericSignal.toDouble()) >= (evidence.threshold ?: 0.0)
        SubsystemHomingComparison.ABS_AT_OR_BELOW -> abs(numericSignal.toDouble()) <= (evidence.threshold ?: 0.0)
    }
    val dwellProgress = if (homing.dwellMs <= 0L) 1f else (heldMs / homing.dwellMs).coerceIn(0f, 1f)
    val wouldHome = conditionMet && dwellProgress >= 1f

    LearningLabCard(
        title = "Try the homing evidence",
        explanation = "This preview does not command hardware. It shows why one sample is not enough: the evidence must remain true for the dwell time before zero is established.",
    ) {
        if (evidence.comparison == SubsystemHomingComparison.TRUE || evidence.comparison == SubsystemHomingComparison.FALSE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${evidence.fieldId} is active", color = AresTextPrimary, modifier = Modifier.weight(1f))
                Switch(checked = booleanSignal, onCheckedChange = { booleanSignal = it })
            }
        } else {
            Text("Observed ${evidence.fieldId}: ${"%.2f".format(numericSignal)}", color = AresTextPrimary)
            Slider(
                value = numericSignal,
                onValueChange = { numericSignal = it },
                valueRange = -20f..20f,
            )
        }
        Text("Evidence held for ${heldMs.toInt()} ms (required ${homing.dwellMs} ms)", color = AresTextSecondary)
        Slider(
            value = heldMs,
            onValueChange = { heldMs = it },
            valueRange = 0f..homing.timeoutMs.coerceAtLeast(1L).toFloat(),
        )
        LinearProgressIndicator(progress = { dwellProgress }, modifier = Modifier.fillMaxWidth())
        Text(
            when {
                !conditionMet -> "Result: keep searching; evidence is not yet true."
                wouldHome -> "Result: neutralize, establish zero, and mark the mechanism homed."
                else -> "Result: evidence is promising, but keep searching until it has dwelled long enough."
            },
            color = if (wouldHome) AresCyan else AresTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun FeedforwardConceptLab(loop: SubsystemControlLoopDocument) {
    val feedforward = loop.feedforward
    if (feedforward.kind == SubsystemFeedforwardKind.NONE) return
    var velocity by remember(loop.uid, feedforward.kind) { mutableFloatStateOf(0f) }
    var acceleration by remember(loop.uid, feedforward.kind) { mutableFloatStateOf(0f) }
    var angleDegrees by remember(loop.uid, feedforward.kind) { mutableFloatStateOf(0f) }
    val velocityValue = velocity.toDouble()
    val accelerationValue = acceleration.toDouble()
    val static = if (velocity == 0f) 0.0 else feedforward.kS * sign(velocityValue)
    val gravity = when (feedforward.kind) {
        SubsystemFeedforwardKind.NONE, SubsystemFeedforwardKind.SIMPLE_MOTOR -> 0.0
        SubsystemFeedforwardKind.ELEVATOR -> feedforward.kG
        SubsystemFeedforwardKind.ARM,
        SubsystemFeedforwardKind.FOUR_BAR_LINKAGE,
        SubsystemFeedforwardKind.TWO_DOF_ARM -> feedforward.kG * cos(Math.toRadians(angleDegrees.toDouble()))
    }
    val output = static + feedforward.kV * velocityValue + feedforward.kA * accelerationValue + gravity

    LearningLabCard(
        title = "Try the feedforward model",
        explanation = "Feedforward predicts the voltage needed for the requested motion. Feedback then corrects what the prediction missed.",
    ) {
        LabSlider("Requested velocity", velocity, -10f..10f) { velocity = it }
        LabSlider("Requested acceleration", acceleration, -10f..10f) { acceleration = it }
        if (feedforward.kind == SubsystemFeedforwardKind.ARM) {
            LabSlider("Arm angle", angleDegrees, -180f..180f, "°") { angleDegrees = it }
        }
        Text(
            "Predicted output: ${"%.2f".format(output)} V = static ${"%.2f".format(static)} + velocity ${"%.2f".format(feedforward.kV * velocityValue)} + acceleration ${"%.2f".format(feedforward.kA * accelerationValue)} + gravity ${"%.2f".format(gravity)}",
            color = AresTextPrimary,
        )
        Text("The controller adds PID feedback correction after this prediction.", color = AresTextSecondary)
    }
}

// ── Control Theory Interactive Learning Sandbox ───────────────────────────────────────────

internal enum class MechanismPlantKind(val displayName: String) {
    FLYWHEEL("Velocity Flywheel (Inertia plant)"),
    ARM("Pivoting Arm (Gravity plant)"),
    ELEVATOR("Linear Elevator (Constant gravity plant)")
}

internal data class StepResponseMetrics(
    val riseTimeSec: Double?,
    val overshootPercent: Double,
    val settlingTimeSec: Double?,
    val steadyStateError: Double,
    val isBounded: Boolean,
    val statusMessage: String
)

internal enum class SandboxControllerStrategy(val displayName: String) {
    PID_FEEDFORWARD("PID + Feedforward"),
    LINEAR_ADRC("Linear ADRC (Observer)")
}

/**
 * Runs a small normalized teaching model. It is intentionally not a robot digital twin and never
 * writes gains or hardware output. Invalid or unreasonably large requests are rejected up front so
 * UI experimentation cannot create an unbounded allocation or misleading non-finite result.
 */
internal fun simulateStepResponse(
    plant: MechanismPlantKind,
    strategy: SandboxControllerStrategy,
    kp: Double,
    ki: Double,
    kd: Double,
    ks: Double,
    kv: Double,
    kg: Double,
    b0: Double? = null,
    omegaO: Double = 20.0,
    omegaC: Double = 10.0,
    simDurationSec: Double = 2.0,
    dt: Double = 0.01
): Pair<List<Pair<Double, Double>>, StepResponseMetrics> {
    val gains = doubleArrayOf(kp, ki, kd, ks, kv, kg, omegaO, omegaC)
    require(gains.all(Double::isFinite)) { "Sandbox gains and bandwidths must be finite" }
    require(dt.isFinite() && dt in 0.001..0.05) { "Sandbox dt must be between 1 ms and 50 ms" }
    require(simDurationSec.isFinite() && simDurationSec in 0.1..10.0) {
        "Sandbox duration must be between 0.1 s and 10.0 s"
    }

    val defaultInputGain = when (plant) {
        MechanismPlantKind.FLYWHEEL -> 1.6
        MechanismPlantKind.ARM -> 12.0
        MechanismPlantKind.ELEVATOR -> 1.75
    }
    val selectedInputGain = b0 ?: defaultInputGain
    require(selectedInputGain.isFinite() && abs(selectedInputGain) > 1e-6) {
        "Sandbox ADRC input gain must be finite and non-zero"
    }

    val totalSteps = (simDurationSec / dt).toInt()
    require(totalSteps in 2..5_000) { "Sandbox sample count must be between 2 and 5,000" }
    val trajectory = ArrayList<Pair<Double, Double>>(totalSteps + 1)
    var position = 0.0
    var velocity = 0.0
    var integralError = 0.0
    var prevError = 1.0
    val setpoint = 1.0
    var isBounded = true
    var failureReason: String? = null
    var firstRiseTimeSec: Double? = null
    var maxOutput = 0.0
    var currentOutput = 0.0
    trajectory.add(0.0 to currentOutput)
    val adrc = if (strategy == SandboxControllerStrategy.LINEAR_ADRC) {
        LinearADRC(selectedInputGain, omegaC, omegaO).apply {
            setOutputLimits(-12.0, 12.0)
            reset(currentOutput)
        }
    } else null

    for (step in 1..totalSteps) {
        val error = setpoint - currentOutput

        integralError = (integralError + error * dt).coerceIn(-12.0, 12.0)
        val dError = (error - prevError) / dt
        prevError = error

        val voltage: Double = when (strategy) {
            SandboxControllerStrategy.LINEAR_ADRC -> adrc!!.calculate(setpoint, currentOutput, dt)
            else -> {
                val fb = kp * error + ki * integralError + kd * dError
                val gravityComp = when (plant) {
                    MechanismPlantKind.FLYWHEEL -> 0.0
                    MechanismPlantKind.ELEVATOR -> kg
                    MechanismPlantKind.ARM -> kg * cos(position)
                }
                val ff = ks * sign(setpoint) + kv * setpoint + gravityComp
                (fb + ff).coerceIn(-12.0, 12.0)
            }
        }

        when (plant) {
            MechanismPlantKind.FLYWHEEL -> {
                // Normalized first-order flywheel: tau*v_dot + v = K*voltage.
                val tau = 0.25
                val steadyStatePerVolt = 0.40
                val vDot = (-velocity + steadyStatePerVolt * voltage) / tau
                velocity += vDot * dt
                position += velocity * dt
            }
            MechanismPlantKind.ARM -> {
                // J * theta_ddot + b * theta_dot + mgl * cos(theta) = Kt * u
                val inertia = 0.15
                val damping = 0.40
                val gravityTorque = 2.5 * cos(position)
                val motorTorque = 1.8 * voltage
                val accel = (motorTorque - damping * velocity - gravityTorque) / inertia
                velocity += accel * dt
                position += velocity * dt
            }
            MechanismPlantKind.ELEVATOR -> {
                // m * x_ddot + b * x_dot + m*g = Kt * u
                val mass = 2.0
                val damping = 1.0
                val gravityForce = mass * 9.81 * 0.20
                val motorForce = 3.5 * voltage
                val accel = (motorForce - damping * velocity - gravityForce) / mass
                velocity += accel * dt
                position += velocity * dt
            }
        }

        currentOutput = if (plant == MechanismPlantKind.FLYWHEEL) velocity else position
        if (!position.isFinite() || !velocity.isFinite() || !currentOutput.isFinite() ||
            abs(currentOutput) > 10.0 || abs(velocity) > 50.0
        ) {
            isBounded = false
            failureReason = "The normalized response exceeded the preview's safe display bounds."
            break
        }

        val timeSec = step * dt
        trajectory.add(timeSec to currentOutput)
        if (firstRiseTimeSec == null && currentOutput >= 0.90 * setpoint) firstRiseTimeSec = timeSec
        if (currentOutput > maxOutput) maxOutput = currentOutput
    }

    val overshootPercent = ((maxOutput - setpoint).coerceAtLeast(0.0) / setpoint) * 100.0
    val lastOutsideIndex = trajectory.indexOfLast { abs(it.second - setpoint) > 0.05 * setpoint }
    val settlingTimeSec = when {
        !isBounded -> null
        lastOutsideIndex < 0 -> 0.0
        lastOutsideIndex >= trajectory.lastIndex -> null
        else -> trajectory[lastOutsideIndex + 1].first
    }
    val finalY = trajectory.lastOrNull()?.second ?: 0.0
    val steadyStateError = if (isBounded) abs(finalY - setpoint) else Double.POSITIVE_INFINITY
    val statusMessage = when {
        !isBounded -> failureReason ?: "The normalized response became non-finite."
        settlingTimeSec == null -> "Bounded, but it did not settle within the ${"%.1f".format(simDurationSec)} s preview."
        else -> "Bounded for the full preview; this is educational evidence, not robot validation."
    }

    return trajectory to StepResponseMetrics(
        riseTimeSec = firstRiseTimeSec,
        overshootPercent = overshootPercent,
        settlingTimeSec = settlingTimeSec,
        steadyStateError = steadyStateError,
        isBounded = isBounded,
        statusMessage = statusMessage
    )
}

@Composable
internal fun ControlTheorySandboxLab(
    loop: SubsystemControlLoopDocument? = null,
    onApplyGains: ((kp: Double, ki: Double, kd: Double, ks: Double, kv: Double, kg: Double) -> Unit)? = null
) {
    val configuredLoop = loop
    val labKey = configuredLoop?.uid ?: "standalone-control-learning"
    var expanded by remember(labKey) { mutableStateOf(configuredLoop == null) }
    var plant by remember(labKey) {
        mutableStateOf(
            when (configuredLoop?.feedforward?.kind) {
                SubsystemFeedforwardKind.ARM -> MechanismPlantKind.ARM
                SubsystemFeedforwardKind.ELEVATOR -> MechanismPlantKind.ELEVATOR
                else -> MechanismPlantKind.FLYWHEEL
            }
        )
    }

    var controllerStrategy by remember(labKey) { mutableStateOf(SandboxControllerStrategy.PID_FEEDFORWARD) }
    var kp by remember(labKey) { mutableFloatStateOf(configuredLoop?.kP?.finiteFloat() ?: 6f) }
    var ki by remember(labKey) { mutableFloatStateOf(configuredLoop?.kI?.finiteFloat() ?: 0.5f) }
    var kd by remember(labKey) { mutableFloatStateOf(configuredLoop?.kD?.finiteFloat() ?: 0.1f) }
    var ks by remember(labKey) { mutableFloatStateOf(configuredLoop?.feedforward?.kS?.finiteFloat() ?: 0.05f) }
    var kv by remember(labKey) { mutableFloatStateOf(configuredLoop?.feedforward?.kV?.finiteFloat() ?: 2.5f) }
    var kg by remember(labKey) { mutableFloatStateOf(configuredLoop?.feedforward?.kG?.finiteFloat() ?: 0f) }
    var showTheory by remember { mutableStateOf(false) }

    val (trajectory, metrics) = remember(plant, controllerStrategy, kp, ki, kd, ks, kv, kg) {
        simulateStepResponse(
            plant = plant,
            strategy = controllerStrategy,
            kp = kp.toDouble(),
            ki = ki.toDouble(),
            kd = kd.toDouble(),
            ks = ks.toDouble(),
            kv = kv.toDouble(),
            kg = kg.toDouble()
        )
    }

    LearningLabCard(
        title = "Control-response learning sandbox",
        explanation = "Explore how feedback and feedforward change a normalized teaching model before testing a real mechanism."
    ) {
        Text(
            "Learning model only — it does not command hardware, save gains, or prove that a real robot is safe.",
            color = AresTextPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "Close learning sandbox" else "Open learning sandbox")
        }

        if (!expanded) return@LearningLabCard

        Text(
            "The target is a normalized value of 1.0. The generic plant is useful for learning trends, not predicting your mechanism's exact motion.",
            color = AresTextSecondary,
            fontSize = 12.sp,
        )

        Text("Choose a generic mechanism model", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MechanismPlantKind.entries.forEach { kind ->
                val selected = plant == kind
                FilterChip(
                    selected = selected,
                    onClick = { plant = kind },
                    label = { Text(kind.displayName) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                )
            }
        }

        Text("Choose a controller demonstration", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SandboxControllerStrategy.entries.forEach { strategy ->
                val selected = controllerStrategy == strategy
                FilterChip(
                    selected = selected,
                    onClick = { controllerStrategy = strategy },
                    label = { Text(strategy.displayName) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null,
                )
            }
        }
        if (controllerStrategy == SandboxControllerStrategy.LINEAR_ADRC) {
            Text(
                "Advanced concept preview: this does not change the subsystem's selected control strategy.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (configuredLoop != null && onApplyGains != null) {
                Button(
                    onClick = {
                        onApplyGains(
                            kp.toDouble(),
                            ki.toDouble(),
                            kd.toDouble(),
                            ks.toDouble(),
                            kv.toDouble(),
                            kg.toDouble()
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AresCyan,
                        contentColor = AresOnAccent
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Apply gains to controller")
                }
            }
            if (configuredLoop != null) {
                OutlinedButton(onClick = {
                    kp = configuredLoop.kP.finiteFloat()
                    ki = configuredLoop.kI.finiteFloat()
                    kd = configuredLoop.kD.finiteFloat()
                    ks = configuredLoop.feedforward.kS.finiteFloat()
                    kv = configuredLoop.feedforward.kV.finiteFloat()
                    kg = configuredLoop.feedforward.kG.finiteFloat()
                }) { Text("Reset to form values") }
            }
            OutlinedButton(onClick = {
                when (plant) {
                    MechanismPlantKind.FLYWHEEL -> {
                        kp = 6f; ki = 0.5f; kd = 0.1f; ks = 0.05f; kv = 2.5f; kg = 0f
                    }
                    MechanismPlantKind.ARM -> {
                        kp = 3f; ki = 0f; kd = 0.2f; ks = 0f; kv = 0f; kg = 1.4f
                    }
                    MechanismPlantKind.ELEVATOR -> {
                        kp = 12f; ki = 0.5f; kd = 1.5f; ks = 0.05f; kv = 0.5f; kg = 1.12f
                    }
                }
            }) { Text("Load teaching example") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AresBackground)
                .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                .padding(8.dp)
                .semantics {
                    contentDescription = "Normalized step-response preview. ${metrics.statusMessage}"
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val minimum = min(0.0, trajectory.minOfOrNull { it.second } ?: 0.0)
                val maximum = max(1.2, trajectory.maxOfOrNull { it.second } ?: 1.2)
                val span = (maximum - minimum).coerceAtLeast(0.1)
                fun canvasY(value: Double): Float = (h * (1.0 - (value - minimum) / span)).toFloat()
                val setpointY = canvasY(1.0)
                drawLine(
                    color = AresTextTertiary.copy(alpha = 0.5f),
                    start = Offset(0f, setpointY),
                    end = Offset(w, setpointY),
                    strokeWidth = 1.5f,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )

                val toleranceTop = canvasY(1.05)
                val toleranceBottom = canvasY(0.95)
                drawRect(
                    color = AresGreen.copy(alpha = 0.08f),
                    topLeft = Offset(0f, toleranceTop),
                    size = androidx.compose.ui.geometry.Size(w, toleranceBottom - toleranceTop)
                )

                if (trajectory.isNotEmpty()) {
                    val path = Path()
                    val maxT = trajectory.last().first.coerceAtLeast(0.01).toFloat()

                    trajectory.forEachIndexed { index, (t, y) ->
                        val px = (t.toFloat() / maxT) * w
                        val py = canvasY(y)
                        if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                    }

                    drawPath(
                        path = path,
                        color = if (metrics.isBounded) AresCyan else AresError,
                        style = Stroke(width = 2.5f)
                    )
                }
            }

            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Target: 1.00", color = AresTextTertiary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    text = if (metrics.isBounded) "BOUNDED PREVIEW" else "PREVIEW STOPPED",
                    color = if (metrics.isBounded) AresGreen else AresError,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Text(metrics.statusMessage, color = AresTextPrimary, fontSize = 11.sp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricPill(
                "Rise time", metrics.riseTimeSec?.let { "%.2fs".format(it) } ?: "Not reached",
                if (metrics.riseTimeSec == null) "Target not reached" else "Reached 90%", AresCyan, Modifier.weight(1f)
            )
            MetricPill(
                "Overshoot", "%.1f%%".format(metrics.overshootPercent),
                if (metrics.overshootPercent > 20.0) "High" else "Controlled", if (metrics.overshootPercent > 20.0) AresAmber else AresGreen,
                Modifier.weight(1f)
            )
            MetricPill(
                "Settling time", metrics.settlingTimeSec?.let { "%.2fs".format(it) } ?: "Not settled",
                if (metrics.settlingTimeSec == null) "Outside 5% band" else "Stayed within 5%", AresGold, Modifier.weight(1f)
            )
            MetricPill(
                "Final error", if (metrics.steadyStateError.isFinite()) "%.3f".format(metrics.steadyStateError) else "N/A",
                if (!metrics.steadyStateError.isFinite()) "Preview stopped" else if (metrics.steadyStateError > 0.05) "Above 5%" else "Within 5%",
                if (metrics.steadyStateError > 0.05) AresError else AresGreen, Modifier.weight(1f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabSlider("Proportional Gain (kP)", kp, 0f..25f) { kp = it }
            LabSlider("Integral Gain (kI)", ki, 0f..10f) { ki = it }
            LabSlider("Derivative Gain (kD)", kd, 0f..5f) { kd = it }
            if (plant != MechanismPlantKind.FLYWHEEL) {
                LabSlider("Gravity Compensation (kG)", kg, 0f..5f, " V") { kg = it }
            }
            LabSlider("Velocity Feedforward (kV)", kv, 0f..3f) { kv = it }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showTheory = !showTheory }) {
                Icon(if (showTheory) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (showTheory) "Hide Control Fundamentals" else "Learn How PID & Feedforward Work", fontSize = 11.sp, color = AresCyan)
            }
        }

        if (showTheory) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresSurface)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TheoryConceptRow(
                    term = "kP (Proportional)",
                    summary = "Virtual Spring Constant",
                    detail = "Acts like a restorative spring: force is proportional to position error (F = -kP * e). Higher kP speeds up response but causes ringing/overshoot if damping is low."
                )
                TheoryConceptRow(
                    term = "kI (Integral)",
                    summary = "Steady-State Eliminator",
                    detail = "Accumulates past errors over time (∫ e dt) to push past static friction and gravity sag. Warning: excess kI causes windup and slow unstable hunting oscillations."
                )
                TheoryConceptRow(
                    term = "kD (Derivative)",
                    summary = "Virtual Damper / Shock Absorber",
                    detail = "Opposes the rate of change of error (-kD * de/dt). Dampens oscillations and suppresses overshoot, but amplifies high-frequency encoder noise."
                )
                TheoryConceptRow(
                    term = "Feedforward (kS, kV, kA, kG)",
                    summary = "Physics-Based Predictive Control",
                    detail = "Estimates the voltage a modeled mechanism needs before feedback reacts: V = kS*sign(v) + kV*v + kA*a + kG(θ). PID then corrects model error, disturbances, and changing conditions."
                )
                TheoryConceptRow(
                    term = "Linear ADRC",
                    summary = "Active Disturbance Rejection",
                    detail = "Uses an Extended State Observer (ESO) to estimate total disturbance (friction, load mass, battery drop) in real time without needing complex math models or integral windup."
                )
            }
        }
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    verdict: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = AresTextTertiary, fontWeight = FontWeight.Bold)
        Text(value, fontSize = 12.sp, color = accentColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(verdict, fontSize = 9.sp, color = AresTextSecondary)
    }
}

private fun Double.finiteFloat(): Float = if (isFinite()) toFloat() else 0f

@Composable
private fun TheoryConceptRow(term: String, summary: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(term, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresCyan)
            Text("— $summary", fontSize = 10.sp, color = AresTextSecondary, fontWeight = FontWeight.Medium)
        }
        Text(detail, fontSize = 10.sp, color = AresTextTertiary, lineHeight = 14.sp)
    }
}

@Composable
private fun LearningLabCard(
    title: String,
    explanation: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(explanation, color = AresTextSecondary, fontSize = 12.sp)
                }
                ConceptHelp(title, explanation, if (title.contains("homing", true)) "homing" else "feedforward")
            }
            content()
            OutlinedButton(onClick = { openSubsystemGuide(if (title.contains("homing", true)) "homing" else "feedforward") }) {
                Text("Read the full explanation")
            }
        }
    }
}

@Composable
private fun LabSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    onChange: (Float) -> Unit,
) {
    Text("$label: ${"%.2f".format(value)}$suffix", color = AresTextPrimary)
    Slider(value = value, onValueChange = onChange, valueRange = range)
}

private fun openSubsystemGuide(anchor: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI("$SUBSYSTEM_GUIDE#$anchor"))
        }
    }
}
