package com.ares.analytics.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IntegrationInstructions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.ManagedToolchainInstallState
import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ManagedToolchainService
import com.ares.analytics.service.RobotToolchainSnapshot
import com.ares.analytics.service.ToolchainReadiness
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.openExternalLink
import com.ares.analytics.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun ProfileRobotBuildToolsSection(
    config: WorkspaceConfig,
    toolchains: RobotToolchainSnapshot,
    toolchainInstallState: ManagedToolchainInstallState,
    managedToolchainService: ManagedToolchainService,
) {
    val scope = rememberCoroutineScope()
    AresCard(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.IntegrationInstructions, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                Text("Robot build tools", fontWeight = FontWeight.Bold, color = AresTextPrimary, fontSize = 15.sp)
            }
            Text(
                "The installed ARES app already has its own runtime. These optional tools are used only to build, simulate, or deploy ${config.league.name} robot projects.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            toolchains.components.forEach { component ->
                Row(
                    modifier = Modifier.fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(8.dp)).padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (component.readiness) {
                            ToolchainReadiness.READY -> "READY"
                            ToolchainReadiness.OPTIONAL_DOWNLOAD -> "INSTALL"
                            ToolchainReadiness.MANUAL_SETUP_REQUIRED -> "ACTION NEEDED"
                        },
                        color = when (component.readiness) {
                            ToolchainReadiness.READY -> AresGreen
                            ToolchainReadiness.OPTIONAL_DOWNLOAD -> AresCyan
                            ToolchainReadiness.MANUAL_SETUP_REQUIRED -> AresAmber
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(component.name, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
                        Text(component.detail, color = AresTextSecondary, fontSize = 11.sp)
                        component.location?.let { Text(it, color = AresTextTertiary, fontSize = 10.sp) }
                    }
                }
            }
            when (val install = toolchainInstallState) {
                is ManagedToolchainInstallState.Working -> {
                    install.fraction?.let { fraction ->
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = AresCyan)
                    }
                    Text(install.message, color = AresTextSecondary, fontSize = 11.sp)
                }
                is ManagedToolchainInstallState.Succeeded -> Text(install.message, color = AresGreen, fontSize = 11.sp)
                is ManagedToolchainInstallState.Failed -> Text(install.message, color = AresAmber, fontSize = 11.sp)
                ManagedToolchainInstallState.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (toolchains.components.any { it.name.startsWith("Java") && it.readiness != ToolchainReadiness.READY }) {
                    Button(
                        onClick = {
                            if (ManagedToolchainPaths.managedJdkInstallationSupported()) {
                                scope.launch { runCatching { managedToolchainService.installManagedJdk21(config.league) } }
                            } else {
                                openExternalLink(ManagedToolchainPaths.JDK_21_DOWNLOAD_URL)
                            }
                        },
                        enabled = toolchainInstallState !is ManagedToolchainInstallState.Working,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text(if (ManagedToolchainPaths.managedJdkInstallationSupported()) "Install private JDK 21" else "Download JDK 21")
                    }
                }
                OutlinedButton(onClick = { scope.launch { managedToolchainService.refresh(config.league) } }) {
                    Text("Recheck tools")
                }
                OutlinedButton(onClick = {
                    openExternalLink("https://github.com/ARES-23247/ARES-Analytics/blob/master/docs/start/ROBOT_BUILD_TOOLS.md")
                }) { Text("Setup guide") }
            }
        }
    }
}
