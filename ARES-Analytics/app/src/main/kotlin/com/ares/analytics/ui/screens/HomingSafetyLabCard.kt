package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import com.ares.analytics.ui.help.HomingSafetyTeachingInput
import com.ares.analytics.ui.help.TeachingHomingMethod
import com.ares.analytics.ui.help.advanceHomingEvidence
import com.ares.analytics.ui.help.attemptTeachingNeutralRecovery
import com.ares.analytics.ui.help.evaluateHomingSafetyTeaching
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

/**
 * Hardware-free Academy model for cached feedback, homing dwell, fault latching, and recovery.
 * It owns no service, file, NT4 publisher, simulator, or hardware reference.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun HomingSafetyLabCard(modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf(HomingSafetyTeachingInput()) }
    var neutralWriteSucceeds by remember { mutableStateOf(true) }
    val result = evaluateHomingSafetyTeaching(input)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Homing and feedback safety model", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Teaching model · cached values only · no NT4, project writes, simulator commands, or physical hardware.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )

            Text("1 · Choose the evidence source", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                TeachingHomingMethod.entries.forEach { method ->
                    FilterChip(
                        selected = input.method == method,
                        onClick = { input = input.copy(method = method, evidenceDwellMs = 0L, homed = false) },
                        label = { Text(method.label) },
                        modifier = Modifier.semantics {
                            stateDescription = if (input.method == method) "Selected homing evidence" else "Available homing evidence"
                        },
                    )
                }
            }

            HorizontalDivider(color = AresBorder)
            Text("2 · Change one cached input", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            TeachingSwitch("Configuration healthy", input.configurationHealthy) {
                input = input.copy(configurationHealthy = it)
            }
            TeachingSlider(
                label = "Cached feedback age",
                value = input.feedbackAgeMs.toFloat(),
                valueRange = 0f..500f,
                valueText = "${input.feedbackAgeMs} ms (timeout ${input.feedbackTimeoutMs} ms)",
                description = "Cached feedback age in milliseconds",
            ) { input = input.copy(feedbackAgeMs = it.toLong()) }

            when (input.method) {
                TeachingHomingMethod.SENSOR -> TeachingSwitch("Home sensor active", input.sensorActive) {
                    input = input.copy(sensorActive = it)
                }
                TeachingHomingMethod.CURRENT_STALL -> CurrentEvidenceInputs(input) { input = it }
                TeachingHomingMethod.VELOCITY_STALL -> VelocityEvidenceInputs(input) { input = it }
                TeachingHomingMethod.COMBINED_STALL -> {
                    CurrentEvidenceInputs(input) { input = it }
                    VelocityEvidenceInputs(input) { input = it }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { input = advanceHomingEvidence(input, 50L) },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) { Text("Advance evidence 50 ms") }
                OutlinedButton(onClick = { input = input.copy(evidenceDwellMs = 0L, homed = false) }) {
                    Text("Reset home reference")
                }
            }
            Text(
                "Evidence dwell: ${input.evidenceDwellMs} / ${input.requiredDwellMs} ms · Home reference: ${if (input.homed) "established" else "not established"}",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )

            HorizontalDivider(color = AresBorder)
            Text("3 · Observe fault latching and neutral recovery", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { input = input.copy(faultLatched = true) }) {
                    Text("Simulate failed output write")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Neutral write succeeds", color = AresTextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = neutralWriteSucceeds,
                        onCheckedChange = { neutralWriteSucceeds = it },
                        modifier = Modifier.semantics { contentDescription = "Whether the modeled neutral write succeeds" },
                    )
                }
                OutlinedButton(
                    onClick = { input = attemptTeachingNeutralRecovery(input, neutralWriteSucceeds) },
                    enabled = input.faultLatched,
                ) { Text("Attempt neutral recovery") }
            }

            SafetyDecisionCard(input, result.motionPermitted, result.reasons)

            OutlinedButton(onClick = {
                input = HomingSafetyTeachingInput()
                neutralWriteSucceeds = true
            }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Reset teaching model")
            }
        }
    }
}

@Composable
private fun CurrentEvidenceInputs(
    input: HomingSafetyTeachingInput,
    onChange: (HomingSafetyTeachingInput) -> Unit,
) {
    TeachingSwitch("Current reading valid", input.currentValid) { onChange(input.copy(currentValid = it)) }
    TeachingSlider(
        label = "Cached current",
        value = input.currentAmps.toFloat(),
        valueRange = 0f..8f,
        valueText = "${"%.1f".format(input.currentAmps)} A (stall threshold ${"%.1f".format(input.currentThresholdAmps)} A)",
        description = "Cached current measurement in amperes",
    ) { onChange(input.copy(currentAmps = it.toDouble())) }
}

@Composable
private fun VelocityEvidenceInputs(
    input: HomingSafetyTeachingInput,
    onChange: (HomingSafetyTeachingInput) -> Unit,
) {
    TeachingSwitch("Velocity reading valid", input.velocityValid) { onChange(input.copy(velocityValid = it)) }
    TeachingSlider(
        label = "Cached speed magnitude",
        value = kotlin.math.abs(input.velocityRps).toFloat(),
        valueRange = 0f..2f,
        valueText = "${"%.2f".format(kotlin.math.abs(input.velocityRps))} rot/s (stall threshold ${"%.2f".format(input.velocityThresholdRps)} rot/s)",
        description = "Cached mechanism speed in rotations per second",
    ) { onChange(input.copy(velocityRps = it.toDouble())) }
}

@Composable
private fun TeachingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = AresTextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun TeachingSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    description: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label · $valueText", color = AresTextPrimary, fontSize = 12.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = description
                stateDescription = valueText
            },
        )
    }
}

@Composable
private fun SafetyDecisionCard(
    input: HomingSafetyTeachingInput,
    motionPermitted: Boolean,
    reasons: List<String>,
) {
    val accent = if (motionPermitted) AresGreen else AresError
    Surface(
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.75f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (motionPermitted) "Motion permitted in teaching model" else "Motion blocked; neutral required"
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (motionPermitted) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = accent,
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    if (motionPermitted) "MOTION PERMITTED · teaching model only" else "MOTION BLOCKED · neutral required",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (reasons.isEmpty()) {
                Text("Configuration, freshness, home reference, and fault state all pass this simplified check.", color = AresTextSecondary)
            } else {
                reasons.forEach { Text("• $it", color = AresTextSecondary, fontSize = 12.sp) }
            }
            if (input.faultLatched) {
                Text("A reset button alone is not recovery: the neutral write must succeed first.", color = AresGold, fontSize = 12.sp)
            }
        }
    }
}
