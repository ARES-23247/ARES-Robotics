package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.theme.AresThemeSettings

enum class WidgetCategory(val displayName: String) {
    RECOMMENDED("Recommended"),
    LIVE("Live control"),
    ANALYSIS("Analysis"),
    DIAGNOSTICS("Diagnostics"),
    REPLAY("Replay & review"),
    DEVELOPER("Developer tools")
}

data class AvailableWidget(
    val type: String,
    val displayName: String,
    val description: String,
    val icon: ImageVector,
    val category: WidgetCategory,
    val recommended: Boolean = false
)

val availableWidgetsList = listOf(
    AvailableWidget("driver_station", "Driver Station", "Select and run FTC OpModes from the dashboard.", Icons.Default.SportsEsports, WidgetCategory.LIVE, true),
    AvailableWidget("autonomous_selector", "Autonomous Selector", "Arm one of the generated routines compiled into the robot.", Icons.Default.Route, WidgetCategory.LIVE, true),
    AvailableWidget("field_viewer", "Field 2D Viewer", "Live robot pose and trajectory on the game field.", Icons.Default.Map, WidgetCategory.LIVE, true),
    AvailableWidget("telemetry_chart", "Live Telemetry Chart", "Searchable, scrolling multi-channel signal scope.", Icons.AutoMirrored.Filled.ShowChart, WidgetCategory.LIVE, true),
    AvailableWidget("joystick_visualizer", "Gamepad Monitor", "Controller sticks, triggers, buttons, and command shaping.", Icons.Default.Gamepad, WidgetCategory.LIVE),
    AvailableWidget("mecanum_visualizer", "Mecanum Visualizer", "Wheel velocity, current, and traction-force vectors.", Icons.Default.Settings, WidgetCategory.LIVE),
    AvailableWidget("swerve_animator", "Swerve Visualizer", "Target and measured module vectors.", Icons.Default.DirectionsCar, WidgetCategory.LIVE),
    AvailableWidget("mechanism_visualizer", "Linkage Animator", "Arm, slide, and mechanism motion rendering.", Icons.Default.Build, WidgetCategory.LIVE),
    AvailableWidget("camera_stream", "Camera Stream", "Limelight, PhotonVision, or WPILib MJPEG feed.", Icons.Default.Videocam, WidgetCategory.LIVE),
    AvailableWidget("indicator_lights", "Indicator Lights", "Live GoBilda PWM indicator-light state.", Icons.Default.Lightbulb, WidgetCategory.LIVE),

    AvailableWidget("advanced_analytics", "Advanced Analytics", "Regressions, driver score, heatmap, correlations, and tuning confidence.", Icons.Default.Insights, WidgetCategory.ANALYSIS, true),
    AvailableWidget("statistics_panel", "Signal Statistics", "Distributions, descriptive statistics, and error forensics.", Icons.Default.Analytics, WidgetCategory.ANALYSIS),
    AvailableWidget("trends_card", "Battery Trends", "Multi-session degradation and regression trends.", Icons.AutoMirrored.Filled.TrendingDown, WidgetCategory.ANALYSIS),
    AvailableWidget("session_summary", "Session Summary", "Headline metrics for the selected recording.", Icons.Default.Summarize, WidgetCategory.ANALYSIS),
    AvailableWidget("ai_coach", "AI Forensics Coach", "Evidence-backed pit diagnostics and repair guidance.", Icons.Default.Psychology, WidgetCategory.ANALYSIS),
    AvailableWidget("driver_motion_review", "Driver Motion Review", "Practice prompts from timestamp-synchronized chassis motion; never a driver score.", Icons.Default.SportsEsports, WidgetCategory.ANALYSIS),
    AvailableWidget("pit_evidence_checklist", "Pit Evidence Checklist", "Observed telemetry thresholds, possible causes, and verification steps without pretending to diagnose.", Icons.AutoMirrored.Filled.FactCheck, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("vision_quality", "Vision & EKF Quality", "AprilTag acceptance, latency, and estimator quality.", Icons.Default.Camera, WidgetCategory.ANALYSIS),
    AvailableWidget("motor_health", "Motor Health", "Current draw, thermal risk, and stall warnings.", Icons.Default.ElectricBolt, WidgetCategory.ANALYSIS),

    AvailableWidget("system_health", "Dashboard & Robot Health", "Ingest, query, cache, reconnect, loop, and battery health.", Icons.Default.Memory, WidgetCategory.DIAGNOSTICS, true),
    AvailableWidget("alerts", "Live Alerts", "Battery, motor, communications, and sensor warnings.", Icons.Default.Warning, WidgetCategory.DIAGNOSTICS, true),
    AvailableWidget("battery_health", "Battery Diagnostics", "Voltage, state of charge, and brownout risk.", Icons.Default.BatteryChargingFull, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("power_distribution", "Power Distribution", "Current draw by PDP or PDH channel.", Icons.Default.ElectricBolt, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("brownout_protection", "Brownout Protection", "Battery-sag scaling and active protection state.", Icons.Default.BatteryAlert, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("imu_visualizer", "IMU Visualizer", "Roll, pitch, yaw, and attitude health.", Icons.Default.CompassCalibration, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("ekf_telemetry", "EKF Diagnostics", "Estimator drift, innovation, and covariance.", Icons.Default.QueryStats, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("control_profiler", "Control Loop Profiler", "Target-versus-actual mechanism error and timing.", Icons.Default.Speed, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("profiling_diagnostics", "Profiling Diagnostics", "Maximum and average loop/subsystem timings.", Icons.Default.HourglassEmpty, WidgetCategory.DIAGNOSTICS),
    AvailableWidget("hardware_topology", "Hardware Topology", "Interactive CAN and REV hardware map tree with live telemetry.", Icons.Default.Hub, WidgetCategory.DIAGNOSTICS, true),
    AvailableWidget("subsystem_health", "Subsystem Health", "Configuration, feedback, homing, calibration, current, and latched-fault status for every mechanism.", Icons.Default.HealthAndSafety, WidgetCategory.DIAGNOSTICS, true),
    AvailableWidget("state_tracker", "Subsystem State Tracker", "Current subsystem state-machine states.", Icons.Default.AccountTree, WidgetCategory.DIAGNOSTICS),

    AvailableWidget("runs_index", "Recorded Sessions", "Practice runs, match logs, comparisons, and tags.", Icons.Default.History, WidgetCategory.REPLAY, true),
    AvailableWidget("pose_viewer", "Robot Pose Tracker", "Numeric EKF, odometry, and vision pose values.", Icons.Default.MyLocation, WidgetCategory.REPLAY),
    AvailableWidget("match_schedule", "Match Schedule", "TBA/TOA schedule and match association.", Icons.Default.CalendarMonth, WidgetCategory.REPLAY),

    AvailableWidget("console_viewer", "Robot Console", "Live logs with search and severity filtering.", Icons.Default.Terminal, WidgetCategory.DEVELOPER),
    AvailableWidget("tuning_card", "Live Tuning", "Update exposed robot variables over NT4.", Icons.Default.Tune, WidgetCategory.DEVELOPER),
    AvailableWidget("path_tuning", "Path Tuning", "Cross-track and along-track controller error.", Icons.Default.Timeline, WidgetCategory.DEVELOPER)
)

fun filterWidgets(query: String, category: WidgetCategory): List<AvailableWidget> {
    val normalized = query.trim().lowercase()
    return availableWidgetsList.filter { widget ->
        val categoryMatch = if (category == WidgetCategory.RECOMMENDED) widget.recommended else widget.category == category
        val queryMatch = normalized.isEmpty() || widget.displayName.lowercase().contains(normalized) ||
            widget.description.lowercase().contains(normalized) || widget.type.lowercase().contains(normalized)
        categoryMatch && queryMatch
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetPicker(onDismiss: () -> Unit, onSelectWidget: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(WidgetCategory.RECOMMENDED) }
    val results = remember(query, category) { filterWidgets(query, category) }
    val touch = AresThemeSettings.touchOptimizedMode

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add a dashboard widget", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Choose the signal that helps answer your next question.", color = AresTextSecondary, fontSize = 12.sp)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(if (touch) 48.dp else 40.dp)) {
                    Icon(Icons.Default.Close, "Close widget picker", tint = AresTextSecondary)
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().height(560.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search widgets") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") }
                    }) else null
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WidgetCategory.entries.forEach { option ->
                        AssistChip(
                            onClick = { category = option },
                            label = { Text(option.displayName) },
                            leadingIcon = if (category == option) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (category == option) AresCyan.copy(alpha = 0.15f) else AresSurfaceElevated,
                                labelColor = if (category == option) AresCyan else AresTextSecondary
                            ),
                            border = AssistChipDefaults.assistChipBorder(
                                enabled = true,
                                borderColor = if (category == option) AresCyan else AresBorder
                            )
                        )
                    }
                }
                Text("${results.size} ${if (results.size == 1) "widget" else "widgets"}", color = AresTextTertiary, fontSize = 11.sp)
                if (results.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.SearchOff, null, tint = AresTextTertiary, modifier = Modifier.size(40.dp))
                        Text("No widgets match that search", color = AresTextSecondary)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = if (touch) 290.dp else 250.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(results, key = { it.type }) { widget -> WidgetPickerCard(widget, onSelectWidget, onDismiss) }
                    }
                }
            }
        },
        confirmButton = {},
        modifier = Modifier.widthIn(min = 560.dp, max = 1080.dp),
        containerColor = AresSurface,
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun WidgetPickerCard(widget: AvailableWidget, onSelectWidget: (String) -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AresSurfaceElevated)
            .border(1.dp, AresBorder.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .clickable {
                onSelectWidget(widget.type)
                onDismiss()
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(widget.icon, null, tint = AresCyan, modifier = Modifier.size(22.dp))
            Text(widget.displayName, modifier = Modifier.weight(1f), color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (widget.recommended) {
                Text("RECOMMENDED", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(widget.description, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 15.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(widget.category.displayName.uppercase(), color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
