package com.ares.analytics.viewmodel

import com.ares.analytics.service.versioncontrol.GitHubConnectionState
import com.ares.analytics.service.versioncontrol.GitHubAuthenticationService
import com.ares.analytics.service.versioncontrol.GitHubBackupCatalog
import com.ares.analytics.service.versioncontrol.ProjectBackupPlan
import com.ares.analytics.service.versioncontrol.ProjectBackupAutoSyncState
import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.ares.analytics.service.versioncontrol.ProjectBackupAutoSyncService
import com.ares.analytics.service.versioncontrol.ProjectRecoveryPlan
import com.ares.analytics.service.versioncontrol.ProjectRecoveryService
import com.ares.analytics.service.versioncontrol.ProjectRemoteBackupService
import com.ares.analytics.service.versioncontrol.ProjectRestorePlan
import com.ares.analytics.service.versioncontrol.ProjectVersionControlService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectBackupState(
    val projectPath: String = "",
    val plan: ProjectBackupPlan? = null,
    val restorePlan: ProjectRestorePlan? = null,
    val recoveryPlan: ProjectRecoveryPlan? = null,
    val githubState: GitHubConnectionState = GitHubConnectionState.Disconnected,
    val githubCatalog: GitHubBackupCatalog = GitHubBackupCatalog(),
    val selectedInstallationId: Long? = null,
    val autoSync: ProjectBackupAutoSyncState = ProjectBackupAutoSyncState(),
    val isBusy: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
)

sealed class ProjectBackupIntent {
    data class Load(val projectPath: String) : ProjectBackupIntent()
    object Refresh : ProjectBackupIntent()
    data class StartLocalHistory(val authorName: String, val authorEmail: String) : ProjectBackupIntent()
    data class SaveVersion(
        val confirmationToken: String,
        val message: String,
        val authorName: String,
        val authorEmail: String,
    ) : ProjectBackupIntent()
    object SignInToGitHub : ProjectBackupIntent()
    object OpenGitHubAppInstallation : ProjectBackupIntent()
    object RefreshGitHubDestinations : ProjectBackupIntent()
    data class SelectGitHubInstallation(val installationId: Long) : ProjectBackupIntent()
    data class ConnectGitHubRepository(val installationId: Long, val repositoryId: Long) : ProjectBackupIntent()
    object SyncGitHubBackup : ProjectBackupIntent()
    data class SetAutomaticGitHubBackup(val enabled: Boolean) : ProjectBackupIntent()
    object PreviewGitHubRestore : ProjectBackupIntent()
    data class ConfirmGitHubRestore(val confirmationToken: String) : ProjectBackupIntent()
    data class PreviewRecovery(val refName: String) : ProjectBackupIntent()
    data class ConfirmRecovery(val refName: String, val confirmationToken: String) : ProjectBackupIntent()
    data class ExportArchive(val destinationPath: String) : ProjectBackupIntent()
    object DisconnectGitHubDestination : ProjectBackupIntent()
    object DisconnectGitHub : ProjectBackupIntent()
    object ClearMessage : ProjectBackupIntent()
}

