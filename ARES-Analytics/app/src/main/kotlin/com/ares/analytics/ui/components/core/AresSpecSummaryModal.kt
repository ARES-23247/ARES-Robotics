package com.ares.analytics.ui.components.core

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Data model for a single row in an ARES spec table.
 */
data class AresSpecRow(
    val id: String,
    val primaryLabel: String,
    val secondaryLabel: String? = null,
    val badge: String? = null,
    val columns: List<Pair<String, String>>, // Header to Value
    val onEditClick: (() -> Unit)? = null,
)

/**
 * Data model for a section/tab in an ARES spec summary.
 */
data class AresSpecSection(
    val title: String,
    val icon: ImageVector? = null,
    val rows: List<AresSpecRow>,
    val emptyMessage: String = "No items configured in this section.",
)

/**
 * High-density, multi-category modal dialog for inspecting complete builder specifications at a glance.
 */
@Composable
fun AresSpecSummaryModal(
    isOpen: Boolean,
    title: String,
    subtitle: String,
    sections: List<AresSpecSection>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    rawMarkdownGenerator: (() -> String)? = null,
) {
    if (!isOpen) return

    var selectedSectionIndex by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var copyStatus by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .widthIn(min = 850.dp, max = 1180.dp)
                .heightIn(min = 550.dp, max = 800.dp)
                .semantics { contentDescription = "$title Spec Summary" },
            color = AresSurfaceElevated,
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 24.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AresSurface)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = null, tint = AresCyan, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                                Text(subtitle, color = AresTextSecondary, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (rawMarkdownGenerator != null) {
                                OutlinedButton(
                                    onClick = {
                                        val text = rawMarkdownGenerator()
                                        try {
                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                                            copyStatus = "Copied to clipboard!"
                                        } catch (_: Exception) {
                                            copyStatus = "Failed to copy"
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(copyStatus ?: "Copy Spec Sheet", fontSize = 12.sp, maxLines = 1)
                                }
                            }
                            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = AresTextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Section Tabs & Search Filter
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            sections.forEachIndexed { index, section ->
                                val isSelected = index == selectedSectionIndex
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedSectionIndex = index },
                                    label = { Text("${section.title} (${section.rows.size})") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = AresSurface,
                                        labelColor = AresTextPrimary,
                                        selectedContainerColor = AresCyan,
                                        selectedLabelColor = AresOnAccent,
                                    ),
                                )
                            }
                        }

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Filter spec rows…", fontSize = 12.sp, color = AresTextTertiary) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = AresTextSecondary, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.width(240.dp),
                            singleLine = true,
                        )
                    }
                }

                HorizontalDivider(color = AresBorder)

                // Body table
                val activeSection = sections.getOrNull(selectedSectionIndex) ?: sections.firstOrNull()
                val filteredRows = activeSection?.rows.orEmpty().filter { row ->
                    searchQuery.isBlank() ||
                        row.primaryLabel.contains(searchQuery, ignoreCase = true) ||
                        row.secondaryLabel?.contains(searchQuery, ignoreCase = true) == true ||
                        row.badge?.contains(searchQuery, ignoreCase = true) == true ||
                        row.columns.any { it.first.contains(searchQuery, ignoreCase = true) || it.second.contains(searchQuery, ignoreCase = true) }
                }

                if (activeSection == null || filteredRows.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (searchQuery.isNotBlank()) "No spec rows matching \"$searchQuery\"." else activeSection?.emptyMessage ?: "No data.",
                            color = AresTextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        filteredRows.forEachIndexed { index, row ->
                            AresSpecRowCard(row, isEven = index % 2 == 0)
                        }
                    }
                }

                HorizontalDivider(color = AresBorder)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AresSurface)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${filteredRows.size} total items in active category",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                    ) {
                        Text("Close Overview", color = AresBackground, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AresSpecRowCard(row: AresSpecRow, isEven: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (isEven) AresSurface else AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = row.primaryLabel,
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                    if (row.badge != null) {
                        Surface(
                            color = AresCyan.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.35f)),
                        ) {
                            Text(
                                text = row.badge.uppercase(),
                                color = AresCyan,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (row.secondaryLabel != null) {
                        Text(
                            text = row.secondaryLabel,
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

                if (row.onEditClick != null) {
                    Row(
                        modifier = Modifier
                            .clickable(onClick = row.onEditClick)
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = AresCyan, modifier = Modifier.size(13.dp))
                        Text("Edit", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (row.columns.isNotEmpty()) {
                HorizontalDivider(color = AresBorder.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    row.columns.forEach { (header, value) ->
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(header.uppercase(), color = AresTextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(value, color = AresTextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
