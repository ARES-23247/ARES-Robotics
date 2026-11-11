package com.ares.analytics.ui.components.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.shared.RobotUnit
import com.ares.analytics.shared.UnitCategory
import com.ares.analytics.shared.UnitConversion
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import kotlin.math.*
import kotlin.math.abs
import kotlin.math.max

@Composable
internal fun TelemetryChartHeaderAndToolbar(
    isReplay: Boolean,
    timeWindows: List<Int>,
    selectedWindowSec: Int,
    onSelectWindowSec: (Int) -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: (() -> Unit)?,
    activeTopics: List<String>,
    selectedKeys: List<String>,
    onAddKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    isTreeVisible: Boolean,
    onToggleTreeVisible: () -> Unit,
    channelColors: List<Color>,
    targetUnits: Map<String, RobotUnit>,
    onSelectCategoryUnit: (UnitCategory, RobotUnit) -> Unit,
) {
    var dropdownExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Header row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                if (!isReplay) "Live Telemetry Viewer" else "Replay Telemetry Viewer",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AresTextPrimary,
            )
            Text(
                if (!isReplay) {
                    "Real-time streaming multi-channel scope"
                } else {
                    "Bounded history ending at the selected replay instant"
                },
                style = MaterialTheme.typography.bodySmall,
                color = AresTextTertiary,
            )
        }

        // Window size selector
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            onToggleFullscreen?.let { WidgetFullscreenButton(isFullscreen, it) }
            timeWindows.forEach { sec ->
                FilterChip(
                    selected = selectedWindowSec == sec,
                    onClick = { onSelectWindowSec(sec) },
                    label = { Text("${sec}s", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AresCyan,
                        selectedLabelColor = AresOnAccent,
                        containerColor = AresSurfaceElevated,
                        labelColor = AresTextSecondary,
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selectedWindowSec == sec,
                        borderColor = AresBorder,
                    ),
                )
            }
        }
    }

    HorizontalDivider(color = AresBorder, thickness = 1.dp)

    // Dropdown Search / Add Channel controls
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Button(
                onClick = { dropdownExpanded = true },
                colors = ButtonDefaults.buttonColors(containerColor = AresSurfaceElevated),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Channel", color = AresTextPrimary, fontSize = 12.sp)
            }

            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier
                    .width(300.dp)
                    .background(AresSurfaceElevated)
                    .border(1.dp, AresBorder),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search NT4 topics...", color = AresTextTertiary) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AresCyan,
                        unfocusedBorderColor = AresBorder,
                        focusedTextColor = AresTextPrimary,
                        unfocusedTextColor = AresTextPrimary,
                    ),
                )
                val filteredTopics = activeTopics.filter {
                    it.contains(searchQuery, ignoreCase = true) && !selectedKeys.contains(it)
                }

                if (filteredTopics.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("No matching topics found", color = AresTextTertiary, fontSize = 12.sp) },
                        onClick = {},
                    )
                } else {
                    filteredTopics.take(10).forEach { topic ->
                        DropdownMenuItem(
                            text = { Text(topic, color = AresTextPrimary, fontSize = 12.sp) },
                            onClick = {
                                onAddKey(topic)
                                dropdownExpanded = false
                                searchQuery = ""
                            },
                        )
                    }
                }
            }
        }

        Button(
            onClick = onToggleTreeVisible,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isTreeVisible) AresCyan else AresSurfaceElevated,
                contentColor = if (isTreeVisible) AresOnAccent else AresTextPrimary,
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = if (isTreeVisible) Icons.AutoMirrored.Filled.MenuOpen else Icons.Default.Menu,
                contentDescription = null,
                tint = if (isTreeVisible) AresOnAccent else AresCyan,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (isTreeVisible) "Hide Signals" else "Show Signals",
                color = if (isTreeVisible) AresOnAccent else AresTextPrimary,
                fontSize = 12.sp,
            )
        }

        // Legend chips
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(selectedKeys) { key ->
                val index = selectedKeys.indexOf(key)
                val color = channelColors[index % channelColors.size]
                val detectedUnit = UnitConversion.detectUnitFromKey(key)
                val targetUnit = targetUnits[key] ?: detectedUnit
                var unitMenuExpanded by remember { mutableStateOf(false) }

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AresSurfaceElevated)
                            .border(1.dp, AresBorder, RoundedCornerShape(6.dp))
                            .clickable {
                                if (detectedUnit != null && detectedUnit.category != UnitCategory.NONE) {
                                    unitMenuExpanded = true
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(color),
                        )
                        val label = key.split("/").last()
                        val unitSuffix = targetUnit?.let { " (${it.symbol})" } ?: ""
                        Text(
                            "$label$unitSuffix",
                            color = AresTextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = AresTextTertiary,
                            modifier = Modifier
                                .size(12.dp)
                                .clickable { onRemoveKey(key) },
                        )
                    }

                    if (detectedUnit != null && detectedUnit.category != UnitCategory.NONE) {
                        DropdownMenu(
                            expanded = unitMenuExpanded,
                            onDismissRequest = { unitMenuExpanded = false },
                            modifier = Modifier.background(AresSurfaceElevated).border(1.dp, AresBorder),
                        ) {
                            RobotUnit.entries.filter { it.category == detectedUnit.category }.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text("${unit.name} (${unit.symbol})", color = AresTextPrimary, fontSize = 12.sp) },
                                    onClick = {
                                        onSelectCategoryUnit(detectedUnit.category, unit)
                                        unitMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (telemetryChartGroupCount(selectedKeys) > 1) {
        Text(
            text = "Mixed dimensions use separate, time-aligned Y-axis bands.",
            color = AresTextTertiary,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TelemetryChartEmptyState(
    onSelectTopics: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.Timeline, contentDescription = null, tint = AresCyan, modifier = Modifier.size(26.dp))
            Text(
                text = "No signals plotted yet",
                color = AresTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
            Text(
                text = "Click a Quick-Plot bundle below, or click (+) in the Signal Tree on the left to add individual topics.",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chartStarterBundles.forEach { bundle ->
                    OutlinedButton(
                        onClick = { onSelectTopics(bundle.topics) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AresCyan),
                        shape = RoundedCornerShape(7.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(bundle.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

