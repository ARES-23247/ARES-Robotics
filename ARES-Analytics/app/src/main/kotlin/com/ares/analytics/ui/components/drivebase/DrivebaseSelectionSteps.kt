package com.ares.analytics.ui.components.drivebase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.drivebase.*
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.drivebase.*
import com.areslib.drivetrain.DisabledDrivePolicy
import com.areslib.drivetrain.DrivetrainControlKind
import com.areslib.drivetrain.DrivetrainNeutralMode

@Composable
fun DriveTypeStep(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    SectionHeading("1 · Choose how the robot moves", "Select your drive kinematics archetype. ARES rebuilds the editable configuration draft accordingly.")
    val cards = listOf(
        Triple(DrivebaseKind.FTC_MECANUM, DrivebaseKind.FTC_MECANUM.displayName(state.league), "Four angled rollers allow omnidirectional forward, strafe, and turning motion."),
        Triple(DrivebaseKind.FRC_CTRE_SWERVE, DrivebaseKind.FRC_CTRE_SWERVE.displayName(state.league), "Four independently steering and driving modules; supports read-only TunerConstants import."),
        Triple(DrivebaseKind.DIFFERENTIAL, DrivebaseKind.DIFFERENTIAL.displayName(state.league), "Left and right wheel groups drive like a tank; no sideways strafing motion."),
        Triple(DrivebaseKind.CUSTOM, DrivebaseKind.CUSTOM.displayName(state.league), "Start with an example motor and gyro, then declare, configure, and classify custom hardware.")
    ).filter { (kind, _, _) -> kind in visibleDrivebaseKinds(state.league, state.advanced, state.draft.kind) }

    if (!state.advanced) {
        Surface(
            color = AresGreen.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(
                "Showing the drivetrain that this ${state.league.name} project can generate, compile, and run without handwritten runtime code. Turn on Advanced to inspect descriptor-only architectures that require a programmer.",
                color = AresTextPrimary,
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }

    cards.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (kind, title, explanation) ->
                val isSelected = state.draft.kind == kind
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) AresCyan else AresBorder,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { viewModel.onIntent(DrivebaseBuilderIntent.SelectKind(kind)) },
                    color = if (isSelected) AresCyan.copy(alpha = 0.10f) else AresSurface,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (kind.runtimeSupport(state.league) == DrivebaseRuntimeSupport.NO_CODE_RUNNABLE) AresGreen.copy(alpha = 0.15f) else AresGold.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    kind.runtimeSupportLabel(state.league),
                                    color = if (kind.runtimeSupport(state.league) == DrivebaseRuntimeSupport.NO_CODE_RUNNABLE) AresGreen else AresGold,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(explanation, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                        Text(
                            if (isSelected) "● SELECTED" else "Choose this drive",
                            color = if (isSelected) AresCyan else AresTextTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    if (state.draft.kind == DrivebaseKind.FRC_CTRE_SWERVE) CtreImportCard(state, viewModel)
}

@Composable
fun HardwareStep(state: DrivebaseBuilderState, viewModel: DrivebaseBuilderViewModel) {
    val hardwareGuidance = when (state.draft.kind) {
        DrivebaseKind.FTC_MECANUM -> if (state.league == League.XRP) {
            "Configure all four XRP motor ports plus the built-in IMU and wheel encoders. Ports 3 and 4 use the expansion connectors."
        } else {
            "Configure all four wheel motors plus Pinpoint, IMU, vision, or other reviewed localization sensors."
        }
        DrivebaseKind.FRC_CTRE_SWERVE -> "Review all four drive/steer/encoder modules plus the gyro imported from the vendor configuration."
        DrivebaseKind.DIFFERENTIAL -> "Describe left/right leaders, optional followers, and a gyro. This remains descriptor-only until a season runtime adapter is implemented."
        DrivebaseKind.CUSTOM -> "Classify every custom drive device and sensor. This remains descriptor-only until a team-owned runtime adapter is implemented."
    }
    SectionHeading("2 · Identify hardware", hardwareGuidance)

    if (state.draft.kind == DrivebaseKind.FRC_CTRE_SWERVE &&
        state.issues.any { it.severity == DrivebaseIssueSeverity.ERROR && it.message.contains("Hardware ID") }
    ) {
        Surface(
            color = AresGold.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, AresGold.copy(alpha = 0.65f)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Need to practice before the robot is wired?", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "ARES can assign unique placeholder CAN IDs for desktop simulation. They are not a physical wiring plan and must be replaced before commissioning.",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                }
                OutlinedButton(
                    onClick = { viewModel.onIntent(DrivebaseBuilderIntent.UseSimulationCanIds) },
                    border = BorderStroke(1.dp, AresGold),
                ) {
                    Text("Use simulation IDs", color = AresGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 440.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Left Column (38%): Interactive 2D Chassis Visualizer
        Surface(
            modifier = Modifier.weight(0.38f),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("TOP-DOWN CHASSIS", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AresCyan.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.3f)),
                    ) {
                        Text(
                            "FRONT ▲",
                            color = AresCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                InteractiveChassisCanvas(
                    state = state,
                    onSelectHardware = { id -> viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(id)) },
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(AresGreen, androidx.compose.foundation.shape.CircleShape))
                        Text("Normal Direction", color = AresTextSecondary, fontSize = 10.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(AresError, androidx.compose.foundation.shape.CircleShape))
                        Text("Inverted Direction", color = AresTextSecondary, fontSize = 10.sp)
                    }
                }
            }
        }

        // Right Column (62%): 2x2 Motor Layout Grid + Auxiliary Hardware & Sensors
        Surface(
            modifier = Modifier.weight(0.62f),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("DRIVE MOTORS (2×2 PHYSICAL LAYOUT)", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.league == com.ares.analytics.shared.models.League.FTC) {
                                "FTC hardware-map names: fl, fr, rl, rr"
                            } else if (state.league == League.XRP) {
                                "XRP motor ports: 1 and 2 built in; 3 and 4 on the expansion connectors"
                            } else {
                                "FRC: assign a unique CAN ID and bus to every drive device"
                            },
                            color = AresTextTertiary,
                            fontSize = 10.sp,
                        )
                    }
                }

                val cornerHardware = state.draft.cornerDriveHardware()
                val fl = cornerHardware.getOrNull(0)
                val fr = cornerHardware.getOrNull(1)
                val rl = cornerHardware.getOrNull(2)
                val rr = cornerHardware.getOrNull(3)

                // Front Row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MotorGridCard(
                        cornerCode = "FL",
                        defaultCornerName = "Front-Left",
                        device = fl,
                        isSelected = fl?.id == state.selectedHardwareId,
                        onSelect = { fl?.id?.let { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(it)) } },
                        onToggleInvert = { fl?.let { dev -> viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(dev.copy(inverted = !dev.inverted))) } },
                        addressLabel = fl?.takeIf { state.league == League.XRP }?.let { "PORT ${it.hardwareName}" },
                        modifier = Modifier.weight(1f),
                    )
                    MotorGridCard(
                        cornerCode = "FR",
                        defaultCornerName = "Front-Right",
                        device = fr,
                        isSelected = fr?.id == state.selectedHardwareId,
                        onSelect = { fr?.id?.let { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(it)) } },
                        onToggleInvert = { fr?.let { dev -> viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(dev.copy(inverted = !dev.inverted))) } },
                        addressLabel = fr?.takeIf { state.league == League.XRP }?.let { "PORT ${it.hardwareName}" },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Rear Row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MotorGridCard(
                        cornerCode = "RL",
                        defaultCornerName = "Rear-Left",
                        device = rl,
                        isSelected = rl?.id == state.selectedHardwareId,
                        onSelect = { rl?.id?.let { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(it)) } },
                        onToggleInvert = { rl?.let { dev -> viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(dev.copy(inverted = !dev.inverted))) } },
                        addressLabel = rl?.takeIf { state.league == League.XRP }?.let { "PORT ${it.hardwareName}" },
                        modifier = Modifier.weight(1f),
                    )
                    MotorGridCard(
                        cornerCode = "RR",
                        defaultCornerName = "Rear-Right",
                        device = rr,
                        isSelected = rr?.id == state.selectedHardwareId,
                        onSelect = { rr?.id?.let { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(it)) } },
                        onToggleInvert = { rr?.let { dev -> viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(dev.copy(inverted = !dev.inverted))) } },
                        addressLabel = rr?.takeIf { state.league == League.XRP }?.let { "PORT ${it.hardwareName}" },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Auxiliary Hardware List & Sensor Creator
                val cornerIds = cornerHardware.mapNotNull { it?.id }.toSet()
                val auxHardware = state.draft.hardware.filterNot { it.id in cornerIds }
                
                HorizontalDivider(color = AresBorder)
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("AUXILIARY SENSORS & CAMERAS (${auxHardware.size})", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (state.league == League.XRP) "Built-in IMU and rangefinder; custom expansion sensors require an explicit adapter"
                            else "Pinpoint odometry, Limelight cameras, IMUs, and distance sensors",
                            color = AresTextTertiary,
                            fontSize = 10.sp,
                        )
                    }
                    if (state.league != League.XRP) {
                        var addMenu by remember { mutableStateOf(false) }
                        Box {
                        Button(
                            onClick = { addMenu = true },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            modifier = Modifier.height(28.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add Sensor / Camera", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        DropdownMenu(addMenu, { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("📷 Limelight / Vision Camera", fontSize = 11.sp) },
                                onClick = {
                                    addMenu = false
                                    viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.LIMELIGHT))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📍 goBILDA Pinpoint / Odometry Pod", fontSize = 11.sp) },
                                onClick = {
                                    addMenu = false
                                    viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.ODOMETRY))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("🧭 Control Hub IMU / Gyroscope", fontSize = 11.sp) },
                                onClick = {
                                    addMenu = false
                                    viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.GYRO))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("📏 Laser Distance Sensor (ToF / REV 2m)", fontSize = 11.sp) },
                                onClick = {
                                    addMenu = false
                                    viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.DISTANCE_SENSOR))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("⚙️ Custom Sensor / Expansion Device", fontSize = 11.sp) },
                                onClick = {
                                    addMenu = false
                                    viewModel.onIntent(DrivebaseBuilderIntent.AddHardware(DriveHardwareRole.OTHER))
                                }
                            )
                        }
                    }
                    }
                }

                if (auxHardware.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = AresSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AresBorder)
                    ) {
                        Text(
                            if (state.league == League.XRP) {
                                "The generated XRP runtime reads the built-in IMU, wheel encoders, and rangefinder without declaring fictitious CAN devices."
                            } else {
                                "No auxiliary sensors configured. Click '+ Add Sensor / Camera' above to add Pinpoint odometry, Limelights, or distance sensors."
                            },
                            color = AresTextTertiary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        auxHardware.forEach { device ->
                            AuxHardwareRow(
                                device = device,
                                isSelected = device.id == state.selectedHardwareId,
                                onSelect = { viewModel.onIntent(DrivebaseBuilderIntent.SelectHardware(device.id)) },
                                onToggleInvert = { viewModel.onIntent(DrivebaseBuilderIntent.UpdateHardware(device.copy(inverted = !device.inverted))) },
                                onRemove = { viewModel.onIntent(DrivebaseBuilderIntent.RemoveHardware(device.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}


