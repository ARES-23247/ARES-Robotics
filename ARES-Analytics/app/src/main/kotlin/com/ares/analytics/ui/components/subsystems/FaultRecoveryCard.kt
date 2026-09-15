package com.ares.analytics.ui.components.subsystems

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.*

@Composable
fun FaultRecoveryCard(document: SubsystemDocument, viewModel: SubsystemGeneratorViewModel) {
    val recovery = document.safety.faultRecovery
    val eligibleActuators = document.hardware.filter {
        it.following == null && it.kind in setOf(SubsystemHardwareKind.MOTOR, SubsystemHardwareKind.CONTINUOUS_SERVO)
    }

    EditorCard("Automatic Jam Recovery / Anti-Stall", Icons.Default.Build) {
        Text("Detects mechanical jams from motor current and triggers automatic recovery.", color = AresTextSecondary, fontSize = 11.sp)
        if (eligibleActuators.isNotEmpty()) {
            ToggleRow("Enable anti-jam pulse", recovery.enabled) { value ->
                viewModel.edit { doc ->
                    val actuator = eligibleActuators.firstOrNull { it.hardwareId == doc.safety.faultRecovery.actuatorId }
                        ?: eligibleActuators.first()
                    val current = actuator.measurements.firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }
                    doc.copy(safety = doc.safety.copy(
                        faultRecovery = doc.safety.faultRecovery.copy(
                            enabled = value,
                            actuatorId = actuator.hardwareId.takeIf { value },
                            currentFieldId = current?.fieldId.takeIf { value },
                        ),
                        requiresCurrentMonitoring = doc.safety.requiresCurrentMonitoring || value,
                    ))
                }
            }
            if (recovery.enabled) {
                DropdownSelector("Actuator to recover", recovery.actuatorId ?: eligibleActuators.first().hardwareId, eligibleActuators.map { it.hardwareId }) { selected ->
                    val current = eligibleActuators.first { it.hardwareId == selected }.measurements
                        .firstOrNull { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }?.fieldId
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(actuatorId = selected, currentFieldId = current))) }
                }
                val currentOptions = document.hardware.firstOrNull { it.hardwareId == recovery.actuatorId }?.measurements
                    .orEmpty().filter { it.source == SubsystemMeasurementSource.MOTOR_CURRENT_AMPS }.map { it.fieldId }
                if (currentOptions.isNotEmpty()) {
                    DropdownSelector("Cached current signal", recovery.currentFieldId ?: currentOptions.first(), currentOptions) { selected ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentFieldId = selected))) }
                    }
                } else {
                    Text("The selected actuator needs a cached motor-current signal before recovery can be saved.", color = AresGold, fontSize = 10.sp)
                }
                DoubleInput("Jam current threshold (A)", recovery.currentThresholdAmps) { value ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentThresholdAmps = value))) }
                }
                LongInput("Jam evidence duration (ms)", recovery.currentDurationMs) { value ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(currentDurationMs = value))) }
                }
                EnumSelector(
                    "Recovery action",
                    recovery.recoveryAction,
                    listOf(FaultRecoveryActionKind.REVERSE_BRIEFLY, FaultRecoveryActionKind.NEUTRAL_STOP),
                ) { action ->
                    viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(recoveryAction = action))) }
                }
                if (recovery.recoveryAction == FaultRecoveryActionKind.REVERSE_BRIEFLY) {
                    DoubleInput("Reverse output (normalized -1 to 1)", recovery.reverseDutyCycle) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(reverseDutyCycle = value))) }
                    }
                    LongInput("Reverse duration (ms)", recovery.reverseDurationMs) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(reverseDurationMs = value))) }
                    }
                    IntInput("Maximum automatic retries", recovery.maxRetries) { value ->
                        viewModel.edit { doc -> doc.copy(safety = doc.safety.copy(faultRecovery = doc.safety.faultRecovery.copy(maxRetries = value))) }
                    }
                }
                FieldGuidance("Recovery is bounded and uses cached current only. Exhausted retries or a failed write leave the subsystem neutral and fault-latched.")
            }
        } else {
            Text("Add an independently controlled motor before enabling anti-jam protection.", color = AresTextTertiary, fontSize = 10.sp)
        }
    }
}
