package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.hardware.HardwareInventoryItem
import com.ares.analytics.service.hardware.HardwareInventoryOwner
import com.ares.analytics.service.hardware.HardwareIssueSeverity
import com.ares.analytics.service.hardware.HardwareReviewStatus
import com.ares.analytics.service.hardware.HardwareSetupSnapshot
import com.ares.analytics.service.hardware.commissioningPlan
import com.ares.analytics.service.commissioning.CommissioningSimulationStatus
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.hardware.HardwareSetupState
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel

/** Descriptor-backed review of every physical address before a project can become deployable. */
@Composable
fun HardwareSetupScreen(
    viewModel: HardwareSetupViewModel,
    onOpenDrivebase: () -> Unit,
    onOpenSubsystems: () -> Unit,
    onOpenPitDiagnostics: (() -> Unit)? = null,
    onBackToStudio: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HardwareSetupHeader(state, viewModel::refresh, onBackToStudio)
        }
        state.error?.let { error -> item { MessageCard("Hardware Setup error", error, HardwareIssueSeverity.ERROR) } }
        if (state.loading) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = AresCyan)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading canonical hardware descriptors…", color = AresTextSecondary)
                }
            }
        } else {
            state.snapshot?.let { snapshot ->
                item { ReviewStatusCard(snapshot) }
                item { CommissioningEvidenceLadder(snapshot) }
                snapshot.issues.forEach { issue ->
                    item { MessageCard(if (issue.itemUid == null) "Project check" else "Device check", issue.message, issue.severity) }
                }
                item {
                    SourceActions(onOpenDrivebase, onOpenSubsystems)
                }
                item {
                    CommissioningGuide(snapshot, onOpenPitDiagnostics)
                }
                HardwareInventoryOwner.entries.forEach { owner ->
                    val owned = snapshot.items.filter { it.owner == owner }
                    if (owned.isNotEmpty()) {
                        item {
                            Text(
                                if (owner == HardwareInventoryOwner.DRIVEBASE) "Drivebase hardware" else "Subsystem hardware",
                                color = AresTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        items(owned, key = HardwareInventoryItem::uid) { item -> HardwareItemCard(item) }
                    }
                }
                item {
                    ReviewChecklist(state, viewModel, onBackToStudio)
                }
                item {
                    PhysicalValidationSection(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CommissioningEvidenceLadder(snapshot: HardwareSetupSnapshot) {
    val simulationVerified = snapshot.simulationVerification.status == CommissioningSimulationStatus.VERIFIED
    val configurationReviewed = snapshot.reviewStatus == HardwareReviewStatus.CURRENT
    val ready = simulationVerified && configurationReviewed && snapshot.errorIssues.isEmpty()
    val physicallyValidated = snapshot.physicalValidation != null
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Commissioning evidence", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "Each level has a different meaning. ARES never turns simulation or a document review into a claim that hardware was tested.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            EvidenceRow(
                "Simulation verified",
                simulationVerified,
                when (snapshot.simulationVerification.status) {
                    CommissioningSimulationStatus.VERIFIED ->
                        "${snapshot.simulationVerification.controllerCount} controller(s), ${snapshot.simulationVerification.scenarioCount} deterministic nominal/fault scenarios passed for the current descriptors."
                    CommissioningSimulationStatus.NOT_AVAILABLE -> "No generated subsystem controller exists to exercise; no simulation claim was made."
                    CommissioningSimulationStatus.NEEDS_REVIEW ->
                        "${snapshot.simulationVerification.failures.size} scenario(s) need review before this level can pass."
                },
            )
            EvidenceRow(
                "Configuration reviewed",
                configurationReviewed,
                if (configurationReviewed) "${snapshot.reviewedBy.orEmpty()} reviewed inventory ${snapshot.inventoryHash.take(12)}…." else "Complete the named-person mapping checklist for this exact inventory hash.",
            )
            EvidenceRow(
                "Ready for physical validation",
                ready,
                if (ready) "Simulation safety checks and the configuration review are current. Use the supervised checklist below." else "Requires both current simulation evidence and a current configuration review.",
            )
            EvidenceRow(
                "Physically validated",
                physicallyValidated,
                snapshot.physicalValidation?.let { evidence ->
                    "${evidence.validatedBy} recorded supervised evidence for inventory ${evidence.inventoryHash.take(12)}…."
                } ?: "Not recorded. This requires a supervised real-robot procedure and explicit human evidence; it is never inferred.",
            )
            snapshot.simulationVerification.performanceWarnings.take(3).forEach { warning ->
                Text("Performance note: $warning", color = AresAmber, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun PhysicalValidationSection(state: HardwareSetupState, viewModel: HardwareSetupViewModel) {
    val snapshot = state.snapshot ?: return
    val ready = snapshot.reviewStatus == HardwareReviewStatus.CURRENT && snapshot.simulationVerification.verified
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, if (snapshot.physicalValidation != null) AresGreen else AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Optional supervised physical validation", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Do this only with a real robot, your team's documented safety procedure, a clear test area, and an operator ready to stop. Studio records your evidence; it does not perform or infer the test.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            snapshot.physicalValidation?.let { evidence ->
                Surface(color = AresGreen.copy(alpha = .08f), border = BorderStroke(1.dp, AresGreen), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text("PHYSICALLY VALIDATED · ${evidence.validatedBy}", color = AresGreen, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Text(evidence.evidenceSummary, color = AresTextPrimary, fontSize = 11.sp)
                        Text("Bound to ${evidence.inventoryHash.take(16)}…", color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    }
                }
            }
            if (!ready) {
                Text("Complete current simulation and configuration-review evidence before this form unlocks.", color = AresAmber, fontSize = 11.sp)
            }
            ReviewCheck("Motor direction, follower behavior, and encoder polarity were observed on the real robot.", state.directionsAndPolarityTested, viewModel::setDirectionsAndPolarityTested)
            ReviewCheck("Sensor units, coordinate signs, freshness, and stationary-versus-frozen behavior were checked.", state.unitsAndSensorsTested, viewModel::setUnitsAndSensorsTested)
            ReviewCheck("Disable/Stop produced safe neutral output and startup remained neutral.", state.disabledNeutralTested, viewModel::setDisabledNeutralTested)
            ReviewCheck("Soft limits, current limits, homing/calibration, and mechanical clearance were tested.", state.limitsAndCurrentTested, viewModel::setLimitsAndCurrentTested)
            ReviewCheck("A supervised fault and explicit neutral-recovery procedure completed successfully.", state.faultRecoveryTested, viewModel::setFaultRecoveryTested)
            OutlinedTextField(
                value = state.physicalValidatorName,
                onValueChange = viewModel::setPhysicalValidatorName,
                enabled = ready,
                label = { Text("Validated by") },
                supportingText = { Text("Team member who directly observed the physical checks.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.physicalEvidenceSummary,
                onValueChange = viewModel::setPhysicalEvidenceSummary,
                enabled = ready,
                label = { Text("Observed evidence and remaining limitations") },
                supportingText = { Text("Name the robot, procedure, result, and anything not tested. Minimum 20 characters.") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::savePhysicalValidation,
                enabled = state.canSavePhysicalValidation,
                colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
            ) {
                Text(if (snapshot.physicalValidation == null) "Record physical validation evidence" else "Replace with new physical evidence")
            }
        }
    }
}

@Composable
private fun EvidenceRow(label: String, passed: Boolean, detail: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (passed) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (passed) "$label passed" else "$label not established",
            tint = if (passed) AresGreen else AresAmber,
            modifier = Modifier.size(17.dp),
        )
        Column {
            Text("$label · ${if (passed) "ESTABLISHED" else "NOT ESTABLISHED"}", color = AresTextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = AresTextSecondary, fontSize = 10.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun CommissioningGuide(
    snapshot: HardwareSetupSnapshot,
    onOpenPitDiagnostics: (() -> Unit)?,
) {
    val plan = remember(snapshot.inventoryHash) { snapshot.commissioningPlan() }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Configure and identify the real robot", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "These names come from the canonical robot description. Enter them exactly in Driver Station → Configure Robot → Hardware; capitalization matters.",
                color = AresTextSecondary,
                lineHeight = 19.sp,
            )
            if (plan.hardwareMapEntries.isNotEmpty()) {
                Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, AresBorder)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        plan.hardwareMapEntries.forEach { item ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.displayName, color = AresTextPrimary, modifier = Modifier.weight(1f), fontSize = 12.sp)
                                Text(item.address.ifBlank { "NOT CONFIGURED" }, color = if (item.address.isBlank()) AresRed else AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(plan.clipboardText)) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy exact hardware names")
                }
            }

            HorizontalDivider(color = AresBorder)
            Text("Safely check motor position and direction", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                "ARES Robotics Studio never pulses a physical motor from this screen. On the Driver Station, select the TeleOp named ARES Drivetrain Diagnostic. It is hold-to-run and uses the generated names and directions below.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            Surface(color = AresAmber.copy(alpha = 0.10f), border = BorderStroke(1.dp, AresAmber), shape = RoundedCornerShape(9.dp)) {
                Text(
                    "Before Play: put the robot on secure blocks so every wheel is clear of the floor, remove game pieces, keep hands and clothing away, and have one person ready to press Stop. Release the button immediately if the wrong wheel or direction moves.",
                    color = AresTextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
            if (plan.ftcDiagnosticAvailable) {
                plan.ftcMotorChecks.forEach { check ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(check.gamepadControl, color = AresCyan, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.width(100.dp))
                        Text("${check.displayName}  [${check.hardwareMapName}]  · ${check.configuredDirection}", color = AresTextPrimary, fontSize = 12.sp)
                    }
                }
            } else {
                Text(plan.ftcDiagnosticBlockReason.orEmpty(), color = AresAmber, fontSize = 12.sp, lineHeight = 18.sp)
            }
            Text(
                "The diagnostic proves only motor identity and configured direction. It does not validate odometry, closed-loop tuning, current limits, mechanisms, or match readiness.",
                color = AresTextTertiary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            if (plan.subsystemChecks.isNotEmpty()) {
                HorizontalDivider(color = AresBorder)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Default.Science, contentDescription = null, tint = AresCyan, modifier = Modifier.size(18.dp))
                    Text("Commission subsystem devices", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Start with read-only signals in the simulator or live self-test. Motion proposals below are intentionally unarmed: this app does not send them to a robot.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
                plan.subsystemChecks.forEach { check ->
                    Surface(color = AresSurfaceElevated, shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, AresBorder)) {
                        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${check.subsystemName} · ${check.deviceName}", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(check.hardwareAddress, color = AresCyan, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                            }
                            if (check.readOnlySignals.isNotEmpty()) {
                                Text("Observe first: ${check.readOnlySignals.joinToString()}", color = AresTextSecondary, fontSize = 11.sp)
                            }
                            if (check.controlStrategies.isNotEmpty()) {
                                Text("Control: ${check.controlStrategies.joinToString { it.lowercase().replace('_', ' ') }}", color = AresTextSecondary, fontSize = 11.sp)
                            }
                            check.homingMethod?.let { method ->
                                Text(
                                    "Homing: ${method.lowercase().replace('_', ' ')}${check.homingEvidence.takeIf { it.isNotEmpty() }?.joinToString(prefix = " · evidence: ").orEmpty()}",
                                    color = AresTextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            if (check.requiresCalibration) Text("Calibration must be established before motion.", color = AresAmber, fontSize = 11.sp)
                            if (check.requiresCurrentMonitoring) Text("A fresh, valid current reading is required.", color = AresAmber, fontSize = 11.sp)
                            when {
                                check.followerOnly -> Text("Follower device: verify it with its leader; do not command it independently.", color = AresAmber, fontSize = 11.sp)
                                check.pulseProposal != null -> Text(
                                    "UNARMED PULSE PROPOSAL · safety checklist required · ≤${check.pulseProposal.maximumDurationMs} ms · ≤${(check.pulseProposal.maximumTravelFromNeutralFraction * 100).toInt()}% from neutral ${check.pulseProposal.safeNeutralOutput}",
                                    color = AresAmber,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                Text(
                    "After identity, direction, sensors, and homing pass, transfer only reviewed gains from the simulation/tuning tools. Re-run neutral recovery before enabling normal commands.",
                    color = AresTextTertiary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (onOpenPitDiagnostics != null) {
                OutlinedButton(onClick = onOpenPitDiagnostics) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open live read-only self-test")
                }
            }
        }
    }
}

@Composable
private fun HardwareSetupHeader(
    state: HardwareSetupState,
    onRefresh: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Robot Studio")
                    }
                }
                Text("Hardware Setup", color = AresTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh, enabled = !state.loading && !state.saving) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Refresh")
                }
            }
            Text(
                "Compare canonical drivetrain and subsystem addresses with the actual robot. This screen reads those documents; edit them in their owning builders.",
                color = AresTextSecondary,
                lineHeight = 20.sp,
            )
            Surface(color = AresAmber.copy(alpha = 0.10f), border = BorderStroke(1.dp, AresAmber), shape = RoundedCornerShape(8.dp)) {
                Text(
                    "Recording a review is not a hardware test. It proves only that a named person compared wiring, addresses, directions, safe outputs, and limits with the current descriptor hashes.",
                    color = AresTextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun ReviewStatusCard(snapshot: HardwareSetupSnapshot) {
    val (label, explanation, tint) = when (snapshot.reviewStatus) {
        HardwareReviewStatus.CURRENT -> Triple(
            "Current reviewed mapping",
            "${snapshot.reviewedBy.orEmpty()} reviewed this exact ${snapshot.items.size}-device inventory.",
            AresGreen,
        )
        HardwareReviewStatus.STALE -> Triple(
            "Review is stale",
            "A drivetrain or subsystem descriptor changed after the last review. Compare the updated mapping again.",
            AresAmber,
        )
        HardwareReviewStatus.INVALID -> Triple(
            "Review record is invalid",
            "Repair the reported issue, then record a new review.",
            AresRed,
        )
        HardwareReviewStatus.NOT_REVIEWED -> Triple(
            "Not physically reviewed",
            "Inspect every required device and complete the checklist below.",
            AresAmber,
        )
    }
    Surface(color = tint.copy(alpha = 0.09f), border = BorderStroke(1.dp, tint), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (snapshot.reviewStatus == HardwareReviewStatus.CURRENT) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = tint,
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Text(explanation, color = AresTextSecondary, fontSize = 12.sp)
                Text("Inventory hash ${snapshot.inventoryHash.take(16)}…", color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SourceActions(onOpenDrivebase: () -> Unit, onOpenSubsystems: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Change a name, address, direction, or safety setting", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            Text("Switch directly to the Drivetrain or Subsystems tab to edit hardware definitions.", color = AresTextSecondary, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenDrivebase) { Text("← Edit Drivetrain") }
                OutlinedButton(onClick = onOpenSubsystems) { Text("← Edit Subsystems") }
            }
        }
    }
}

@Composable
private fun HardwareItemCard(item: HardwareInventoryItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.displayName, color = AresTextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (item.required) "REQUIRED" else "OPTIONAL", color = if (item.required) AresAmber else AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("${item.ownerDisplayName} · ${item.role}", color = AresTextSecondary, fontSize = 12.sp)
            Text(item.addressDescription, color = AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            item.configurationDetails.forEach { detail ->
                Text("• $detail", color = AresTextSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Text(
                when (item.addressKind) {
                    com.ares.analytics.service.hardware.HardwareAddressKind.FTC_HARDWARE_MAP ->
                        if (item.address.isBlank()) "Add this device in Configure Robot → Hardware on the Driver Station, then enter the exact same name here."
                        else "In Configure Robot → Hardware on the Driver Station, name this device exactly: ${item.address}"
                    com.ares.analytics.service.hardware.HardwareAddressKind.XRP_PORT ->
                        "Connect this device to ${item.addressDescription}; the built-in drivetrain uses motor ports 1 and 2."
                    com.ares.analytics.service.hardware.HardwareAddressKind.CAN ->
                        "Set this device to ${item.addressDescription}; CAN IDs must be unique on each bus."
                    com.ares.analytics.service.hardware.HardwareAddressKind.PWM -> "Connect this device to ${item.addressDescription}."
                    com.ares.analytics.service.hardware.HardwareAddressKind.I2C -> "Confirm the configured I²C device and address match ${item.addressDescription}."
                    com.ares.analytics.service.hardware.HardwareAddressKind.SPI -> "Confirm the onboard SPI device is present and its orientation matches the robot descriptor."
                    com.ares.analytics.service.hardware.HardwareAddressKind.PNEUMATICS -> "Match both the pneumatics module CAN ID/type and solenoid channel shown in ${item.addressDescription}."
                    com.ares.analytics.service.hardware.HardwareAddressKind.DIO,
                    com.ares.analytics.service.hardware.HardwareAddressKind.ANALOG,
                    com.ares.analytics.service.hardware.HardwareAddressKind.UNKNOWN -> "Match the controller configuration to ${item.addressDescription}."
                },
                color = AresCyan,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Text(
                if (item.inverted) "Direction: reversed at the hardware boundary" else "Direction: normal at the hardware boundary",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            Text(item.sourcePath, color = AresTextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ReviewChecklist(
    state: HardwareSetupState,
    viewModel: HardwareSetupViewModel,
    onBackToStudio: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Record a physical mapping review", color = AresTextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Complete this beside the disabled robot with power removed where your team procedure requires it.", color = AresTextSecondary)
            HorizontalDivider(color = AresBorder)
            ReviewCheck("Every listed device exists on this robot and the wiring diagram matches.", state.wiringMatched, viewModel::setWiringMatched)
            ReviewCheck("Hardware-map names, CAN IDs/buses, and channels match the controller configuration.", state.addressesChecked, viewModel::setAddressesChecked)
            ReviewCheck("Motor/servo directions and follower relationships were checked mechanically.", state.directionsChecked, viewModel::setDirectionsChecked)
            ReviewCheck("Every actuator has a safe neutral and disabled/stop behavior was reviewed.", state.neutralOutputsChecked, viewModel::setNeutralOutputsChecked)
            ReviewCheck("Current, soft, motion, homing, and feedback limits were reviewed where applicable.", state.limitsChecked, viewModel::setLimitsChecked)
            OutlinedTextField(
                value = state.reviewerName,
                onValueChange = viewModel::setReviewerName,
                label = { Text("Reviewed by") },
                supportingText = { Text("Team member name; this does not create a cloud role or claim a hardware test.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = viewModel::saveReview,
                    enabled = state.canSaveReview,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = AresOnAccent)
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(if (state.saving) "Recording review…" else "Record reviewed mapping", fontWeight = FontWeight.Bold)
                }
                if (state.snapshot?.reviewStatus == HardwareReviewStatus.CURRENT && onBackToStudio != null) {
                    Button(
                        onClick = onBackToStudio,
                        colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                    ) {
                        Text("Complete Hardware & Return to Studio →")
                    }
                }
            }
        }
    }
}
@Composable
private fun ReviewCheck(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = AresTextPrimary, modifier = Modifier.padding(top = 11.dp), lineHeight = 19.sp)
    }
}

@Composable
private fun MessageCard(title: String, message: String, severity: HardwareIssueSeverity) {
    val tint = when (severity) {
        HardwareIssueSeverity.INFO -> AresCyan
        HardwareIssueSeverity.WARNING -> AresAmber
        HardwareIssueSeverity.ERROR -> AresRed
    }
    AresCard(backgroundColor = tint.copy(alpha = 0.08f), borderColor = tint, cornerRadius = 10.dp, contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(if (severity == HardwareIssueSeverity.ERROR) Icons.Default.Error else Icons.Default.Warning, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(message, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
