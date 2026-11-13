@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Publish
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.notebook.EngineeringPortfolioCompiler.displayLabel
import com.ares.analytics.domain.notebook.NotebookSectionParser
import com.ares.analytics.shared.models.EngineeringNotebookEntry
import com.ares.analytics.shared.models.NotebookEntryType
import com.ares.analytics.shared.models.NotebookReviewState
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.theme.AresAmber
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresRed
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.notebook.EngineeringNotebookState
import com.ares.analytics.viewmodel.notebook.EngineeringNotebookViewModel
import com.ares.analytics.viewmodel.notebook.NotebookEditorMode
import com.ares.analytics.viewmodel.notebook.NotebookReviewFilter
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val notebookCardTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

@Composable
fun EngineeringNotebookScreen(
    viewModel: EngineeringNotebookViewModel,
    workspace: WorkspaceConfig,
    reloadTrigger: Int = 0,
    onOpenReplay: (String) -> Unit = {},
    onOpenGuidedReview: (String) -> Unit = {},
    onOpenIntegrations: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current

    LaunchedEffect(viewModel, reloadTrigger) {
        viewModel.refresh()
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(AresBackground).padding(16.dp)) {
        val wideLayout = maxWidth >= 980.dp
        if (wideLayout) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NotebookSidebarColumn(
                    state = state,
                    workspace = workspace,
                    viewModel = viewModel,
                    onCopyPortfolio = {
                        viewModel.buildPortfolioMarkdownForClipboard { md ->
                            clipboard.setText(AnnotatedString(md))
                        }
                    },
                    modifier = Modifier.width(340.dp).fillMaxHeight(),
                )
                NotebookEditorPane(
                    state = state,
                    viewModel = viewModel,
                    onOpenReplay = onOpenReplay,
                    onOpenGuidedReview = onOpenGuidedReview,
                    onOpenIntegrations = onOpenIntegrations,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                NotebookSidebarColumn(
                    state = state,
                    workspace = workspace,
                    viewModel = viewModel,
                    onCopyPortfolio = {
                        viewModel.buildPortfolioMarkdownForClipboard { md ->
                            clipboard.setText(AnnotatedString(md))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                )
                NotebookEditorPane(
                    state = state,
                    viewModel = viewModel,
                    onOpenReplay = onOpenReplay,
                    onOpenGuidedReview = onOpenGuidedReview,
                    onOpenIntegrations = onOpenIntegrations,
                    modifier = Modifier.fillMaxWidth(),
                    scrollable = false,
                )
            }
        }
    }
}

@Composable
private fun NotebookSidebarColumn(
    state: EngineeringNotebookState,
    workspace: WorkspaceConfig,
    viewModel: EngineeringNotebookViewModel,
    onCopyPortfolio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = remember(state.entries, state.searchQuery, state.typeFilter, state.reviewFilter) {
        state.filteredEntries()
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Book, contentDescription = null, tint = AresCyan, modifier = Modifier.size(20.dp))
                    Column {
                        Text("Engineering Notebook", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("${state.entries.size} workspace entries", color = AresTextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh notebook", tint = AresTextSecondary, modifier = Modifier.size(16.dp))
                    }
                    Button(
                        onClick = { viewModel.startNewEntry() },
                        colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text("Search title, tag, run ID…", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NotebookReviewFilter.entries.forEach { revFilter ->
                    FilterChip(
                        selected = state.reviewFilter == revFilter,
                        onClick = { viewModel.setReviewFilter(revFilter) },
                        label = { Text(revFilter.label, fontSize = 10.sp) },
                    )
                }
                FilterChip(
                    selected = state.typeFilter == null,
                    onClick = { viewModel.setTypeFilter(null) },
                    label = { Text("All Types", fontSize = 10.sp) },
                )
                NotebookEntryType.entries.forEach { entryType ->
                    val shortLabel = when (entryType) {
                        NotebookEntryType.ENGINEERING_NOTE -> "Note"
                        NotebookEntryType.SESSION_SUMMARY -> "Session"
                        NotebookEntryType.SOFTWARE_CHANGE -> "Change"
                        NotebookEntryType.ROBOT_ISSUE -> "Issue"
                    }
                    FilterChip(
                        selected = state.typeFilter == entryType,
                        onClick = { viewModel.setTypeFilter(entryType) },
                        label = { Text(shortLabel, fontSize = 10.sp) },
                    )
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (filtered.isEmpty()) {
                    Text(
                        if (state.entries.isEmpty()) {
                            "No workspace entries yet. Click + New or log evidence from Guided Run Review, Match Strategy, Tuning, or Run History."
                        } else {
                            "No entries match the active search or filters."
                        },
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(filtered, key = { "${it.entryId}-r${it.revision}" }) { entry ->
                            NotebookEntryListCard(
                                entry = entry,
                                selected = !state.isNewDraft && state.selectedEntryId == entry.entryId,
                                onClick = { viewModel.selectEntry(entry.entryId) },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = AresBorder)

            // FIRST Season Portfolio Compiler Box
            Surface(
                color = AresSurfaceElevated,
                border = BorderStroke(1.dp, AresBorder),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "FIRST Season Portfolio Compiler",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = state.approvedOnlyForPortfolio,
                            onCheckedChange = viewModel::setApprovedOnlyForPortfolio,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Human-approved entries only (${state.portfolioCandidateEntries().size})",
                            color = AresTextSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                choosePortfolioDestination(workspace, "html")?.let(viewModel::exportPortfolioHtml)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Print .html", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                choosePortfolioDestination(workspace, "md")?.let(viewModel::exportPortfolioMarkdown)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(".md", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = onCopyPortfolio,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotebookEntryListCard(
    entry: EngineeringNotebookEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = remember(entry.markdownBody, entry.entryId) { NotebookSectionParser.extractTitle(entry) }
    val approved = entry.reviewState == NotebookReviewState.APPROVED || entry.reviewState == NotebookReviewState.PUBLISHED
    val stateColor = if (approved) AresGreen else AresAmber
    val timeText = remember(entry.updatedAtMs) {
        notebookCardTimeFormatter.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.updatedAtMs))
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) AresCyan.copy(alpha = 0.12f) else AresSurfaceElevated,
        border = BorderStroke(1.dp, if (selected) AresCyan else AresBorder),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = AresTextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${entry.entryType.displayLabel()} · r${entry.revision} · ${entry.evidence.size} refs",
                    color = AresTextSecondary,
                    fontSize = 10.sp,
                )
                Text(
                    entry.reviewState.name,
                    color = stateColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(timeText, color = AresTextTertiary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun NotebookEditorPane(
    state: EngineeringNotebookState,
    viewModel: EngineeringNotebookViewModel,
    onOpenReplay: (String) -> Unit,
    onOpenGuidedReview: (String) -> Unit,
    onOpenIntegrations: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    val activeEntry = state.activeRevisionEntry
    val colModifier = if (scrollable) {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    } else {
        Modifier.fillMaxWidth().padding(16.dp)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = AresSurface),
        border = BorderStroke(1.dp, AresBorder),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(colModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Top Entry Metadata & Revision Selector
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (state.isNewDraft) "New Engineering Notebook Draft" else "Entry `${state.selectedEntryId}`",
                        color = AresTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    val hashText = activeEntry?.contentHash?.take(16)?.let { "SHA-256 $it…" } ?: "Unsaved draft"
                    val stateBadge = activeEntry?.reviewState?.name ?: "UNSAVED"
                    Text(
                        "$stateBadge · $hashText · ${state.receipts.size} external receipt(s)",
                        color = AresTextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.revisions.takeLast(6).forEach { rev ->
                        FilterChip(
                            selected = state.selectedRevisionNumber == rev.revision,
                            onClick = { viewModel.selectRevision(rev.revision) },
                            label = { Text("r${rev.revision}", fontSize = 11.sp) },
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenIntegrations,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.Publish, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Publishers", fontSize = 11.sp)
                    }
                }
            }

            state.statusMessage?.let { msg ->
                Surface(
                    color = AresGreen.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AresGreen.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AresGreen, modifier = Modifier.size(16.dp))
                        Text(msg, color = AresGreen, fontSize = 12.sp)
                    }
                }
            }

            state.errorMessage?.let { err ->
                Surface(
                    color = AresRed.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AresRed.copy(alpha = 0.65f)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(err, color = AresRed, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
                }
            }

            // Editor Mode Switcher
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotebookEditorMode.entries.forEach { mode ->
                    if (state.editorMode == mode) {
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(mode.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.setEditorMode(mode) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(mode.label, fontSize = 12.sp)
                        }
                    }
                }
            }

            when (state.editorMode) {
                NotebookEditorMode.GUIDED_FORM -> GuidedRubricFormEditor(
                    draftType = state.draftType,
                    sections = state.structuredDraft,
                    authorId = state.draftAuthorId,
                    onTypeChange = viewModel::updateDraftType,
                    onAuthorChange = viewModel::updateAuthorId,
                    onUpdateSections = viewModel::updateStructured,
                )
                NotebookEditorMode.RAW_MARKDOWN -> RawMarkdownEditorPanel(
                    markdown = state.rawMarkdownDraft,
                    authorId = state.draftAuthorId,
                    onAuthorChange = viewModel::updateAuthorId,
                    onMarkdownChange = viewModel::updateRawMarkdown,
                )
                NotebookEditorMode.PREVIEW -> FormattedNotebookPreviewPanel(
                    markdown = if (state.isDirty) state.rawMarkdownDraft else (activeEntry?.markdownBody ?: state.rawMarkdownDraft),
                )
            }

            NotebookEvidenceSection(
                evidence = state.draftEvidence,
                onAddReference = viewModel::addEvidenceReference,
                onRemoveReference = viewModel::removeEvidenceReference,
                onOpenReplaySession = onOpenReplay,
                onOpenGuidedReviewSession = onOpenGuidedReview,
            )

            HorizontalDivider(color = AresBorder)

            // Save Revision & Human Sign-Off Bar
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::saveRevision,
                    enabled = !state.isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (state.isNewDraft) "Save Entry (r1)" else "Save New Revision (r${(state.revisions.lastOrNull()?.revision ?: 0) + 1})",
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (!state.isNewDraft && activeEntry != null) {
                    OutlinedTextField(
                        value = state.reviewerId,
                        onValueChange = viewModel::updateReviewerId,
                        label = { Text("Mentor / Peer Reviewer") },
                        placeholder = { Text("e.g. Coach Patel") },
                        singleLine = true,
                        modifier = Modifier.width(220.dp),
                    )
                    Button(
                        onClick = viewModel::approveSelectedRevision,
                        enabled = !state.isSaving && !state.isDirty && activeEntry.reviewState != NotebookReviewState.APPROVED,
                        colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                    ) {
                        Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (activeEntry.reviewState == NotebookReviewState.APPROVED) "Approved" else "Approve r${activeEntry.revision}",
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

private fun choosePortfolioDestination(workspace: WorkspaceConfig, extension: String): File? {
    val slug = listOf(workspace.teamId, workspace.robotName.ifBlank { workspace.robotId }, workspace.seasonId)
        .filter(String::isNotBlank)
        .joinToString("-")
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .ifBlank { "ares-engineering-portfolio" }
    val desc = if (extension == "html") "Print-Ready HTML Portfolio (*.html)" else "Markdown Portfolio (*.md)"
    return DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export FIRST Engineering Portfolio (.$extension)",
        defaultFileName = "$slug-portfolio.$extension",
        filterDescription = desc,
        extensions = listOf(extension),
    )
}
