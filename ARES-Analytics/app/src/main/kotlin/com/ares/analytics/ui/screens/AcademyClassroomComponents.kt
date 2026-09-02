@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.AcademyLearningAssignment
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.theme.*
import java.io.File
import com.ares.analytics.ui.util.DesktopFileChoosers

@Composable
internal fun ClassroomPracticeSetupCard(
    projectPath: String,
    projectLabel: String,
    statusMessage: String?,
    onCreatePracticeProject: () -> Unit,
    onInstallPracticePack: () -> Unit,
    onOpenImports: () -> Unit,
    onOpenRunReview: () -> Unit,
) {
    ClassroomSection("Offline practice setup") {
        Text(
            if (projectPath.isBlank()) "No robot project is selected."
            else "Current project: ${projectLabel.ifBlank { File(projectPath).name }}\n$projectPath",
            color = AresTextPrimary,
        )
        Text(
            "Create New Workspace uses a hash-pinned official FTC or FRC starter. Once downloaded, its verified cache can be reused offline. Your current workspace is not deleted.",
            color = AresTextSecondary,
            fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCreatePracticeProject) { Text("Create practice workspace") }
            Button(
                onClick = onInstallPracticePack,
                enabled = projectPath.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text("Install & import practice runs") }
            OutlinedButton(onClick = onOpenRunReview) { Text("Open Guided Run Review") }
            OutlinedButton(onClick = onOpenImports) { Text("Import my own log") }
        }
        statusMessage?.let {
            Text(it, color = if (it.contains("failed", ignoreCase = true) || it.contains("not installed", ignoreCase = true)) AresAmber else AresGreen)
        }
    }
}

@Composable
internal fun AssignmentCard(
    assignment: AcademyLearningAssignment,
    studentName: String,
    onCompletedChange: (Boolean) -> Unit,
    onExportWorksheet: () -> Unit,
    onRemove: () -> Unit,
) {
    val lessonNames = assignment.lessonIds.mapNotNull(LearningCatalog::lesson).joinToString { it.title }
    Surface(
        color = AresSurfaceElevated,
        border = BorderStroke(1.dp, if (assignment.completed) AresGreen else AresBorder),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.fillMaxWidth().semantics {
            stateDescription = if (assignment.completed) "Assignment marked complete" else "Assignment still open"
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(assignment.title, color = AresTextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "${studentName.ifBlank { "Active learner" }} · ${assignment.dueLabel.ifBlank { "No due date" }}",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }
                FilterChip(
                    selected = assignment.completed,
                    onClick = { onCompletedChange(!assignment.completed) },
                    label = { Text(if (assignment.completed) "Checklist complete" else "Mark checklist complete") },
                )
            }
            Text("Lessons: $lessonNames", color = AresTextSecondary, fontSize = 12.sp)
            if (assignment.instructions.isNotBlank()) {
                Text(assignment.instructions, color = AresTextSecondary, fontSize = 12.sp)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onExportWorksheet) { Text("Export worksheet") }
                TextButton(onClick = onRemove) { Text("Remove assignment") }
            }
            Text(
                "The assignment checklist does not complete lesson evidence or approve hardware use.",
                color = AresAmber,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
internal fun ClassroomSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            content()
        }
    }
}

internal fun chooseAssignmentWorksheetFile(studentName: String, assignmentTitle: String): File? {
    fun slug(value: String, fallback: String): String = value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { fallback }
    return DesktopFileChoosers.chooseSaveFile(
        title = "Export ARES Academy assignment worksheet",
        defaultFileName = "ares-academy-${slug(studentName, "student")}-${slug(assignmentTitle, "assignment")}.md",
        filterDescription = "Markdown document (*.md)",
        extensions = listOf("md")
    )
}

internal fun chooseAcademyReportFile(studentName: String, pathId: String): File? {
    val studentSlug = studentName.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "student" }
    return DesktopFileChoosers.chooseSaveFile(
        title = "Export ARES Academy learning record",
        defaultFileName = "ares-academy-$studentSlug-$pathId.md",
        filterDescription = "Markdown document (*.md)",
        extensions = listOf("md")
    )
}

