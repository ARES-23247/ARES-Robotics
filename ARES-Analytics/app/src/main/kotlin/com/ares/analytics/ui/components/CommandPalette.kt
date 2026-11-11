@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputComponent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.LearningCatalog
import com.ares.analytics.domain.learning.LearningCheckpoint
import com.ares.analytics.domain.learning.LearningCheckpointAction
import com.ares.analytics.domain.learning.LearningJourneyEvaluator
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.service.LearningProgressService
import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlinx.coroutines.launch

enum class CommandPaletteProjectCategory(val label: String) {
    STAGE("Robot Studio Stage"),
    SUBSYSTEM("Subsystem"),
    SUPERSTRUCTURE("Superstructure"),
    ROUTINE("Autonomous Routine"),
}

data class CommandPaletteProjectItem(
    val id: String,
    val label: String,
    val subtitle: String,
    val category: CommandPaletteProjectCategory,
    val target: NavigationTarget,
    val keywords: List<String> = emptyList(),
)

fun defaultRobotStudioStageItems(): List<CommandPaletteProjectItem> = listOf(
    CommandPaletteProjectItem(
        id = "stage:identity",
        label = "1. Robot Identity & Dimensions",
        subtitle = "Robot Studio Stage 1 · Team, season, and bumper footprint",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.PROJECT_IDENTITY,
        keywords = listOf("identity", "dimensions", "footprint", "bumper", "stage 1"),
    ),
    CommandPaletteProjectItem(
        id = "stage:drivetrain",
        label = "2. Drivetrain Builder",
        subtitle = "Robot Studio Stage 2 · Swerve, mecanum, differential, and localization",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.DRIVEBASE_BUILDER,
        keywords = listOf("drivetrain", "drivebase", "swerve", "mecanum", "localization", "stage 2"),
    ),
    CommandPaletteProjectItem(
        id = "stage:subsystems",
        label = "3. Mechanisms / Subsystems",
        subtitle = "Robot Studio Stage 3 · Hardware, stateflow, PID, and commissioning",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.SUBSYSTEM_GEN,
        keywords = listOf("subsystem", "mechanism", "arm", "elevator", "intake", "shooter", "stage 3"),
    ),
    CommandPaletteProjectItem(
        id = "stage:wiring",
        label = "4. Hardware Ports & Pit Wiring",
        subtitle = "Robot Studio Stage 4 · CAN buses, REV Hub ports, and conflict audit",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.HARDWARE_SETUP,
        keywords = listOf("wiring", "ports", "can", "expansion hub", "control hub", "stage 4"),
    ),
    CommandPaletteProjectItem(
        id = "stage:superstructure",
        label = "5. Superstructure Coordinator",
        subtitle = "Robot Studio Stage 5 · Multi-subsystem postures, interlocks, and simulation lab",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.SUPERSTRUCTURE_STUDIO,
        keywords = listOf("superstructure", "posture", "interlock", "coordinator", "stage 5"),
    ),
    CommandPaletteProjectItem(
        id = "stage:controls",
        label = "6. TeleOp Controls",
        subtitle = "Robot Studio Stage 6 · Driver and operator gamepad bindings",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.CONTROLS,
        keywords = listOf("controls", "gamepad", "controller", "teleop", "bindings", "stage 6"),
    ),
    CommandPaletteProjectItem(
        id = "stage:autonomous",
        label = "7. Autonomous Routines",
        subtitle = "Robot Studio Stage 7 · Trajectories, action timeline, and scrubber",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.PATH_PLANNER,
        keywords = listOf("autonomous", "routine", "path", "trajectory", "stage 7"),
    ),
    CommandPaletteProjectItem(
        id = "stage:verification",
        label = "8. Verify & Build Readiness",
        subtitle = "Robot Studio Stage 8 · Codegen, safety checks, and Gradle verification",
        category = CommandPaletteProjectCategory.STAGE,
        target = NavigationTarget.ROBOT_STUDIO,
        keywords = listOf("verify", "build", "readiness", "codegen", "stage 8"),
    ),
)

