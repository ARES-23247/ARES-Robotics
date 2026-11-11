package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DashboardLayoutConfig
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.service.dashboard.DashboardWidgetCapability
import com.ares.analytics.service.dashboard.DashboardWidgetCatalog
import com.ares.analytics.service.dashboard.DashboardWidgetCategory
import com.ares.analytics.service.dashboard.DashboardWidgetPropertyKind
import com.ares.analytics.service.dashboard.DashboardWidgetPropertySpec
import com.ares.analytics.service.dashboard.DashboardWidgetServiceGroup
import com.ares.analytics.service.dashboard.DashboardWidgetSpec
import com.ares.analytics.service.dashboard.DashboardWidgetType
import com.ares.analytics.service.dashboard.allows
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.DashboardState
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.tuning.TuningParameterDeclaration

data class CommonTelemetrySignalPreset(
    val id: String,
    val label: String,
    val topic: String,
    val unit: String,
    val description: String,
    val minimum: Double,
    val maximum: Double,
    val warningLow: Double? = null,
    val warningHigh: Double? = null,
    val defaultDisplayMode: String = "bar",
)

val commonTelemetrySignalPresets: List<CommonTelemetrySignalPreset> = listOf(
    CommonTelemetrySignalPreset(
        id = "loop-time",
        label = "Loop Cycle Time",
        topic = "Robot/LoopTimeMs",
        unit = "ms",
        description = "Control loop execution period (target ≤ 20 ms)",
        minimum = 0.0,
        maximum = 40.0,
        warningHigh = 25.0,
        defaultDisplayMode = "bar",
    ),
    CommonTelemetrySignalPreset(
        id = "battery-voltage",
        label = "Battery Voltage",
        topic = "Robot/BatteryVoltage",
        unit = "V",
        description = "Main battery voltage under load",
        minimum = 8.0,
        maximum = 14.0,
        warningLow = 11.2,
        defaultDisplayMode = "bar",
    ),
    CommonTelemetrySignalPreset(
        id = "pose-x",
        label = "Robot Field X",
        topic = "Drive/Pose_X",
        unit = "m",
        description = "Estimated forward field position (+X)",
        minimum = -9.0,
        maximum = 9.0,
        defaultDisplayMode = "value",
    ),
    CommonTelemetrySignalPreset(
        id = "pose-y",
        label = "Robot Field Y",
        topic = "Drive/Pose_Y",
        unit = "m",
        description = "Estimated lateral field position (+Y)",
        minimum = -5.0,
        maximum = 5.0,
        defaultDisplayMode = "value",
    ),
    CommonTelemetrySignalPreset(
        id = "pose-heading",
        label = "Robot Heading",
        topic = "Drive/Pose_Heading",
        unit = "rad",
        description = "CCW-positive heading angle in radians",
        minimum = -3.15,
        maximum = 3.15,
        defaultDisplayMode = "value",
    ),
    CommonTelemetrySignalPreset(
        id = "brownout-count",
        label = "Brownout Count",
        topic = "Robot/BrownoutCount",
        unit = "",
        description = "Cumulative voltage brownout events",
        minimum = 0.0,
        maximum = 10.0,
        warningHigh = 1.0,
        defaultDisplayMode = "value",
    ),
)

data class ChartStarterBundle(
    val id: String,
    val title: String,
    val subtitle: String,
    val topics: List<String>,
)

val chartStarterBundles: List<ChartStarterBundle> = listOf(
    ChartStarterBundle(
        id = "health",
        title = "Loop Time & Battery",
        subtitle = "Robot/LoopTimeMs + Robot/BatteryVoltage",
        topics = listOf("Robot/LoopTimeMs", "Robot/BatteryVoltage"),
    ),
    ChartStarterBundle(
        id = "pose",
        title = "Robot Pose (X, Y, Heading)",
        subtitle = "Drive/Pose_X, Drive/Pose_Y, Drive/Pose_Heading",
        topics = listOf("Drive/Pose_X", "Drive/Pose_Y", "Drive/Pose_Heading"),
    ),
    ChartStarterBundle(
        id = "currents",
        title = "Drive Wheel Currents",
        subtitle = "Drive/FL_Current .. Drive/BR_Current",
        topics = listOf("Drive/FL_Current", "Drive/FR_Current", "Drive/BL_Current", "Drive/BR_Current"),
    ),
)

fun applySignalPreset(preset: CommonTelemetrySignalPreset): SingleSignalConfiguration =
    SingleSignalConfiguration(
        topic = normalizeDashboardTopic(preset.topic),
        label = preset.label,
        unit = preset.unit,
        displayMode = preset.defaultDisplayMode,
        minimum = preset.minimum,
        maximum = preset.maximum,
        warningLow = preset.warningLow,
        warningHigh = preset.warningHigh,
    )

fun filterTelemetryTopicSuggestions(
    topics: Collection<String>,
    query: String,
    limit: Int = 8,
): List<String> {
    val normalizedQuery = query.trim().lowercase()
    return topics
        .asSequence()
        .map(::normalizeDashboardTopic)
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .filter { topic ->
            normalizedQuery.isEmpty() || topic.lowercase().contains(normalizedQuery)
        }
        .take(limit)
        .toList()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TelemetryTopicQuickPickerSection(
    selectedTopic: String,
    topicSuggestions: Collection<String>,
    onSelectPreset: (CommonTelemetrySignalPreset) -> Unit,
    onSelectTopic: (String) -> Unit,
) {
    var topicFilter by remember { mutableStateOf("") }
    val filteredSuggestions = remember(topicSuggestions, topicFilter) {
        filterTelemetryTopicSuggestions(topicSuggestions, topicFilter, limit = 8)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Quick-Pick Common Robot Signal (auto-fills label, unit & bounds)",
            color = AresTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            commonTelemetrySignalPresets.forEach { preset ->
                val isSelected = normalizeDashboardTopic(selectedTopic).equals(preset.topic, ignoreCase = true)
                OutlinedButton(
                    onClick = { onSelectPreset(preset) },
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    border = BorderStroke(1.dp, if (isSelected) AresGreen else AresCyan.copy(alpha = 0.55f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) AresGreen.copy(alpha = 0.12f) else AresSurfaceElevated,
                        contentColor = if (isSelected) AresGreen else AresTextPrimary,
                    ),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = if (preset.unit.isNotBlank()) "${preset.label} (${preset.unit})" else preset.label,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (topicSuggestions.isNotEmpty()) {
            Surface(
                color = AresSurfaceElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, AresBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Active Topics from Current Session (${topicSuggestions.size})",
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (topicSuggestions.size > 4) {
                        AresTextField(
                            value = topicFilter,
                            onValueChange = { topicFilter = it },
                            label = "Filter active topics",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (filteredSuggestions.isEmpty()) {
                        Text("No active topics match '$topicFilter'", color = AresTextTertiary, fontSize = 10.sp)
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            filteredSuggestions.forEach { suggestion ->
                                OutlinedButton(
                                    onClick = { onSelectTopic(suggestion) },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    border = BorderStroke(1.dp, AresBorder),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        text = suggestion,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = AresCyan,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
