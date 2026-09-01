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
internal fun FirstRoutineGuideCard(
    projectSelected: Boolean,
    onStart: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresCyan.copy(alpha = .10f)),
        border = BorderStroke(1.dp, AresCyan),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("New to autonomous routines?", fontWeight = FontWeight.Bold, color = AresTextPrimary)
            Text(
                "Build one short simulator-first drive with a starting pose, a field goal, the Safe motion preset, and an explicit review boundary.",
                style = MaterialTheme.typography.bodySmall,
                color = AresTextSecondary,
            )
            Button(
                onClick = onStart,
                enabled = projectSelected,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) {
                Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Start guided first routine")
            }
            if (!projectSelected) {
                Text(
                    "Choose a robot project first so validation uses the correct league, field, and generated catalog.",
                    color = AresGold,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
internal fun FirstRoutineGuideDialog(
    league: League,
    dimensions: RobotDimensions,
    onDismiss: () -> Unit,
    onApply: (GuidedFirstRoutinePlan) -> Unit,
) {
    val initial = remember(league, dimensions) { defaultGuidedFirstRoutinePlan(league, dimensions) }
    var page by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf(initial.name) }
    var startX by remember { mutableStateOf(formatRoutineNumber(initial.startingPose.xMeters)) }
    var startY by remember { mutableStateOf(formatRoutineNumber(initial.startingPose.yMeters)) }
    var startHeading by remember { mutableStateOf(formatRoutineNumber(Math.toDegrees(initial.startingPose.headingRadians))) }
    var targetX by remember { mutableStateOf(formatRoutineNumber(initial.targetPose.xMeters)) }
    var targetY by remember { mutableStateOf(formatRoutineNumber(initial.targetPose.yMeters)) }
    var targetHeading by remember { mutableStateOf(formatRoutineNumber(Math.toDegrees(initial.targetPose.headingRadians))) }
    var alliance by remember { mutableStateOf(initial.authoredAlliance) }
    var mirror by remember { mutableStateOf(initial.mirrorForOppositeAlliance) }
    var previewCommitment by remember { mutableStateOf(false) }

    fun number(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
    fun headingRadians(text: String): Double? = number(text)?.let { degrees ->
        val wrapped = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        Math.toRadians(wrapped)
    }
    val parsedPlan = remember(name, startX, startY, startHeading, targetX, targetY, targetHeading, alliance, mirror) {
        val sx = number(startX)
        val sy = number(startY)
        val sh = headingRadians(startHeading)
        val tx = number(targetX)
        val ty = number(targetY)
        val th = headingRadians(targetHeading)
        if (listOf(sx, sy, sh, tx, ty, th).any { it == null }) null else GuidedFirstRoutinePlan(
            name = name,
            startingPose = RoutinePose(sx!!, sy!!, sh!!),
            targetPose = RoutinePose(tx!!, ty!!, th!!),
            authoredAlliance = alliance,
            mirrorForOppositeAlliance = mirror,
        )
    }
    val validationErrors = remember(parsedPlan, league, dimensions) {
        parsedPlan?.let { validateGuidedFirstRoutinePlan(it, league, dimensions) }
            ?: listOf("Use finite numbers for every position and heading field.")
    }
    val pageReady = when (page) {
        0 -> name.isNotBlank() && name.trim().length <= 80
        1 -> validationErrors.isEmpty()
        2 -> previewCommitment
        else -> validationErrors.isEmpty() && previewCommitment
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("First routine guide")
                Text("Step ${page + 1} of 4", style = MaterialTheme.typography.labelMedium, color = AresTextSecondary)
            }
        },
        text = {
            Column(
                Modifier.fillMaxWidth().widthIn(max = 680.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (page) {
                    0 -> {
                        Text("1 · Name the purpose", fontWeight = FontWeight.Bold)
                        Text(
                            "This guide creates one unsaved match-autonomous draft. It does not generate code or command a robot.",
                            color = AresTextSecondary,
                        )
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Routine name") },
                            supportingText = { Text("Use a name that describes the intended move, not a match result.") },
                            isError = name.isBlank() || name.trim().length > 80,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = routineTextFieldColors(),
                        )
                    }
                    1 -> {
                        Text("2 · Place the robot and goal", fontWeight = FontWeight.Bold)
                        Text(
                            "Coordinates are meters; heading is degrees counter-clockwise. The whole robot footprint must stay inside the ${league.name} field.",
                            color = AresTextSecondary,
                        )
                        GuidedPoseTextEditors("Starting pose", startX, startY, startHeading, { startX = it }, { startY = it }, { startHeading = it })
                        GuidedPoseTextEditors("Drive goal", targetX, targetY, targetHeading, { targetX = it }, { targetY = it }, { targetHeading = it })
                        validationErrors.forEach { error ->
                            Text("Needs attention: $error", color = AresError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    2 -> {
                        Text("3 · Choose match behavior", fontWeight = FontWeight.Bold)
                        Text(
                            "The guide fixes motion to the conservative Safe preset. That limits the preview; it does not calibrate traction or prove clearance.",
                            color = AresTextSecondary,
                        )
                        RoutineAlliancePicker(alliance) { alliance = it }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = mirror, onCheckedChange = { mirror = it })
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Mirror for the opposite alliance")
                                Text("ARES mirrors the authored starting pose and route at runtime.", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary)
                            }
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = previewCommitment, onCheckedChange = { previewCommitment = it })
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "I will inspect the field preview for boundaries and obstacles before saving. I understand simulation does not prove physical safety.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!previewCommitment) {
                            Text("Review required: confirm the preview and physical-safety boundary before continuing.", color = AresGold, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> {
                        Text("4 · Review the draft", fontWeight = FontWeight.Bold)
                        Text("Name: ${name.ifBlank { "Not entered" }}")
                        Text("Start: $startX m, $startY m, $startHeading°")
                        Text("Goal: $targetX m, $targetY m, $targetHeading°")
                        Text("Motion: Safe · Authored for ${alliance.name.lowercase()} · Mirror: ${if (mirror) "yes" else "no"}")
                        HorizontalDivider(color = AresBorder)
                        Text(
                            "Apply creates an unsaved .aresroutine draft and autonomous-catalog entry in memory. Review the canvas and validation card; only Save & Generate writes canonical files.",
                            color = AresTextSecondary,
                        )
                        Text(
                            "A generated, simulated routine still requires supervised field-clearance and restrained-hardware validation before physical use.",
                            color = AresGold,
                            fontWeight = FontWeight.SemiBold,
                        )
                        validationErrors.forEach { error ->
                            Text("Needs attention: $error", color = AresError, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (page < 3) page++ else parsedPlan?.let(onApply)
                },
                enabled = pageReady,
                colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            ) { Text(if (page < 3) "Continue" else "Apply unsaved draft") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page > 0) TextButton(onClick = { page-- }) { Text("Back") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun GuidedPoseTextEditors(
    title: String,
    x: String,
    y: String,
    heading: String,
    onX: (String) -> Unit,
    onY: (String) -> Unit,
    onHeading: (String) -> Unit,
) {
    Text(title, fontWeight = FontWeight.SemiBold, color = AresTextPrimary)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GuidedNumberField(x, "X", "m", onX, Modifier.fillMaxWidth())
        GuidedNumberField(y, "Y", "m", onY, Modifier.fillMaxWidth())
        GuidedNumberField(heading, "Heading", "°", onHeading, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GuidedNumberField(
    value: String,
    label: String,
    unit: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    val valid = value.trim().toDoubleOrNull()?.isFinite() == true
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = { Text(unit) },
        isError = !valid,
        supportingText = if (valid) null else ({ Text("Enter a number") }),
        singleLine = true,
        modifier = modifier,
        colors = routineTextFieldColors(),
    )
}

