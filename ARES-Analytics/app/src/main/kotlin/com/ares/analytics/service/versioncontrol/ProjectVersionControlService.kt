package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

enum class ProjectChangeKind { ADDED, MODIFIED, DELETED, RENAMED, CONFLICT }

data class ProjectChange(val path: String, val kind: ProjectChangeKind)

data class ProjectVersion(
    val commitId: String,
    val message: String,
    val authorName: String,
    val committedAtEpochSeconds: Long,
)

data class ProjectRecoveryPoint(
    val refName: String,
    val commitId: String,
    val message: String,
    val authorName: String,
    val committedAtEpochSeconds: Long,
)

data class ProjectRecoveryPlan(
    val refName: String,
    val currentCommit: String,
    val targetCommit: String,
    val changes: List<ProjectChange>,
    val confirmationToken: String?,
) {
    val canRecover: Boolean get() = changes.isNotEmpty() && confirmationToken != null
}
/** Optional boundary used by zero-code editors to checkpoint only the canonical files they saved. */
fun interface ProjectCheckpointRecorder {
    suspend fun checkpoint(projectPath: String, label: String, pathScopes: Set<String>): ProjectBackupPlan?

    companion object {
        val NONE = ProjectCheckpointRecorder { _, _, _ -> null }
    }
}
enum class ProjectRestoreDisposition { UP_TO_DATE, REMOTE_AHEAD, LOCAL_AHEAD }

data class ProjectRestorePlan(
    val disposition: ProjectRestoreDisposition,
    val localCommit: String,
    val remoteCommit: String,
    val changes: List<ProjectChange>,
    val confirmationToken: String?,
) {
    val canRestore: Boolean get() =
        disposition == ProjectRestoreDisposition.REMOTE_AHEAD && changes.isNotEmpty() && confirmationToken != null
}

data class ProjectBackupPlan(
    val projectPath: String,
    val initialized: Boolean,
    val branch: String?,
    val changes: List<ProjectChange>,
    val blockedSensitivePaths: List<String>,
    val lastCommit: String?,
    val remoteUrl: String?,
    val destination: GitHubBackupDestination?,
    val confirmationToken: String?,
    val versions: List<ProjectVersion> = emptyList(),
    val recoveryPoints: List<ProjectRecoveryPoint> = emptyList(),
) {
    val canCommit: Boolean get() =
        initialized && changes.isNotEmpty() && blockedSensitivePaths.isEmpty() && confirmationToken != null
}

/**
 * Review-first local Git history plus an optional permission-scoped GitHub App backup.
 *
 * JGit is embedded, so local history does not require a separate Git installation. GitHub App
 * user tokens are protected outside the project and never appear in remotes or process arguments.
 * Every synchronization revalidates the stable installation/repository identity and current
 * private/write permissions before sending bytes.
 */
