package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.AlertRecord
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardCriticalAlertStack(
    alerts: List<AlertRecord>,
    onDismiss: (AlertRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = modifier.width(320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        alerts.forEach { alert ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AresError.copy(alpha = 0.95f)),
                shape = RoundedCornerShape(8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Warning, null, tint = AresBackground, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = criticalAlertTitle(alert.ruleKey),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AresBackground,
                        )
                        Text(
                            text = "Peak: ${String.format("%.2f", alert.peakValue)} | Triggered: ${timeFormat.format(Date(alert.triggerTimestampMs))}",
                            fontSize = 10.sp,
                            color = AresBackground.copy(alpha = 0.8f),
                        )
                    }
                    IconButton(onClick = { onDismiss(alert) }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Dismiss", tint = AresBackground, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
internal fun criticalAlertTitle(ruleKey: String): String = when {
    ruleKey.contains("brownout", ignoreCase = true) -> "CRITICAL BROWNOUT"
    ruleKey.contains("comms", ignoreCase = true) -> "COMMS / PACKET LOSS"
    ruleKey.contains("can", ignoreCase = true) -> "CANBUS HARDWARE ERROR"
    ruleKey.contains("battery", ignoreCase = true) -> "LOW BATTERY ALERT"
    else -> ruleKey.uppercase()
}
