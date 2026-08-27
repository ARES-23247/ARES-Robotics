package com.ares.analytics.ui.screens.onboarding

import com.ares.analytics.ui.theme.AresOnAccent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.service.ManagedToolchainInstallState
import com.ares.analytics.service.ManagedToolchainPaths

/** Advisory check for the external JDK used by robot builds and simulation. The packaged app has its own runtime. */
@Composable
fun JavaVerificationStep(
    isValid: Boolean?,
    isVerifying: Boolean,
    message: String,
    installState: ManagedToolchainInstallState,
    onVerifyClick: () -> Unit,
    onInstallClick: () -> Unit,
) {
    val installWorking = installState is ManagedToolchainInstallState.Working
    val icon = when (isValid) {
        true -> Icons.Default.CheckCircle
        false -> Icons.Default.Warning
        null -> Icons.Default.HourglassEmpty
    }
    val tint = when (isValid) {
        true -> AresGreen
        false -> AresAmber
        null -> AresTextTertiary
    }
    val iconDescription = when (isValid) {
        true -> "Robot build tools ready"
        false -> "Robot build tools need attention"
        null -> "Robot build tools not checked"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isValid == false) AresAmber else AresBorder, RoundedCornerShape(8.dp))
            .background(AresSurfaceElevated)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = iconDescription, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Robot build tools (optional)", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                if (isVerifying) "Checking the Java version..." else message,
                style = MaterialTheme.typography.bodySmall,
                color = if (isValid == false) AresAmber else AresTextSecondary,
            )
            Text(
                "ARES Robotics Studio uses its bundled runtime. This check only affects Build and Local Simulator.",
                style = MaterialTheme.typography.bodySmall,
                color = AresTextTertiary,
            )
            when (installState) {
                is ManagedToolchainInstallState.Working -> {
                    installState.fraction?.let { fraction ->
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = AresCyan,
                        )
                    }
                    Text(installState.message, color = AresTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                is ManagedToolchainInstallState.Succeeded ->
                    Text(installState.message, color = AresGreen, style = MaterialTheme.typography.bodySmall)
                is ManagedToolchainInstallState.Failed ->
                    Text(installState.message, color = AresAmber, style = MaterialTheme.typography.bodySmall)
                ManagedToolchainInstallState.Idle -> Unit
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isValid != true) {
                Button(
                    onClick = onInstallClick,
                    enabled = !installWorking && !isVerifying,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = AresBackground)
                    Text(
                        if (ManagedToolchainPaths.managedJdkInstallationSupported()) "Install JDK 21" else "Download JDK 21",
                        color = AresBackground,
                    )
                }
            }
            Button(
                onClick = onVerifyClick,
                enabled = !isVerifying && !installWorking,
                colors = ButtonDefaults.buttonColors(containerColor = AresBorder, contentColor = AresTextPrimary),
            ) {
                if (isVerifying) {
                    CircularProgressIndicator(color = AresTextPrimary, strokeWidth = 2.dp, modifier = Modifier.width(20.dp))
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = AresTextPrimary)
                    Text("Recheck", color = AresTextPrimary)
                }
            }
        }
    }
}
