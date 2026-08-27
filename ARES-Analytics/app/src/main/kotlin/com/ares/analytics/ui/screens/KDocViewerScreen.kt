package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.help.DeveloperReference
import com.ares.analytics.ui.help.DeveloperReferenceCatalog
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

/**
 * Curated map from common ARES concepts to their current source-of-truth locations.
 *
 * This screen intentionally avoids claiming to be complete generated KDoc or an AI assistant.
 * Students use it to find the owning module, units, invariants, and tests, then verify the live
 * declaration in source.
 */
@Composable
fun KDocViewerScreen() {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var selectedId by remember { mutableStateOf(DeveloperReferenceCatalog.entries.first().id) }
    val matches = remember(query, category) { DeveloperReferenceCatalog.search(query, category) }
    val selected = DeveloperReferenceCatalog.entries.firstOrNull { it.id == selectedId }
        ?: matches.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().background(AresBackground).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = AresSurface),
            border = BorderStroke(1.dp, AresBorder),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Book, contentDescription = null, tint = AresCyan, modifier = Modifier.size(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Developer Reference", color = AresTextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "A curated map to current source, units, invariants, and tests—not generated API documentation.",
                        color = AresTextSecondary,
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.width(330.dp),
                    singleLine = true,
                    placeholder = { Text("Try: pose, clock, hardware reads…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DeveloperReferenceCatalog.categories.forEach { candidate ->
                FilterChip(
                    selected = category == candidate,
                    onClick = { category = candidate },
                    label = { Text(candidate, fontSize = 11.sp) },
                )
            }
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(
                modifier = Modifier.width(330.dp).fillMaxHeight(),
                color = AresSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                if (matches.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "No curated concept matches. Search the workspace source for the exact API name.",
                            color = AresTextSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(matches, key = DeveloperReference::id) { entry ->
                            ReferenceListItem(entry, entry.id == selected?.id) { selectedId = entry.id }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = AresSurface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AresBorder),
            ) {
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Choose a concept to inspect.", color = AresTextSecondary)
                    }
                } else {
                    ReferenceDetail(selected)
                }
            }
        }
    }
}

@Composable
private fun ReferenceListItem(entry: DeveloperReference, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) AresCyanGlow else AresSurfaceElevated,
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.title, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            Text(entry.category, color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(entry.responsibility, color = AresTextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ReferenceDetail(entry: DeveloperReference) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        item {
            Text(entry.category, color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(entry.title, color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 25.sp)
            Spacer(Modifier.height(5.dp))
            Text(entry.responsibility, color = AresTextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
        }
        item { HorizontalDivider(color = AresBorder) }
        item {
            ReferenceSection("Source of truth", Icons.Default.Source) {
                Surface(color = AresBackground, shape = RoundedCornerShape(7.dp)) {
                    Text(
                        entry.sourcePath,
                        modifier = Modifier.fillMaxWidth().padding(11.dp),
                        color = AresGreen,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
                Text("Open this file and read its current KDoc before coding against it.", color = AresTextTertiary, fontSize = 11.sp)
            }
        }
        item {
            ReferenceSection("Units and conventions", Icons.Default.CheckCircle) {
                Text(entry.units, color = AresTextPrimary, lineHeight = 20.sp)
            }
        }
        item {
            ReferenceSection("Invariants to preserve", Icons.Default.CheckCircle) {
                entry.invariants.forEach { invariant ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Text("•", color = AresCyan, fontWeight = FontWeight.Bold)
                        Text(invariant, color = AresTextSecondary, lineHeight = 20.sp)
                    }
                }
            }
        }
        item {
            Surface(
                color = AresAmber.copy(alpha = 0.10f),
                shape = RoundedCornerShape(9.dp),
                border = BorderStroke(1.dp, AresAmber.copy(alpha = 0.55f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Verification starting point", color = AresAmber, fontWeight = FontWeight.Bold)
                    Text(entry.relatedTests, color = AresTextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Text(
                        "A passing nearby test is evidence for the current code. This curated page alone is not.",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferenceSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, contentDescription = null, tint = AresCyan, modifier = Modifier.size(18.dp))
            Text(title, color = AresTextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}
