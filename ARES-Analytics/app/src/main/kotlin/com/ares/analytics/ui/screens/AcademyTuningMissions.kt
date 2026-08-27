package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.util.Locale

internal enum class AcademyControlMission(
    val label: String,
    val teachingGoal: String,
    val plant: MechanismPlantKind,
) {
    FLYWHEEL(
        label = "Flywheel response",
        teachingGoal = "In the normalized model: rise ≤ 0.70 s, overshoot < 5%, and final error < 3%.",
        plant = MechanismPlantKind.FLYWHEEL,
    ),
    ARM(
        label = "Arm with gravity",
        teachingGoal = "In the normalized model: overshoot < 8%, settling ≤ 1.20 s, and final error < 2%.",
        plant = MechanismPlantKind.ARM,
    ),
    ELEVATOR(
        label = "Elevator with gravity",
        teachingGoal = "In the normalized model: overshoot < 3%, settling ≤ 0.90 s, and final error < 2%.",
        plant = MechanismPlantKind.ELEVATOR,
    ),
}

internal data class AcademyControlMissionResult(
    val bounded: Boolean,
    val metTeachingGoal: Boolean,
    val riseTimeSec: Double?,
    val overshootPercent: Double,
    val settlingTimeSec: Double?,
    val finalErrorPercent: Double,
    val explanation: String,
)

/**
 * Evaluates gains against the existing normalized Academy plant model.
 *
 * This deliberately does not return a hardware-safe verdict. A passing result means only that the
 * selected response met this lesson's goals in this simplified, unitless model.
 */
internal fun evaluateAcademyControlMission(
    mission: AcademyControlMission,
    kp: Double,
    kd: Double,
    kv: Double,
    kg: Double,
): AcademyControlMissionResult {
    val (_, metrics) = simulateStepResponse(
        plant = mission.plant,
        strategy = SandboxControllerStrategy.PID_FEEDFORWARD,
        kp = kp,
        ki = 0.0,
        kd = kd,
        ks = if (mission == AcademyControlMission.ELEVATOR) 0.05 else 0.0,
        kv = if (mission == AcademyControlMission.ELEVATOR) 0.5 else kv,
        kg = kg,
    )
    val finalErrorPercent = metrics.steadyStateError * 100.0
    val metGoal = metrics.isBounded && when (mission) {
        AcademyControlMission.FLYWHEEL ->
            (metrics.riseTimeSec ?: Double.POSITIVE_INFINITY) <= 0.70 &&
                metrics.overshootPercent < 5.0 && finalErrorPercent < 3.0
        AcademyControlMission.ARM ->
            metrics.overshootPercent < 8.0 &&
                (metrics.settlingTimeSec ?: Double.POSITIVE_INFINITY) <= 1.20 && finalErrorPercent < 2.0
        AcademyControlMission.ELEVATOR ->
            metrics.overshootPercent < 3.0 &&
                (metrics.settlingTimeSec ?: Double.POSITIVE_INFINITY) <= 0.90 && finalErrorPercent < 2.0
    }
    val explanation = when {
        !metrics.isBounded -> "The teaching response left its display bounds. Reduce the gains before comparing metrics."
        metGoal -> "This setting meets the lesson goal in the simplified model. It is not a robot tuning recommendation."
        metrics.riseTimeSec == null -> "The response never reached 90% of the model target during this preview."
        metrics.settlingTimeSec == null -> "The response stayed bounded but did not settle inside the lesson window."
        metrics.overshootPercent >= 8.0 -> "The model overshot substantially; compare a lower kP or greater damping."
        else -> "Change one term at a time and compare which measured response changed."
    }
    return AcademyControlMissionResult(
        bounded = metrics.isBounded,
        metTeachingGoal = metGoal,
        riseTimeSec = metrics.riseTimeSec,
        overshootPercent = metrics.overshootPercent,
        settlingTimeSec = metrics.settlingTimeSec,
        finalErrorPercent = finalErrorPercent,
        explanation = explanation,
    )
}

/** Interactive, hardware-free control-response challenges for Robot Academy. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AcademyTuningMissions(modifier: Modifier = Modifier) {
    var mission by remember { mutableStateOf(AcademyControlMission.FLYWHEEL) }
    var kp by remember(mission) { mutableFloatStateOf(defaultGains(mission).first.toFloat()) }
    var kd by remember(mission) { mutableFloatStateOf(defaultGains(mission).second.toFloat()) }
    var feedforward by remember(mission) { mutableFloatStateOf(defaultGains(mission).third.toFloat()) }
    val result = evaluateAcademyControlMission(
        mission = mission,
        kp = kp.toDouble(),
        kd = kd.toDouble(),
        kv = if (mission == AcademyControlMission.FLYWHEEL) feedforward.toDouble() else 0.0,
        kg = if (mission == AcademyControlMission.FLYWHEEL) 0.0 else feedforward.toDouble(),
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Control response challenges", style = MaterialTheme.typography.titleMedium, color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Simplified normalized plants · no project changes, hardware commands, or robot-safe gain recommendations.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AcademyControlMission.entries.forEach { option ->
                    FilterChip(
                        selected = mission == option,
                        onClick = { mission = option },
                        label = { Text(option.label) },
                    )
                }
            }
            Text(mission.teachingGoal, color = AresCyan, fontSize = 12.sp)
            MissionSlider("kP · proportional feedback", kp, 0f..20f) { kp = it }
            MissionSlider("kD · derivative damping", kd, 0f..3f) { kd = it }
            MissionSlider(
                if (mission == AcademyControlMission.FLYWHEEL) "kV · velocity feedforward" else "kG · gravity feedforward",
                feedforward,
                if (mission == AcademyControlMission.FLYWHEEL) 0f..4f else 0f..3f,
            ) { feedforward = it }
            Surface(
                color = if (result.metTeachingGoal) AresGreen.copy(alpha = 0.10f) else AresSurface,
                border = BorderStroke(1.dp, if (result.metTeachingGoal) AresGreen else AresGold),
                shape = RoundedCornerShape(9.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        if (result.metTeachingGoal) "TEACHING GOAL MET" else "KEEP EXPLORING",
                        color = if (result.metTeachingGoal) AresGreen else AresGold,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(result.explanation, color = AresTextPrimary, fontSize = 12.sp)
                    Text(
                        "rise=${formatMetric(result.riseTimeSec, "s")} · overshoot=${formatMetric(result.overshootPercent, "%")} · " +
                            "settling=${formatMetric(result.settlingTimeSec, "s")} · final error=${formatMetric(result.finalErrorPercent, "%")}",
                        color = AresTextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MissionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AresTextSecondary, fontSize = 12.sp)
            Text(String.format(Locale.ROOT, "%.2f", value), color = AresCyan, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = String.format(Locale.ROOT, "%.2f", value)
            },
        )
    }
}

private fun defaultGains(mission: AcademyControlMission): Triple<Double, Double, Double> = when (mission) {
    AcademyControlMission.FLYWHEEL -> Triple(6.0, 0.1, 2.5)
    AcademyControlMission.ARM -> Triple(3.0, 0.2, 1.4)
    AcademyControlMission.ELEVATOR -> Triple(12.0, 1.5, 1.12)
}

private fun formatMetric(value: Double?, unit: String): String =
    value?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.2f%s", it, unit) } ?: "not reached"
