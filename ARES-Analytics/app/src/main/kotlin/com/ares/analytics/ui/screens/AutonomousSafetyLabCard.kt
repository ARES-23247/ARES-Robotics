@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.help.AutonomousSafetyTeachingInput
import com.ares.analytics.ui.help.TeachingAutoCondition
import com.ares.analytics.ui.help.TeachingAutoFailurePolicy
import com.ares.analytics.ui.help.evaluateAutonomousSafetyTeaching
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.util.Locale

/**
 * Hardware-free Academy lab for reasoning about a small autonomous plan before opening the real
 * routine builder. This composable has no project, NT4, simulator, or robot side effects.
 */
@Composable
fun AutonomousSafetyLabCard(modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf(AutonomousSafetyTeachingInput()) }
    val result = evaluateAutonomousSafetyTeaching(input)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Autonomous plan sandbox", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Change one input, predict whether validation will pass, then compare the written reasons. This sandbox never saves a routine or starts a robot.",
                color = AresTextSecondary,
                lineHeight = 20.sp,
            )

            SectionTitle("1. Starting pose and field bounds")
            Text(
                "Teaching field: ${decimal(input.fieldLengthMeters)} × ${decimal(input.fieldWidthMeters)} m. Robot body: ${decimal(input.robotLengthMeters)} × ${decimal(input.robotWidthMeters)} m, rotated by the pose heading. The real builder uses the selected project's measured dimensions and field.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            TeachingSlider(
                label = "Starting X",
                value = input.startXMeters,
                range = 0f..input.fieldLengthMeters.toFloat(),
                unit = "m",
                onValue = { input = input.copy(startXMeters = it) },
            )
            TeachingSlider(
                label = "Starting Y",
                value = input.startYMeters,
                range = 0f..input.fieldWidthMeters.toFloat(),
                unit = "m",
                onValue = { input = input.copy(startYMeters = it) },
            )
            TeachingSlider(
                label = "Starting heading",
                value = input.startHeadingDegrees,
                range = -180f..180f,
                unit = "deg CCW+",
                onValue = { input = input.copy(startHeadingDegrees = it) },
            )
            TeachingSlider(
                label = "Target X",
                value = input.targetXMeters,
                range = 0f..input.fieldLengthMeters.toFloat(),
                unit = "m",
                onValue = { input = input.copy(targetXMeters = it) },
            )
            TeachingSlider(
                label = "Target Y",
                value = input.targetYMeters,
                range = 0f..input.fieldWidthMeters.toFloat(),
                unit = "m",
                onValue = { input = input.copy(targetYMeters = it) },
            )
            TeachingSlider(
                label = "Target heading",
                value = input.targetHeadingDegrees,
                range = -180f..180f,
                unit = "deg CCW+",
                onValue = { input = input.copy(targetHeadingDegrees = it) },
            )

            HorizontalDivider(color = AresBorder)
            SectionTitle("2. Motion and timeout")
            TeachingSlider(
                label = "Maximum speed",
                value = input.maxSpeedMetersPerSecond,
                range = 0.1f..4f,
                unit = "m/s",
                onValue = { input = input.copy(maxSpeedMetersPerSecond = it) },
            )
            TeachingSlider(
                label = "Step timeout",
                value = input.timeoutSeconds,
                range = 0.1f..10f,
                unit = "s",
                onValue = { input = input.copy(timeoutSeconds = it) },
            )
            Text(
                result.estimatedDriveSeconds?.let {
                    "Ideal distance ÷ speed estimate: ${decimal(it)} s. Validation asks for at least 25% margin because acceleration and settling are omitted."
                } ?: "Ideal drive time is unavailable until distance and speed are valid.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )

            HorizontalDivider(color = AresBorder)
            SectionTitle("3. Parallel action, condition, and resources")
            TeachingSwitch(
                label = "Named mechanism action exists",
                explanation = "A real routine can reference only an action in the selected project's action catalog.",
                checked = input.mechanismActionAvailable,
                onChecked = { input = input.copy(mechanismActionAvailable = it) },
            )
            TeachingSwitch(
                label = "Mechanism action is optional",
                explanation = "Only an explicitly optional action may use continue-on-failure in this teaching plan.",
                checked = input.mechanismActionOptional,
                onChecked = { input = input.copy(mechanismActionOptional = it) },
            )
            TeachingSwitch(
                label = "Mechanism branch also claims drivebase",
                explanation = "Parallel branches cannot safely own the same exclusive resource.",
                checked = input.mechanismClaimsDrivebase,
                onChecked = { input = input.copy(mechanismClaimsDrivebase = it) },
            )
            ChoiceRow(
                label = "Action condition",
                choices = TeachingAutoCondition.entries,
                selected = input.condition,
                text = { it.label },
                onSelected = { input = input.copy(condition = it) },
            )
            ChoiceRow(
                label = "Failure behavior",
                choices = TeachingAutoFailurePolicy.entries,
                selected = input.failurePolicy,
                text = { it.label },
                onSelected = { input = input.copy(failurePolicy = it) },
            )

            ResultPanel(input, result)
            Button(
                onClick = { input = AutonomousSafetyTeachingInput() },
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Text("Reset safe example", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ResultPanel(
    input: AutonomousSafetyTeachingInput,
    result: com.ares.analytics.ui.help.AutonomousSafetyTeachingResult,
) {
    val accent = if (result.previewReady) AresGreen else AresAmber
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (result.previewReady) "Preview ready teaching plan" else "Teaching plan blocked"
        },
        color = AresSurfaceElevated,
        border = BorderStroke(1.dp, accent),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    if (result.previewReady) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = accent,
                )
                Text(
                    if (result.previewReady) "PREVIEW READY" else "PREVIEW BLOCKED",
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
            }
            result.planSummary.forEachIndexed { index, summary ->
                Text("${index + 1}. $summary", color = AresTextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (result.reasons.isNotEmpty()) {
                Text("Fix before opening the real builder:", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                result.reasons.forEach { Text("• $it", color = AresTextPrimary, fontSize = 12.sp, lineHeight = 18.sp) }
            } else if (input.condition == TeachingAutoCondition.NOT_READY) {
                Text(
                    "The plan validates, but the false condition means the mechanism action will be skipped. The drive step still follows its own timeout and failure behavior.",
                    color = AresTextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            Text(
                "Teaching boundary: preview readiness is not generated-code evidence, collision proof, field clearance, or physical safety approval.",
                color = AresTextSecondary,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun TeachingSlider(
    label: String,
    value: Double,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValue: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label: ${decimal(value)} $unit", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onValue(it.toDouble()) },
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = "$label in $unit" },
        )
    }
}

@Composable
private fun TeachingSwitch(
    label: String,
    explanation: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(explanation, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    text: (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelected(choice) },
                    label = { Text(text(choice)) },
                    modifier = Modifier.widthIn(min = 150.dp).semantics {
                        stateDescription = if (choice == selected) "Selected" else "Not selected"
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
}

private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
