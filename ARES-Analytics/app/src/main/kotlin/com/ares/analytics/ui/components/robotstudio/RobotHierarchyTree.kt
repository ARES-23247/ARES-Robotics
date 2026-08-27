package com.ares.analytics.ui.components.robotstudio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageId
import com.ares.analytics.viewmodel.robotstudio.RobotStudioStageStatus
import com.ares.analytics.viewmodel.robotstudio.RobotStudioState

sealed class RobotStudioSelection {
    data object Identity : RobotStudioSelection()
    data object Drivetrain : RobotStudioSelection()
    data class Subsystem(val documentId: String, val displayName: String = "") : RobotStudioSelection()
    data object Superstructure : RobotStudioSelection()
    data object Autonomous : RobotStudioSelection()
    data object Controls : RobotStudioSelection()
    data object PortMap : RobotStudioSelection()
    data object Verification : RobotStudioSelection()
}

data class SubsystemTreeItem(
    val documentId: String,
    val displayName: String,
    val isDraft: Boolean = false,
    val status: RobotStudioStageStatus = RobotStudioStageStatus.READY,
)

@Composable
fun RobotHierarchyTree(
    state: RobotStudioState,
    subsystems: List<SubsystemTreeItem>,
    selected: RobotStudioSelection,
    onSelect: (RobotStudioSelection) -> Unit,
    onAddSubsystem: () -> Unit,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(if (isCollapsed) 56.dp else 220.dp)
            .fillMaxHeight(),
        color = AresSurface,
        border = BorderStroke(1.dp, AresBorder),
    ) {
        if (isCollapsed) {
            CollapsedTreeRail(
                selected = selected,
                onSelect = onSelect,
                onToggleCollapse = onToggleCollapse,
            )
        } else {
            ExpandedTreePanel(
                state = state,
                subsystems = subsystems,
                selected = selected,
                onSelect = onSelect,
                onAddSubsystem = onAddSubsystem,
                onToggleCollapse = onToggleCollapse,
            )
        }
    }
}