class ProjectVersionControlService internal constructor(
    private val githubAuthentication: GitHubAuthenticationService,
    private val onBackupRelevantChange: (String) -> Unit,
    private val onBackupSynchronized: (String) -> Unit,
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val remotePusher: (Git, String) -> Unit = ::pushWithJGit,
    private val remoteMainFetcher: (Git, String) -> ObjectId = ::fetchMainWithJGit,
) : ProjectCheckpointRecorder {
    init {
        configureJGitLogging()
    }

    private val historyMutex = Mutex()
    private val destinationStore = ProjectGitHubDestinationStore()
    private val reviewTokens = ProjectReviewTokenFactory()

    suspend fun inspect(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        buildPlan(requireCanonicalProjectRoot(projectPath))
    }

    suspend fun initialize(projectPath: String, authorName: String, authorEmail: String): ProjectBackupPlan =
        withContext(Dispatchers.IO) {
            validateIdentity(authorName, authorEmail)
            val root = requireCanonicalProjectRoot(projectPath)
            require(!File(root, ".git").exists()) { "This project already has local version history." }
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                val config = git.repository.config
                config.setString("user", null, "name", authorName.trim())
                config.setString("user", null, "email", authorEmail.trim())
                config.save()
            }
            buildPlan(root)
        }

    /**
     * Creates the first local version for an app-created project while it is still staged.
     *
     * The app, rather than a student, authors this mechanical baseline. Later reviewed versions
     * can replace the repository identity with the student's or team's name and email.
     */
    suspend fun initializeNewProject(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            val root = requireCanonicalProjectRoot(projectPath)
            require(!File(root, ".git").exists()) { "The staged project already contains Git history." }
            Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
                val config = git.repository.config
                config.setString("user", null, "name", AUTOMATIC_HISTORY_AUTHOR_NAME)
                config.setString("user", null, "email", AUTOMATIC_HISTORY_AUTHOR_EMAIL)
                config.save()
                val initial = buildPlan(root)
                require(initial.blockedSensitivePaths.isEmpty()) {
                    "The starter contains private local files that cannot enter project history: ${initial.blockedSensitivePaths.joinToString()}."
                }
                require(initial.changes.isNotEmpty()) { "The starter did not contain any project files to save." }
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage(INITIAL_PROJECT_COMMIT_MESSAGE)
                    .setAuthor(AUTOMATIC_HISTORY_AUTHOR_NAME, AUTOMATIC_HISTORY_AUTHOR_EMAIL)
                    .setCommitter(AUTOMATIC_HISTORY_AUTHOR_NAME, AUTOMATIC_HISTORY_AUTHOR_EMAIL)
                    .setSign(false)
                    .call()
            }
            buildPlan(root).also { plan ->
                require(plan.lastCommit != null && plan.changes.isEmpty()) {
                    "ARES could not create a clean first project version."
                }
            }
        }
    }

    suspend fun commit(
        projectPath: String,
        expectedConfirmationToken: String,
        message: String,
        authorName: String,
        authorEmail: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        validateIdentity(authorName, authorEmail)
        require(message.trim().length in 3..120) { "Describe this saved version in 3 to 120 characters." }
        val root = requireCanonicalProjectRoot(projectPath)
        val current = buildPlan(root)
        require(current.canCommit) {
            if (current.blockedSensitivePaths.isNotEmpty()) {
                "Remove or ignore sensitive local files before saving a version: ${current.blockedSensitivePaths.joinToString()}."
            } else {
                "There are no reviewed project changes to save."
            }
        }
        require(current.confirmationToken == expectedConfirmationToken) {
            "The project changed after this preview. Review the updated file list before saving."
        }
        Git.open(root).use { git ->
            val config = git.repository.config
            config.setString("user", null, "name", authorName.trim())
            config.setString("user", null, "email", authorEmail.trim())
            config.save()
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            git.commit()
                .setMessage(message.trim())
                .setAuthor(authorName.trim(), authorEmail.trim())
                .setCommitter(authorName.trim(), authorEmail.trim())
                .setSign(false)
                .call()
        }
        buildPlan(root).also { onBackupRelevantChange(root.path) }
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
                    if (addedOrigin) {
                        destinationStore.addOrigin(git, repository.cloneUrl)
                    }
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
            buildPlan(root).also { onBackupSynchronized(root.path) }
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
            buildPlan(root).also { onBackupSynchronized(root.path) }
        }
    }

    /**
     * Fetches and reviews the selected GitHub backup without changing the working tree.
     * Only an unambiguous fast-forward can become restorable.
     */
    suspend fun previewGitHubRestore(projectPath: String): ProjectRestorePlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git -> prepareRestore(git, credential) }
        }
    }

    /** Applies the exact reviewed fast-forward and preserves the previous commit under an ARES safety ref. */
    suspend fun restoreFromGitHub(
        projectPath: String,
        expectedConfirmationToken: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git ->
                val reviewed = prepareRestore(git, credential)
                require(reviewed.canRestore && reviewed.confirmationToken == expectedConfirmationToken) {
                    "The GitHub backup changed after this preview. Review the updated file list before restoring."
                }
                val localId = ObjectId.fromString(reviewed.localCommit)
                val remoteId = ObjectId.fromString(reviewed.remoteCommit)
                createRestoreSafetyRef(git, localId, remoteId)
                val result = git.merge()
                    .include(remoteId)
                    .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                    .call()
                require(result.mergeStatus.isSuccessful) {
                    "ARES could not safely restore the reviewed GitHub version. The previous local version is still preserved."
                }
                require(git.repository.resolve(Constants.HEAD) == remoteId) {
                    "ARES did not reach the reviewed GitHub version. The previous local version is still preserved."
                }
            }
            // Revalidate the canonical marker after JGit updates the working tree.
            requireCanonicalProjectRoot(root.path)
            buildPlan(root)
        }
    }

    /** Reviews a prior ARES-created safety point without changing project files. */
    suspend fun previewRecovery(
        projectPath: String,
        recoveryRefName: String,
    ): ProjectRecoveryPlan = withContext(Dispatchers.IO) {
        val root = requireCanonicalProjectRoot(projectPath)
        Git.open(root).use { git -> prepareRecovery(git, recoveryRefName) }
    }

    /** Restores the exact reviewed safety point and first preserves the current version for redo. */
    suspend fun recoverToSafetyPoint(
        projectPath: String,
        recoveryRefName: String,
        expectedConfirmationToken: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        val root = requireCanonicalProjectRoot(projectPath)
        Git.open(root).use { git ->
            val reviewed = prepareRecovery(git, recoveryRefName)
            require(reviewed.canRecover && reviewed.confirmationToken == expectedConfirmationToken) {
                "The project changed after this recovery preview. Review the updated file list before restoring."
            }
            val currentId = ObjectId.fromString(reviewed.currentCommit)
            val targetId = ObjectId.fromString(reviewed.targetCommit)
            createRestoreSafetyRef(git, currentId, targetId)
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(targetId.name).call()
            require(git.repository.resolve(Constants.HEAD) == targetId && git.status().call().isClean) {
                "ARES could not restore the reviewed safety point. The current version is still preserved as a recovery point."
            }
        }
        requireCanonicalProjectRoot(root.path)
        buildPlan(root)
    }

    /**
     * Creates a local version for canonical files just written by one zero-code editor.
     *
     * This is deliberately path-scoped: unrelated hand edits remain uncommitted and visible for
     * review. Projects that have not opted into local history are left unchanged.
     */
    override suspend fun checkpoint(
        projectPath: String,
        label: String,
        pathScopes: Set<String>,
    ): ProjectBackupPlan? = withContext(Dispatchers.IO) {
        historyMutex.withLock {
            require(label.trim().length in 3..90) { "Describe the automatic checkpoint in 3 to 90 characters." }
            val root = requireCanonicalProjectRoot(projectPath)
            if (!File(root, ".git").isDirectory) return@withLock null
            val scopes = pathScopes.mapTo(linkedSetOf(), ::normalizeCheckpointScope)
            require(scopes.isNotEmpty()) { "An automatic checkpoint must name at least one canonical project file." }
            Git.open(root).use { git ->
                val selected = projectChanges(git).filter { change ->
                    scopes.any { scope -> change.path == scope || change.path.startsWith("$scope/") }
                }
                if (selected.isEmpty()) return@withLock buildPlan(root)
                val sensitive = selected.map(ProjectChange::path).filter(::isSensitiveProjectPath)
                require(sensitive.isEmpty()) {
                    "ARES did not checkpoint private files: ${sensitive.joinToString()}."
                }
                val config = git.repository.config
                val authorName = config.getString("user", null, "name")?.takeIf(String::isNotBlank)
                    ?: error("Local history has no author name. Open Project History and save one reviewed version first.")
                val authorEmail = config.getString("user", null, "email")?.takeIf(String::isNotBlank)
                    ?: error("Local history has no author email. Open Project History and save one reviewed version first.")
                selected.map(ProjectChange::path).distinct().forEach { path ->
                    // JGit's path-limited commit requires new files in the index first. Both calls
                    // remain scoped to the exact reviewed path; unrelated staged or working-tree
                    // changes are still excluded by CommitCommand.setOnly below.
                    git.add().addFilepattern(path).call()
                    git.add().setUpdate(true).addFilepattern(path).call()
                }
                val command = git.commit()
                    .setMessage("ARES checkpoint: ${label.trim()}")
                    .setAuthor(authorName, authorEmail)
                    .setCommitter(authorName, authorEmail)
                    .setSign(false)
                selected.map(ProjectChange::path).distinct().forEach(command::setOnly)
                command.call()
            }
            buildPlan(root).also { onBackupRelevantChange(root.path) }
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
        buildPlan(root).also { onBackupRelevantChange(root.path) }
    }

    private fun buildPlan(root: File): ProjectBackupPlan {
        if (!File(root, ".git").isDirectory) {
            return ProjectBackupPlan(root.path, false, null, emptyList(), emptyList(), null, null, null, null)
        }
        Git.open(root).use { git ->
            val changes = projectChanges(git)
            val sensitive = changes.map(ProjectChange::path).filter(::isSensitiveProjectPath).distinct().sorted()
            val lastCommit = git.repository.resolve(Constants.HEAD)?.name
            val branch = runCatching { git.repository.branch }.getOrNull()
            val versions = if (lastCommit == null) {
                emptyList()
            } else {
                git.log().setMaxCount(MAX_VISIBLE_VERSIONS).call().map { commit ->
                    ProjectVersion(
                        commitId = commit.name,
                        message = commit.shortMessage.ifBlank { "Saved robot version" },
                        authorName = commit.authorIdent?.name?.takeIf(String::isNotBlank) ?: "Unknown teammate",
                        committedAtEpochSeconds = commit.commitTime.toLong(),
                    )
                }
            }
            val recoveryPoints = lastCommit?.let { buildRecoveryPoints(git, it) }.orEmpty()
            return ProjectBackupPlan(
                projectPath = root.path,
                initialized = true,
                branch = branch,
                changes = changes,
                blockedSensitivePaths = sensitive,
                lastCommit = lastCommit,
                remoteUrl = destinationStore.originUrl(git),
                destination = destinationStore.read(git),
                confirmationToken = changes.takeIf { it.isNotEmpty() }?.let { reviewTokens.workingTreeToken(root, it) },
                versions = versions,
                recoveryPoints = recoveryPoints,
            )
        }
    }

    private fun projectChanges(git: Git): List<ProjectChange> {
        val status = git.status().call()
        return buildList {
            status.added.forEach { add(ProjectChange(it, ProjectChangeKind.ADDED)) }
            status.untracked.forEach { add(ProjectChange(it, ProjectChangeKind.ADDED)) }
            status.changed.forEach { add(ProjectChange(it, ProjectChangeKind.MODIFIED)) }
            status.modified.forEach { add(ProjectChange(it, ProjectChangeKind.MODIFIED)) }
            status.removed.forEach { add(ProjectChange(it, ProjectChangeKind.DELETED)) }
            status.missing.forEach { add(ProjectChange(it, ProjectChangeKind.DELETED)) }
            status.conflicting.forEach { add(ProjectChange(it, ProjectChangeKind.CONFLICT)) }
        }.distinctBy { it.path to it.kind }.sortedWith(compareBy(ProjectChange::path, ProjectChange::kind))
    }

    private fun buildRecoveryPoints(git: Git, currentCommit: String): List<ProjectRecoveryPoint> =
        git.repository.refDatabase.getRefsByPrefix(RESTORE_BACKUP_REF_PREFIX)
            .asSequence()
            .filter { ref -> ref.objectId?.name != currentCommit }
            .mapNotNull { ref ->
                val id = ref.objectId ?: return@mapNotNull null
                runCatching {
                    RevWalk(git.repository).use { walk ->
                        val commit = walk.parseCommit(id)
                        ProjectRecoveryPoint(
                            refName = ref.name,
                            commitId = commit.name,
                            message = commit.shortMessage.ifBlank { "Saved recovery point" },
                            authorName = commit.authorIdent?.name?.takeIf(String::isNotBlank) ?: "Unknown teammate",
                            committedAtEpochSeconds = commit.commitTime.toLong(),
                        )
                    }
                }.getOrNull()
            }
            .sortedByDescending(ProjectRecoveryPoint::refName)
            .take(MAX_VISIBLE_RECOVERY_POINTS)
            .toList()

    private fun prepareRecovery(git: Git, recoveryRefName: String): ProjectRecoveryPlan {
        require(git.status().call().isClean) {
            "Save the current changes as a local version before restoring a recovery point."
        }
        require(recoveryRefName.startsWith(RESTORE_BACKUP_REF_PREFIX)) {
            "That recovery point is not owned by ARES."
        }
        val targetId = git.repository.exactRef(recoveryRefName)?.objectId
            ?: error("That recovery point is no longer available.")
        val currentId = git.repository.resolve(Constants.HEAD)
            ?: error("Save at least one local version before using recovery.")
        require(targetId != currentId) { "The project already matches that recovery point." }
        validateRestorableTree(git, targetId)
        val changes = diffCommits(git, currentId, targetId)
        require(changes.isNotEmpty()) { "The project already contains the same reviewed files." }
        return ProjectRecoveryPlan(
            refName = recoveryRefName,
            currentCommit = currentId.name,
            targetCommit = targetId.name,
            changes = changes,
            confirmationToken = reviewTokens.restoreToken(currentId.name, targetId.name, changes),
        )
    }

    private fun prepareRestore(
        git: Git,
        credential: StoredGitHubAppCredential,
    ): ProjectRestorePlan {
        require(git.status().call().isClean) {
            "Save the current changes as a local version before checking or restoring GitHub."
        }
        val destination = destinationStore.read(git)
            ?: error("Choose an approved personal or team repository before checking GitHub versions.")
        val (_, repository) = githubAuthentication.verifyDestinationAccess(credential, destination)
        val origin = destinationStore.originUrl(git)
            ?: error("The saved GitHub destination has no origin remote. Choose the destination again.")
        require(destinationStore.sameRepository(origin, repository.cloneUrl)) {
            "The origin remote changed after this destination was approved. ARES will not restore from it."
        }
        val localId = git.repository.resolve(Constants.HEAD)
            ?: error("Save at least one local version before checking GitHub versions.")
        val remoteId = invokeRemoteOperation(RemoteOperation.FETCH) {
            remoteMainFetcher(git, credential.accessToken)
        }
        val disposition = RevWalk(git.repository).use { walk ->
            val local = walk.parseCommit(localId)
            val remote = walk.parseCommit(remoteId)
            when {
                localId == remoteId -> ProjectRestoreDisposition.UP_TO_DATE
                walk.isMergedInto(local, remote) -> ProjectRestoreDisposition.REMOTE_AHEAD
                walk.isMergedInto(remote, local) -> ProjectRestoreDisposition.LOCAL_AHEAD
                else -> error(
                    "Both this computer and GitHub contain different saved versions. " +
                        "ARES will not guess which work to replace; inspect and reconcile the histories before retrying.",
                )
            }
        }
        val changes = if (disposition == ProjectRestoreDisposition.REMOTE_AHEAD) {
            validateRestorableTree(git, remoteId)
            diffCommits(git, localId, remoteId)
        } else {
            emptyList()
        }
        return ProjectRestorePlan(
            disposition = disposition,
            localCommit = localId.name,
            remoteCommit = remoteId.name,
            changes = changes,
            confirmationToken = changes.takeIf { it.isNotEmpty() }
                ?.let { reviewTokens.restoreToken(localId.name, remoteId.name, it) },
        )
    }

    private fun validateRestorableTree(git: Git, remoteId: ObjectId) {
        var canonicalMarkerFound = false
        var totalBytes = 0L
        RevWalk(git.repository).use { walk ->
            val commit = walk.parseCommit(remoteId)
            TreeWalk(git.repository).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val path = tree.pathString
                    require(!isSensitiveProjectPath(path)) {
                        "The GitHub version contains a private credential path ($path). ARES will not restore it."
                    }
                    val mode = tree.getFileMode(0)
                    require(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        "The GitHub version contains an unsupported link or special file ($path). ARES will not restore it."
                    }
                    val size = git.repository.open(tree.getObjectId(0)).size
                    require(size <= ProjectVersionControlLimits.MAX_REVIEWED_FILE_BYTES) {
                        "$path is too large for a reviewed GitHub restore."
                    }
                    totalBytes = Math.addExact(totalBytes, size)
                    require(totalBytes <= ProjectVersionControlLimits.MAX_RESTORED_PROJECT_BYTES) {
                        "The GitHub project is too large for a reviewed restore."
                    }
                    if (path == ".ares/project.json") canonicalMarkerFound = true
                }
            }
        }
        require(canonicalMarkerFound) {
            "The GitHub version is not a canonical ARES robot project. Nothing was restored."
        }
    }

    private fun diffCommits(git: Git, localId: ObjectId, remoteId: ObjectId): List<ProjectChange> =
        RevWalk(git.repository).use { walk ->
            val localTree = walk.parseCommit(localId).tree
            val remoteTree = walk.parseCommit(remoteId).tree
            DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
                formatter.setRepository(git.repository)
                formatter.isDetectRenames = true
                formatter.scan(localTree, remoteTree).map { entry ->
                    val kind = when (entry.changeType) {
                        DiffEntry.ChangeType.ADD -> ProjectChangeKind.ADDED
                        DiffEntry.ChangeType.DELETE -> ProjectChangeKind.DELETED
                        DiffEntry.ChangeType.RENAME, DiffEntry.ChangeType.COPY -> ProjectChangeKind.RENAMED
                        DiffEntry.ChangeType.MODIFY -> ProjectChangeKind.MODIFIED
                    }
                    val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) entry.oldPath else entry.newPath
                    ProjectChange(path, kind)
                }.sortedWith(compareBy(ProjectChange::path, ProjectChange::kind))
            }
        }

    private fun createRestoreSafetyRef(git: Git, localId: ObjectId, remoteId: ObjectId) {
        val refName = "$RESTORE_BACKUP_REF_PREFIX${epochSeconds()}-${localId.name.take(12)}-${remoteId.name.take(12)}"
        val update = git.repository.updateRef(refName)
        update.setNewObjectId(localId)
        update.setExpectedOldObjectId(ObjectId.zeroId())
        require(update.update().name in setOf("NEW", "NO_CHANGE")) {
            "ARES could not create the restore safety checkpoint. Nothing was restored."
        }
    }

    private fun <T> invokeRemoteOperation(operation: RemoteOperation, block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        val safeMessage = friendlyRemoteFailure(operation, failure)
        if (safeMessage == failure.message) throw failure
        throw IllegalStateException(safeMessage, failure)
    }

    private fun validateIdentity(name: String, email: String) {
        require(name.trim().length in 2..80) { "Enter the student or team member name used for saved versions." }
        require(email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            "Enter a valid email address for saved versions."
        }
    }

    companion object {
        private const val MAX_VISIBLE_VERSIONS = 20
        private const val MAX_VISIBLE_RECOVERY_POINTS = 10
        private const val AUTOMATIC_HISTORY_AUTHOR_NAME = "ARES Robotics Studio"
        private const val AUTOMATIC_HISTORY_AUTHOR_EMAIL = "local-history@aresfirst.org"
        private const val INITIAL_PROJECT_COMMIT_MESSAGE = "Create robot project with ARES Robotics Studio"
        private const val RESTORE_BACKUP_REF_PREFIX = "refs/ares/restore-backups/"
    }

    private fun normalizeCheckpointScope(scope: String): String {
        val normalized = scope.replace('\\', '/').trim().trim('/')
        require(normalized.isNotEmpty() && normalized != "." && !normalized.startsWith(".git")) {
            "Automatic checkpoint paths must stay inside canonical project files."
        }
        require(normalized.split('/').none { it == "." || it == ".." }) {
            "Automatic checkpoint paths must not contain traversal segments."
        }
        return normalized
    }
}

