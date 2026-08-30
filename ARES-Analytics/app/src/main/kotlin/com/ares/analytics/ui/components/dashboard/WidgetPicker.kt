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
import com.ares.analytics.service.dashboard.DashboardWidgetCategory
import com.ares.analytics.service.dashboard.DashboardWidgetType

typealias WidgetCategory = DashboardWidgetCategory
typealias AvailableWidget = DashboardWidgetDefinition

val availableWidgetsList: List<AvailableWidget>
    get() = DashboardWidgetRegistry.definitions

fun filterWidgets(query: String, category: WidgetCategory): List<AvailableWidget> {
    val normalized = query.trim().lowercase()
    return availableWidgetsList.filter { widget ->
        val categoryMatch = if (category == WidgetCategory.RECOMMENDED) widget.recommended else widget.category == category
        val queryMatch = normalized.isEmpty() || widget.displayName.lowercase().contains(normalized) ||
            widget.description.lowercase().contains(normalized) || widget.type.serializedName.lowercase().contains(normalized)
        categoryMatch && queryMatch
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WidgetPicker(onDismiss: () -> Unit, onSelectWidget: (DashboardWidgetType) -> Unit) {
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
private fun WidgetPickerCard(widget: AvailableWidget, onSelectWidget: (DashboardWidgetType) -> Unit, onDismiss: () -> Unit) {
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
