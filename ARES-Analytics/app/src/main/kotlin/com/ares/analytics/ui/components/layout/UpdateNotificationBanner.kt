package com.ares.analytics.ui.components.layout

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.UpdateCheckerService
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.theme.*

@Composable
fun UpdateNotificationBanner(
    updateState: UpdateCheckerService.UpdateState.UpdateAvailable,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopEnd
    ) {
        Card(
            modifier = Modifier
                .width(360.dp)
                .animateContentSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AresCyan
                    )
                    Text(
                        text = "Software Update Available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AresTextPrimary
                    )
                }

                Text(
                    text = "A new version (${updateState.latestVersion}) of ARES Robotics Studio is available.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AresTextSecondary
                )

                if (!updateState.releaseNotes.isNullOrEmpty()) {
                    Text(
                        text = updateState.releaseNotes,
                        style = MaterialTheme.typography.bodySmall,
                        color = AresTextSecondary.copy(alpha = 0.7f),
                        maxLines = 3,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss
                    ) {
                        Text("Dismiss", color = AresTextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { openExternalLink(updateState.downloadUrl) },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent)
                    ) {
                        Text("Update", color = AresBackground)
                    }
                }
            }
        }
    }
}