fun discoverProjectCommandPaletteItems(projectPath: String): List<CommandPaletteProjectItem> {
    val stages = defaultRobotStudioStageItems()
    if (projectPath.isBlank()) return stages
    val loaded = runCatching { AresProjectDocuments().load(projectPath).query }.getOrNull()
        ?: return stages

    val subsystemItems = loaded.subsystems.map { doc ->
        CommandPaletteProjectItem(
            id = "subsystem:${doc.documentId}",
            label = "Subsystem: ${doc.displayName}",
            subtitle = ".ares/subsystems/${doc.documentId}.aressubsystem · ${doc.template.name}",
            category = CommandPaletteProjectCategory.SUBSYSTEM,
            target = NavigationTarget.SUBSYSTEM_GEN,
            keywords = listOf(doc.documentId, doc.displayName, doc.template.name, "subsystem", "mechanism"),
        )
    }

    val superstructureItems = loaded.superstructures.map { doc ->
        CommandPaletteProjectItem(
            id = "superstructure:${doc.superstructureId}",
            label = "Superstructure: ${doc.displayName}",
            subtitle = ".ares/superstructures/${doc.superstructureId}.aressuperstructure · ${doc.states.size} postures",
            category = CommandPaletteProjectCategory.SUPERSTRUCTURE,
            target = NavigationTarget.SUPERSTRUCTURE_STUDIO,
            keywords = listOf(doc.superstructureId, doc.displayName, "superstructure", "coordinator") +
                doc.states.map { it.displayName },
        )
    }

    val routineItems = loaded.routines.map { doc ->
        CommandPaletteProjectItem(
            id = "routine:${doc.documentId}",
            label = "Routine: ${doc.name}",
            subtitle = ".ares/routines/${doc.documentId}.aresroutine · ${doc.steps.size} steps",
            category = CommandPaletteProjectCategory.ROUTINE,
            target = NavigationTarget.PATH_PLANNER,
            keywords = listOf(doc.documentId, doc.name, "routine", "autonomous", "auto"),
        )
    }

    return stages + subsystemItems + superstructureItems + routineItems
}

fun filterCommandPaletteProjectItems(
    query: String,
    items: List<CommandPaletteProjectItem>,
): List<CommandPaletteProjectItem> {
    val normalized = query.trim().lowercase()
    if (normalized.isEmpty()) {
        return items.filter { it.category == CommandPaletteProjectCategory.STAGE }
    }
    return items.filter { item ->
        item.label.lowercase().contains(normalized) ||
            item.subtitle.lowercase().contains(normalized) ||
            item.category.label.lowercase().contains(normalized) ||
            item.keywords.any { it.lowercase().contains(normalized) }
    }
}

fun filterNavigationTargets(query: String, developerMode: Boolean): List<NavigationTarget> {
    val normalized = query.trim().lowercase()
    return availablePaletteTargets(developerMode).filter { target ->
        normalized.isEmpty() ||
            target.label.lowercase().contains(normalized) ||
            target.groupLabel().lowercase().contains(normalized) ||
            target.searchTerms().any { it.contains(normalized) }
    }
}

@Composable
fun CommandPalette(
    developerMode: Boolean,
    projectPath: String = "",
    projectItems: List<CommandPaletteProjectItem>? = null,
    onDismiss: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit,
    onOpenGlossaryTerm: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val allProjectItems = remember(projectPath, projectItems) {
        projectItems ?: discoverProjectCommandPaletteItems(projectPath)
    }
    val matchingProjectItems = remember(query, allProjectItems) {
        filterCommandPaletteProjectItems(query, allProjectItems)
    }
    val results = remember(query, developerMode) { filterNavigationTargets(query, developerMode) }
    val glossaryResults = remember(query) {
        if (query.isBlank()) emptyList() else GlossaryCatalog.search(query).take(5)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Go anywhere", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Search screens, Robot Studio stages, mechanisms, superstructures, routines, or glossary terms.",
                    color = AresTextSecondary,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column(Modifier.width(580.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text("Search screens, subsystems, routines, or stages...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                HorizontalDivider(color = AresBorder)
                if (matchingProjectItems.isEmpty() && results.isEmpty() && glossaryResults.isEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                        Text("No matching screens or project entities", color = AresTextSecondary)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(matchingProjectItems, key = { "project-${it.id}" }) { item ->
                            val icon = when (item.category) {
                                CommandPaletteProjectCategory.STAGE -> Icons.Default.SettingsInputComponent
                                CommandPaletteProjectCategory.SUBSYSTEM -> Icons.Default.Build
                                CommandPaletteProjectCategory.SUPERSTRUCTURE -> Icons.Default.AccountTree
                                CommandPaletteProjectCategory.ROUTINE -> Icons.Default.Route
                            }
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onNavigate(item.target)
                                    onDismiss()
                                }.background(AresSurfaceElevated, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(icon, null, tint = AresGreen, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(item.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.subtitle, color = AresTextTertiary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(item.category.label, color = AresGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        items(results, key = { it.name }) { target ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onNavigate(target)
                                    onDismiss()
                                }.background(AresSurfaceElevated, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(target.icon, null, tint = AresCyan, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(target.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(target.groupLabel(), color = AresTextTertiary, fontSize = 10.sp)
                                }
                                Text("Open", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                        items(glossaryResults, key = { "glossary-${it.term}" }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onOpenGlossaryTerm(entry.term)
                                    onDismiss()
                                }.background(AresSurfaceElevated, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = AresCyan, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(entry.term, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        entry.definition,
                                        color = AresTextTertiary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("Glossary", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = AresSurface,
        shape = RoundedCornerShape(14.dp)
    )
}
