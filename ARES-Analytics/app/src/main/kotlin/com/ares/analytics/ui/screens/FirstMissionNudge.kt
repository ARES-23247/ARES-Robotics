package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
internal fun FirstMissionNudge(onStart: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        color = AresCyan.copy(alpha = .10f),
        border = BorderStroke(1.dp, AresCyan.copy(alpha = .65f)),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.School, contentDescription = null, tint = AresCyan)
            Column(Modifier.weight(1f)) {
                Text("Try your first simulator mission", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    "Open the guided mission to launch Local Sim, identify live telemetry, and stop it safely.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
            }
            Button(onClick = onStart) { Text("Start mission") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss first mission suggestion")
            }
        }
    }
}