@Composable
private fun ExpandedTreePanel(
    state: RobotStudioState,
    subsystems: List<SubsystemTreeItem>,
    selected: RobotStudioSelection,
    onSelect: (RobotStudioSelection) -> Unit,
    onAddSubsystem: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header with Collapse Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "ROBOT STRUCTURE",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    )
                    val progress = state.progressPresentation()
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = progress.tone.color().copy(alpha = 0.12f),
                    ) {
                        Text(
                            progress.label,
                            color = progress.tone.color(),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                }
                IconButton(
                    onClick = onToggleCollapse,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Collapse tree",
                        tint = AresTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            HorizontalDivider(color = AresBorder, modifier = Modifier.padding(bottom = 4.dp))

            // 1. Identity Node
            val identityStage = state.stages.firstOrNull { it.id == RobotStudioStageId.PROJECT_IDENTITY }
            TreeNodeRow(
                icon = Icons.Default.Badge,
                label = "Project Identity",
                subtitle = state.projectName.takeIf { it.isNotBlank() } ?: "FTC/FRC Setup",
                status = identityStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Identity,
                onClick = { onSelect(RobotStudioSelection.Identity) },
            )

            // 2. Drivetrain Node
            val hardwareStage = state.stages.firstOrNull { it.id == RobotStudioStageId.HARDWARE }
            TreeNodeRow(
                icon = Icons.Default.Settings,
                label = "Drivetrain",
                subtitle = "Kinematics & Odom",
                status = state.hardwareReadiness?.drivetrain?.status ?: hardwareStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Drivetrain,
                onClick = { onSelect(RobotStudioSelection.Drivetrain) },
            )

            Spacer(Modifier.height(4.dp))

            // 3. Mechanisms Section Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "MECHANISMS (${subsystems.size})",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = AresCyan.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.3f)),
                    modifier = Modifier.clickable(onClick = onAddSubsystem),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add subsystem", tint = AresCyan, modifier = Modifier.size(12.dp))
                        Text("Add", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Subsystem Children
            if (subsystems.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .clickable(onClick = onAddSubsystem),
                    color = AresBackground,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, AresBorder),
                ) {
                    Text(
                        "+ Add first mechanism",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            } else {
                subsystems.forEach { sub ->
                    val isSubSelected = selected is RobotStudioSelection.Subsystem && selected.documentId == sub.documentId
                    TreeNodeRow(
                        icon = Icons.Default.Construction,
                        label = sub.displayName,
                        subtitle = if (sub.isDraft) "Draft edits" else "Subsystem DSL",
                        status = sub.status,
                        state = state,
                        isSelected = isSubSelected,
                        indent = 12.dp,
                        onClick = { onSelect(RobotStudioSelection.Subsystem(sub.documentId, sub.displayName)) },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // 4. Superstructure Node
            val coordStage = state.stages.firstOrNull { it.id == RobotStudioStageId.COORDINATION }
            TreeNodeRow(
                icon = Icons.Default.Layers,
                label = "Superstructure",
                subtitle = "Presets & Interlocks",
                status = coordStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Superstructure,
                onClick = { onSelect(RobotStudioSelection.Superstructure) },
            )

            // 5. Routines & Autonomous Node
            val autoStage = state.stages.firstOrNull { it.id == RobotStudioStageId.AUTONOMOUS }
            TreeNodeRow(
                icon = Icons.Default.Route,
                label = "Routines & Auto",
                subtitle = "Sequences & Match Auto",
                status = autoStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Autonomous,
                onClick = { onSelect(RobotStudioSelection.Autonomous) },
            )

            // 6. Controls Node
            val controlsStage = state.stages.firstOrNull { it.id == RobotStudioStageId.CONTROLS }
            TreeNodeRow(
                icon = Icons.Default.SportsEsports,
                label = "TeleOp Controls",
                subtitle = "Gamepad Bindings",
                status = controlsStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Controls,
                onClick = { onSelect(RobotStudioSelection.Controls) },
            )

            // 7. Port Map Node
            TreeNodeRow(
                icon = Icons.Default.ElectricalServices,
                label = "Port Map & Review",
                subtitle = "Hardware Review",
                status = state.hardwareReadiness?.portMap?.status ?: hardwareStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.PortMap,
                onClick = { onSelect(RobotStudioSelection.PortMap) },
            )

            val verificationStage = state.stages.firstOrNull { it.id == RobotStudioStageId.GENERATE_VERIFY }
            TreeNodeRow(
                icon = Icons.AutoMirrored.Filled.FactCheck,
                label = "Verification",
                subtitle = "Evidence & Build Results",
                status = verificationStage?.status,
                state = state,
                isSelected = selected is RobotStudioSelection.Verification,
                onClick = { onSelect(RobotStudioSelection.Verification) },
            )
        }

        // Read-only guidance. Project verification is the single action in the global toolbar.
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HorizontalDivider(color = AresBorder)
            val nextStage = state.nextStage
            Text(
                when {
                    state.loading -> "Checking canonical project documents…"
                    state.error != null -> "Readiness unavailable — refresh after fixing the project inspection error."
                    nextStage != null -> "Next: ${nextStage.title}"
                    else -> "Authoring is current. Use Verify & build in the top toolbar."
                },
                color = if (state.error != null) AresRed else AresTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CollapsedTreeRail(
    selected: RobotStudioSelection,
    onSelect: (RobotStudioSelection) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onToggleCollapse,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "Expand tree", tint = AresCyan)
        }

        HorizontalDivider(color = AresBorder)

        CollapsedIconButton(
            icon = Icons.Default.Badge,
            contentDescription = "Project Identity",
            isSelected = selected is RobotStudioSelection.Identity,
            onClick = { onSelect(RobotStudioSelection.Identity) },
        )
        CollapsedIconButton(
            icon = Icons.Default.Settings,
            contentDescription = "Drivetrain",
            isSelected = selected is RobotStudioSelection.Drivetrain,
            onClick = { onSelect(RobotStudioSelection.Drivetrain) },
        )
        CollapsedIconButton(
            icon = Icons.Default.Construction,
            contentDescription = "Mechanisms and subsystems",
            isSelected = selected is RobotStudioSelection.Subsystem,
            onClick = { onSelect(RobotStudioSelection.Subsystem("")) },
        )
        CollapsedIconButton(
            icon = Icons.Default.Layers,
            contentDescription = "Superstructure",
            isSelected = selected is RobotStudioSelection.Superstructure,
            onClick = { onSelect(RobotStudioSelection.Superstructure) },
        )
        CollapsedIconButton(
            icon = Icons.Default.Route,
            contentDescription = "Routines and autonomous",
            isSelected = selected is RobotStudioSelection.Autonomous,
            onClick = { onSelect(RobotStudioSelection.Autonomous) },
        )
        CollapsedIconButton(
            icon = Icons.Default.SportsEsports,
            contentDescription = "TeleOp controls",
            isSelected = selected is RobotStudioSelection.Controls,
            onClick = { onSelect(RobotStudioSelection.Controls) },
        )
        CollapsedIconButton(
            icon = Icons.Default.ElectricalServices,
            contentDescription = "Port map and hardware review",
            isSelected = selected is RobotStudioSelection.PortMap,
            onClick = { onSelect(RobotStudioSelection.PortMap) },
        )
        CollapsedIconButton(
            icon = Icons.AutoMirrored.Filled.FactCheck,
            contentDescription = "Verification",
            isSelected = selected is RobotStudioSelection.Verification,
            onClick = { onSelect(RobotStudioSelection.Verification) },
        )
    }
}

@Composable
private fun CollapsedIconButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) AresCyan.copy(alpha = 0.15f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, AresCyan.copy(alpha = 0.5f)) else null,
        shape = RoundedCornerShape(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (isSelected) AresCyan else AresTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TreeNodeRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    status: RobotStudioStageStatus?,
    state: RobotStudioState,
    isSelected: Boolean,
    indent: androidx.compose.ui.unit.Dp = 0.dp,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) AresCyan.copy(alpha = 0.12f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, AresCyan.copy(alpha = 0.4f)) else null,
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isSelected) AresCyan else AresTextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    color = if (isSelected) AresTextPrimary else AresTextPrimary.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
            StatusBadge(state.nodePresentation(status))
        }
    }
}

@Composable
private fun StatusBadge(status: RobotStudioStatusPresentation) {
    val color = status.tone.color()
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = status.label,
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
    }
}

@Composable
internal fun RobotStudioPresentationTone.color(): Color = when (this) {
    RobotStudioPresentationTone.SUCCESS -> AresGreen
    RobotStudioPresentationTone.INFO -> AresCyan
    RobotStudioPresentationTone.WARNING -> AresAmber
    RobotStudioPresentationTone.ERROR -> AresRed
    RobotStudioPresentationTone.MUTED -> AresTextTertiary
}
