package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.defaultGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.areslib.catalog.*
import com.areslib.routine.*

@Composable
internal fun RoutineSetupCard(
    state: PathPlannerState,
    projectPath: String?,
    league: League,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    onRobotDimensionsChanged: (RobotDimensions) -> Unit,
    onIntent: (PathPlannerIntent) -> Unit
) {
    val entry = state.autonomousEntry
    val dimensions = state.robotDimensions
    val modeLabel = if (state.availableInAutonomousSelector) "Match autonomous" else "Reusable routine"
    val footprintLabel = "${formatRoutineNumber(dimensions.lengthMeters)} × ${formatRoutineNumber(dimensions.widthMeters)} m"

    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Routine setup", fontWeight = FontWeight.Bold, color = AresTextPrimary)
                    Text(
                        "$modeLabel  •  $footprintLabel footprint",
                        style = MaterialTheme.typography.labelSmall,
                        color = AresTextSecondary
                    )
                }
                Text("Match auto", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
                Switch(
                    checked = state.availableInAutonomousSelector,
                    onCheckedChange = { checked ->
                        if (checked) onExpandedChanged(true)
                        onIntent(PathPlannerIntent.SetAutonomousAvailability(checked, league))
                    },
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(onClick = { onExpandedChanged(!expanded) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        if (expanded) "Hide routine setup" else "Show routine setup"
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = AresBorder.copy(alpha = .7f))
                OutlinedTextField(
                    value = state.routine.description.orEmpty(),
                    onValueChange = { onIntent(PathPlannerIntent.UpdateRoutineDescription(it)) },
                    label = { Text("What this routine does (optional)") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = routineTextFieldColors()
                )

                Text("Robot footprint", fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
                Text(
                    "Used to keep every drive goal safely inside the field.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AresTextSecondary
                )
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    RoutineDecimalEditor(dimensions.lengthMeters, "Length", "m", Modifier.weight(1f)) {
                        if (it in RobotDimensions.MIN_SIZE_METERS..RobotDimensions.MAX_SIZE_METERS) {
                            onRobotDimensionsChanged(dimensions.copy(lengthMeters = it))
                        }
                    }
                    RoutineDecimalEditor(dimensions.widthMeters, "Width", "m", Modifier.weight(1f)) {
                        if (it in RobotDimensions.MIN_SIZE_METERS..RobotDimensions.MAX_SIZE_METERS) {
                            onRobotDimensionsChanged(dimensions.copy(widthMeters = it))
                        }
                    }
                }

                HorizontalDivider(color = AresBorder.copy(alpha = .7f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Starting position",
                            color = if (entry != null) AresCyan else AresTextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (entry != null) "Initial field pose for match autonomous"
                            else "No starting position (routine runs from current robot pose)",
                            style = MaterialTheme.typography.labelSmall,
                            color = AresTextSecondary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (entry != null) "Set" else "None",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry != null) AresCyan else AresTextSecondary
                        )
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = entry != null,
                            onCheckedChange = { checked ->
                                onIntent(PathPlannerIntent.SetAutonomousAvailability(checked, league))
                            }
                        )
                    }
                }

                if (entry != null) {
                    Text("Autonomous starting pose & heading", color = AresCyan, style = MaterialTheme.typography.labelMedium)
                    RoutinePoseEditors(entry.startingPose) {
                        onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(startingPose = it), league))
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp), Alignment.CenterVertically) {
                        RoutineAlliancePicker(entry.authoredAlliance) {
                            onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(authoredAlliance = it), league))
                        }
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = entry.mirrorForOppositeAlliance,
                                onCheckedChange = {
                                    onIntent(PathPlannerIntent.UpdateAutonomousEntry(entry.copy(mirrorForOppositeAlliance = it), league))
                                }
                            )
                            Text("Mirror alliance", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text(state.capabilityStatus, style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
            }
        }
    }
}

@Composable
internal fun RoutineAlliancePicker(value: RoutineAlliance, onChange: (RoutineAlliance) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Authored for ${value.name.lowercase()}") }
        DropdownMenu(expanded, { expanded = false }) {
            RoutineAlliance.entries.forEach { alliance ->
                DropdownMenuItem({ Text(alliance.name.lowercase().replaceFirstChar(Char::uppercase)) }, {
                    onChange(alliance)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun RoutineValidationCard(issues: List<RoutineValidationIssue>) {
    val hasErrors = issues.any { it.severity == RoutineValidationSeverity.ERROR }
    val accent = if (hasErrors) AresError else AresGold
    Card(colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .1f)), border = BorderStroke(1.dp, accent)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (hasErrors) "Needs attention" else "Review before deployment", color = accent, fontWeight = FontWeight.Bold)
            issues.take(5).forEach { Text("• ${it.message}", style = MaterialTheme.typography.bodySmall, color = AresTextPrimary) }
            if (issues.size > 5) Text("+ ${issues.size - 5} more", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
        }
    }
}

@Composable
internal fun EmptyRoutineCard() {
    Card(colors = CardDefaults.cardColors(containerColor = AresCyan.copy(alpha = .08f)), border = BorderStroke(1.dp, AresCyan.copy(alpha = .5f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Add what the robot should do", color = AresCyan, fontWeight = FontWeight.Bold)
            Text("A routine can become an autonomous choice, a controller macro, or a reusable building block later.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

