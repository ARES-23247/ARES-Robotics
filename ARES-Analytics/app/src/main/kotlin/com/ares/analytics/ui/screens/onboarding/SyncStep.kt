package com.ares.analytics.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotProfile
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.OnboardingFieldErrors
import com.ares.analytics.viewmodel.OnboardingStep
import com.ares.analytics.viewmodel.ProjectSetupMode
import com.areslib.project.AresProjectAuthoringModel
import java.io.File

/** Project, robot, advanced, and review content shared by the staged onboarding wizard. */
@Composable
fun SyncStep(
    step: OnboardingStep,
    projectSetupMode: ProjectSetupMode,
    projectPath: String,
    projectParentPath: String,
    projectFolderName: String,
    projectDetectionMessage: String?,
    projectTemplateName: String,
    projectTemplateVersion: String,
    projectCreationMessage: String?,
    authoringModel: AresProjectAuthoringModel,
    onAuthoringModelChange: (AresProjectAuthoringModel) -> Unit,
    onProjectSetupModeChange: (ProjectSetupMode) -> Unit,
    onProjectPathChange: (String) -> Unit,
    onBrowseProject: () -> Unit,
    onProjectParentPathChange: (String) -> Unit,
    onProjectFolderNameChange: (String) -> Unit,
    onBrowseProjectParent: () -> Unit,
    teamId: String,
    onTeamIdChange: (String) -> Unit,
    cloudRobots: List<RobotProfile>,
    selectedOptionText: String,
    onSelectedOptionTextChange: (String) -> Unit,
    robotId: String,
    onRobotIdChange: (String) -> Unit,
    seasonId: String,
    onSeasonIdChange: (String) -> Unit,
    robotName: String,
    onRobotNameChange: (String) -> Unit,
    league: League,
    onLeagueChange: (League) -> Unit,
    nt4Host: String,
    onNt4HostChange: (String) -> Unit,
    simulatorCommand: String,
    onSimulatorCommandChange: (String) -> Unit,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    fieldErrors: OnboardingFieldErrors,
    cloudConfigured: Boolean,
) {
    when (step) {
        OnboardingStep.PROJECT -> ProjectSelection(
            mode = projectSetupMode,
            projectPath = projectPath,
            projectParentPath = projectParentPath,
            projectFolderName = projectFolderName,
            detectionMessage = projectDetectionMessage,
            templateName = projectTemplateName,
            templateVersion = projectTemplateVersion,
            creationMessage = projectCreationMessage,
            authoringModel = authoringModel,
            onAuthoringModelChange = onAuthoringModelChange,
            error = fieldErrors.projectPath,
            league = league,
            onLeagueChange = onLeagueChange,
            onModeChange = onProjectSetupModeChange,
            onProjectPathChange = onProjectPathChange,
            onBrowseProject = onBrowseProject,
            onProjectParentPathChange = onProjectParentPathChange,
            onProjectFolderNameChange = onProjectFolderNameChange,
            onBrowseProjectParent = onBrowseProjectParent,
        )
        OnboardingStep.ROBOT -> RobotDetails(
            teamId = teamId,
            onTeamIdChange = onTeamIdChange,
            cloudRobots = cloudRobots,
            selectedOptionText = selectedOptionText,
            onSelectedOptionTextChange = onSelectedOptionTextChange,
            robotId = robotId,
            onRobotIdChange = onRobotIdChange,
            seasonId = seasonId,
            onSeasonIdChange = onSeasonIdChange,
            robotName = robotName,
            onRobotNameChange = onRobotNameChange,
            league = league,
            onLeagueChange = onLeagueChange,
            fieldErrors = fieldErrors,
        )
        OnboardingStep.OPTIONAL -> AdvancedConnectionSettings(
            nt4Host = nt4Host,
            onNt4HostChange = onNt4HostChange,
            simulatorCommand = simulatorCommand,
            onSimulatorCommandChange = onSimulatorCommandChange,
            expanded = advancedExpanded,
            onExpandedChange = onAdvancedExpandedChange,
        )
        OnboardingStep.REVIEW -> CompletionSummary(
            projectPath = projectPath,
            teamId = teamId,
            seasonId = seasonId,
            robotId = robotId,
            robotName = robotName,
            league = league,
            nt4Host = nt4Host,
            simulatorCommand = simulatorCommand,
            cloudConfigured = cloudConfigured,
        )
    }
}

