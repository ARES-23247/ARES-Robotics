package com.ares.analytics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.help.GlossaryCatalog
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

fun filterNavigationTargets(query: String, developerMode: Boolean): List<NavigationTarget> {
    val normalized = query.trim().lowercase()
    return availablePaletteTargets(developerMode).filter { target ->
        normalized.isEmpty() ||
            target.label.lowercase().contains(normalized) ||
            target.groupLabel().lowercase().contains(normalized) ||
            target.searchTerms().any { it.contains(normalized) }
    }
}

@Composable
fun CommandPalette(
    developerMode: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit,
    onOpenGlossaryTerm: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val results = remember(query, developerMode) { filterNavigationTargets(query, developerMode) }
    val glossaryResults = remember(query) {
        if (query.isBlank()) emptyList() else GlossaryCatalog.search(query).take(5)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Go anywhere", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Search by screen, task, or problem. Try “disconnected,” “import log,” or “gamepad.”", color = AresTextSecondary, fontSize = 12.sp)
            }
        },
        text = {
            Column(Modifier.width(580.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    singleLine = true,
                    placeholder = { Text("What do you want to do?") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                HorizontalDivider(color = AresBorder)
                if (results.isEmpty() && glossaryResults.isEmpty()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                        Text("No matching screens", color = AresTextSecondary)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results, key = { it.name }) { target ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onNavigate(target)
                                    onDismiss()
                                }.background(AresSurfaceElevated, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(target.icon, null, tint = AresCyan, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(target.label, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(target.groupLabel(), color = AresTextTertiary, fontSize = 10.sp)
                                }
                                Text("Open", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                        items(glossaryResults, key = { "glossary-${it.term}" }) { entry ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    onOpenGlossaryTerm(entry.term)
                                    onDismiss()
                                }.background(AresSurfaceElevated, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = AresCyan, modifier = Modifier.size(20.dp))
                                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                    Text(entry.term, color = AresTextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        entry.definition,
                                        color = AresTextTertiary,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("Glossary", color = AresTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = AresSurface,
        shape = RoundedCornerShape(14.dp)
    )
}
