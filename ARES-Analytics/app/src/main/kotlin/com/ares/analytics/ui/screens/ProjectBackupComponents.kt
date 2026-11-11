package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.versioncontrol.GitHubAccountKind
import com.ares.analytics.service.versioncontrol.GitHubBackupAccount
import com.ares.analytics.service.versioncontrol.GitHubBackupCatalog
import com.ares.analytics.service.versioncontrol.GitHubBackupRepository
import com.ares.analytics.service.versioncontrol.ProjectBackupPlan
import com.ares.analytics.service.versioncontrol.ProjectChange
import com.ares.analytics.service.versioncontrol.ProjectRestoreDisposition
import com.ares.analytics.service.versioncontrol.ProjectRestorePlan
import com.ares.analytics.ui.theme.*
import com.ares.analytics.ui.util.AresFormatters
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.ProjectBackupIntent
import com.ares.analytics.viewmodel.ProjectBackupViewModel
import java.io.File

/**
 * 1-click student pit checkpoint presets so teams can quickly label and save a clean recovery
 * point before risky pit changes (PID tuning, autonomous path edits, or match-ready freeze).
 */
enum class StudentPitCheckpointPreset(
    val id: String,
    val label: String,
    val commitMessage: String,
    val helperHint: String,
) {
    BEFORE_TUNING(
        id = "before_tuning",
        label = "Before Tuning PID",
        commitMessage = "Checkpoint before tuning PID and feedforward gains",
        helperHint = "Save a known-good baseline before adjusting kP, kD, or gravity compensation.",
    ),
    BEFORE_AUTO_TEST(
        id = "before_auto_test",
        label = "Before Auto Test",
        commitMessage = "Checkpoint before testing autonomous path and event markers",
        helperHint = "Save your working autonomous routine before changing waypoints or speed constraints.",
    ),
    MATCH_READY_BASELINE(
        id = "match_ready_baseline",
        label = "Match-Ready Baseline",
        commitMessage = "Match-ready pit baseline verified for competition",
        helperHint = "Mark this version as the safe competition configuration to restore if a pit experiment goes wrong.",
    ),
    AFTER_WIRING_UPDATE(
        id = "after_wiring_update",
        label = "After Hardware / Port Update",
        commitMessage = "Updated CAN IDs, motor directions, and sensor ports",
        helperHint = "Record hardware port or inversion updates after verifying each mechanism moves the right way.",
    ),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StudentPitCheckpointBar(
    currentMessage: String,
    canSaveNow: Boolean,
    onSelectMessage: (String) -> Unit,
    onQuickSaveCheckpoint: (StudentPitCheckpointPreset) -> Unit,
) {
    Surface(
        color = AresSurface,
        border = BorderStroke(1.dp, AresCyan.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Default.BookmarkAdded,
                    contentDescription = null,
                    tint = AresCyan,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    "1-Click Pit Checkpoints",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                )
            }
            Text(
                "Pick a pit checkpoint label below to fill in the version summary—or save immediately when your changed files are ready:",
                color = AresTextSecondary,
                fontSize = 11.sp,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StudentPitCheckpointPreset.entries.forEach { preset ->
                    val isSelected = currentMessage.trim() == preset.commitMessage
                    Surface(
                        color = if (isSelected) AresCyan.copy(alpha = 0.14f) else AresSurfaceElevated,
                        border = BorderStroke(1.dp, if (isSelected) AresCyan else AresBorder),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.widthIn(min = 195.dp, max = 250.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                preset.label,
                                color = AresTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                            )
                            Text(
                                preset.helperHint,
                                color = AresTextSecondary,
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(
                                    onClick = { onSelectMessage(preset.commitMessage) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp),
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(if (isSelected) "Filled" else "Use Label", fontSize = 10.sp)
                                }
                                if (canSaveNow) {
                                    Button(
                                        onClick = { onQuickSaveCheckpoint(preset) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AresCyan,
                                            contentColor = AresOnAccent,
                                        ),
                                    ) {
                                        Text("Quick Save", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GitHubRestorePreview(
    restore: ProjectRestorePlan,
    busy: Boolean,
    viewModel: ProjectBackupViewModel,
) {
    when (restore.disposition) {
        ProjectRestoreDisposition.UP_TO_DATE -> Text(
            "GitHub and this computer contain the same saved version.",
            color = AresGreen,
        )
        ProjectRestoreDisposition.LOCAL_AHEAD -> Text(
            "This computer is newer than GitHub. Use Sync backup now after reviewing local changes.",
            color = AresTextSecondary,
        )
        ProjectRestoreDisposition.REMOTE_AHEAD -> {
            Card(
                colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                border = BorderStroke(1.dp, AresCyan),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Review newer GitHub version", fontWeight = FontWeight.Bold)
                    Text(
                        "ARES will only fast-forward to this exact reviewed version. It creates a local safety checkpoint first and never force-pushes or guesses through conflicting histories.",
                        color = AresTextSecondary,
                    )
                    ReviewedChanges(
                        changes = restore.changes,
                        fromLabel = "This computer ${restore.localCommit.take(8)}",
                        toLabel = "GitHub ${restore.remoteCommit.take(8)}",
                    )
                    Button(
                        onClick = {
                            viewModel.onIntent(
                                ProjectBackupIntent.ConfirmGitHubRestore(requireNotNull(restore.confirmationToken)),
                            )
                        },
                        enabled = !busy && restore.canRestore,
                    ) { Text("Restore this reviewed version") }
                }
            }
        }
    }
}

@Composable
internal fun ReviewedChanges(changes: List<ProjectChange>, fromLabel: String, toLabel: String) {
    Text("$fromLabel  →  $toLabel", color = AresCyan, fontWeight = FontWeight.Bold)
    changes.take(30).groupBy { projectArea(it.path) }.forEach { (area, areaChanges) ->
        Text(area, fontWeight = FontWeight.Bold)
        areaChanges.forEach { change ->
            val verb = change.kind.name.lowercase().replaceFirstChar(Char::uppercase)
            Text("$verb • ${change.path}", color = AresTextSecondary)
        }
    }
    if (changes.size > 30) Text("…and ${changes.size - 30} more files", color = AresTextSecondary)
}

internal fun projectArea(path: String): String = when {
    path == ".ares/project.json" -> "Robot identity"
    path.startsWith(".ares/drivetrains/") -> "Drivebase"
    path.startsWith(".ares/subsystems/") -> "Subsystems"
    path.startsWith(".ares/controllers/") || path.startsWith(".ares/controls/") -> "Controller bindings"
    path.startsWith(".ares/routines/") || path.contains("autonomous") -> "Autonomous routines"
    path.startsWith(".ares/tuning/") -> "Tuning"
    path.startsWith(".ares/fields/") || path.contains("apriltag", ignoreCase = true) -> "Field and AprilTags"
    path.startsWith("docs/") || path.endsWith(".md", ignoreCase = true) -> "Documentation"
    path.contains("generated", ignoreCase = true) -> "Generated project plumbing"
    else -> "Project files"
}

internal fun chooseProjectArchive(projectPath: String): File? {
    val root = File(projectPath).canonicalFile
    val safeName = root.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "ares-robot" }
    return DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export portable ARES robot project",
        defaultFileName = "$safeName.aresproject.zip",
        initialDirectory = root.parentFile,
        filterDescription = "ARES project archive (*.aresproject.zip)",
        extensions = listOf("zip"),
    )
}

internal fun formatVersionTime(epochSeconds: Long): String =
    AresFormatters.formatDateTimeShort(epochSeconds * 1000L)

@Composable
internal fun RepositoryDestinationPicker(
    plan: ProjectBackupPlan,
    catalog: GitHubBackupCatalog,
    selectedInstallationId: Long?,
    busy: Boolean,
    viewModel: ProjectBackupViewModel,
) {
    if (catalog.accounts.isEmpty()) {
        Text(
            "No approved personal or team destination is visible. A team owner can install ARES for selected repositories, then you can refresh this list.",
            color = AresTextSecondary,
        )
        return
    }
    Text("1. Choose who owns the backup", fontWeight = FontWeight.Bold)
    catalog.accounts.forEach { account ->
        GitHubAccountRow(
            account = account,
            selected = account.installationId == selectedInstallationId,
            busy = busy,
            onSelect = { viewModel.onIntent(ProjectBackupIntent.SelectGitHubInstallation(account.installationId)) },
        )
    }
    val selected = catalog.accounts.firstOrNull { it.installationId == selectedInstallationId } ?: return
    Text("2. Choose an approved private repository", fontWeight = FontWeight.Bold)
    val repositories = catalog.repositoriesFor(selected.installationId)
    if (repositories.isEmpty()) {
        Text(
            "No repositories are approved for ${selected.login}. Ask a team owner to add one under Install or manage ARES access.",
            color = AresTextSecondary,
        )
    } else {
        repositories.forEach { repository ->
            GitHubRepositoryRow(
                repository = repository,
                accountCanWrite = selected.canWriteContents,
                canConnect = plan.lastCommit != null && plan.changes.isEmpty(),
                busy = busy,
                onConnect = {
                    viewModel.onIntent(
                        ProjectBackupIntent.ConnectGitHubRepository(
                            installationId = selected.installationId,
                            repositoryId = repository.repositoryId,
                        ),
                    )
                },
            )
        }
    }
    if (plan.lastCommit == null || plan.changes.isNotEmpty()) {
        Text("Save a clean local version before connecting the online backup.", color = AresTextSecondary)
    }
}

@Composable
private fun GitHubAccountRow(
    account: GitHubBackupAccount,
    selected: Boolean,
    busy: Boolean,
    onSelect: () -> Unit,
) {
    val kind = if (account.kind == GitHubAccountKind.ORGANIZATION) "Team organization" else "Personal account"
    val access = if (account.canWriteContents) "Repository contents: read and write" else "Repository contents: not writable"
    OutlinedButton(
        onClick = onSelect,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text("${if (selected) "Selected • " else ""}${account.login} — $kind", fontWeight = FontWeight.Bold)
            Text("$access • ${account.repositorySelection.lowercase()} repositories", color = AresTextSecondary)
        }
    }
}

@Composable
private fun GitHubRepositoryRow(
    repository: GitHubBackupRepository,
    accountCanWrite: Boolean,
    canConnect: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(repository.fullName, fontWeight = FontWeight.Bold)
                val status = when {
                    !accountCanWrite -> "Unavailable • ARES App needs Contents: write permission"
                    repository.unavailableReason != null -> "Unavailable • ${repository.unavailableReason}"
                    else -> "Ready • Private • Read and write"
                }
                Text(status, color = if (repository.canUseForBackup && accountCanWrite) AresGreen else AresError)
            }
            Button(
                onClick = onConnect,
                enabled = !busy && canConnect && accountCanWrite && repository.canUseForBackup,
            ) {
                Text("Use this repository")
            }
        }
    }
}

enum class StudentPitRoleIdentity(
    val id: String,
    val label: String,
    val authorName: String,
    val authorEmail: String,
) {
    PIT_CREW("pit_crew", "Pit Crew", "ARES Pit Crew", "pit-crew@ares.local"),
    CONTROLS_STUDENT("controls_student", "Controls Student", "Controls & Tuning Student", "controls@ares.local"),
    AUTO_STUDENT("auto_student", "Auto Student", "Autonomous & Vision Student", "autonomous@ares.local"),
}

data class SmartPitCheckpointRecommendation(
    val preset: StudentPitCheckpointPreset,
    val recommendedMessage: String,
    val areaBreakdownSummary: String,
    val reason: String,
)

fun recommendPitCheckpointFromChanges(changes: List<ProjectChange>): SmartPitCheckpointRecommendation? {
    if (changes.isEmpty()) return null
    val grouped = changes.groupBy { projectArea(it.path) }
    val breakdown = grouped.entries.joinToString(", ") { "${it.key} (${it.value.size})" }
    val areas = grouped.keys

    val preset = when {
        "Tuning" in areas -> StudentPitCheckpointPreset.BEFORE_TUNING
        "Autonomous routines" in areas || "Field and AprilTags" in areas -> StudentPitCheckpointPreset.BEFORE_AUTO_TEST
        "Subsystems" in areas || "Drivebase" in areas || "Controller bindings" in areas -> StudentPitCheckpointPreset.AFTER_WIRING_UPDATE
        else -> StudentPitCheckpointPreset.MATCH_READY_BASELINE
    }

    return SmartPitCheckpointRecommendation(
        preset = preset,
        recommendedMessage = "${preset.commitMessage} [$breakdown]",
        areaBreakdownSummary = breakdown,
        reason = "Detected changes in $breakdown — recommended checkpoint: ${preset.label}.",
    )
}

@Composable
internal fun StudentPitRoleIdentityBar(
    authorName: String,
    authorEmail: String,
    onSelectRole: (StudentPitRoleIdentity) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Shared Pit Laptop? Pick a 1-Click Role Identity:",
            color = AresTextPrimary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
        )
        com.ares.analytics.ui.components.core.AresPresetChipBar(
            items = StudentPitRoleIdentity.entries,
            labelFor = { "${it.label} (${it.authorName})" },
            isSelected = { authorName.trim() == it.authorName && authorEmail.trim() == it.authorEmail },
            onSelect = onSelectRole,
        )
    }
}

@Composable
internal fun SmartPitCheckpointRecommendationBanner(
    changes: List<ProjectChange>,
    onApplyRecommendation: (SmartPitCheckpointRecommendation) -> Unit,
) {
    val recommendation = androidx.compose.runtime.remember(changes) { recommendPitCheckpointFromChanges(changes) } ?: return
    com.ares.analytics.ui.components.core.AresCoachBanner(
        badgeText = "SUGGESTED",
        title = "Smart Checkpoint: ${recommendation.preset.label}",
        subtitle = "Changed areas: ${recommendation.areaBreakdownSummary}",
        trailingHeader = {
            OutlinedButton(
                onClick = { onApplyRecommendation(recommendation) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("Use Suggested Summary", fontSize = 11.sp)
            }
        },
    ) {}
}

