package com.ares.analytics.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputComponent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.navigation.NavigationTarget
import com.ares.analytics.domain.navigation.PitOperationsSubTab
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresCyanGlow
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
fun CommandPalette(
    developerMode: Boolean,
    projectPath: String = "",
    projectItems: List<CommandPaletteProjectItem>? = null,
    recentTargets: List<NavigationTarget> = emptyList(),
    pitTabItems: List<CommandPalettePitTabItem> = defaultPitOperationsTabItems(),
    batteryItems: List<CommandPaletteBatteryItem> = emptyList(),
    matchItems: List<CommandPaletteMatchItem> = emptyList(),
    runItems: List<CommandPaletteRunItem> = emptyList(),
    notebookItems: List<CommandPaletteNotebookItem> = emptyList(),
    onDismiss: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit,
    onOpenGlossaryTerm: (String) -> Unit = {},
    onOpenRun: (String) -> Unit = {},
    onOpenNotebookEntry: (String) -> Unit = {},
    onOpenProjectItem: ((CommandPaletteProjectItem) -> Unit)? = null,
    onOpenPitTab: (PitOperationsSubTab) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
    onOpenBattery: (String) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
    onOpenScheduledMatch: (Int) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Go anywhere", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    "Jump to screens, recent runs, notebook entries, battery packs (BAT-xx), matches, pit tabs, subsystems, routines, or glossary terms.",
                    color = AresTextSecondary,
                    fontSize = 12.sp,
                )
            }
        },
        text = {
            CommandPaletteSurfaceContent(
                developerMode = developerMode,
                projectPath = projectPath,
                projectItems = projectItems,
                recentTargets = recentTargets,
                pitTabItems = pitTabItems,
                batteryItems = batteryItems,
                matchItems = matchItems,
                runItems = runItems,
                notebookItems = notebookItems,
                onDismiss = onDismiss,
                onNavigate = onNavigate,
                onOpenGlossaryTerm = onOpenGlossaryTerm,
                onOpenRun = onOpenRun,
                onOpenNotebookEntry = onOpenNotebookEntry,
                onOpenProjectItem = onOpenProjectItem,
                onOpenPitTab = onOpenPitTab,
                onOpenBattery = onOpenBattery,
                onOpenScheduledMatch = onOpenScheduledMatch,
            )
        },
        confirmButton = {},
        containerColor = AresSurface,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