@Composable
private fun ProjectSelection(
    mode: ProjectSetupMode,
    projectPath: String,
    projectParentPath: String,
    projectFolderName: String,
    detectionMessage: String?,
    templateName: String,
    templateVersion: String,
    creationMessage: String?,
    authoringModel: AresProjectAuthoringModel,
    onAuthoringModelChange: (AresProjectAuthoringModel) -> Unit,
    error: String?,
    league: League,
    onLeagueChange: (League) -> Unit,
    onModeChange: (ProjectSetupMode) -> Unit,
    onProjectPathChange: (String) -> Unit,
    onBrowseProject: () -> Unit,
    onProjectParentPathChange: (String) -> Unit,
    onProjectFolderNameChange: (String) -> Unit,
    onBrowseProjectParent: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("How would you like to begin?", color = AresTextPrimary, fontWeight = FontWeight.Bold)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProjectModeCard(
                title = "Create standalone robot project",
                description = "Export a complete FTC or FRC repository that works in Studio, an IDE, or the terminal.",
                selected = mode == ProjectSetupMode.CREATE_NEW,
                onClick = { onModeChange(ProjectSetupMode.CREATE_NEW) },
                modifier = Modifier.weight(1f),
            )
            ProjectModeCard(
                title = "Open an existing project",
                description = "Use an ARES FTC or FRC repository already on this computer.",
                selected = mode == ProjectSetupMode.OPEN_EXISTING,
                onClick = { onModeChange(ProjectSetupMode.OPEN_EXISTING) },
                modifier = Modifier.weight(1f),
            )
        }
        ProjectModeCard(
            title = "Explore Lightbot",
            description = "Create your own editable copy of the official simulation-first FTC mecanum and lighting example.",
            selected = mode == ProjectSetupMode.EXPLORE_DEMO,
            onClick = { onModeChange(ProjectSetupMode.EXPLORE_DEMO) },
            modifier = Modifier.fillMaxWidth(),
        )

        if (mode.createsProject) {
            if (mode == ProjectSetupMode.CREATE_NEW) LeagueSelector(league, onLeagueChange)
            if (mode == ProjectSetupMode.CREATE_NEW) {
                Text("Who owns robot behavior?", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthoringModelCard(
                        title = "GUI-owned robot",
                        description = "Robot Studio owns canonical .ares documents and generates runtime code and tests.",
                        selected = authoringModel == AresProjectAuthoringModel.GUI_OWNED,
                        onClick = { onAuthoringModelChange(AresProjectAuthoringModel.GUI_OWNED) },
                    )
                    AuthoringModelCard(
                        title = "Code-first Kotlin robot",
                        description = "Your Kotlin is authoritative. Register actions, telemetry, tunables, safety evidence, and simulation support so Studio can display it.",
                        selected = authoringModel == AresProjectAuthoringModel.CODE_FIRST,
                        onClick = { onAuthoringModelChange(AresProjectAuthoringModel.CODE_FIRST) },
                    )
                    AuthoringModelCard(
                        title = "Hybrid robot",
                        description = "Studio owns drivetrain and routines; explicitly registered team Kotlin owns selected mechanisms.",
                        selected = authoringModel == AresProjectAuthoringModel.HYBRID,
                        onClick = { onAuthoringModelChange(AresProjectAuthoringModel.HYBRID) },
                    )
                }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (mode == ProjectSetupMode.EXPLORE_DEMO) {
                            "Verified example: $templateName · built with ARES $templateVersion"
                        } else {
                            "Verified starter: $templateName $templateVersion"
                        },
                        color = AresTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (mode == ProjectSetupMode.EXPLORE_DEMO) {
                            "The installer keeps the reviewed Lightbot example unchanged and creates a separate editable copy in the folder you choose. Studio never edits its packaged example or the ARES source checkout."
                        } else {
                            "The official installer includes this exact, SHA-256-verified starter. ARES can create it offline; the network is only a recovery fallback for source builds."
                        },
                        color = AresTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (mode == ProjectSetupMode.EXPLORE_DEMO) {
                            "SIMULATION ONLY UNTIL REVIEWED — Explore, change, build, and simulate this copy. It is not evidence that any physical robot wiring, directions, limits, or calibration were validated."
                        } else {
                            "SIMULATION FIRST — This generic starter contains no Team 23247 season mechanisms or calibration. Build and simulation are supported; complete Hardware Setup and the commissioning checklist before physical deployment."
                        },
                        color = AresError,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            PathChooser(
                value = projectParentPath,
                onValueChange = onProjectParentPathChange,
                label = "Create inside this folder",
                placeholder = platformPathPlaceholder("Robots"),
                buttonLabel = "Choose parent folder",
                onBrowse = onBrowseProjectParent,
            )
            AresTextField(
                value = projectFolderName,
                onValueChange = onProjectFolderNameChange,
                label = "New project folder name",
                placeholder = "team-23247-robot",
                modifier = Modifier.fillMaxWidth(),
            )
            FieldMessage(error)
            if (projectPath.isNotBlank() && error == null) {
                Text("New project: $projectPath", color = AresGreen, style = MaterialTheme.typography.bodySmall)
            }
            creationMessage?.let { Text(it, color = AresGreen, style = MaterialTheme.typography.bodySmall) }
        } else {
            Text(
                "Choose the repository root—the folder containing settings.gradle or settings.gradle.kts.",
                color = AresTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            PathChooser(
                value = projectPath,
                onValueChange = onProjectPathChange,
                label = "Robot project folder",
                placeholder = platformPathPlaceholder("my-robot-project"),
                buttonLabel = "Choose project",
                onBrowse = onBrowseProject,
            ) {
                FieldMessage(error)
            }
        }
        if (detectionMessage != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen)
                Text(detectionMessage, color = AresGreen, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AuthoringModelCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) AresCyanGlow else AresSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(description, color = AresTextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun platformPathPlaceholder(folderName: String, separator: Char = File.separatorChar): String =
    if (separator == '\\') "C:\\Users\\...\\$folderName" else "/path/to/$folderName"

@Composable
private fun ProjectModeCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) AresCyanGlow else AresSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = if (selected) AresCyan else AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(description, color = AresTextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(if (selected) "Selected" else "Select", color = if (selected) AresGreen else AresTextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PathChooser(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    buttonLabel: String,
    onBrowse: () -> Unit,
    supportingContent: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AresTextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
            supportingContent()
        }
        Button(
            onClick = onBrowse,
            colors = ButtonDefaults.buttonColors(containerColor = AresBorder),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = AresTextPrimary)
            Text(buttonLabel, color = AresTextPrimary)
        }
    }
}

@Composable
private fun RobotDetails(
    teamId: String,
    onTeamIdChange: (String) -> Unit,
    cloudRobots: List<RobotProfile>,
    selectedOptionText: String,
    onSelectedOptionTextChange: (String) -> Unit,
    robotId: String,
    onRobotIdChange: (String) -> Unit,
    seasonId: String,
    onSeasonIdChange: (String) -> Unit,
    robotName: String,
    onRobotNameChange: (String) -> Unit,
    league: League,
    onLeagueChange: (League) -> Unit,
    fieldErrors: OnboardingFieldErrors,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LeagueSelector(league, onLeagueChange)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldWithError(
                value = teamId,
                onValueChange = onTeamIdChange,
                label = "FIRST team number",
                placeholder = "23247",
                error = fieldErrors.teamId,
                modifier = Modifier.weight(1f),
            )
            FieldWithError(
                value = seasonId,
                onValueChange = onSeasonIdChange,
                label = "Season",
                placeholder = "2026",
                error = fieldErrors.seasonId,
                modifier = Modifier.weight(1f),
            )
        }

        if (cloudRobots.isNotEmpty()) {
            RobotProfilePicker(
                robots = cloudRobots,
                selectedOptionText = selectedOptionText,
                onSelectedOptionTextChange = onSelectedOptionTextChange,
                onRobotIdChange = onRobotIdChange,
                onSeasonIdChange = onSeasonIdChange,
                onLeagueChange = onLeagueChange,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldWithError(
                value = robotId,
                onValueChange = onRobotIdChange,
                label = "Robot ID",
                placeholder = "AresIII",
                error = fieldErrors.robotId,
                modifier = Modifier.weight(1f),
            )
            FieldWithError(
                value = robotName,
                onValueChange = onRobotNameChange,
                label = "Friendly name (optional)",
                placeholder = "Competition robot",
                error = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LeagueSelector(league: League, onLeagueChange: (League) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Competition", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
        ) {
            League.entries.forEach { option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (league == option) AresCyanGlow else Color.Transparent)
                        .clickable { onLeagueChange(option) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (option == League.FTC) "FTC" else "FRC",
                        color = if (league == option) AresCyan else AresTextSecondary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RobotProfilePicker(
    robots: List<RobotProfile>,
    selectedOptionText: String,
    onSelectedOptionTextChange: (String) -> Unit,
    onRobotIdChange: (String) -> Unit,
    onSeasonIdChange: (String) -> Unit,
    onLeagueChange: (League) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth().clickable { expanded = true }) {
        AresTextField(
            value = selectedOptionText,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = "Saved cloud robot (optional)",
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AresTextSecondary) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder),
        ) {
            robots.forEach { robot ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(robot.name, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text("${robot.league.name} • Season ${robot.seasonId}", color = AresTextSecondary, fontSize = 10.sp)
                        }
                    },
                    onClick = {
                        onSelectedOptionTextChange(robot.name)
                        onRobotIdChange(robot.robotId)
                        onSeasonIdChange(robot.seasonId)
                        onLeagueChange(robot.league)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun AdvancedConnectionSettings(
    nt4Host: String,
    onNt4HostChange: (String) -> Unit,
    simulatorCommand: String,
    onSimulatorCommandChange: (String) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Connection settings (advanced, optional)", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text("The detected defaults work for most teams.", color = AresTextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = AresTextSecondary,
                )
            }
            if (expanded) {
                AresTextField(
                    value = nt4Host,
                    onValueChange = onNt4HostChange,
                    label = "Robot NetworkTables address",
                    modifier = Modifier.fillMaxWidth(),
                )
                AresTextField(
                    value = simulatorCommand,
                    onValueChange = onSimulatorCommandChange,
                    label = "Simulator command (optional)",
                    placeholder = ":TeamCode:runSim",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CompletionSummary(
    projectPath: String,
    teamId: String,
    seasonId: String,
    robotId: String,
    robotName: String,
    league: League,
    nt4Host: String,
    simulatorCommand: String,
    cloudConfigured: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = androidx.compose.foundation.BorderStroke(1.dp, AresBorder),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Workspace summary", color = AresTextPrimary, fontWeight = FontWeight.Bold)
            SummaryRow("Project", projectPath)
            SummaryRow("Robot", robotName.ifBlank { robotId })
            SummaryRow("Team and season", "$teamId • $seasonId • ${league.name}")
            SummaryRow("Robot connection", nt4Host.ifBlank { "Use app default" })
            SummaryRow("Simulator", simulatorCommand.ifBlank { "Not configured" })
            HorizontalDivider(color = AresBorder)
            SummaryRow("Cloud sync", if (cloudConfigured) "Signed in" else "Off — local setup is fully usable")
            Text(
                "ARES stores and analyzes logs on this computer. You can add cloud sync later in Settings.",
                color = AresGreen,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(0.35f), color = AresTextSecondary, style = MaterialTheme.typography.bodySmall)
        Text(value, modifier = Modifier.weight(0.65f), color = AresTextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FieldWithError(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        AresTextField(
            value = value,
            onValueChange = onValueChange,
            label = label,
            placeholder = placeholder,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldMessage(error)
    }
}

@Composable
private fun FieldMessage(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(3.dp))
        Text(error, color = AresError, style = MaterialTheme.typography.labelSmall)
    }
}
