package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

internal data class DashboardOfflineNextStep(
    val headline: String,
    val subtitle: String,
    val primarySimActionLabel: String,
)

internal fun describeDashboardOfflineNextStep(
    isLocalSimulatorSelected: Boolean,
    canLaunchSimulator: Boolean,
    isLaunchPreparationRunning: Boolean = false,
): DashboardOfflineNextStep = when {
    isLaunchPreparationRunning -> DashboardOfflineNextStep(
        headline = "Preparing Local Simulator…",
        subtitle = "Building and verifying your robot project. Live telemetry cards will activate automatically once the physics server connects on 127.0.0.1:5810.",
        primarySimActionLabel = "Building Simulator…",
    )
    isLocalSimulatorSelected -> DashboardOfflineNextStep(
        headline = "Local Simulator mode is selected — ready to launch",
        subtitle = "Click 'Launch Local Simulator' to start the physics server (no robot hardware needed), or open Run History to replay a saved match log.",
        primarySimActionLabel = if (canLaunchSimulator) "Launch Local Simulator" else "Show Simulator Bar",
    )
    else -> DashboardOfflineNextStep(
        headline = "Choose where your Dashboard data should come from",
        subtitle = "No physical robot is connected yet. For safe practice without hardware, launch the Local Simulator in one click, or open Run History to inspect recorded sessions.",
        primarySimActionLabel = "Practice in Local Simulator",
    )
}

@Composable
internal fun DashboardOfflineGuide(
    onOpenRunHistory: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit,
    onLaunchLocalSimulator: () -> Unit = {},
    isLocalSimulatorSelected: Boolean = false,
    canLaunchSimulator: Boolean = true,
    isLaunchPreparationRunning: Boolean = false,
) {
    val step = describeDashboardOfflineNextStep(
        isLocalSimulatorSelected = isLocalSimulatorSelected,
        canLaunchSimulator = canLaunchSimulator,
        isLaunchPreparationRunning = isLaunchPreparationRunning,
    )

    Surface(
        color = AresCyan.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Explore, null, tint = AresCyan, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(step.headline, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    step.subtitle,
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            Button(
                onClick = onLaunchLocalSimulator,
                enabled = !isLaunchPreparationRunning,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.DesktopWindows, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(step.primarySimActionLabel, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Button(
                onClick = onOpenRunHistory,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Run History", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            OutlinedButton(
                onClick = onOpenHelp,
                modifier = Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 4.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                Icon(Icons.Default.School, contentDescription = null, tint = AresCyan, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Simulator Guide", fontSize = 11.sp, color = AresTextPrimary)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Dismiss dashboard guidance", tint = AresTextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
