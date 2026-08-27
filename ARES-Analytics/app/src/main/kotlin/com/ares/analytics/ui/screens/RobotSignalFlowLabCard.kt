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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.help.RobotSignalTeachingInput
import com.ares.analytics.ui.help.RobotSignalTeachingResult
import com.ares.analytics.ui.help.RobotSignalTeachingSnapshot
import com.ares.analytics.ui.help.TeachingSignalPath
import com.ares.analytics.ui.help.runRobotSignalTeachingLoop
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import java.util.Locale

/** Hardware-free visual trace through controller input, Redux, mock IO, and telemetry. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun RobotSignalFlowLabCard(modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf(RobotSignalTeachingInput()) }
    var currentState by remember { mutableStateOf(RobotSignalTeachingSnapshot()) }
    var result by remember { mutableStateOf<RobotSignalTeachingResult?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Trace one robot signal",
                modifier = Modifier.semantics { heading() },
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            Text(
                "Teaching model · no production Redux store, NT4 publisher, project write, simulator command, or physical hardware.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )

            Text("1 · Choose a signal path", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                TeachingSignalPath.entries.forEach { path ->
                    FilterChip(
                        selected = input.path == path,
                        onClick = {
                            input = defaultInput(path)
                            currentState = RobotSignalTeachingSnapshot()
                            result = null
                        },
                        label = { Text(path.label) },
                        modifier = Modifier.semantics {
                            stateDescription = if (input.path == path) "Selected signal path" else "Available signal path"
                        },
                    )
                }
            }

            HorizontalDivider(color = AresBorder)
            Text("2 · Configure one modeled loop", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            SignalSlider(
                label = input.path.inputLabel,
                value = input.rawInput.toFloat(),
                range = input.path.inputRange.start.toFloat()..input.path.inputRange.endInclusive.toFloat(),
                valueText = "${format(input.rawInput)} ${input.path.inputUnit}",
            ) { input = input.copy(rawInput = it.toDouble()) }

            if (input.path != TeachingSignalPath.DISTANCE_SENSOR) {
                SignalSwitch("Invert this device", input.inverted) { input = input.copy(inverted = it) }
                SignalSlider(
                    label = "Controller deadband",
                    value = input.deadband.toFloat(),
                    range = 0f..0.3f,
                    valueText = format(input.deadband),
                ) { input = input.copy(deadband = it.toDouble()) }
                SignalSwitch("Configuration healthy", input.configurationHealthy) {
                    input = input.copy(configurationHealthy = it)
                }
                SignalSwitch("Mock output write succeeds", input.outputWriteSucceeds) {
                    input = input.copy(outputWriteSucceeds = it)
                }
            }

            if (input.path == TeachingSignalPath.MOTOR) {
                SignalSlider(
                    label = "Cached encoder velocity",
                    value = input.cachedMeasurement.toFloat(),
                    range = 0f..10f,
                    valueText = "${format(input.cachedMeasurement)} rot/s",
                ) { input = input.copy(cachedMeasurement = it.toDouble()) }
            }
            if (input.path != TeachingSignalPath.POSITIONAL_SERVO) {
                SignalSwitch("Cached measurement valid", input.measurementValid) {
                    input = input.copy(measurementValid = it)
                }
                SignalSlider(
                    label = "Cached measurement age",
                    value = input.measurementAgeMs.toFloat(),
                    range = 0f..300f,
                    valueText = "${input.measurementAgeMs} ms (limit ${input.feedbackTimeoutMs} ms)",
                ) { input = input.copy(measurementAgeMs = it.toLong()) }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val next = runRobotSignalTeachingLoop(currentState, input)
                        result = next
                        currentState = next.finalState
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) { Text("Run one modeled loop") }
                OutlinedButton(onClick = {
                    input = defaultInput(input.path)
                    currentState = RobotSignalTeachingSnapshot()
                    result = null
                }) { Text("Reset signal trace") }
            }

            result?.let { SignalFlowResult(it) } ?: EmptySignalResult()
        }
    }
}

@Composable
private fun SignalFlowResult(result: RobotSignalTeachingResult) {
    HorizontalDivider(color = AresBorder)
    Text("3 · Follow the evidence", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
    TeachingStageCard("A · Retained previous snapshot") {
        SnapshotText(result.previousState)
        Text(
            "This object remains unchanged after the reducer creates the next snapshot.",
            color = AresTextSecondary,
            fontSize = 12.sp,
        )
    }
    TeachingArrow("dispatch")
    TeachingStageCard("B · Typed Redux action(s)") {
        result.actionDescriptions.forEach { Text(it, color = AresTextPrimary, fontFamily = FontFamily.Monospace) }
    }
    TeachingArrow("pure reducer returns a new value")
    TeachingStageCard("C · Immutable next state") { SnapshotText(result.reducerState) }
    TeachingArrow("controller reads state")
    TeachingStageCard("D · Controller decision") {
        Text(result.controllerDecision, color = AresTextPrimary)
        Text(
            result.controllerOutput?.let { "Requested mock output: ${format(it)}" } ?: "Requested mock output: none",
            color = AresTextSecondary,
        )
    }
    TeachingArrow("IO boundary")
    TeachingStageCard("E · Mock adapter result") {
        Text(result.ioResult, color = AresTextPrimary)
        Text(
            "Fault latch: ${if (result.finalState.outputFaultLatched) "LATCHED · recovery required" else "clear"}",
            color = if (result.finalState.outputFaultLatched) AresError else AresGreen,
            fontWeight = FontWeight.SemiBold,
        )
    }
    TeachingArrow("publish observation")
    val telemetryStatus = if (result.telemetry.valid) "VALID" else "INVALID OR STALE"
    TeachingStageCard("F · Unit-tagged telemetry") {
        Text(result.telemetry.topic, color = AresTextPrimary, fontFamily = FontFamily.Monospace)
        Text(
            "Value: ${result.telemetry.value?.let(::format) ?: "not published as valid"} ${result.telemetry.unit}",
            color = AresTextPrimary,
        )
        Text("Status: $telemetryStatus · ${result.telemetry.freshnessText}", color = AresTextSecondary)
    }
}

@Composable
private fun EmptySignalResult() {
    Surface(
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Choose a path, predict what each stage will contain, then run one modeled loop. The trace will preserve the old state beside the new state.",
            modifier = Modifier.padding(14.dp),
            color = AresTextSecondary,
        )
    }
}

@Composable
private fun TeachingStageCard(title: String, content: @Composable () -> Unit) {
    Surface(
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun TeachingArrow(label: String) {
    Text("↓ $label", color = AresTextSecondary, fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp))
}

@Composable
private fun SnapshotText(snapshot: RobotSignalTeachingSnapshot) {
    Text(
        "sequence=${snapshot.eventSequence}, requested=${format(snapshot.requestedValue)}, " +
            "cached=${format(snapshot.cachedMeasurement)}, valid=${snapshot.cachedMeasurementValid}, " +
            "age=${snapshot.cachedMeasurementAgeMs} ms, fault=${snapshot.outputFaultLatched}",
        color = AresTextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
    )
}

@Composable
private fun SignalSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
private fun SignalSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label · $valueText", color = AresTextPrimary, fontSize = 12.sp)
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = valueText
            },
        )
    }
}

private fun defaultInput(path: TeachingSignalPath): RobotSignalTeachingInput = when (path) {
    TeachingSignalPath.MOTOR -> RobotSignalTeachingInput(path = path)
    TeachingSignalPath.POSITIONAL_SERVO -> RobotSignalTeachingInput(path = path, rawInput = 0.0)
    TeachingSignalPath.DISTANCE_SENSOR -> RobotSignalTeachingInput(path = path, rawInput = 50.0)
}

private fun format(value: Double): String = String.format(Locale.ROOT, "%.3f", value)
