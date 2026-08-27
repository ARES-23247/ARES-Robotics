package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.RobotTopicContract
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Parses either the robot's compact CSV publication or a JSON string array. */
internal fun parseAvailableAutoDocuments(raw: String?): List<String> {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return emptyList()
    val decoded = if (value.startsWith("[")) {
        runCatching { Json.decodeFromString<List<String>>(value) }.getOrDefault(emptyList())
    } else {
        value.split(',')
    }
    return decoded.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
}

/**
 * Arms a generated autonomous entry for either league without changing robot code at runtime.
 *
 * The robot publishes its compiled catalog and locks the requested ID at autonomous init. The
 * card writes both platform request topics so one dashboard layout works for FTC and FRC.
 */
@Composable
fun AutonomousSelectorCard(
    nt4ClientService: Nt4ClientService,
    modifier: Modifier = Modifier
) {
    var available by remember {
        mutableStateOf(parseAvailableAutoDocuments(nt4ClientService.latestValues[RobotTopicContract.AVAILABLE_AUTONOMOUS_ROUTINES]?.stringValue))
    }
    var robotSelected by remember { mutableStateOf(nt4ClientService.latestValues[RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE]?.stringValue.orEmpty()) }
    var requested by remember { mutableStateOf(robotSelected) }
    var status by remember { mutableStateOf(nt4ClientService.latestValues[RobotTopicContract.AUTONOMOUS_STATUS]?.stringValue ?: "Waiting for robot") }
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(nt4ClientService) {
        nt4ClientService.uiTelemetryFlow.collect { frame ->
            when (frame.key) {
                RobotTopicContract.AVAILABLE_AUTONOMOUS_ROUTINES -> {
                    available = parseAvailableAutoDocuments(frame.stringValue)
                    if (requested !in available) requested = robotSelected.takeIf { it in available }.orEmpty()
                }
                RobotTopicContract.SELECTED_AUTONOMOUS_ROUTINE -> {
                    robotSelected = frame.stringValue.orEmpty()
                    if (requested.isEmpty()) requested = robotSelected
                }
                RobotTopicContract.AUTONOMOUS_STATUS -> status = frame.stringValue.orEmpty().ifBlank { "Idle" }
            }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AresSurface)
            .border(1.dp, AresBorder, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Route, contentDescription = null, tint = AresCyan)
            Column {
                Text("Autonomous selector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AresTextPrimary)
                Text("The robot locks this choice when autonomous starts.", color = AresTextSecondary, fontSize = 11.sp)
            }
        }

        Column {
            Text("ROUTINE", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AresSurfaceElevated)
                    .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                    .clickable(enabled = available.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    requested.ifBlank { if (available.isEmpty()) "No compiled routines reported" else "Choose a routine" },
                    modifier = Modifier.weight(1f),
                    color = if (requested.isBlank()) AresTextSecondary else AresTextPrimary,
                    fontFamily = FontFamily.Monospace
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose autonomous routine", tint = AresTextSecondary)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    available.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id, fontFamily = FontFamily.Monospace) },
                            onClick = { requested = id; expanded = false }
                        )
                    }
                }
            }
        }

        Button(
            onClick = {
                val selection = requested
                if (selection.isNotBlank()) scope.launch {
                    nt4ClientService.publishString(RobotTopicContract.FTC_AUTONOMOUS_REQUEST, selection)
                    nt4ClientService.publishString(RobotTopicContract.FRC_AUTONOMOUS_REQUEST, selection)
                    nt4ClientService.publishString(
                        RobotTopicContract.FRC_SMART_DASHBOARD_AUTONOMOUS_REQUEST,
                        selection,
                    )
                }
            },
            enabled = requested.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (requested == robotSelected && requested.isNotBlank()) "Re-arm selection" else "Arm this routine")
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Robot", color = AresTextSecondary, fontSize = 11.sp)
            Text(robotSelected.ifBlank { "Not reported" }, color = if (robotSelected.isBlank()) AresAmber else AresGreen, fontSize = 11.sp)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Status", color = AresTextSecondary, fontSize = 11.sp)
            Text(status, color = AresTextPrimary, fontSize = 11.sp)
        }
    }
}
