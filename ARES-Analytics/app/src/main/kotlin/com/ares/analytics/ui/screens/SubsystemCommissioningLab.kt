package com.ares.analytics.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.service.commissioning.SubsystemCommissioningPlant
import com.ares.analytics.service.commissioning.SubsystemCommissioningResult
import com.ares.analytics.service.commissioning.SubsystemCommissioningScenario
import com.ares.analytics.service.commissioning.commissioningScenariosFor
import com.ares.analytics.service.commissioning.defaultCommissioningPlant
import com.ares.analytics.service.commissioning.simulateSubsystemCommissioning
import com.areslib.subsystem.SubsystemControlLoopDocument
import com.areslib.subsystem.SubsystemControlStrategy
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemFeedforwardKind
import com.areslib.subsystem.subsystemUnitCanRepresentVelocity
import com.areslib.subsystem.subsystemUnitIsCanonicalAngle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Guided, hardware-free review of the exact controller selected in a subsystem descriptor.
 * Applying changes updates only the unsaved editor draft; it never writes files or commands IO.
 */
@Composable
internal fun SubsystemCommissioningLab(
    document: SubsystemDocument,
    loop: SubsystemControlLoopDocument,
    onApplyToDraft: (SubsystemControlLoopDocument) -> Unit,
) {
    val feedbackController = loop.strategy in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
        SubsystemControlStrategy.BANG_BANG,
    )
    val pidController = loop.strategy in setOf(
        SubsystemControlStrategy.POSITION_PID,
        SubsystemControlStrategy.PROFILED_POSITION_PID,
        SubsystemControlStrategy.VELOCITY_PID,
    )
    val targetField = document.stateFields.firstOrNull { it.fieldId == loop.targetFieldId }
    val measurementField = document.stateFields.firstOrNull { it.fieldId == loop.measurementFieldId }
    val initialPlant = remember(loop.uid) { defaultCommissioningPlant(loop, targetField?.unit) }
    var plant by remember(loop.uid) { mutableStateOf(initialPlant) }
    var scenario by remember(loop.uid) { mutableStateOf(SubsystemCommissioningScenario.NOMINAL) }
    var reviewedLoop by remember(loop.uid) { mutableStateOf(loop) }
    val availablePlants = when {
        loop.strategy == SubsystemControlStrategy.SERVO_POSITION ->
            listOf(SubsystemCommissioningPlant.POSITIONAL_SERVO)
        loop.strategy == SubsystemControlStrategy.VELOCITY_PID ||
            subsystemUnitCanRepresentVelocity(targetField?.unit) ->
            listOf(SubsystemCommissioningPlant.FLYWHEEL)
        subsystemUnitIsCanonicalAngle(targetField?.unit) ->
            listOf(SubsystemCommissioningPlant.ROTARY_ARM)
        loop.feedforward.kind == SubsystemFeedforwardKind.ELEVATOR ->
            listOf(SubsystemCommissioningPlant.ELEVATOR)
        loop.strategy == SubsystemControlStrategy.DIRECT -> listOf(
            SubsystemCommissioningPlant.ROTARY_ARM,
            SubsystemCommissioningPlant.ELEVATOR,
            SubsystemCommissioningPlant.FLYWHEEL,
        )
        else -> listOf(SubsystemCommissioningPlant.ELEVATOR)
    }
    val effectivePlant = plant.takeIf { it in availablePlants } ?: availablePlants.first()
    val availableScenarios = commissioningScenariosFor(reviewedLoop)
    val effectiveScenario = scenario.takeIf { it in availableScenarios } ?: SubsystemCommissioningScenario.NOMINAL
    val result = remember(reviewedLoop, effectivePlant, effectiveScenario) {
        simulateSubsystemCommissioning(reviewedLoop, effectivePlant, effectiveScenario)
    }
    val canApply = result.metrics.bounded && result.metrics.neutralizedOnFault != false

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Science, contentDescription = null, tint = AresCyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Column {
                Text("Commission this controller safely", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "${loop.strategy.studentLabel()} · ${targetField?.unit ?: "unitless"} target" +
                        (measurementField?.unit?.let { " · $it feedback" } ?: ""),
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Text(
            "This deterministic teaching plant never commands hardware. It checks controller behavior and draft settings; it cannot prove robot-safe gains.",
            color = AresTextPrimary,
            fontSize = 11.sp,
        )

        Text("1. Choose a teaching mechanism", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        ChoiceChips(
            values = availablePlants,
            selected = effectivePlant,
            label = SubsystemCommissioningPlant::displayName,
            onSelected = { plant = it },
        )

        Text("2. Inject a situation", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        ChoiceChips(
            values = availableScenarios,
            selected = effectiveScenario,
            label = SubsystemCommissioningScenario::displayName,
            onSelected = { scenario = it },
        )

        CommissioningPlot(result)
        Text(result.metrics.statusMessage, color = AresTextPrimary, fontSize = 11.sp)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CommissioningMetric(
                label = "Peak output",
                value = "%.2f".format(result.metrics.peakAbsoluteCommand),
                verdict = "Bound %.2f…%.2f".format(reviewedLoop.minimumOutput, reviewedLoop.maximumOutput),
                safe = result.metrics.bounded,
                modifier = Modifier.width(190.dp),
            )
            CommissioningMetric(
                label = "Saturated",
                value = "%.0f%%".format(result.metrics.saturationPercent),
                verdict = if (result.metrics.saturationPercent > 40.0) "Review limits/gains" else "Limited duration",
                safe = result.metrics.saturationPercent <= 40.0,
                modifier = Modifier.width(190.dp),
            )
            CommissioningMetric(
                label = "Final error",
                value = result.metrics.finalError?.let { "%.3f".format(it) } ?: "Not applicable",
                verdict = if (!feedbackController) "Open loop" else if (result.metrics.enteredTolerance) "Entered tolerance" else "Not settled",
                safe = !feedbackController || result.metrics.enteredTolerance,
                modifier = Modifier.width(190.dp),
            )
            CommissioningMetric(
                label = "Safety fault",
                value = when (result.metrics.neutralizedOnFault) {
                    true -> "Neutral"
                    false -> "Unsafe"
                    null -> "Not injected"
                },
                verdict = "Fail-closed check",
                safe = result.metrics.neutralizedOnFault != false,
                modifier = Modifier.width(190.dp),
            )
            CommissioningMetric(
                label = "Fault recovery",
                value = when (result.metrics.neutralRecoverySucceeded) {
                    true -> "Neutral confirmed"
                    false -> "Failed"
                    null -> if (result.metrics.faultLatched == true) "Still latched" else "Not required"
                },
                verdict = when {
                    result.metrics.faultLatched == true -> "Explicit recovery contract"
                    result.metrics.neutralizedOnFault == true -> "Fail-closed condition"
                    else -> "No recovery event"
                },
                safe = result.metrics.neutralRecoverySucceeded != false,
                modifier = Modifier.width(190.dp),
            )
        }

        Text("3. Adjust reviewed draft values", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        if (pidController) {
            CommissioningSlider("Proportional gain (kP)", reviewedLoop.kP, 0.0..25.0) {
                reviewedLoop = reviewedLoop.copy(kP = it)
            }
            CommissioningSlider("Integral gain (kI)", reviewedLoop.kI, 0.0..10.0) {
                reviewedLoop = reviewedLoop.copy(kI = it)
            }
            CommissioningSlider("Derivative gain (kD)", reviewedLoop.kD, 0.0..5.0) {
                reviewedLoop = reviewedLoop.copy(kD = it)
            }
        }
        if (loop.strategy == SubsystemControlStrategy.BANG_BANG) {
            CommissioningSlider("Stop tolerance", reviewedLoop.tolerance, 0.0..1.0) {
                reviewedLoop = reviewedLoop.copy(tolerance = it)
            }
            CommissioningSlider("Restart hysteresis", reviewedLoop.hysteresis, 0.0..1.0) {
                reviewedLoop = reviewedLoop.copy(hysteresis = it)
            }
        }
        if (loop.strategy == SubsystemControlStrategy.PROFILED_POSITION_PID) {
            CommissioningSlider("Profile maximum velocity", reviewedLoop.motionProfile.maximumVelocity, 0.1..10.0) {
                reviewedLoop = reviewedLoop.copy(motionProfile = reviewedLoop.motionProfile.copy(maximumVelocity = it))
            }
            CommissioningSlider("Profile maximum acceleration", reviewedLoop.motionProfile.maximumAcceleration, 0.1..20.0) {
                reviewedLoop = reviewedLoop.copy(motionProfile = reviewedLoop.motionProfile.copy(maximumAcceleration = it))
            }
        }
        if (reviewedLoop.feedforward.kind != SubsystemFeedforwardKind.NONE && pidController) {
            Text("Feedforward prediction", color = AresTextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            CommissioningSlider("Static friction (kS)", reviewedLoop.feedforward.kS, 0.0..5.0) {
                reviewedLoop = reviewedLoop.copy(feedforward = reviewedLoop.feedforward.copy(kS = it))
            }
            CommissioningSlider("Velocity term (kV)", reviewedLoop.feedforward.kV, 0.0..10.0) {
                reviewedLoop = reviewedLoop.copy(feedforward = reviewedLoop.feedforward.copy(kV = it))
            }
            CommissioningSlider("Acceleration term (kA)", reviewedLoop.feedforward.kA, 0.0..5.0) {
                reviewedLoop = reviewedLoop.copy(feedforward = reviewedLoop.feedforward.copy(kA = it))
            }
            if (reviewedLoop.feedforward.kind in setOf(
                    SubsystemFeedforwardKind.ARM,
                    SubsystemFeedforwardKind.ELEVATOR,
                    SubsystemFeedforwardKind.TWO_DOF_ARM,
                )
            ) {
                CommissioningSlider("Gravity term (kG)", reviewedLoop.feedforward.kG, 0.0..5.0) {
                    reviewedLoop = reviewedLoop.copy(feedforward = reviewedLoop.feedforward.copy(kG = it))
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = canApply,
                onClick = { onApplyToDraft(reviewedLoop) },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Apply reviewed settings to draft")
            }
            OutlinedButton(onClick = { reviewedLoop = loop }) { Text("Reset from form") }
        }
        Text(
            "Applying changes only the unsaved descriptor draft. Use the normal review and save flow afterward; physical commissioning remains a separate supervised step.",
            color = AresTextTertiary,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun <T> ChoiceChips(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
                leadingIcon = if (value == selected) {
                    { Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(15.dp)) }
                } else null,
            )
        }
    }
}

@Composable
private fun CommissioningPlot(result: SubsystemCommissioningResult) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(AresBackground)
            .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .semantics {
                contentDescription = "Commissioning response plot. ${result.metrics.statusMessage}"
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(155.dp)) {
            val finiteMeasurements = result.samples.map { it.measurement }.filter(Double::isFinite)
            val finiteReferences = result.samples.map { it.reference }.filter(Double::isFinite)
            val minimum = min(0.0, min(finiteMeasurements.minOrNull() ?: 0.0, finiteReferences.minOrNull() ?: 0.0))
            val maximum = max(1.0, max(finiteMeasurements.maxOrNull() ?: 1.0, finiteReferences.maxOrNull() ?: 1.0))
            val span = (maximum - minimum).coerceAtLeast(0.1)
            val maxTime = result.samples.lastOrNull()?.timeSeconds?.coerceAtLeast(0.01) ?: 1.0
            fun x(time: Double) = (time / maxTime * size.width).toFloat()
            fun y(value: Double) = (size.height * (1.0 - (value - minimum) / span)).toFloat()

            val reference = Path()
            val measurement = Path()
            result.samples.forEachIndexed { index, sample ->
                val px = x(sample.timeSeconds)
                val referenceY = y(sample.reference)
                val measurementY = y(sample.measurement.takeIf(Double::isFinite) ?: minimum)
                if (index == 0) {
                    reference.moveTo(px, referenceY)
                    measurement.moveTo(px, measurementY)
                } else {
                    reference.lineTo(px, referenceY)
                    measurement.lineTo(px, measurementY)
                }
                if (sample.faultActive || sample.faultLatched) {
                    drawLine(AresError.copy(alpha = 0.45f), Offset(px, 0f), Offset(px, size.height), strokeWidth = 1f)
                }
            }
            drawPath(reference, AresTextTertiary, style = Stroke(width = 1.5f))
            drawPath(measurement, if (result.metrics.bounded) AresCyan else AresError, style = Stroke(width = 2.5f))
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("— ${result.referenceLabel}", color = AresTextTertiary, fontSize = 10.sp)
            Text("— ${result.measurementLabel}", color = AresCyan, fontSize = 10.sp)
            if (result.metrics.neutralizedOnFault != null) Text("│ safety fault", color = AresError, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CommissioningMetric(
    label: String,
    value: String,
    verdict: String,
    safe: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AresTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = if (safe) AresGreen else AresAmber,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(verdict, color = AresTextSecondary, fontSize = 9.sp)
    }
}

@Composable
private fun CommissioningSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    onValueChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = AresTextSecondary, fontSize = 10.sp)
            Text("%.3f".format(value), color = AresTextPrimary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive).toFloat(),
            onValueChange = { onValueChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

private fun SubsystemControlStrategy.studentLabel(): String = when (this) {
    SubsystemControlStrategy.DIRECT -> "Bounded open-loop output"
    SubsystemControlStrategy.POSITION_PID -> "Position PID"
    SubsystemControlStrategy.PROFILED_POSITION_PID -> "Profiled position PID"
    SubsystemControlStrategy.VELOCITY_PID -> "Velocity PID"
    SubsystemControlStrategy.BANG_BANG -> "Hysteretic on/off"
    SubsystemControlStrategy.SERVO_POSITION -> "Positional servo"
}
