@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.components.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary

@Composable
fun RunFilterHeader(
    isAiAnalystOpen: Boolean,
    onToggleAiAnalyst: () -> Unit,
    isCohortExplorerOpen: Boolean = false,
    onToggleCohortExplorer: () -> Unit = {},
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    availableTags: List<String> = emptyList(),
    selectedTagFilter: String? = null,
    onSelectTagFilter: (String?) -> Unit = {},
    visibleRunCount: Int? = null,
    totalRunCount: Int? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val countSuffix = if (visibleRunCount != null && totalRunCount != null) {
                    if (visibleRunCount == totalRunCount) " ($totalRunCount runs)"
                    else " ($visibleRunCount of $totalRunCount runs)"
                } else ""
                Text(
                    "Robot Run History & Cohort Analytics$countSuffix",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary,
                )
                Text(
                    "Click any metric row to plot its timeline, or click any Run # column header to edit Match #, Alliance, Tags, or jump to Replay / Notebook.",
                    fontSize = 12.sp,
                    color = AresTextSecondary,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isCohortExplorerOpen) {
                    Button(
                        onClick = onToggleCohortExplorer,
                        colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Insights, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cohort Analytics", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(
                        onClick = onToggleCohortExplorer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresGreen),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.Insights, contentDescription = null, tint = AresGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Cohort Analytics", color = AresGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                if (isAiAnalystOpen) {
                    OutlinedButton(
                        onClick = onToggleAiAnalyst,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, AresCyan),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AresCyan, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Hide AI Analyst", color = AresCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onToggleAiAnalyst,
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("AI Run Analyst", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.width(300.dp),
                singleLine = true,
                placeholder = { Text("Filter by Match #, tag, alliance, ID…", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(14.dp))
                        }
                    }
                } else null,
            )

            if (availableTags.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = selectedTagFilter == null,
                        onClick = { onSelectTagFilter(null) },
                        label = { Text("All tags", fontSize = 11.sp) },
                    )
                    availableTags.take(12).forEach { tag ->
                        val active = selectedTagFilter?.equals(tag, ignoreCase = true) == true
                        FilterChip(
                            selected = active,
                            onClick = { onSelectTagFilter(if (active) null else tag) },
                            label = { Text(tag, fontSize = 11.sp) },
                        )
                    }
                }
            }
        }
    }
}
