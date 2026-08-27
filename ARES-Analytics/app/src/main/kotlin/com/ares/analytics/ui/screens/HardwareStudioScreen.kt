package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel

enum class HardwareStudioTab(
    val label: String,
    val icon: ImageVector,
    val description: String,
) {
    DRIVETRAIN(
        label = "Drivetrain & Localization",
        icon = Icons.Default.Settings,
        description = "Kinematics, wheel geometry, dead-wheel/Pinpoint odometry, and chassis preview",
    ),
    MECHANISMS(
        label = "Mechanisms & Subsystems",
        icon = Icons.Default.Construction,
        description = "Actuators, servos, motors, sensors, state machines, and motion profiles",
    ),
    PORT_MAP(
        label = "Port Map & Physical Review",
        icon = Icons.Default.ElectricalServices,
        description = "Physical address allocations, CAN IDs, conflict checking, and sign-off",
    ),
}

/**
 * Unified Hardware Studio consolidating Drivetrain kinematics, Subsystem mechanisms,
 * and Physical Port review into a single, cohesive workspace.
 */
@Composable
fun HardwareStudioScreen(
    drivebaseViewModel: DrivebaseBuilderViewModel,
    subsystemViewModel: SubsystemGeneratorViewModel,
    hardwareSetupViewModel: HardwareSetupViewModel,
    initialTab: HardwareStudioTab = HardwareStudioTab.DRIVETRAIN,
    onBackToStudio: () -> Unit,
) {
    var activeTab by remember { mutableStateOf(initialTab) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AresBackground),
    ) {
        // Unified Studio Sub-Header Strip
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = onBackToStudio) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Robot Studio")
                        }
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Hardware Studio", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = AresCyan.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.3f)),
                                ) {
                                    Text(
                                        "UNIFIED HARDWARE",
                                        color = AresCyan,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(activeTab.description, color = AresTextSecondary, fontSize = 11.sp)
                        }
                    }

                    // Tab Selector Switcher
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HardwareStudioTab.entries.forEach { tab ->
                            val selected = tab == activeTab
                            FilterChip(
                                selected = selected,
                                onClick = { activeTab = tab },
                                label = {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Text(tab.label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AresCyan,
                                    selectedLabelColor = AresOnAccent,
                                    selectedLeadingIconColor = AresOnAccent,
                                    containerColor = AresSurfaceElevated,
                                    labelColor = AresTextPrimary,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = if (selected) AresCyan else AresBorder,
                                    selectedBorderColor = AresCyan,
                                    enabled = true,
                                    selected = selected,
                                ),
                            )
                        }
                    }
                }
            }
        }

        // Active Studio Body
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            when (activeTab) {
                HardwareStudioTab.DRIVETRAIN -> {
                    DrivebaseBuilderScreen(
                        viewModel = drivebaseViewModel,
                        onContinueToSubsystems = { activeTab = HardwareStudioTab.MECHANISMS },
                        onBackToStudio = onBackToStudio,
                    )
                }
                HardwareStudioTab.MECHANISMS -> {
                    SubsystemGeneratorScreen(
                        viewModel = subsystemViewModel,
                        onContinueToPortMap = { activeTab = HardwareStudioTab.PORT_MAP },
                        onBackToDrivetrain = { activeTab = HardwareStudioTab.DRIVETRAIN },
                    )
                }
                HardwareStudioTab.PORT_MAP -> {
                    HardwareSetupScreen(
                        viewModel = hardwareSetupViewModel,
                        onOpenDrivebase = { activeTab = HardwareStudioTab.DRIVETRAIN },
                        onOpenSubsystems = { activeTab = HardwareStudioTab.MECHANISMS },
                        onBackToStudio = onBackToStudio,
                    )
                }
            }
        }
    }
}