internal fun CommandPaletteSurfaceContent(
    developerMode: Boolean,
    projectPath: String = "",
    projectItems: List<CommandPaletteProjectItem>? = null,
    recentTargets: List<NavigationTarget> = emptyList(),
    pitTabItems: List<CommandPalettePitTabItem> = defaultPitOperationsTabItems(),
    batteryItems: List<CommandPaletteBatteryItem> = emptyList(),
    matchItems: List<CommandPaletteMatchItem> = emptyList(),
    runItems: List<CommandPaletteRunItem> = emptyList(),
    notebookItems: List<CommandPaletteNotebookItem> = emptyList(),
    onDismiss: () -> Unit,
    onNavigate: (NavigationTarget) -> Unit,
    onOpenGlossaryTerm: (String) -> Unit = {},
    onOpenRun: (String) -> Unit = {},
    onOpenNotebookEntry: (String) -> Unit = {},
    onOpenProjectItem: ((CommandPaletteProjectItem) -> Unit)? = null,
    onOpenPitTab: (PitOperationsSubTab) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
    onOpenBattery: (String) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
    onOpenScheduledMatch: (Int) -> Unit = { onNavigate(NavigationTarget.PIT_DIAGNOSTICS) },
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val allProjectItems = remember(projectPath, projectItems) {
        projectItems ?: discoverProjectCommandPaletteItems(projectPath)
    }
    val entries = remember(
        query,
        developerMode,
        recentTargets,
        allProjectItems,
        pitTabItems,
        batteryItems,
        matchItems,
        runItems,
        notebookItems,
    ) {
        buildUnifiedCommandPaletteEntries(
            query = query,
            developerMode = developerMode,
            recentTargets = recentTargets,
            projectItems = allProjectItems,
            pitTabItems = pitTabItems,
            batteryItems = batteryItems,
            matchItems = matchItems,
            runItems = runItems,
            notebookItems = notebookItems,
        )
    }

    LaunchedEffect(query, entries.size) {
        selectedIndex = 0
        if (entries.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(selectedIndex, entries.size) {
        if (entries.isNotEmpty() && selectedIndex in entries.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val activateEntry: (CommandPaletteUnifiedEntry) -> Unit = { entry ->
        when (entry) {
            is CommandPaletteUnifiedEntry.RecentDestination -> onNavigate(entry.target)
            is CommandPaletteUnifiedEntry.PitTab -> onOpenPitTab(entry.item.tab)
            is CommandPaletteUnifiedEntry.BatteryPack -> onOpenBattery(entry.item.batteryId)
            is CommandPaletteUnifiedEntry.ScheduledMatch -> onOpenScheduledMatch(entry.item.matchNumber)
            is CommandPaletteUnifiedEntry.RunReplay -> onOpenRun(entry.item.sessionId)
            is CommandPaletteUnifiedEntry.NotebookNote -> onOpenNotebookEntry(entry.item.entryId)
            is CommandPaletteUnifiedEntry.ProjectEntity -> {
                if (onOpenProjectItem != null) onOpenProjectItem(entry.item)
                else onNavigate(entry.item.target)
            }
            is CommandPaletteUnifiedEntry.ScreenDestination -> onNavigate(entry.target)
            is CommandPaletteUnifiedEntry.GlossaryEntry -> onOpenGlossaryTerm(entry.term)
        }
        onDismiss()
    }

    val handleKeyEvent: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            when (event.key) {
                Key.DirectionDown -> {
                    if (entries.isNotEmpty()) {
                        selectedIndex = (selectedIndex + 1).coerceAtMost(entries.lastIndex)
                    }
                    true
                }
                Key.DirectionUp -> {
                    if (entries.isNotEmpty()) {
                        selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                    }
                    true
                }
                Key.Enter, Key.NumPadEnter -> {
                    entries.getOrNull(selectedIndex)?.let(activateEntry)
                    entries.isNotEmpty()
                }
                Key.Escape -> {
                    onDismiss()
                    true
                }
                else -> false
            }
        }
    }

    Column(
        modifier = Modifier
            .width(580.dp)
            .onPreviewKeyEvent(handleKeyEvent),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                selectedIndex = 0
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag("command-palette-search"),
            singleLine = true,
            placeholder = { Text("Search screens, BAT-01, Match #12, runs, notebook, subsystems...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (query.isBlank()) "Recent & Recommended Destinations" else "${entries.size} matching results",
                color = AresTextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "↑/↓ Select · Enter Open · Esc Close",
                color = AresTextTertiary,
                fontSize = 11.sp,
            )
        }
        HorizontalDivider(color = AresBorder)
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                Text("No matching screens, batteries, matches, runs, notebook entries, or project entities", color = AresTextSecondary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    CommandPaletteEntryRow(
                        entry = entry,
                        isSelected = index == selectedIndex,
                        onClick = { activateEntry(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandPaletteEntryRow(
    entry: CommandPaletteUnifiedEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val (icon, tint) = paletteEntryVisuals(entry)
    val bgColor = if (isSelected) AresCyanGlow else AresSurfaceElevated
    val shape = RoundedCornerShape(8.dp)
    val borderModifier = if (isSelected) Modifier.border(BorderStroke(1.dp, AresCyan), shape) else Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .background(bgColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("palette-entry-${entry.key}")
            .semantics {
                stateDescription = if (isSelected) "selected" else "unselected"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.title,
                color = AresTextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.subtitle,
                color = AresTextTertiary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            entry.badge,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun paletteEntryVisuals(entry: CommandPaletteUnifiedEntry): Pair<ImageVector, Color> = when (entry) {
    is CommandPaletteUnifiedEntry.RecentDestination -> Icons.Default.History to AresCyan
    is CommandPaletteUnifiedEntry.PitTab -> Icons.Default.SettingsInputComponent to AresAmber
    is CommandPaletteUnifiedEntry.BatteryPack -> Icons.Default.BatteryChargingFull to AresGreen
    is CommandPaletteUnifiedEntry.ScheduledMatch -> Icons.Default.Schedule to AresGold
    is CommandPaletteUnifiedEntry.RunReplay -> Icons.Default.PlayArrow to AresCyan
    is CommandPaletteUnifiedEntry.NotebookNote -> Icons.Default.Book to AresGold
    is CommandPaletteUnifiedEntry.ProjectEntity -> when (entry.item.category) {
        CommandPaletteProjectCategory.STAGE -> Icons.Default.SettingsInputComponent to AresGreen
        CommandPaletteProjectCategory.SUBSYSTEM -> Icons.Default.Build to AresGreen
        CommandPaletteProjectCategory.SUPERSTRUCTURE -> Icons.Default.AccountTree to AresGreen
        CommandPaletteProjectCategory.ROUTINE -> Icons.Default.Route to AresGreen
    }
    is CommandPaletteUnifiedEntry.ScreenDestination -> entry.target.icon to AresCyan
    is CommandPaletteUnifiedEntry.GlossaryEntry -> Icons.AutoMirrored.Filled.MenuBook to AresTextSecondary
}
