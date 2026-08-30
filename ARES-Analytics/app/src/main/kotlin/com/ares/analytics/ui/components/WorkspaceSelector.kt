package com.ares.analytics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/** Workspace picker and creation entry points owned by the global Studio shell. */
@Composable
internal fun WorkspaceSelector(
    current: WorkspaceConfig,
    workspaces: List<WorkspaceConfig>,
    compact: Boolean,
    onSelect: (String) -> Unit,
    onRemove: (WorkspaceConfig) -> Unit,
    onCreate: () -> Unit,
    onExploreDemo: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .background(AresSurface)
                .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val badgeBackground = if (current.league == League.FTC) AresGold else AresCyan
            Text(
                text = current.league.name,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = AresBackground,
                modifier = Modifier
                    .background(badgeBackground, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Text(
                text = if (compact) current.robotId else "${current.robotId} (Team ${current.teamId})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary,
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresTextSecondary)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder),
        ) {
            Text(
                "MY ROBOTS",
                color = AresTextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            workspaces.forEach { workspace ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.width(220.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${workspace.robotId} (Team ${workspace.teamId})",
                                    fontWeight = if (workspace.id == current.id) FontWeight.Bold else FontWeight.Normal,
                                    color = if (workspace.id == current.id) AresCyan else AresTextPrimary,
                                )
                                Text(
                                    text = "${workspace.league.name} • Season ${workspace.seasonId}",
                                    fontSize = 11.sp,
                                    color = AresTextSecondary,
                                )
                            }
                            IconButton(
                                onClick = {
                                    expanded = false
                                    onRemove(workspace)
                                },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove workspace",
                                    tint = AresError.copy(alpha = 0.8f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(workspace.id)
                    },
                )
            }

            HorizontalDivider(color = AresBorder, modifier = Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = AresCyan, modifier = Modifier.size(18.dp))
                        Text("Create or open a robot...", color = AresCyan, fontWeight = FontWeight.Bold)
                    }
                },
                onClick = {
                    expanded = false
                    onCreate()
                },
            )
            HorizontalDivider(color = AresBorder, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "EXAMPLES",
                color = AresTextTertiary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = {
                    Column {
                        Text("Explore the demo robot", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                        Text(
                            "Create one editable, simulation-first FTC example",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                },
                onClick = {
                    expanded = false
                    onExploreDemo()
                },
            )
        }
    }
}
