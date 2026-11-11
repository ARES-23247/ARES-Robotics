package com.ares.analytics.ui.screens

import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.components.core.AresDialogVariant
import com.ares.analytics.ui.components.tuning.*

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.TuningIntent
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.service.AlignedDataRow
import com.ares.analytics.service.RecommendationQuality
import com.ares.analytics.service.TuningApplyPhase
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import com.areslib.control.assist.SysIdMechanism
import com.areslib.control.assist.SysIdRoutine
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import com.ares.analytics.viewmodel.CalibrationArmPhase
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent

/**
 * Real-time PID controller gain tuning and SysId system identification test screen.
 *
 * Configures PID gains ($k_P, k_I, k_D$) and feedforward coefficients ($k_S, k_V, k_A$), executing live NT4 parameter updates.
 * Runs SysId quasi-static voltage ramps and dynamic step routines to solve OLS motor model parameters:
 * $$V = k_S \operatorname{sgn}(v) + k_V v + k_A a$$
 *
 * @param viewModel [TuningViewModel] handling live gain updates.
 * @param sysIdViewModel [SysIdViewModel] managing automated SysId voltage routines and OLS matrix regression solvers.
 * @see TuningViewModel
 * @see SysIdViewModel
 * @see com.ares.analytics.service.SysIdService
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningScreen(
    viewModel: TuningViewModel,
    sysIdViewModel: SysIdViewModel,
    experimentViewModel: GuidedTuningExperimentViewModel,
    canLaunchSimulator: Boolean,
    canApplyCandidateToSimulator: Boolean,
    simulatorStatus: String,
    onLaunchSimulator: () -> Unit,
    onApplyCandidateToSimulator: () -> Unit,
    onOpenDashboard: () -> Unit,
    onStopSimulator: () -> Unit,
    onOpenGuidedRunReview: () -> Unit,
    onOpenReplay: (String, Long) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sysIdState by sysIdViewModel.state.collectAsState()
    val experimentState by experimentViewModel.state.collectAsState()
    var activeMode by remember { mutableStateOf(0) }
    var activeCalTab by remember { mutableStateOf(0) }
    var showArmConfirmation by remember { mutableStateOf(false) }
    val motionEnabled = sysIdState.isRobotConnected &&
        sysIdState.capabilitiesKnown &&
        sysIdState.selectedMechanism in sysIdState.supportedMechanisms &&
        (!sysIdState.requiresNetworkArm ||
            (sysIdState.armPhase == CalibrationArmPhase.ARMED && sysIdState.robotCalibrationArmed))

    LaunchedEffect(state.catalog, state.selectedProfileId, experimentState.seed) {
        experimentViewModel.onIntent(GuidedTuningExperimentIntent.RefreshTuningContext)
    }

    if (showArmConfirmation) {
        AresDialog(
            title = "Arm physical calibration?",
            onDismiss = { showArmConfirmation = false },
            variant = AresDialogVariant.WARNING,
            confirmText = "Arm for 60 seconds",
            onConfirm = {
                showArmConfirmation = false
                sysIdViewModel.onIntent(SysIdIntent.ArmCalibration)
            },
            dismissText = "Cancel",
        ) {
            Text(
                "The selected drivetrain or mechanism can move as soon as a calibration is started. " +
                    "Confirm the FTC tuning OpMode is started, the robot is clear, and an operator is ready to press Stop."
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Guided experiment", "Advanced profiles & calibration").forEachIndexed { index, label ->
                if (activeMode == index) {
                    Button(
                        onClick = {},
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) { Text(label) }
                } else {
                    OutlinedButton(onClick = { activeMode = index }) { Text(label) }
                }
            }
        }
        StudentTuningSymptomHelperCard(
            onApplySymptomStarter = { starter ->
                activeMode = 0
                experimentViewModel.onIntent(GuidedTuningExperimentIntent.ApplySymptomStarter(starter))
            },
        )
        if (activeMode == 0) {
            GuidedTuningExperimentPanel(
                viewModel = experimentViewModel,
                state = experimentState,
                tuningState = state,
                canLaunchSimulator = canLaunchSimulator,
                canApplyCandidateToSimulator = canApplyCandidateToSimulator,
                simulatorStatus = simulatorStatus,
                onLaunchSimulator = onLaunchSimulator,
                onApplyCandidateToSimulator = onApplyCandidateToSimulator,
                onOpenDashboard = onOpenDashboard,
                onStopSimulator = onStopSimulator,
                onOpenGuidedRunReview = onOpenGuidedRunReview,
                onOpenReplay = onOpenReplay,
                onOpenAdvancedProfiles = { activeMode = 1 },
            )
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Left Column: Constants Tuning Board
        GainTuningPanel(
            viewModel = viewModel,
            state = state,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )

        // Right Column: Auto-Calibration Board
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(12.dp))
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-Calibration Board", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AresTextPrimary)

                // Connection Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (sysIdState.isRobotConnected) AresGreen else AresError,
                                RoundedCornerShape(4.dp)
                            )
                    )
                    Text(
                        text = if (sysIdState.isRobotConnected) "Robot Connected" else "Robot Disconnected",
                        fontSize = 11.sp,
                        color = AresTextSecondary
                    )
                }
            }
            HorizontalDivider(color = AresBorder)

            if (sysIdState.requiresNetworkArm) {
                Surface(
                    modifier = Modifier.fillMaxWidth().border(
                        1.dp,
                        if (motionEnabled) AresGreen else AresGold,
                        RoundedCornerShape(8.dp)
                    ),
                    color = if (motionEnabled) AresGreen.copy(alpha = .06f) else AresGold.copy(alpha = .06f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                when (sysIdState.armPhase) {
                                    CalibrationArmPhase.DISARMED -> "FTC CALIBRATION DISARMED"
                                    CalibrationArmPhase.ARMING -> "WAITING FOR ROBOT ACKNOWLEDGEMENT"
                                    CalibrationArmPhase.ARMED -> "FTC CALIBRATION ARMED"
                                    CalibrationArmPhase.NOT_REQUIRED -> "ROBOT AUTHORIZATION ACTIVE"
                                },
                                color = if (motionEnabled) AresGreen else AresGold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                            Text(sysIdState.armStatus, color = AresTextSecondary, fontSize = 10.sp)
                            if (!sysIdState.calibrationModeEnabled) {
                                Text("Start ARES Live Tuning TeleOp before arming.", color = AresTextSecondary, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        if (sysIdState.armPhase == CalibrationArmPhase.DISARMED) {
                            Button(
                                onClick = { showArmConfirmation = true },
                                enabled = sysIdState.isRobotConnected && sysIdState.calibrationModeEnabled,
                                colors = ButtonDefaults.buttonColors(containerColor = AresGold, contentColor = AresOnAccent)
                            ) { Text("ARM") }
                        } else {
                            OutlinedButton(onClick = {
                                sysIdViewModel.onIntent(SysIdIntent.DisarmCalibration("Operator disarmed"))
                            }) { Text("DISARM") }
                        }
                    }
                }
            }

            // Tabs Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AresSurfaceElevated, RoundedCornerShape(6.dp))
                    .border(1.dp, AresBorder, RoundedCornerShape(6.dp)),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val calTabs = listOf("SysId", "Pinpoint", "Track Width", "Vision")
                calTabs.forEachIndexed { index, title ->
                    val selected = activeCalTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (selected) AresCyan else Color.Transparent, RoundedCornerShape(6.dp))
                            .clickable { activeCalTab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (selected) AresOnAccent else AresTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Tab Content Window
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when (activeCalTab) {
                    0 -> { // SysId Drivetrain Tab
                        val simulation = sysIdState.simulationEvaluation
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("1 · Learn with a simulated mechanism", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                            Text(
                                "ARES runs a known teaching plant locally so you can see what kS, kV, kA, fit quality, and a conservative PID starting point mean. These values are not measurements of your robot and cannot be promoted.",
                                fontSize = 10.sp,
                                color = AresTextSecondary,
                            )
                            OutlinedButton(
                                onClick = { sysIdViewModel.onIntent(SysIdIntent.RunSimulationPreview) },
                                enabled = !sysIdState.isRoutineRunning && !sysIdState.isLoading && !sysIdState.isSimulationRunning,
                            ) { Text(if (sysIdState.isSimulationRunning) "Running lesson…" else "Run hardware-free SysId lesson") }
                            Text(
                                sysIdState.simulationMessage,
                                color = if (simulation?.recoveredWithinTolerance == true && simulation.closedLoop?.stable == true) AresGreen else AresAmber,
                                fontSize = 10.sp,
                            )
                            simulation?.recommendation?.let { preview ->
                                val f = com.ares.analytics.ui.util.AresFormatters::formatDecimal
                                ParamRow("Teaching feedforward kS / kV / kA", "${f(preview.recommendedkS, 3, "N/A")} / ${f(preview.recommendedkV, 3, "N/A")} / ${f(preview.recommendedkA, 3, "N/A")}")
                                ParamRow("Teaching feedback kP / kI / kD", "${f(preview.recommendedGains.kP, 3, "N/A")} / ${f(preview.recommendedGains.kI, 3, "N/A")} / ${f(preview.recommendedGains.kD, 3, "N/A")}")
                                ParamRow("Known-plant fit", "${f(preview.rSquared * 100.0, 1, "N/A")}% R²")
                            }
                        }
                        HorizontalDivider(color = AresBorder)
                        Text("2 · Measure the real mechanism", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                        Text(
                            when {
                                !sysIdState.isRobotConnected -> "Connect a robot or simulator to discover its live SysId capabilities."
                                !sysIdState.capabilitiesKnown -> "Waiting for the connected runtime to advertise supported live SysId mechanisms. Motion remains disabled."
                                sysIdState.supportedMechanisms.isEmpty() -> "This runtime does not implement live SysId. Use the hardware-free lesson or install a supported runtime."
                                else -> "Live runtime supports: ${sysIdState.supportedMechanisms.joinToString { it.name.lowercase() }}. Unsupported targets remain visible for learning but cannot move hardware."
                            },
                            fontSize = 10.sp,
                            color = AresTextSecondary,
                        )
                        if (sysIdState.isRoutineRunning) {
                            AbortCard(sysIdViewModel)
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("SysId Routine Target:", fontSize = 12.sp, color = AresTextSecondary)
                                Row(
                                    modifier = Modifier
                                        .background(AresSurfaceElevated, RoundedCornerShape(6.dp))
                                        .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                                ) {
                                    SysIdMechanism.values().forEach { mech ->
                                        val selected = sysIdState.selectedMechanism == mech
                                        val liveSupported = sysIdState.capabilitiesKnown && mech in sysIdState.supportedMechanisms
                                        Box(
                                            modifier = Modifier
                                                .background(if (selected) AresCyan else Color.Transparent, RoundedCornerShape(6.dp))
                                                .clickable { sysIdViewModel.onIntent(SysIdIntent.SetMechanism(mech)) }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = if (liveSupported) mech.name else "${mech.name} · LESSON ONLY",
                                                color = if (selected) AresOnAccent else if (liveSupported) AresTextPrimary else AresTextSecondary,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Select SysId Test to Run:", fontSize = 11.sp, color = AresTextSecondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RoutineButton(
                                        name = "Quasistatic (Combined)",
                                        desc = "Voltage ramps forward then reverse",
                                        onClick = { sysIdViewModel.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.QUASISTATIC)) },
                                        enabled = motionEnabled
                                    )
                                    RoutineButton(
                                        name = "Dynamic (Combined)",
                                        desc = "Voltage steps forward then reverse",
                                        onClick = { sysIdViewModel.onIntent(SysIdIntent.StartRoutine(SysIdRoutine.DYNAMIC)) },
                                        enabled = motionEnabled
                                    )
                                }
                            }
                        }
                        val summary = sysIdState.summary
                        if (summary != null) {
                            val f = com.ares.analytics.ui.util.AresFormatters::formatDecimal
                            HorizontalDivider(color = AresBorder)
                            Text("SysId Calculation Results:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                            ParamRow("Static Friction (kS)", "${f(summary.kS, 4, "N/A")} V")
                            ParamRow("Velocity Constant (kV)", "${f(summary.kV, 4, "N/A")} V/(m/s)")
                            ParamRow("Acceleration Constant (kA)", "${f(summary.kA, 4, "N/A")} V/(m/s²)")
                            ParamRow("OLS Fit Quality (R²)", "${f(summary.rSquared * 100, 2, "N/A")}%")
                        }
                        val recommendation = sysIdState.tuningRecommendation
                        if (recommendation != null) {
                            val f = com.ares.analytics.ui.util.AresFormatters::formatDecimal
                            HorizontalDivider(color = AresBorder)
                            Text("Measured-Plant Recommendation", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                            ParamRow("Safety status", recommendation.quality.name.replace('_', ' '))
                            ParamRow("Confidence", "${f(recommendation.confidence * 100.0, 1, "N/A")}%")
                            ParamRow("Data quality", "${f(recommendation.dataQuality.score * 100.0, 1, "N/A")}%")
                            ParamRow(
                                "Capture coverage",
                                "${recommendation.dataQuality.sampleCount} samples / ${f(recommendation.dataQuality.durationMs / 1000.0, 2, "N/A")}s"
                            )
                            ParamRow(
                                "Telemetry cadence",
                                "${f(recommendation.dataQuality.medianPeriodMs, 1, "N/A")}ms median / ${recommendation.dataQuality.maximumGapMs}ms max gap"
                            )
                            ParamRow(
                                "Feedback kP / kI / kD",
                                "${f(recommendation.recommendedGains.kP, 4, "N/A")} / ${f(recommendation.recommendedGains.kI, 4, "N/A")} / ${f(recommendation.recommendedGains.kD, 4, "N/A")}"
                            )
                            recommendation.warnings.forEach { warning ->
                                Text(warning, color = AresAmber, fontSize = 10.sp)
                            }
                            when (sysIdState.tuningApplyState.phase) {
                                TuningApplyPhase.APPLIED_AWAITING_VALIDATION -> {
                                    Text("Applied. Run the same routine again to validate; unstable results roll back automatically.", color = AresAmber, fontSize = 10.sp)
                                    OutlinedButton(onClick = { sysIdViewModel.onIntent(SysIdIntent.RollbackRecommendation) }) {
                                        Text("Rollback Now")
                                    }
                                }
                                TuningApplyPhase.VALIDATED -> Text(sysIdState.tuningApplyState.message, color = AresGreen, fontSize = 10.sp)
                                TuningApplyPhase.ROLLED_BACK, TuningApplyPhase.FAILED ->
                                    Text(sysIdState.tuningApplyState.message, color = AresError, fontSize = 10.sp)
                                else -> Button(
                                    enabled = recommendation.quality != RecommendationQuality.REJECTED,
                                    onClick = { sysIdViewModel.onIntent(SysIdIntent.ApproveRecommendation(recommendation)) }
                                ) {
                                    Text(if (recommendation.quality == RecommendationQuality.REVIEW_REQUIRED) "Review on proposal board" else "Send to proposal board")
                                }
                            }
                        }
                    }
                    1, 2, 3 -> {
                        DrivetrainKinematicsAndVisionCalibrationTabContent(
                            activeCalTab = activeCalTab,
                            sysIdState = sysIdState,
                            sysIdViewModel = sysIdViewModel,
                            motionEnabled = motionEnabled,
                        )
                    }
                }

                // Error Display
                sysIdState.errorMessage?.let { err ->
                    Text(err, color = AresError, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Export/Apply Status
                if (sysIdState.exportStatus.isNotEmpty()) {
                    Text(
                        text = sysIdState.exportStatus,
                        color = if (sysIdState.exportStatus.contains("Failed")) AresError else AresGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    LaunchedEffect(sysIdState.exportStatus) {
                        kotlinx.coroutines.delay(3000)
                        sysIdViewModel.onIntent(SysIdIntent.ClearExportStatus)
                    }
                }

                Spacer(Modifier.weight(1f))
                Text("Live Telemetry Plot (Velocity vs. Time)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AresTextSecondary)
                TuningLivePlot(samples = sysIdState.liveSamples)
            }
        }
        }
    }
}
