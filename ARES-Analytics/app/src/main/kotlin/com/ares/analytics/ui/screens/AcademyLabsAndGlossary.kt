@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.dashboard.EkfSensorFusionLabCard
import com.ares.analytics.ui.components.pathplanner.MotionProfileLabCard
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.help.GlossaryTerm
import com.ares.analytics.ui.help.LearningCatalog
import com.ares.analytics.ui.help.LearningLab
import com.ares.analytics.ui.help.LearningLabGuide
import com.ares.analytics.ui.theme.*

@Composable
internal fun LearningLabsPane(initialLab: LearningLab, onBack: () -> Unit) {
    var selectedLab by remember(initialLab) { mutableStateOf(initialLab) }
    val guide = LearningCatalog.labGuide(selectedLab)
    Column(
        modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Back to lessons")
            }
            Column(Modifier.weight(1f)) {
                Text("Guided learning labs", color = AresTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Simplified models do not command hardware, change project files, or prove a robot design is safe.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LearningLab.entries.forEach { lab ->
                FilterChip(
                    selected = selectedLab == lab,
                    onClick = { selectedLab = lab },
                    label = { Text(lab.label) },
                    modifier = Modifier.semantics {
                        stateDescription = if (selectedLab == lab) "Selected lab" else "Available lab"
                    },
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { LabGuideCard(guide) }
            item {
                when (selectedLab) {
                    LearningLab.CONTROL -> ControlTheorySandboxLab()
                    LearningLab.TUNING_MISSIONS -> AcademyTuningMissions()
                    LearningLab.SENSOR_FUSION -> EkfSensorFusionLabCard()
                    LearningLab.KINEMATICS_VECTORS -> KinematicsVectorLabCard()
                    LearningLab.MOTION_PROFILE -> MotionProfileLabCard()
                    LearningLab.MECHANISM_SIZING -> MechanismKinematicsLabCard()
                    LearningLab.HOMING_SAFETY -> HomingSafetyLabCard()
                    LearningLab.STATE_FLOW -> RobotSignalFlowLabCard()
                    LearningLab.AUTONOMOUS_SAFETY -> AutonomousSafetyLabCard()
                }
            }
            item {
                TeachingNotice(
                    title = "Return to the lesson",
                    body = "Use Back to lessons to record your reflection. Running a model does not automatically mark understanding or safety.",
                    accent = AresCyan,
                )
            }
        }
    }
}

@Composable
private fun LabGuideCard(guide: LearningLabGuide) {
    Surface(color = AresSurface, border = BorderStroke(1.dp, AresBorder), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(guide.title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(guide.outcome, color = AresTextSecondary, lineHeight = 20.sp)
            LessonSection("Before you start", guide.beforeYouStart)
            LessonSection("Try this", guide.tryThis, numbered = true)
            LessonSection("Reflect", guide.reflectionQuestions)
            Text("Success: ${guide.successLooksLike}", color = AresGreen, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun GlossaryPane(
    initialTerm: String?,
    onBack: () -> Unit,
    onOpenLesson: (String) -> Unit,
    onOpenDeveloperReference: () -> Unit,
) {
    var query by remember { mutableStateOf(initialTerm.orEmpty()) }
    val matches = remember(query) { GlossaryCatalog.search(query) }
    Column(
        modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Back to Academy")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Glossary", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Short definitions for first-year team members. Cross-links open the lesson or developer reference that owns the concept.",
                color = AresTextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search terms and definitions") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        if (matches.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                Text("No terms match that search.", color = AresTextSecondary)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matches.size) { index ->
                    GlossaryTermCard(matches[index], onOpenLesson, onOpenDeveloperReference)
                }
            }
        }
    }
}

@Composable
private fun GlossaryTermCard(
    entry: GlossaryTerm,
    onOpenLesson: (String) -> Unit,
    onOpenDeveloperReference: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.term, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(entry.definition, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            entry.mentorNote?.let { note ->
                Text("Mentor note: $note", color = AresTextTertiary, fontSize = 11.sp, lineHeight = 15.sp)
            }
            if (entry.relatedLessonIds.isNotEmpty() || entry.relatedDeveloperReferenceIds.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    entry.relatedLessonIds.forEach { lessonId ->
                        val lessonTitle = LearningCatalog.lesson(lessonId)?.title ?: lessonId
                        TextButton(onClick = { onOpenLesson(lessonId) }) { Text("Lesson: $lessonTitle", fontSize = 11.sp) }
                    }
                    entry.relatedDeveloperReferenceIds.forEach { referenceId ->
                        val referenceTitle = DeveloperReferenceCatalog.entries.firstOrNull { it.id == referenceId }?.title ?: referenceId
                        TextButton(onClick = onOpenDeveloperReference) { Text("Reference: $referenceTitle", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