private enum class RemoteOperation { PUSH, FETCH }

/** JGit defaults to DEBUG under Logback's fallback configuration and otherwise prints local paths. */
internal fun configureJGitLogging() {
    val context = LoggerFactory.getILoggerFactory() as? ch.qos.logback.classic.LoggerContext ?: return
    context.getLogger("org.eclipse.jgit").level = ch.qos.logback.classic.Level.WARN
}

internal fun validGitHubClientId(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9_-]{12,128}")) && !value.contains("mock", ignoreCase = true)

internal fun validGitHubAppSlug(value: String): Boolean =
    value.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,98}[a-z0-9])?"))

internal fun validGitHubAppConfiguration(clientId: String, slug: String): Boolean =
    validGitHubClientId(clientId) && validGitHubAppSlug(slug)

internal fun isSensitiveProjectPath(path: String): Boolean {
    val normalized = path.replace('\\', '/').lowercase(Locale.ROOT)
    // The FTC SDK distributes this public debug-signing fixture with every team project. It is
    // required for a portable debug build and is not a team's release/deployment credential.
    if (normalized == "libs/ftc.debug.keystore") return false
    val name = normalized.substringAfterLast('/')
    val privateKeySuffixes = setOf(".jks", ".keystore", ".p12", ".pfx")
    val containsPrivateKeySuffix = privateKeySuffixes.any { suffix ->
        name.endsWith(suffix) || name.contains("$suffix.")
    }
    return normalized == "local.properties" ||
        name == ".env" || name.startsWith(".env.") ||
        containsPrivateKeySuffix ||
        name in setOf("credentials.json", "service-account.json", "service_account.json") ||
        normalized.startsWith(".ares/secrets/")
}

