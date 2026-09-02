package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ares.analytics.service.versioncontrol.GitHubConnectionState
import com.ares.analytics.service.versioncontrol.GitHubAccountKind
import com.ares.analytics.service.versioncontrol.GitHubBackupAccount
import com.ares.analytics.service.versioncontrol.GitHubBackupCatalog
import com.ares.analytics.service.versioncontrol.GitHubBackupRepository
import com.ares.analytics.service.versioncontrol.ProjectBackupPlan
import com.ares.analytics.service.versioncontrol.ProjectBackupAutoSyncState
import com.ares.analytics.service.versioncontrol.ProjectChange
import com.ares.analytics.service.versioncontrol.ProjectRecoveryPlan
import com.ares.analytics.service.versioncontrol.ProjectRestoreDisposition
import com.ares.analytics.service.versioncontrol.ProjectRestorePlan
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.forms.AresTextField
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.util.AresFormatters
import com.ares.analytics.ui.util.DesktopFileChoosers
import com.ares.analytics.viewmodel.ProjectBackupIntent
import com.ares.analytics.viewmodel.ProjectBackupViewModel
import java.io.File

/** Plain-language, review-first local history and permission-scoped GitHub App backup workflow. */
@Composable
fun ProjectBackupScreen(
    viewModel: ProjectBackupViewModel,
    projectPath: String,
) {
    val state by viewModel.state.collectAsState()
    var authorName by remember { mutableStateOf("") }
    var authorEmail by remember { mutableStateOf("") }
    var versionMessage by remember { mutableStateOf("Save robot design") }

    LaunchedEffect(projectPath) { viewModel.onIntent(ProjectBackupIntent.Load(projectPath)) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Project History & Backup",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AresTextPrimary,
                )
                Text(
                    "Save named versions, review what changed, and optionally keep a private GitHub copy.",
                    color = AresTextSecondary,
                )
            }
            OutlinedButton(
                onClick = { viewModel.onIntent(ProjectBackupIntent.Refresh) },
                enabled = !state.isBusy,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
        }
        Text("PROJECT  •  $projectPath", color = AresTextSecondary, style = MaterialTheme.typography.labelSmall)

        state.error?.let { StatusCard(it, AresError) }
        state.notice?.let { StatusCard(it, AresGreen) }
        if (state.isBusy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.width(22.dp), strokeWidth = 2.dp)
                Text("Working…", color = AresTextSecondary)
            }
        }

        val plan = state.plan
        if (plan != null) {
            ProjectStatusSummary(plan, state.restorePlan, state.githubState)
            IdentityFields(authorName, { authorName = it }, authorEmail, { authorEmail = it })
        }
        if (plan != null && !plan.initialized) {
            StepCard("1", "Start local version history", Icons.Default.History) {
                Text(
                    "ARES will track this project inside its current folder. Nothing is uploaded, and you do not need to install Git.",
                    color = AresTextSecondary,
                )
                Button(
                    onClick = {
                        viewModel.onIntent(ProjectBackupIntent.StartLocalHistory(authorName, authorEmail))
                    },
                    enabled = !state.isBusy,
                ) { Text("Start local history") }
            }
        } else if (plan != null) {
            LocalVersionStep(plan, versionMessage, { versionMessage = it }, authorName, authorEmail, state.isBusy, viewModel)
            RecentVersionsStep(plan, state.recoveryPlan, state.isBusy, viewModel)
            GitHubBackupStep(
                plan = plan,
                restorePlan = state.restorePlan,
                connection = state.githubState,
                catalog = state.githubCatalog,
                selectedInstallationId = state.selectedInstallationId,
                autoSync = state.autoSync,
                busy = state.isBusy,
                viewModel = viewModel,
            )
        }
    }
}

