package com.ares.analytics.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.project.ProjectIdentityEditorState
import com.ares.analytics.viewmodel.project.ProjectIdentityField
import com.areslib.project.AresXrpControllerModel

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun XrpRuntimeOptionsEditor(
    state: ProjectIdentityEditorState,
    enabled: Boolean,
    onUpdate: (ProjectIdentityField, String) -> Unit,
    onControllerModelChanged: (AresXrpControllerModel) -> Unit,
    onWifiModeChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("XRP controller, connection & safety", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "These settings are shared by the generated MicroPython robot, local simulator, and Studio. Use a unique Link port when running multiple projects on one computer.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }
        Text("Controller model", color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.draft.xrpControllerModel == AresXrpControllerModel.SPARKFUN_XRP_RP2350,
                onClick = { onControllerModelChanged(AresXrpControllerModel.SPARKFUN_XRP_RP2350) },
                enabled = enabled,
                label = { Text("SparkFun XRP (RP2350 · 4 servos)") },
            )
            FilterChip(
                selected = state.draft.xrpControllerModel == AresXrpControllerModel.SPARKFUN_XRP_BETA_RP2040,
                onClick = { onControllerModelChanged(AresXrpControllerModel.SPARKFUN_XRP_BETA_RP2040) },
                enabled = enabled,
                label = { Text("SparkFun XRP Beta (RP2040 · 2 servos)") },
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.draft.xrpWifiMode == "AP",
                onClick = { onWifiModeChanged("AP") },
                enabled = enabled,
                label = { Text("XRP creates Wi-Fi") },
            )
            FilterChip(
                selected = state.draft.xrpWifiMode == "STATION",
                onClick = { onWifiModeChanged("STATION") },
                enabled = enabled,
                label = { Text("Join an existing network") },
            )
        }
        IdentityField(
            label = "Wi-Fi network name (SSID)",
            value = state.draft.xrpSsid,
            onValueChange = { onUpdate(ProjectIdentityField.XRP_SSID, it) },
            error = state.fieldErrors[ProjectIdentityField.XRP_SSID],
            enabled = enabled,
            help = "The network the laptop and XRP use together. Passwords remain in the untracked xrp_secrets.py file.",
        )
        GeometryRow(
            firstLabel = "XRP Link port",
            firstValue = state.draft.xrpLinkPort,
            firstError = state.fieldErrors[ProjectIdentityField.XRP_LINK_PORT],
            onFirst = { onUpdate(ProjectIdentityField.XRP_LINK_PORT, it) },
            firstHelp = "Dedicated JSONL control/telemetry port; 5810 is reserved for NT4.",
            secondLabel = "Deadman timeout (ms)",
            secondValue = state.draft.xrpDeadmanTimeoutMs,
            secondError = state.fieldErrors[ProjectIdentityField.XRP_DEADMAN_TIMEOUT],
            onSecond = { onUpdate(ProjectIdentityField.XRP_DEADMAN_TIMEOUT, it) },
            secondHelp = "Motors stop when fresh commands do not arrive within this interval.",
            enabled = enabled,
        )
        IdentityField(
            label = "Brownout threshold (volts)",
            value = state.draft.xrpBrownoutThresholdVolts,
            onValueChange = { onUpdate(ProjectIdentityField.XRP_BROWNOUT_THRESHOLD, it) },
            error = state.fieldErrors[ProjectIdentityField.XRP_BROWNOUT_THRESHOLD],
            enabled = enabled,
            help = "Commands fail closed below this project-specific XRP battery voltage.",
        )
    }
}
