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

@Composable
internal fun CollapsedTreeRail(
    selected: RobotStudioSelection,
    onSelect: (RobotStudioSelection) -> Unit,
    onToggleCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
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