private fun pushWithJGit(git: Git, accessToken: String) {
    val results = git.push()
        .setRemote("origin")
        .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", accessToken))
        .setPushAll()
        .call()
    val failures = results.flatMap { it.remoteUpdates }
        .filter { update -> update.status.name !in setOf("OK", "UP_TO_DATE") }
    require(failures.isEmpty()) {
        "GitHub rejected the backup update (${failures.joinToString { it.status.name }}). Nothing remote was overwritten; refresh and resolve the history difference before retrying."
    }
}

internal fun isExcludedArchivePath(path: String): Boolean {
    val segments = path.replace('\\', '/').lowercase(Locale.ROOT).split('/').filter(String::isNotEmpty)
    return segments.any { it in setOf(".git", ".gradle", "build", ".idea", ".vscode", "out") } ||
        segments.lastOrNull() in setOf("local.properties", ".ds_store", "thumbs.db")
}

private fun fetchMainWithJGit(git: Git, accessToken: String): ObjectId {
    git.fetch()
        .setRemote("origin")
        .setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", accessToken))
        .setRefSpecs(RefSpec("+refs/heads/main:refs/remotes/origin/main"))
        .call()
    return git.repository.resolve("refs/remotes/origin/main")
        ?: error("The selected GitHub repository does not contain a main branch to restore.")
}

