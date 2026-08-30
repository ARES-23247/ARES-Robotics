package com.ares.analytics.ui.components.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AlertEngineService
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch
import kotlin.math.sin

@Composable
fun AlertPanel(
    alertEngineService: AlertEngineService,
    modifier: Modifier = Modifier
) {
    val alerts by alertEngineService.alerts.collectAsState()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Panel Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = AresCyan
                )
                Text(
                    "Threshold Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }

            IconButton(
                onClick = {
                    scope.launch { alertEngineService.clearAllResolvedAlerts() }
                }
            ) {
                Icon(imageVector = Icons.Default.ClearAll, contentDescription = "Clear All Resolved", tint = AresTextSecondary)
            }
        }

        HorizontalDivider(color = AresBorder, thickness = 1.dp)

        if (alerts.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("All systems nominal.", color = AresTextTertiary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alerts) { alert ->
                    AlertItem(
                        alert = alert,
                        onTriage = {
                            scope.launch { alertEngineService.triageAlert(alert.alertId) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertItem(
    alert: AlertRecord,
    onTriage: () -> Unit
) {
    val isActive = alert.resolveTimestampMs == null
    val isLatched = alert.resolveTimestampMs != null && !alert.triaged
    val isTriaged = alert.triaged

    // Pulse factor for active alerts
    var pulseColor by remember { mutableStateOf(AresAlertActive) }
    if (isActive) {
        val t = System.currentTimeMillis()
        val pulse = (sin(t / 150.0) + 1.0) / 2.0
        pulseColor = Color(
            red = (AresAlertActive.red * pulse + AresSurfaceElevated.red * (1.0 - pulse)).toFloat(),
            green = (AresAlertActive.green * pulse + AresSurfaceElevated.green * (1.0 - pulse)).toFloat(),
            blue = (AresAlertActive.blue * pulse + AresSurfaceElevated.blue * (1.0 - pulse)).toFloat(),
            alpha = 1f
        )
    }
    val stateColor = when {
        isActive -> pulseColor
        isLatched -> AresAlertLatched
        else -> AresAlertTriaged
    }
    val statusText = when {
        isActive -> "ACTIVE"
        isLatched -> "LATCHED"
        else -> "TRIAGED"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AresSurfaceElevated)
            .border(1.dp, stateColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    alert.ruleKey,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Peak: ${String.format("%.2f", alert.peakValue)} | Status: $statusText",
                fontSize = 11.sp,
                color = AresTextSecondary
            )
        }

        if (!isTriaged) {
            Button(
                onClick = onTriage,
                colors = ButtonDefaults.buttonColors(containerColor = AresBorder),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Triage", color = AresTextPrimary, fontSize = 11.sp)
            }
        }
    }
}
