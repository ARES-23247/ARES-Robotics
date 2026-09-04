package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.GamepadState
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerInputPlatform

@Composable
internal fun SelectedControlCard(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    liveState: GamepadState,
) {
    val control = state.selectedControl ?: return
    val assignedBindings = state.selectedScheme?.bindings.orEmpty().filter { control.controlId in it.source.controlIds }
    val targetMapping = control.mappings.firstOrNull { it.platform == state.targetPlatform }
    var showHardwareSetup by remember(control.controlId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text(control.displayName, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (control.isActive(liveState)) "LIVE INPUT ACTIVE" else control.type.name,
                    color = if (control.isActive(liveState)) AresCyan else AresTextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Button(
                onClick = viewModel::createBinding,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                Text(" Add action")
            }
        }
        HorizontalDivider(color = AresBorder)
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    if (assignedBindings.isEmpty()) "No action assigned yet" else
                        "${assignedBindings.size} assigned action${if (assignedBindings.size == 1) "" else "s"}",
                    color = if (assignedBindings.isEmpty()) AresTextSecondary else AresGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                )
                Text(
                    if (targetMapping == null) {
                        "${state.targetPlatform.studentLabel()} input is not configured"
                    } else {
                        "Ready for ${state.targetPlatform.studentLabel()} generated code"
                    },
                    color = if (targetMapping == null) AresGold else AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            OutlinedButton(onClick = { showHardwareSetup = !showHardwareSetup }) {
                Text(if (showHardwareSetup) "Hide hardware setup" else "Hardware setup", fontSize = 11.sp)
            }
        }
        assignedBindings.take(3).forEach { binding ->
            Text("• ${binding.displayName}", color = AresTextPrimary, fontSize = 11.sp)
        }
        if (showHardwareSetup) {
            HorizontalDivider(color = AresBorder)
            Text("Advanced hardware mapping", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                "Configure how this physical input maps to raw Driver Station / controller hardware.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            HardwareMappingRow(control, state.targetPlatform, liveState, viewModel)
        }
    }
}

@Composable
private fun HardwareMappingRow(
    control: ControllerControlDocument,
    platform: ControllerInputPlatform,
    liveState: GamepadState,
    viewModel: ControlsEditorViewModel,
) {
    val mapping = control.mappings.firstOrNull { it.platform == platform }
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(
            "${platform.studentLabel()}: ${mapping?.buttonIndex?.let { "button $it" } ?: mapping?.axisIndex?.let { "axis $it" } ?: "unmapped"}",
            color = AresTextPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )
        OutlinedButton(onClick = { viewModel.beginDesktopLearning(liveState) }) {
            Text("Detect live press", fontSize = 11.sp)
        }
    }
}

private fun ControllerInputPlatform.studentLabel() = when (this) {
    ControllerInputPlatform.FTC -> "FTC"
    ControllerInputPlatform.FRC -> "FRC"
    ControllerInputPlatform.XRP -> "XRP"
    ControllerInputPlatform.DESKTOP_GLFW -> "Desktop simulator"
}

@Composable
internal fun AccessibleControlList(
    state: ControlsEditorState,
    viewModel: ControlsEditorViewModel,
    liveState: GamepadState,
) {
    val profile = state.selectedProfile ?: return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text("Accessible control list", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Find a control or binding") },
        )
        profile.controls.filter { control ->
            state.search.isBlank() || control.displayName.contains(state.search, true) ||
                control.controlId.contains(state.search, true) ||
                state.selectedScheme?.bindings.orEmpty().any { binding ->
                    control.controlId in binding.source.controlIds && binding.displayName.contains(state.search, true)
                }
        }.forEach { control ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.selectControl(control.controlId) }
                    .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(6.dp)).padding(8.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically,
            ) {
                Text(control.displayName, color = if (control.isActive(liveState)) AresCyan else AresTextPrimary)
                Text(
                    "${control.surface.name.lowercase()} • ${control.type.name.lowercase()}",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
