package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.ui.components.catalog.AresActionCatalogPicker
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.viewmodel.PathPlannerIntent
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.ares.analytics.viewmodel.routine.GuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.RoutinePreviewAction
import com.ares.analytics.viewmodel.routine.defaultGuidedFirstRoutinePlan
import com.ares.analytics.viewmodel.routine.validateGuidedFirstRoutinePlan
import com.areslib.catalog.*
import com.areslib.routine.*
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs

@Composable
internal fun RoutinePoseEditors(pose: RoutinePose, onChanged: (RoutinePose) -> Unit) {
    val currentHeadingDeg = Math.toDegrees(pose.headingRadians)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
            RoutineDecimalEditor(pose.xMeters, "X", "m", Modifier.weight(1f)) { onChanged(pose.copy(xMeters = it)) }
            RoutineDecimalEditor(pose.yMeters, "Y", "m", Modifier.weight(1f)) { onChanged(pose.copy(yMeters = it)) }
            RoutineDecimalEditor(currentHeadingDeg, "Heading", "°", Modifier.weight(1f)) {
                val wrapped = ((it + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
                onChanged(pose.copy(headingRadians = Math.toRadians(wrapped)))
            }
        }
        // Quick heading preset chips (0°, 90°, 180°, -90°)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Heading:", style = MaterialTheme.typography.labelSmall, color = AresTextSecondary, fontSize = 10.sp)
            listOf(
                0.0 to "0° (→)",
                90.0 to "90° (↑)",
                180.0 to "180° (←)",
                -90.0 to "-90° (↓)"
            ).forEach { (deg, label) ->
                val isSelected = Math.abs(((currentHeadingDeg - deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0) < 1.0
                Surface(
                    color = if (isSelected) AresCyan.copy(alpha = 0.2f) else AresSurfaceElevated,
                    border = BorderStroke(1.dp, if (isSelected) AresCyan else AresBorder),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.clickable {
                        onChanged(pose.copy(headingRadians = Math.toRadians(deg)))
                    }
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) AresCyan else AresTextSecondary,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun ParameterEditors(
    descriptors: List<CapabilityParameterDescriptor>,
    arguments: Map<String, String>,
    issues: List<RoutineValidationIssue> = emptyList(),
    onChanged: (Map<String, String>) -> Unit
) {
    descriptors.forEach { descriptor ->
        val value = arguments[descriptor.key] ?: descriptor.initialArgumentValue().orEmpty()
        val fieldError = issues.firstOrNull {
            it.severity == RoutineValidationSeverity.ERROR && it.path.endsWith(".arguments.${descriptor.key}")
        }
        when (descriptor.type) {
            CapabilityParameterType.BOOLEAN -> Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(value == "true", { checked -> onChanged(arguments + (descriptor.key to checked.toString())) })
                    Spacer(Modifier.width(8.dp)); Column { Text(descriptor.displayName); Text(descriptor.description, style = MaterialTheme.typography.labelSmall, color = AresTextSecondary) }
                }
                fieldError?.let { Text(it.message, color = AresError, style = MaterialTheme.typography.labelSmall) }
            }
            CapabilityParameterType.ENUM -> EnumParameterPicker(descriptor, value) { onChanged(arguments + (descriptor.key to it)) }
            CapabilityParameterType.NUMBER,
            CapabilityParameterType.TEXT -> {
                val unit = descriptor.unit
                OutlinedTextField(
                    value = value,
                    onValueChange = { onChanged(arguments + (descriptor.key to it)) },
                    label = { Text(descriptor.displayName) },
                    supportingText = { Text(descriptor.description) },
                    isError = fieldError != null,
                    suffix = if (unit == null) null else ({ Text(unit) }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = routineTextFieldColors()
                )
            }
        }
    }
}

@Composable
internal fun EnumParameterPicker(descriptor: CapabilityParameterDescriptor, value: String, onChanged: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { Text("${descriptor.displayName}: $value"); Text(descriptor.description, style = MaterialTheme.typography.labelSmall) }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            descriptor.options.forEach { option -> DropdownMenuItem({ Text(option) }, { onChanged(option); expanded = false }) }
        }
    }
}

@Composable
internal fun DriveActionList(label: String, keys: List<String>, actions: List<ActionDescriptor>, onChanged: (List<String>) -> Unit) {
    AresActionCatalogPicker(
        actions = actions,
        selectedKey = null,
        pickerLabel = label,
        placeholder = label,
    ) { selected -> if (selected.key !in keys) onChanged(keys + selected.key) }
    keys.forEach { key ->
        val descriptor = actions.firstOrNull { it.key == key }
        val missing = descriptor == null
        Row(Modifier.fillMaxWidth().background(AresBackground.copy(alpha = .4f), RoundedCornerShape(6.dp)).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (missing) {
                Icon(Icons.Default.ErrorOutline, "Missing project action", tint = AresError, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                descriptor?.displayName ?: "Missing action: $key",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = if (missing) AresError else AresTextPrimary,
            )
            IconButton({ onChanged(keys - key) }, Modifier.size(30.dp)) { Icon(Icons.Default.Delete, "Remove", tint = AresError, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
internal fun DriveMechanismActionsEditor(
    drive: RoutineDriveStep,
    actions: List<ActionDescriptor>,
    onChanged: (RoutineDriveStep) -> Unit,
) {
    val configuredCount = drive.markers.size + drive.duringActionKeys.size + drive.arrivalActionKeys.size
    var expanded by remember(configuredCount) { mutableStateOf(configuredCount > 0) }
    Surface(
        color = AresBackground.copy(alpha = .30f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, AresBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.PrecisionManufacturing, null, tint = AresCyan, modifier = Modifier.size(17.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mechanism actions", style = MaterialTheme.typography.labelLarge, color = AresTextPrimary)
                    Text(
                        when {
                            configuredCount > 0 -> "$configuredCount configured for this drive"
                            actions.isEmpty() -> "Add a subsystem in Robot Studio to unlock named actions"
                            else -> "Optional progress, during-motion, and arrival actions"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (actions.isEmpty() && configuredCount == 0) AresTextSecondary else AresCyan,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse mechanism actions" else "Expand mechanism actions",
                    tint = AresTextSecondary,
                )
            }
            if (expanded) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (actions.isEmpty() && configuredCount == 0) {
                        Text(
                            "Create and save a mechanism in Robot Studio, then Save & Generate. Return here to select its typed actions—no action IDs need to be typed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AresTextSecondary,
                        )
                    } else {
                        DriveMarkerEditor(drive.markers, actions) {
                            onChanged(drive.copy(markers = it))
                        }
                        DriveActionList("Run while driving", drive.duringActionKeys, actions) {
                            onChanged(drive.copy(duringActionKeys = it))
                        }
                        DriveActionList("Run on arrival", drive.arrivalActionKeys, actions) {
                            onChanged(drive.copy(arrivalActionKeys = it))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DriveMarkerEditor(
    markers: List<RoutineDriveMarker>,
    actions: List<ActionDescriptor>,
    onChanged: (List<RoutineDriveMarker>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Progress actions", style = MaterialTheme.typography.labelMedium, color = AresTextPrimary)
        Text(
            "Trigger a named robot action once when this drive reaches a chosen percentage.",
            style = MaterialTheme.typography.labelSmall,
            color = AresTextSecondary,
        )
        AresActionCatalogPicker(
            actions = actions,
            selectedKey = null,
            pickerLabel = "Progress action",
            placeholder = "Add progress action",
        ) { action ->
            onChanged(markers + RoutineDriveMarker(progress = 0.5, actionKey = action.key))
        }
        markers.forEachIndexed { index, marker ->
            val descriptor = actions.firstOrNull { it.key == marker.actionKey }
            Surface(
                color = AresBackground.copy(alpha = .4f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, if (descriptor == null) AresError else AresBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AresActionCatalogPicker(actions, marker.actionKey) { action ->
                        onChanged(markers.mapIndexed { markerIndex, existing ->
                            if (markerIndex == index) existing.copy(actionKey = action.key) else existing
                        })
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RoutineDecimalEditor(
                            value = marker.progress * 100.0,
                            label = "Trigger at",
                            suffix = "%",
                            modifier = Modifier.weight(1f),
                        ) { percentage ->
                            onChanged(markers.mapIndexed { markerIndex, existing ->
                                if (markerIndex == index) {
                                    existing.copy(progress = (percentage / 100.0).coerceIn(0.0, 1.0))
                                } else {
                                    existing
                                }
                            })
                        }
                        IconButton(
                            onClick = { onChanged(markers.filterIndexed { markerIndex, _ -> markerIndex != index }) },
                        ) {
                            Icon(Icons.Default.Delete, "Remove progress action", tint = AresError)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MotionPresetPicker(selected: String, onSelected: (String) -> Unit) {
    val presets = listOf("safe", "balanced", "fast", "adaptive")
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Motion: ${selected.replaceFirstChar(Char::uppercase)}"); Spacer(Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, { expanded = false }) {
            presets.forEach { preset -> DropdownMenuItem({ Text(preset.replaceFirstChar(Char::uppercase)) }, { onSelected(preset); expanded = false }) }
        }
    }
}

@Composable
internal fun RoutineDecimalEditor(
    value: Double,
    label: String,
    suffix: String,
    modifier: Modifier = Modifier,
    onChanged: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatRoutineNumber(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = formatRoutineNumber(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            updated.toDoubleOrNull()?.takeIf(Double::isFinite)?.let(onChanged)
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        colors = routineTextFieldColors(),
    )
}

@Composable
internal fun HeadingModePicker(
    drive: RoutineDriveStep,
    onChanged: (RoutineDriveStep) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val modeLabel = when (drive.headingMode) {
        RoutineHeadingMode.LINEAR -> "Linear to goal"
        RoutineHeadingMode.CONSTANT -> "Constant heading"
        RoutineHeadingMode.TANGENTIAL -> "Tangential (forward)"
        RoutineHeadingMode.TANGENTIAL_REVERSE -> "Tangential (reverse)"
        RoutineHeadingMode.POINT_TOWARDS -> "Point towards (X, Y)"
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Heading mode: $modeLabel")
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded, { expanded = false }) {
                listOf(
                    RoutineHeadingMode.LINEAR to "Linear to goal (interpolate start → target heading)",
                    RoutineHeadingMode.CONSTANT to "Constant heading (hold start heading)",
                    RoutineHeadingMode.TANGENTIAL to "Tangential (face curve direction)",
                    RoutineHeadingMode.TANGENTIAL_REVERSE to "Tangential reverse (face opposite curve direction)",
                    RoutineHeadingMode.POINT_TOWARDS to "Point towards field coordinate / AprilTag (X, Y)",
                ).forEach { (mode, description) ->
                    DropdownMenuItem(
                        text = { Text(description) },
                        onClick = {
                            val updatedLookAt = if (mode == RoutineHeadingMode.POINT_TOWARDS) {
                                drive.lookAtPoint ?: RoutinePose(drive.target.xMeters, drive.target.yMeters, 0.0)
                            } else {
                                drive.lookAtPoint
                            }
                            onChanged(drive.copy(headingMode = mode, lookAtPoint = updatedLookAt))
                            expanded = false
                        },
                    )
                }
            }
        }
        if (drive.headingMode == RoutineHeadingMode.POINT_TOWARDS) {
            val point = drive.lookAtPoint ?: RoutinePose(drive.target.xMeters, drive.target.yMeters, 0.0)
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                RoutineDecimalEditor(point.xMeters, "Focus X", "m", Modifier.weight(1f)) { x ->
                    onChanged(drive.copy(lookAtPoint = point.copy(xMeters = x)))
                }
                RoutineDecimalEditor(point.yMeters, "Focus Y", "m", Modifier.weight(1f)) { y ->
                    onChanged(drive.copy(lookAtPoint = point.copy(yMeters = y)))
                }
            }
        }
    }
}

@Composable
internal fun BlendHandoffToggle(
    blendHandoff: Boolean,
    onChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AresBackground.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .border(1.dp, if (blendHandoff) AresCyan.copy(alpha = 0.6f) else AresBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Blend into next step (pathSkip)",
                style = MaterialTheme.typography.labelMedium,
                color = if (blendHandoff) AresCyan else AresTextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Hands off momentum using Foresight stopping-pose lookahead instead of braking to a full stop.",
                style = MaterialTheme.typography.labelSmall,
                color = AresTextSecondary,
            )
        }
        Switch(
            checked = blendHandoff,
            onCheckedChange = onChanged,
        )
    }
}