@Composable
private fun RecentVersionsStep(
    plan: ProjectBackupPlan,
    recoveryPlan: ProjectRecoveryPlan?,
    busy: Boolean,
    viewModel: ProjectBackupViewModel,
) {
    if (plan.versions.isEmpty()) return
    StepCard("3", "Recent saved versions", Icons.Default.History) {
        Text(
            "These are durable checkpoints stored with the robot project. Git commit IDs are shown only as short version IDs.",
            color = AresTextSecondary,
        )
        plan.versions.take(8).forEachIndexed { index, version ->
            if (index > 0) HorizontalDivider(color = AresBorder)
            Text(version.message, fontWeight = FontWeight.Bold)
            Text(
                "${version.authorName} • ${formatVersionTime(version.committedAtEpochSeconds)} • version ${version.commitId.take(8)}",
                color = AresTextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (plan.versions.size > 8) {
            Text("${plan.versions.size - 8} older saved versions are retained.", color = AresTextSecondary)
        }
        if (plan.recoveryPoints.isNotEmpty()) {
            HorizontalDivider(color = AresBorder)
            Text("Undo a previous restore", fontWeight = FontWeight.Bold)
            Text(
                "ARES preserved these versions automatically before a GitHub restore. Review the affected files before going back.",
                color = AresTextSecondary,
            )
            plan.recoveryPoints.take(3).forEach { point ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(point.message, fontWeight = FontWeight.Bold)
                        Text(
                            "${formatVersionTime(point.committedAtEpochSeconds)} • version ${point.commitId.take(8)}",
                            color = AresTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        onClick = { viewModel.onIntent(ProjectBackupIntent.PreviewRecovery(point.refName)) },
                        enabled = !busy,
                    ) { Text("Review") }
                }
            }
            recoveryPlan?.let { recovery ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
                    border = BorderStroke(1.dp, AresCyan),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Review recovery", fontWeight = FontWeight.Bold)
                        ReviewedChanges(
                            changes = recovery.changes,
                            fromLabel = "Current version ${recovery.currentCommit.take(8)}",
                            toLabel = "Recovery version ${recovery.targetCommit.take(8)}",
                        )
                        Button(
                            onClick = {
                                viewModel.onIntent(
                                    ProjectBackupIntent.ConfirmRecovery(
                                        recovery.refName,
                                        requireNotNull(recovery.confirmationToken),
                                    ),
                                )
                            },
                            enabled = !busy && recovery.canRecover,
                        ) { Text("Restore this recovery point") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectStatusSummary(
    plan: ProjectBackupPlan,
    restorePlan: ProjectRestorePlan?,
    connection: GitHubConnectionState,
) {
    val localStatus = if (plan.changes.isEmpty()) {
        "LOCAL • Saved — working files match version ${plan.lastCommit?.take(8) ?: "none"}"
    } else {
        "LOCAL • ${plan.changes.size} unsaved file change${if (plan.changes.size == 1) "" else "s"}"
    }
    val onlineStatus = when {
        plan.destination == null -> "ONLINE BACKUP • Not connected"
        connection !is GitHubConnectionState.Connected -> "ONLINE BACKUP • Sign in required"
        plan.changes.isNotEmpty() -> "ONLINE BACKUP • Save a local version before syncing"
        restorePlan?.disposition == ProjectRestoreDisposition.REMOTE_AHEAD -> "ONLINE BACKUP • A newer reviewed version is available"
        restorePlan?.disposition == ProjectRestoreDisposition.LOCAL_AHEAD -> "ONLINE BACKUP • This computer has versions ready to sync"
        restorePlan?.disposition == ProjectRestoreDisposition.UP_TO_DATE -> "ONLINE BACKUP • Checked and up to date"
        else -> "ONLINE BACKUP • Connected; check or sync when ready"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(localStatus, color = if (plan.changes.isEmpty()) AresGreen else AresTextPrimary, fontWeight = FontWeight.Bold)
            Text(onlineStatus, color = AresTextSecondary)
        }
    }
}

@Composable
private fun IdentityFields(
    name: String,
    onNameChanged: (String) -> Unit,
    email: String,
    onEmailChanged: (String) -> Unit,
) {
    AresCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Who is saving this version?", fontWeight = FontWeight.Bold)
            Text("This name appears in project history so teammates know who made a change.", color = AresTextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AresTextField(name, onNameChanged, label = "Your name", singleLine = true, modifier = Modifier.weight(1f))
                AresTextField(email, onEmailChanged, label = "Email", singleLine = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LocalVersionStep(
    plan: ProjectBackupPlan,
    message: String,
    onMessageChanged: (String) -> Unit,
    authorName: String,
    authorEmail: String,
    busy: Boolean,
    viewModel: ProjectBackupViewModel,
) {
    StepCard("2", "Review and save a local version", Icons.Default.CheckCircle) {
        Text(
            if (plan.changes.isEmpty()) "Everything is saved. Make a change in Robot Studio, then refresh this page."
            else "Review every changed file before saving. ARES checks the contents again when you press Save.",
            color = AresTextSecondary,
        )
        if (plan.blockedSensitivePaths.isNotEmpty()) {
            Text(
                "Blocked private files: ${plan.blockedSensitivePaths.joinToString()}. Remove or ignore these before saving.",
                color = AresError,
            )
        }
        plan.changes.take(30).forEach { change ->
            Text("${change.kind.name.lowercase().replaceFirstChar(Char::uppercase)}  •  ${change.path}")
        }
        if (plan.changes.size > 30) Text("…and ${plan.changes.size - 30} more files", color = AresTextSecondary)
        AresTextField(
            value = message,
            onValueChange = onMessageChanged,
            label = "What changed?",
            supportingText = { Text("Example: Add intake motor and safe current limit") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.onIntent(
                    ProjectBackupIntent.SaveVersion(
                        confirmationToken = requireNotNull(plan.confirmationToken),
                        message = message,
                        authorName = authorName,
                        authorEmail = authorEmail,
                    ),
                )
            },
            enabled = !busy && plan.canCommit,
        ) { Text("Save this version") }
        OutlinedButton(
            onClick = {
                chooseProjectArchive(plan.projectPath)?.let { target ->
                    viewModel.onIntent(ProjectBackupIntent.ExportArchive(target.path))
                }
            },
            enabled = !busy,
        ) { Text("Export portable project archive") }
        Text(
            "The archive excludes Git history, build caches, local settings, and credential files. It can be opened on another computer.",
            color = AresTextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun GitHubBackupStep(
    plan: ProjectBackupPlan,
    restorePlan: ProjectRestorePlan?,
    connection: GitHubConnectionState,
    catalog: GitHubBackupCatalog,
    selectedInstallationId: Long?,
    autoSync: ProjectBackupAutoSyncState,
    busy: Boolean,
    viewModel: ProjectBackupViewModel,
) {
    StepCard("4", "Optional GitHub project backup", Icons.Default.CloudUpload) {
        Text(
            "GitHub stores another copy outside this computer. Sign in, then choose a private repository that a team owner has approved for the ARES GitHub App. No token is stored in the robot project.",
            color = AresTextSecondary,
        )
        if (autoSync.enabled) {
            Text("Automatic backup status: ${autoSync.message}", color = AresTextSecondary)
        }
        when (connection) {
            GitHubConnectionState.Disconnected -> Button(
                onClick = { viewModel.onIntent(ProjectBackupIntent.SignInToGitHub) },
                enabled = !busy,
            ) { Text("Sign in with GitHub") }
            is GitHubConnectionState.Unavailable -> Text(connection.message, color = AresTextSecondary)
            is GitHubConnectionState.AwaitingUser -> {
                Text("Enter code ${connection.userCode} in the GitHub page that ARES opened.", fontWeight = FontWeight.Bold)
                Text("ARES is waiting for approval. The code expires automatically.", color = AresTextSecondary)
            }
            is GitHubConnectionState.Error -> {
                Text(connection.message, color = AresError)
                OutlinedButton(onClick = { viewModel.onIntent(ProjectBackupIntent.SignInToGitHub) }, enabled = !busy) {
                    Text("Try GitHub sign-in again")
                }
            }
            is GitHubConnectionState.Connected -> {
                Text("Signed in as ${connection.login}", color = AresGreen, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.onIntent(ProjectBackupIntent.RefreshGitHubDestinations) },
                        enabled = !busy,
                    ) { Text("Refresh destinations") }
                    OutlinedButton(
                        onClick = { viewModel.onIntent(ProjectBackupIntent.OpenGitHubAppInstallation) },
                        enabled = !busy,
                    ) { Text("Install or manage ARES access") }
                    OutlinedButton(
                        onClick = { viewModel.onIntent(ProjectBackupIntent.DisconnectGitHub) },
                        enabled = !busy,
                    ) { Text("Sign out") }
                }
                val destination = plan.destination
                if (destination != null) {
                    val ownerLabel = if (destination.accountKind == GitHubAccountKind.ORGANIZATION) {
                        "Team organization backup"
                    } else {
                        "Personal backup"
                    }
                    Text(ownerLabel, fontWeight = FontWeight.Bold)
                    Text("Repository: ${destination.ownerLogin}/${destination.repositoryName}", color = AresTextSecondary)
                    Text("GitHub verifies current access before every sync.", color = AresTextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Switch(
                            checked = autoSync.enabled,
                            onCheckedChange = {
                                viewModel.onIntent(ProjectBackupIntent.SetAutomaticGitHubBackup(it))
                            },
                            enabled = !busy,
                        )
                        Column {
                            Text("Back up each saved version automatically", fontWeight = FontWeight.Bold)
                            Text(
                                autoSync.message,
                                color = AresTextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.onIntent(ProjectBackupIntent.SyncGitHubBackup) },
                            enabled = !busy && plan.changes.isEmpty(),
                        ) { Text("Sync backup now") }
                        OutlinedButton(
                            onClick = { viewModel.onIntent(ProjectBackupIntent.PreviewGitHubRestore) },
                            enabled = !busy && plan.changes.isEmpty(),
                        ) { Text("Check for newer version") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.onIntent(ProjectBackupIntent.DisconnectGitHubDestination) },
                            enabled = !busy,
                        ) { Text("Change destination") }
                    }
                    restorePlan?.let { GitHubRestorePreview(it, busy, viewModel) }
                    if (plan.changes.isNotEmpty()) {
                        Text("Save a clean local version before syncing GitHub.", color = AresTextSecondary)
                    }
                } else {
                    if (plan.remoteUrl != null) {
                        Text(
                            "This project has an existing Git origin that ARES did not approve. Choose the matching approved repository; ARES will never replace a different remote.",
                            color = AresError,
                        )
                    }
                    RepositoryDestinationPicker(
                        plan = plan,
                        catalog = catalog,
                        selectedInstallationId = selectedInstallationId,
                        busy = busy,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun GitHubRestorePreview(
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
private fun ReviewedChanges(changes: List<ProjectChange>, fromLabel: String, toLabel: String) {
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

private fun projectArea(path: String): String = when {
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

private fun chooseProjectArchive(projectPath: String): File? {
    val root = File(projectPath).canonicalFile
    val safeName = root.name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifBlank { "ares-robot" }
    return DesktopFileChoosers.chooseSaveFile(
        dialogTitle = "Export portable ARES robot project",
        defaultFileName = "$safeName.aresproject.zip",
        initialDirectory = root.parentFile,
        filterDescription = "ARES project archive (*.aresproject.zip)",
        extensions = arrayOf("zip"),
    )
}

private fun formatVersionTime(epochSeconds: Long): String =
    AresFormatters.formatDateTimeShort(epochSeconds * 1000L)


@Composable
private fun RepositoryDestinationPicker(
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

@Composable
private fun StepCard(number: String, title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, AresBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = AresCyan)
                Text("$number. $title", fontWeight = FontWeight.Bold, color = AresTextPrimary)
            }
            HorizontalDivider(color = AresBorder)
            content()
        }
    }
}

@Composable
private fun StatusCard(message: String, color: androidx.compose.ui.graphics.Color) {
    Card(colors = CardDefaults.cardColors(containerColor = AresSurfaceElevated), border = BorderStroke(1.dp, color)) {
        Text(message, color = color, modifier = Modifier.fillMaxWidth().padding(12.dp))
    }
}
