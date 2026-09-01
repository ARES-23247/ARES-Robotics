package com.ares.analytics.ui.components.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.areslib.catalog.ActionDescriptor

/**
 * Shared project-action browser used by TeleOp bindings and autonomous routines.
 *
 * Both editors resolve the same canonical action catalog, so search, grouping, missing-reference
 * handling, and accessibility belong here rather than in either feature.
 */
@Composable
internal fun AresActionCatalogPicker(
    actions: List<ActionDescriptor>,
    selectedKey: String?,
    modifier: Modifier = Modifier,
    showCatalogSummary: Boolean = false,
    pickerLabel: String = "Catalog action",
    placeholder: String = "Choose an action",
    onSelected: (ActionDescriptor) -> Unit,
) {
    val selected = actions.firstOrNull { it.key == selectedKey }
    val missingSelection = !selectedKey.isNullOrBlank() && selected == null
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val groups = actionBrowserGroups(actions, query)
    val matchCount = groups.sumOf { it.actions.size }

    fun openBrowser() {
        query = ""
        expanded = true
    }

    LaunchedEffect(expanded) {
        if (expanded) searchFocus.requestFocus()
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showCatalogSummary) {
            Text(
                "${actionCatalogSummary(actions)} • .ares/action-catalog.json",
                color = if (actions.isEmpty()) AresGold else AresTextSecondary,
                fontSize = 10.sp,
                modifier = Modifier.semantics {
                    contentDescription = if (actions.isEmpty()) {
                        "No project actions loaded from the action catalog"
                    } else {
                        "${actionCatalogSummary(actions)} loaded from the project action catalog"
                    }
                },
            )
        }
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = ::openBrowser,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = if (selected == null) {
                        "Choose a project action"
                    } else {
                        "Selected action. ${actionAccessibleLabel(selected)}. Open action browser"
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (missingSelection) AresError else AresTextPrimary,
                ),
                border = BorderStroke(1.dp, if (missingSelection) AresError else AresBorder),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(pickerLabel, color = AresTextSecondary, fontSize = 9.sp)
                    Text(
                        selected?.displayName ?: selectedKey.orEmpty().ifBlank { placeholder },
                        color = if (missingSelection) AresError else AresTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    selected?.let {
                        Text(
                            "${it.category.ifBlank { "General" }} • ${it.key}",
                            color = AresTextSecondary,
                            fontSize = 9.sp,
                            maxLines = 1,
                        )
                    }
                }
                Icon(Icons.Default.ArrowDropDown, "Browse all project actions")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.widthIn(min = 380.dp, max = 520.dp),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("Choose an action", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(
                        "All ${actions.size} project actions are shown until you search.",
                        color = AresTextSecondary,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        label = { Text("Search actions") },
                        placeholder = { Text("Try drive, intake, light, or score") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        supportingText = {
                            Text(
                                if (query.isBlank()) "$matchCount actions available" else "$matchCount matching actions",
                                fontSize = 9.sp,
                            )
                        },
                        singleLine = true,
                    )
                }
                if (groups.isEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            if (actions.isEmpty()) "No project actions were loaded." else "No actions match “$query”.",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        )
                        Text(
                            if (actions.isEmpty()) {
                                "Check .ares/action-catalog.json, then reload the project."
                            } else {
                                "Clear the search or try a device, behavior, action, or mechanism."
                            },
                            color = AresTextSecondary,
                            fontSize = 10.sp,
                        )
                        if (query.isNotBlank()) {
                            OutlinedButton(onClick = { query = "" }) { Text("Clear search", fontSize = 10.sp) }
                        }
                    }
                }
                groups.forEachIndexed { index, group ->
                    if (index > 0) HorizontalDivider(color = AresBorder)
                    Text(
                        "${group.category} (${group.actions.size})",
                        color = AresCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    )
                    group.actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(action.displayName, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                                    if (!action.description.isNullOrBlank()) {
                                        Text(action.description, color = AresTextSecondary, fontSize = 9.sp)
                                    }
                                    Text(
                                        "Project catalog • ${action.key}",
                                        color = AresTextSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            },
                            onClick = {
                                query = ""
                                expanded = false
                                onSelected(action)
                            },
                            modifier = Modifier.semantics {
                                contentDescription = actionAccessibleLabel(action)
                            },
                        )
                    }
                }
            }
        }
        if (actions.isEmpty()) {
            Text(
                "No actions are available. Check .ares/action-catalog.json, then reload the project.",
                color = AresGold,
                fontSize = 10.sp,
            )
        } else if (missingSelection) {
            Text(
                "This project references an action that is not in the current catalog: $selectedKey",
                color = AresError,
                fontSize = 10.sp,
            )
        }
    }
}
