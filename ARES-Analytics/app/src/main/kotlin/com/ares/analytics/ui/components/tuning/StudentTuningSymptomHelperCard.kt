@file:OptIn(ExperimentalLayoutApi::class)
package com.ares.analytics.ui.components.tuning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.tuning.canAcceptSimulationResult
import com.ares.analytics.service.tuning.displayValue
import com.ares.analytics.service.tuning.ExperimentDecision
import com.ares.analytics.service.tuning.ExperimentPhase
import com.ares.analytics.service.tuning.ExperimentValue
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.SysIdIntent
import com.ares.analytics.viewmodel.SysIdState
import com.ares.analytics.viewmodel.SysIdViewModel
import com.ares.analytics.viewmodel.tuning.guidedExperimentLabel
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentIntent
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentState
import com.ares.analytics.viewmodel.tuning.GuidedTuningExperimentViewModel
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Plain-English symptom-to-gain diagnostic helper for students learning PID + Feedforward tuning.
 */
enum class StudentTuningSymptom(
    val symptomLabel: String,
    val primaryGain: String,
    val explanation: String,
    val safeStepAdvice: String,
    val experimentStarter: com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter,
) {
    TOO_SLOW(
        symptomLabel = "Too slow / sluggish",
        primaryGain = "Raise kP (or check kV)",
        explanation = "The mechanism approaches the target slowly and takes too long to settle.",
        safeStepAdvice = "For position loops, increase kP by ~20% at a time and re-test. For velocity flywheels, verify kV = 12 V / max Free Speed.",
        experimentStarter = com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter.STOPS_SHORT_SLUGGISH,
    ),
    OVERSHOOTS_OR_BOUNCES(
        symptomLabel = "Overshoots or bounces",
        primaryGain = "Add kD (or lower kP)",
        explanation = "The mechanism flies past the target and oscillates back and forth before stopping.",
        safeStepAdvice = "Add a small derivative gain (kD ≈ 0.05 × kP) to brake as it nears the target, or reduce kP by 15–20%.",
        experimentStarter = com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter.OSCILLATES_AROUND_TARGET,
    ),
    WONT_START_MOVING(
        symptomLabel = "Slow flywheel / launch recovery",
        primaryGain = "Raise kV (velocity FF)",
        explanation = "Shooter wheel or velocity mechanism takes too long to spin back up to target speed.",
        safeStepAdvice = "Increase kV in small bounded steps so the feedforward supplies the steady voltage needed for target velocity.",
        experimentStarter = com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter.SLOW_FLYWHEEL_RECOVERY,
    ),
    SAGS_UNDER_GRAVITY(
        symptomLabel = "Arm / elevator sags",
        primaryGain = "Raise kG (gravity FF)",
        explanation = "An arm or elevator stops short when lifting up and overshoots when going down.",
        safeStepAdvice = "Increase kG until the arm holds horizontal (90°) or the elevator stays stationary with kP = 0, then re-enable kP.",
        experimentStarter = com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter.ARM_ELEVATOR_GRAVITY_DROOP,
    ),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudentTuningSymptomHelperCard(
    onApplySymptomStarter: ((com.ares.analytics.viewmodel.tuning.StudentSymptomExperimentStarter) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var selectedSymptom by remember { mutableStateOf(StudentTuningSymptom.TOO_SLOW) }

    com.ares.analytics.ui.components.core.AresCoachBanner(
        badgeText = "TUNING GUIDE",
        title = "What Should I Tune Next? · 1-Click Symptom-to-Experiment Helper",
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        containerColor = AresSurface,
        trailingHeader = {
            Text(
                "Recommended adjustment: ${selectedSymptom.primaryGain}",
                color = AresGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        },
    ) {
        com.ares.analytics.ui.components.core.AresPresetChipBar(
            items = StudentTuningSymptom.entries,
            labelFor = { it.symptomLabel },
            isSelected = { selectedSymptom == it },
            onSelect = { symptom ->
                selectedSymptom = symptom
                onApplySymptomStarter?.invoke(symptom.experimentStarter)
            },
        )
        Surface(
            color = AresSurfaceElevated,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${selectedSymptom.symptomLabel} → ${selectedSymptom.primaryGain}", color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(selectedSymptom.explanation, color = AresTextPrimary, fontSize = 11.sp)
                    Text("Safe next step: ${selectedSymptom.safeStepAdvice}", color = AresAmber, fontSize = 11.sp)
                }
                if (onApplySymptomStarter != null) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { onApplySymptomStarter(selectedSymptom.experimentStarter) },
                        border = BorderStroke(1.dp, AresCyan),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                    ) {
                        Text("Pre-Fill Experiment", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}


@Composable
internal fun DrivetrainKinematicsAndVisionCalibrationTabContent(
    activeCalTab: Int,
    sysIdState: SysIdState,
    sysIdViewModel: SysIdViewModel,
    motionEnabled: Boolean,
) {
    when (activeCalTab) {
        1 -> {
            if (sysIdState.isRoutineRunning) {
                AbortCard(sysIdViewModel)
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Module Offset Calibration", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                    CalibrationTriggerCard(
                        name = "Start Pinpoint Offset Calibration",
                        desc = "Robot spins in place. Computes pinpoint module offsets from physical center of rotation.",
                        onClick = { sysIdViewModel.onIntent(SysIdIntent.StartCalibration("PINPOINT_SPIN")) },
                        enabled = motionEnabled,
                    )
                    val px = sysIdState.recommendedPinpointXOffsetMm
                    val py = sysIdState.recommendedPinpointYOffsetMm
                    if (px != null && py != null) {
                        ParamRow("Recommended X Offset", String.format("%.2f mm", px))
                        ParamRow("Recommended Y Offset", String.format("%.2f mm", py))
                        Spacer(Modifier.height(4.dp))
                        ApplyButton(onClick = { sysIdViewModel.onIntent(SysIdIntent.ApplyCalibration("PINPOINT_SPIN")) })
                    }

                    HorizontalDivider(color = AresBorder)

                    Text("Ticks/Meter Encoder Calibration", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                    var distText by remember(sysIdState.linearDriveActualDistanceMeters) {
                        mutableStateOf(sysIdState.linearDriveActualDistanceMeters.toString())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("Physical Distance Traveled (meters):", fontSize = 12.sp, color = AresTextSecondary)
                        BasicTextField(
                            value = distText,
                            onValueChange = {
                                distText = it
                                val parsed = it.toDoubleOrNull()
                                if (parsed != null && parsed > 0.0) {
                                    sysIdViewModel.onIntent(SysIdIntent.SetLinearDriveDistance(parsed))
                                }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = AresTextPrimary),
                            cursorBrush = SolidColor(AresCyan),
                            modifier = Modifier
                                .width(60.dp)
                                .height(30.dp)
                                .background(AresSurfaceElevated, RoundedCornerShape(6.dp))
                                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    CalibrationTriggerCard(
                        name = "Start Linear Ticks Calibration",
                        desc = "Robot will drive straight for 3 seconds. Mark start and end points and measure distance to input above.",
                        onClick = { sysIdViewModel.onIntent(SysIdIntent.StartCalibration("LINEAR_DRIVE")) },
                        enabled = motionEnabled,
                    )
                    val ticks = sysIdState.recommendedTicksPerMeter
                    if (ticks != null) {
                        ParamRow("Recommended Ticks/Meter", String.format("%.2f", ticks))
                        Spacer(Modifier.height(4.dp))
                        ApplyButton(onClick = { sysIdViewModel.onIntent(SysIdIntent.ApplyCalibration("LINEAR_DRIVE")) })
                    }
                }
            }
        }
        2 -> {
            if (sysIdState.isRoutineRunning) {
                AbortCard(sysIdViewModel)
            } else {
                CalibrationTriggerCard(
                    name = "Start Track Width Calibration",
                    desc = "Robot will spin in place to calibrate effective track width / moment arm based on IMU vs wheel travel.",
                    onClick = { sysIdViewModel.onIntent(SysIdIntent.StartCalibration("TRACK_WIDTH_SPIN")) },
                    enabled = motionEnabled,
                )
            }
            val tw = sysIdState.recommendedTrackWidthMeters
            if (tw != null) {
                HorizontalDivider(color = AresBorder)
                Text("Calibrated Drivetrain Kinematics:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                ParamRow("Recommended Track Width", String.format("%.4f m", tw))
                Spacer(Modifier.height(4.dp))
                ApplyButton(onClick = { sysIdViewModel.onIntent(SysIdIntent.ApplyCalibration("TRACK_WIDTH_SPIN")) })
            }
        }
        3 -> {
            if (sysIdState.isRoutineRunning) {
                AbortCard(sysIdViewModel)
            } else {
                CalibrationTriggerCard(
                    name = "Start Vision Noise Calibration",
                    desc = "Place the robot stationary facing an AprilTag. Collects standard deviations of Limelight observations.",
                    onClick = { sysIdViewModel.onIntent(SysIdIntent.StartCalibration("VISION_CALIBRATION")) },
                    enabled = motionEnabled,
                )
            }
            val vx = sysIdState.recommendedVisionStdDevsX
            val vy = sysIdState.recommendedVisionStdDevsY
            val vh = sysIdState.recommendedVisionStdDevsHeading
            if (vx != null && vy != null && vh != null) {
                HorizontalDivider(color = AresBorder)
                Text("Calibrated Vision Std Devs:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AresCyan)
                ParamRow("Recommended Std Dev X", String.format("%.4f m", vx))
                ParamRow("Recommended Std Dev Y", String.format("%.4f m", vy))
                ParamRow("Recommended Std Dev Heading", String.format("%.4f rad", vh))
                Spacer(Modifier.height(4.dp))
                ApplyButton(onClick = { sysIdViewModel.onIntent(SysIdIntent.ApplyCalibration("VISION_CALIBRATION")) })
            }
        }
    }
}