/** Coordinates review-first project history and optional private GitHub backup. */
class ProjectBackupViewModel(
    private val service: ProjectVersionControlService,
    private val remoteBackup: ProjectRemoteBackupService,
    private val recovery: ProjectRecoveryService,
    private val githubAuthentication: GitHubAuthenticationService,
    private val autoSync: ProjectBackupAutoSyncService,
    private val archiveExporter: ProjectArchiveExporter,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ProjectBackupState(githubState = githubAuthentication.state.value))
    val state: StateFlow<ProjectBackupState> = _state.asStateFlow()

    init {
        scope.launch {
            githubAuthentication.state.collectLatest { github ->
                _state.update { it.copy(githubState = github) }
            }
        }
        scope.launch {
            autoSync.state.collectLatest { state ->
                _state.update { it.copy(autoSync = state) }
            }
        }
    }

    fun onIntent(intent: ProjectBackupIntent) {
        when (intent) {
            is ProjectBackupIntent.Load -> load(intent.projectPath)
            ProjectBackupIntent.Refresh -> load(_state.value.projectPath)
            ProjectBackupIntent.ClearMessage -> _state.update { it.copy(notice = null, error = null) }
            ProjectBackupIntent.DisconnectGitHub -> runAction(
                "GitHub was disconnected. Local versions remain on this computer.",
                clearCatalog = true,
            ) {
                githubAuthentication.disconnect()
                autoSync.pauseForSignedOutAccount()
                service.inspect(requireProjectPath())
            }
            ProjectBackupIntent.SignInToGitHub -> runAction(
                "GitHub is connected. Choose an approved personal or team repository.",
                refreshCatalog = true,
            ) {
                githubAuthentication.signIn()
                service.inspect(requireProjectPath())
            }
            ProjectBackupIntent.OpenGitHubAppInstallation -> runAction(
                "GitHub opened the ARES App installation page. Return here and refresh destinations after approval.",
            ) {
                githubAuthentication.openInstallationPage()
                service.inspect(requireProjectPath())
            }
            ProjectBackupIntent.RefreshGitHubDestinations -> runAction(
                "GitHub destinations and permissions were refreshed.",
                refreshCatalog = true,
            ) { service.inspect(requireProjectPath()) }
            is ProjectBackupIntent.SelectGitHubInstallation -> _state.update {
                it.copy(selectedInstallationId = intent.installationId, notice = null, error = null)
            }
            is ProjectBackupIntent.StartLocalHistory -> runAction("Local version history is ready. Review the files below, then save your first version.") {
                service.initialize(requireProjectPath(), intent.authorName, intent.authorEmail)
            }
            is ProjectBackupIntent.SaveVersion -> runAction("Version saved locally. Your working files were not moved or replaced.") {
                service.commit(
                    requireProjectPath(),
                    intent.confirmationToken,
                    intent.message,
                    intent.authorName,
                    intent.authorEmail,
                )
            }
            is ProjectBackupIntent.ConnectGitHubRepository -> runAction(
                "Approved GitHub repository connected and synchronized.",
                refreshCatalog = true,
            ) {
                remoteBackup.connectApprovedRepository(
                    requireProjectPath(),
                    intent.installationId,
                    intent.repositoryId,
                )
            }
            ProjectBackupIntent.SyncGitHubBackup -> runAction(
                "GitHub backup is up to date.",
                refreshCatalog = true,
            ) {
                remoteBackup.pushBackup(requireProjectPath())
            }
            is ProjectBackupIntent.SetAutomaticGitHubBackup -> setAutomaticGitHubBackup(intent.enabled)
            ProjectBackupIntent.PreviewGitHubRestore -> previewRestore()
            is ProjectBackupIntent.ConfirmGitHubRestore -> restoreFromGitHub(intent.confirmationToken)
            is ProjectBackupIntent.PreviewRecovery -> previewRecovery(intent.refName)
            is ProjectBackupIntent.ConfirmRecovery -> recoverToSafetyPoint(intent.refName, intent.confirmationToken)
            is ProjectBackupIntent.ExportArchive -> exportArchive(intent.destinationPath)
            ProjectBackupIntent.DisconnectGitHubDestination -> runAction(
                "The online destination was disconnected. No local or GitHub files were deleted.",
            ) { remoteBackup.disconnectBackupDestination(requireProjectPath()) }
        }
    }

    private fun load(projectPath: String) {
        _state.update { it.copy(projectPath = projectPath, restorePlan = null, recoveryPlan = null, notice = null, error = null) }
        if (projectPath.isBlank()) {
            _state.update { it.copy(plan = null, error = "Choose a robot project before opening Project Backup.") }
            return
        }
        runAction(
            notice = null,
            refreshCatalog = _state.value.githubState is GitHubConnectionState.Connected,
        ) {
            autoSync.load(projectPath)
            service.inspect(projectPath)
        }
    }

    private fun setAutomaticGitHubBackup(enabled: Boolean) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            try {
                val autoSync = autoSync.setEnabled(requireProjectPath(), enabled)
                _state.update {
                    it.copy(
                        isBusy = false,
                        autoSync = autoSync,
                        notice = if (enabled) {
                            "Automatic GitHub backup is on for this project. Local versions remain the source of truth."
                        } else {
                            "Automatic GitHub backup is off. Local project history is unchanged."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = failure.message ?: "Automatic GitHub backup could not be changed.",
                    )
                }
            }
        }
    }

    private fun runAction(
        notice: String?,
        refreshCatalog: Boolean = false,
        clearCatalog: Boolean = false,
        block: suspend () -> ProjectBackupPlan,
    ) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, restorePlan = null, recoveryPlan = null, error = null, notice = null) }
            try {
                val plan = block()
                val catalog = when {
                    clearCatalog -> GitHubBackupCatalog()
                    refreshCatalog -> githubAuthentication.discoverDestinations()
                    else -> _state.value.githubCatalog
                }
                _state.update { current ->
                    val selected = current.selectedInstallationId
                        ?.takeIf { id -> catalog.accounts.any { it.installationId == id } }
                        ?: catalog.accounts.firstOrNull()?.installationId
                    current.copy(
                        plan = plan,
                        githubCatalog = catalog,
                        selectedInstallationId = selected,
                        isBusy = false,
                        notice = notice,
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = failure.message ?: "Project Backup could not complete that action.",
                    )
                }
            }
        }
    }

    private fun previewRestore() {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, restorePlan = null, error = null, notice = null) }
            try {
                val restore = recovery.previewGitHubRestore(requireProjectPath())
                _state.update {
                    it.copy(
                        isBusy = false,
                        restorePlan = restore,
                        notice = when (restore.disposition) {
                            com.ares.analytics.service.versioncontrol.ProjectRestoreDisposition.UP_TO_DATE ->
                                "This computer already has the latest GitHub version."
                            com.ares.analytics.service.versioncontrol.ProjectRestoreDisposition.LOCAL_AHEAD ->
                                "This computer has saved versions that are newer than GitHub. Sync the backup when ready."
                            com.ares.analytics.service.versioncontrol.ProjectRestoreDisposition.REMOTE_AHEAD -> null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = failure.message ?: "GitHub versions could not be checked.",
                    )
                }
            }
        }
    }

    private fun restoreFromGitHub(confirmationToken: String) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            try {
                val plan = recovery.restoreFromGitHub(requireProjectPath(), confirmationToken)
                _state.update {
                    it.copy(
                        plan = plan,
                        restorePlan = null,
                        isBusy = false,
                        notice = "The reviewed GitHub version was restored. ARES preserved the previous local version as a safety checkpoint.",
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = failure.message ?: "The GitHub version could not be restored.",
                    )
                }
            }
        }
    }

    private fun previewRecovery(refName: String) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, restorePlan = null, recoveryPlan = null, error = null, notice = null) }
            try {
                val plan = recovery.previewRecovery(requireProjectPath(), refName)
                _state.update { it.copy(isBusy = false, recoveryPlan = plan) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(isBusy = false, error = failure.message ?: "That recovery point could not be reviewed.")
                }
            }
        }
    }

    private fun recoverToSafetyPoint(refName: String, confirmationToken: String) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            try {
                val plan = recovery.recoverToSafetyPoint(requireProjectPath(), refName, confirmationToken)
                _state.update {
                    it.copy(
                        plan = plan,
                        recoveryPlan = null,
                        restorePlan = null,
                        isBusy = false,
                        notice = "The reviewed recovery point was restored. ARES preserved the version you just left, so it can also be recovered.",
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(isBusy = false, error = failure.message ?: "The recovery point could not be restored.")
                }
            }
        }
    }

    private fun exportArchive(destinationPath: String) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(isBusy = true, error = null, notice = null) }
            try {
                val exported = archiveExporter.export(requireProjectPath(), destinationPath)
                val skipped = exported.skippedSensitivePaths.takeIf(List<String>::isNotEmpty)
                    ?.let { " Private files were intentionally skipped: ${it.joinToString()}." }
                    .orEmpty()
                _state.update {
                    it.copy(
                        isBusy = false,
                        notice = "Portable project archive created with ${exported.fileCount} files at ${exported.destinationPath}.$skipped",
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isBusy = false) }
                throw cancelled
            } catch (failure: Exception) {
                _state.update {
                    it.copy(isBusy = false, error = failure.message ?: "The project archive could not be created.")
                }
            }
        }
    }

    private fun requireProjectPath(): String = _state.value.projectPath.takeIf(String::isNotBlank)
        ?: error("Choose a robot project before opening Project Backup.")
}
