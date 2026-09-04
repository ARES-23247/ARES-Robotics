package com.ares.analytics.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.areslib.project.AresFtcHubCommandTransport

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun FtcRuntimeOptionsEditor(
    transport: AresFtcHubCommandTransport,
    limelightProxyEnabled: Boolean,
    enabled: Boolean,
    onTransportChanged: (AresFtcHubCommandTransport) -> Unit,
    onLimelightProxyChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Control Hub runtime", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Choose how this robot sends motor commands. The choice is saved with the project, generated into robot code, and reported back on the dashboard.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = transport == AresFtcHubCommandTransport.STANDARD_SDK,
                onClick = { onTransportChanged(AresFtcHubCommandTransport.STANDARD_SDK) },
                enabled = enabled,
                label = { Text("Standard FTC SDK · recommended") },
            )
            FilterChip(
                selected = transport == AresFtcHubCommandTransport.ARES_PHOTON,
                onClick = { onTransportChanged(AresFtcHubCommandTransport.ARES_PHOTON) },
                enabled = enabled,
                label = { Text("ARES Photon · experimental") },
            )
        }
        Text(
            if (transport == AresFtcHubCommandTransport.ARES_PHOTON) {
                "Experimental: ARES may use a lower-overhead direct REV Hub write path. Every unsupported or failed command falls back to the FTC SDK. Verify it on restrained physical hardware before competition. Local simulation shows it as selected but not hardware-active."
            } else {
                "Uses the supported FTC SDK command path with ARES cached reads and safety handling. This is the safest starting point for a new robot."
            },
            color = if (transport == AresFtcHubCommandTransport.ARES_PHOTON) AresGold else AresTextSecondary,
            fontSize = 11.sp,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Limelight camera proxy", color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    "Off by default. Enable only when the laptop must reach Limelight web/video ports through the Control Hub.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                )
            }
            Switch(limelightProxyEnabled, onLimelightProxyChanged, enabled = enabled)
        }
    }
}
