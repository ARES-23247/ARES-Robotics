package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun DashboardOfflineGuide(
    onOpenRunHistory: () -> Unit,
    onOpenHelp: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = AresCyan.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Explore, null, tint = AresCyan, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Choose where your data should come from", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "No live telemetry yet. For safe practice, select Local Sim above and press Play. To review past data, open Run History.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            OutlinedButton(onClick = onOpenHelp) { Text("Simulator guide") }
            Button(
                onClick = onOpenRunHistory,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
            ) { Text("Run History", fontWeight = FontWeight.Bold) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss dashboard guidance", tint = AresTextSecondary)
            }
        }
    }
}
