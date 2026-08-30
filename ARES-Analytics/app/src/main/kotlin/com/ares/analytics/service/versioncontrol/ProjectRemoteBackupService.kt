package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants

/** Owns permission-scoped GitHub destination selection and backup synchronization. */
class ProjectRemoteBackupService internal constructor(
    private val githubAuthentication: GitHubAuthenticationService,
    private val inspectProject: suspend (String) -> ProjectBackupPlan,
    private val onBackupRelevantChange: (String) -> Unit,
    private val onBackupSynchronized: (String) -> Unit,
    private val remotePusher: (Git, String) -> Unit = ::pushWithJGit,
) {
    private val destinationStore = ProjectGitHubDestinationStore()

    init {
        configureJGitLogging()
    }

    suspend fun connectApprovedRepository(
        projectPath: String,
        installationId: Long,
        repositoryId: Long,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val catalog = githubAuthentication.loadCatalog(credential)
            val account = catalog.accounts.singleOrNull { it.installationId == installationId }
                ?: error("That GitHub App installation is no longer available to this account.")
            require(account.canWriteContents) {
                "The ARES GitHub App installation does not have repository Contents: write permission. Ask a team owner to update it."
            }
            val repository = catalog.repositories.singleOrNull {
                it.installationId == installationId && it.repositoryId == repositoryId
            } ?: error("That repository is no longer granted to the ARES GitHub App installation.")
            require(repository.canUseForBackup) { repository.unavailableReason ?: "That repository cannot be used for backup." }
            require(repository.ownerLogin.equals(account.login, ignoreCase = true)) {
                "GitHub returned a repository outside the selected installation account. Nothing was connected."
            }
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git ->
                require(git.repository.resolve(Constants.HEAD) != null) {
                    "Save at least one local version before connecting an online backup."
                }
                require(git.status().call().isClean) {
                    "Save the current changes as a local version before connecting an online backup."
                }
                val origin = destinationStore.originUrl(git)
                require(origin == null || destinationStore.sameRepository(origin, repository.cloneUrl)) {
                    "This project already has a different origin remote. ARES will not replace it."
                }
                val addedOrigin = origin == null
                try {
                    if (addedOrigin) destinationStore.addOrigin(git, repository.cloneUrl)
                    destinationStore.write(git, account, repository)
                    invokeRemoteOperation(RemoteOperation.PUSH) {
                        remotePusher(git, credential.accessToken)
                    }
                } catch (failure: Exception) {
                    destinationStore.clear(git)
                    if (addedOrigin) destinationStore.removeOrigin(git)
                    throw failure
                }
            }
            inspectProject(root.path).also { onBackupSynchronized(root.path) }
        }
    }

    suspend fun pushBackup(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git ->
                require(git.status().call().isClean) {
                    "Save the current changes as a local version before syncing GitHub."
                }
                val destination = destinationStore.read(git)
                    ?: error("Choose an approved personal or team repository before syncing GitHub.")
                val (account, repository) = githubAuthentication.verifyDestinationAccess(credential, destination)
                val origin = destinationStore.originUrl(git)
                    ?: error("The saved GitHub destination has no origin remote. Choose the destination again.")
                require(destinationStore.sameRepository(origin, destination.cloneUrl)) {
                    "The origin remote changed after this destination was approved. ARES will not push."
                }
                if (!destinationStore.sameRepository(origin, repository.cloneUrl)) {
                    destinationStore.updateOrigin(git, repository.cloneUrl)
                }
                destinationStore.write(git, account, repository)
                invokeRemoteOperation(RemoteOperation.PUSH) {
                    remotePusher(git, credential.accessToken)
                }
            }
            inspectProject(root.path).also { onBackupSynchronized(root.path) }
        }
    }

    suspend fun disconnectBackupDestination(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        val root = requireCanonicalProjectRoot(projectPath)
        Git.open(root).use { git ->
            require(destinationStore.read(git) != null) {
                "This project has no ARES-managed GitHub destination to disconnect."
            }
            if (destinationStore.originUrl(git) != null) destinationStore.removeOrigin(git)
            destinationStore.clear(git)
        }
        inspectProject(root.path).also { onBackupRelevantChange(root.path) }
    }
}
