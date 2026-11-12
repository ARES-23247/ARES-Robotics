package com.ares.analytics.ui.components.robotstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState
import java.util.Locale
import kotlin.math.max

@Composable
fun RobotContextInspector(
    selection: RobotStudioSelection,
    state: RobotStudioState,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    expandedWidth: Dp = 270.dp,
) {
    Surface(
        modifier = modifier
            .width(if (isCollapsed) 44.dp else expandedWidth)
            .fillMaxHeight(),
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
    ) {
        if (isCollapsed) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Expand inspector", tint = AresCyan)
                }
            }
        } else {
            ExpandedInspectorPanel(
                selection = selection,
                state = state,
                onToggleCollapse = onToggleCollapse,
            )
        }
    }
}

@Composable
private fun ExpandedInspectorPanel(
    selection: RobotStudioSelection,
    state: RobotStudioState,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header with Collapse Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CONTEXT INSPECTOR",
                color = AresTextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            IconButton(
                onClick = onToggleCollapse,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Collapse inspector",
                    tint = AresTextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        HorizontalDivider(color = AresBorder)

        // Section Overview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val title = when (selection) {
                    is RobotStudioSelection.Identity -> "Project & Identity"
                    is RobotStudioSelection.Drivetrain -> "Drivetrain Kinematics"
                    is RobotStudioSelection.Subsystem -> selection.displayName.ifBlank { "Subsystem Mechanism" }
                    is RobotStudioSelection.Superstructure -> "Superstructure Coordinator"
                    is RobotStudioSelection.Autonomous -> "Routines & Autonomous"
                    is RobotStudioSelection.Controls -> "TeleOp Gamepad Controls"
                    is RobotStudioSelection.PortMap -> "Port Map & Review"
                    is RobotStudioSelection.Verification -> "Verification Evidence"
                }
                val subtitle = when (selection) {
                    is RobotStudioSelection.Identity -> ".ares/project.json"
                    is RobotStudioSelection.Drivetrain -> ".ares/drivetrains/*.aresdrivetrain"
                    is RobotStudioSelection.Subsystem -> ".ares/subsystems/${selection.documentId}.aressubsystem"
                    is RobotStudioSelection.Superstructure -> ".ares/superstructures/*.aressuperstructure"
                    is RobotStudioSelection.Autonomous -> ".ares/routines/*.aresroutine"
                    is RobotStudioSelection.Controls -> ".ares/controls/*.arescontrols"
                    is RobotStudioSelection.PortMap -> ".ares/evidence/hardware/"
                    is RobotStudioSelection.Verification -> ".ares/local/verification/<run-id>/report.json"
                }

                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = AresTextSecondary, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }

        // Live Issues / Validation Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(8.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val validation = state.validationPresentation(selection)
                val validationColor = validation.status.tone.color()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("LIVE VALIDATION", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = validationColor.copy(alpha = 0.12f),
                    ) {
                        Text(
                            validation.status.label,
                            color = validationColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }

                Text(
                    validation.explanation,
                    color = AresTextPrimary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )

                if (validation.issues.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            if (validation.status.tone == RobotStudioPresentationTone.SUCCESS) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = null,
                            tint = validationColor,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            when (validation.status.tone) {
                                RobotStudioPresentationTone.SUCCESS -> "This section passed canonical project validation."
                                RobotStudioPresentationTone.INFO -> "Validation is still in progress."
                                RobotStudioPresentationTone.WARNING -> "Follow the guidance above before verification."
                                RobotStudioPresentationTone.ERROR -> "Validation could not produce detailed issues."
                                RobotStudioPresentationTone.MUTED -> "No validation result is available yet."
                            },
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                } else {
                    for (issue in validation.issues.take(4)) {
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = validationColor, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                            Text(issue, color = AresTextPrimary, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                    }
                }
            }
        }

        // Text-First Kotlin DSL Reference Card
        KotlinDslContextCard(selection)
    }
}

@Composable
private fun KotlinDslContextCard(selection: RobotStudioSelection) {
    val (dslHeader, dslSnippet) = when (selection) {
        is RobotStudioSelection.Identity -> "aresProject(\"robot-id\", AresLeague.FTC)" to
            "aresProject(\"robot-id\", AresLeague.FTC) {\n    identity(\"23247\", \"2026\", \"alpha\", \"Alpha\")\n}"
        is RobotStudioSelection.Drivetrain -> "drivetrain(\"primary-drivebase\")" to
            "drivetrain(\"primary-drivebase\", \"project-id\") {\n    ftcMecanum(wheelRadiusMeters = 0.048)\n}"
        is RobotStudioSelection.Subsystem -> "subsystem(\"${selection.documentId}\")" to
            "subsystem(\"${selection.documentId}\", SubsystemPlatform.FTC) {\n    template(SubsystemTemplate.ARM_PIVOT)\n}"
        is RobotStudioSelection.Superstructure -> "superstructure(\"main-coordinator\")" to
            "superstructure(\"main-coordinator\", \"project-id\") {\n    posture(\"STOW\") { target(\"arm\", \"targetAngle\", 0.0) }\n}"
        is RobotStudioSelection.Autonomous -> "routine(\"auto-routine\")" to
            "routine(\"auto-routine\", \"Auto Routine\") {\n    driveTo(1.2, 0.6, headingDeg = 0.0)\n}"
        is RobotStudioSelection.Controls -> "controlScheme(\"teleop-default\")" to
            "controlScheme(\"teleop-default\", \"TeleOp\") {\n    driver(\"driver-xbox\", port = 0)\n}"
        is RobotStudioSelection.PortMap, is RobotStudioSelection.Verification -> "project.formatValidationReport()" to
            "val project = aresProject(\"robot-id\", AresLeague.FTC) { ... }\ncheck(project.isValid) { project.formatValidationReport() }"
    }
    val copied = androidx.compose.runtime.remember(selection) { androidx.compose.runtime.mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TEXT-FIRST KOTLIN DSL", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                androidx.compose.material3.TextButton(
                    onClick = {
                        try {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                java.awt.datatransfer.StringSelection(dslSnippet),
                                null,
                            )
                            copied.value = true
                        } catch (_: Exception) {
                        }
                    },
                ) {
                    Text(if (copied.value) "Copied!" else "Copy", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(dslHeader, color = AresCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(
                "Open any builder's 'Spec' dialog and select the 'Kotlin DSL' tab to export the live document as Kotlin code.",
                color = AresTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Surface(
                color = AresSurface,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, AresBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    dslSnippet,
                    color = AresTextPrimary,
                    fontSize = 9.sp,
                    lineHeight = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