private fun friendlyRemoteFailure(operation: RemoteOperation, failure: Throwable): String {
    val messages = generateSequence(failure) { it.cause }
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    if (failure.message?.startsWith("GitHub ") == true || failure.message?.startsWith("The selected GitHub ") == true) {
        return failure.message.orEmpty()
    }
    val permissionDenied = listOf(
        "git-receive-pack not permitted",
        "git-upload-pack not permitted",
        "repository not found",
        "not authorized",
        "unauthorized",
        "forbidden",
        "status code: 403",
        "status code 403",
        "authentication is required",
    ).any(messages::contains)
    if (permissionDenied) {
        val access = if (operation == RemoteOperation.PUSH) "write to" else "read"
        return "ARES no longer has permission to $access this GitHub repository. " +
            "Ask a team owner to restore the ARES GitHub App's repository access, then choose Refresh destinations. " +
            "Local project history is unchanged."
    }
    val unreachable = listOf(
        "timed out",
        "timeout",
        "unknownhost",
        "connection reset",
        "connection refused",
        "network is unreachable",
        "could not resolve host",
    ).any(messages::contains)
    if (unreachable) {
        return "GitHub could not be reached. Check the internet connection and try again. Local project history is unchanged."
    }
    return if (operation == RemoteOperation.PUSH) {
        "GitHub could not update this backup. Refresh destinations and try again; local project history is unchanged."
    } else {
        "GitHub could not check this backup. Refresh destinations and try again; local project history is unchanged."
    }
}
