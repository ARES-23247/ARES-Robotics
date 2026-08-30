package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import java.time.Instant

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

/** Owns reviewed GitHub restores and local ARES safety-point recovery. */
class ProjectRecoveryService internal constructor(
    private val githubAuthentication: GitHubAuthenticationService,
    private val inspectProject: suspend (String) -> ProjectBackupPlan,
    private val remoteMainFetcher: (Git, String) -> ObjectId = ::fetchMainWithJGit,
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
) {
    private val destinationStore = ProjectGitHubDestinationStore()
    private val reviewTokens = ProjectReviewTokenFactory()

    suspend fun previewGitHubRestore(projectPath: String): ProjectRestorePlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git -> prepareGitHubRestore(git, credential) }
        }
    }

    suspend fun restoreFromGitHub(
        projectPath: String,
        expectedConfirmationToken: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        githubAuthentication.withCredential { credential ->
            val root = requireCanonicalProjectRoot(projectPath)
            Git.open(root).use { git ->
                val reviewed = prepareGitHubRestore(git, credential)
                require(reviewed.canRestore && reviewed.confirmationToken == expectedConfirmationToken) {
                    "The GitHub backup changed after this preview. Review the updated file list before restoring."
                }
                val localId = ObjectId.fromString(reviewed.localCommit)
                val remoteId = ObjectId.fromString(reviewed.remoteCommit)
                createSafetyRef(git, localId, remoteId)
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
            requireCanonicalProjectRoot(root.path)
            inspectProject(root.path)
        }
    }

    suspend fun previewRecovery(
        projectPath: String,
        recoveryRefName: String,
    ): ProjectRecoveryPlan = withContext(Dispatchers.IO) {
        val root = requireCanonicalProjectRoot(projectPath)
        Git.open(root).use { git -> prepareRecovery(git, recoveryRefName) }
    }

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
            createSafetyRef(git, currentId, targetId)
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(targetId.name).call()
            require(git.repository.resolve(Constants.HEAD) == targetId && git.status().call().isClean) {
                "ARES could not restore the reviewed safety point. The current version is still preserved as a recovery point."
            }
        }
        requireCanonicalProjectRoot(root.path)
        inspectProject(root.path)
    }

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

    private fun prepareGitHubRestore(
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

    private fun validateRestorableTree(git: Git, commitId: ObjectId) {
        var canonicalMarkerFound = false
        var totalBytes = 0L
        RevWalk(git.repository).use { walk ->
            val commit = walk.parseCommit(commitId)
            TreeWalk(git.repository).use { tree ->
                tree.addTree(commit.tree)
                tree.isRecursive = true
                while (tree.next()) {
                    val path = tree.pathString
                    require(!isSensitiveProjectPath(path)) {
                        "The reviewed version contains a private credential path ($path). ARES will not restore it."
                    }
                    val mode = tree.getFileMode(0)
                    require(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                        "The reviewed version contains an unsupported link or special file ($path). ARES will not restore it."
                    }
                    val size = git.repository.open(tree.getObjectId(0)).size
                    require(size <= ProjectVersionControlLimits.MAX_REVIEWED_FILE_BYTES) {
                        "$path is too large for a reviewed restore."
                    }
                    totalBytes = Math.addExact(totalBytes, size)
                    require(totalBytes <= ProjectVersionControlLimits.MAX_RESTORED_PROJECT_BYTES) {
                        "The reviewed project is too large for a restore."
                    }
                    if (path == ".ares/project.json") canonicalMarkerFound = true
                }
            }
        }
        require(canonicalMarkerFound) {
            "The reviewed version is not a canonical ARES robot project. Nothing was restored."
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

    private fun createSafetyRef(git: Git, currentId: ObjectId, targetId: ObjectId) {
        val refName = "$RESTORE_BACKUP_REF_PREFIX${epochSeconds()}-${currentId.name.take(12)}-${targetId.name.take(12)}"
        val update = git.repository.updateRef(refName)
        update.setNewObjectId(currentId)
        update.setExpectedOldObjectId(ObjectId.zeroId())
        require(update.update().name in setOf("NEW", "NO_CHANGE")) {
            "ARES could not create the restore safety checkpoint. Nothing was restored."
        }
    }

}

internal const val RESTORE_BACKUP_REF_PREFIX = "refs/ares/restore-backups/"

internal fun listProjectRecoveryPoints(git: Git, currentCommit: String): List<ProjectRecoveryPoint> =
    git.repository.refDatabase.getRefsByPrefix(RESTORE_BACKUP_REF_PREFIX)
        .asSequence()
        .filter { ref -> ref.objectId?.name != currentCommit }
        .mapNotNull { ref ->
            val commitId = ref.objectId ?: return@mapNotNull null
            RevWalk(git.repository).use { walk ->
                val commit = runCatching { walk.parseCommit(commitId) }.getOrNull() ?: return@use null
                ProjectRecoveryPoint(
                    refName = ref.name,
                    commitId = commit.name,
                    message = commit.shortMessage.ifBlank { "Saved recovery point" },
                    authorName = commit.authorIdent?.name?.takeIf(String::isNotBlank) ?: "Unknown teammate",
                    committedAtEpochSeconds = commit.commitTime.toLong(),
                )
            }
        }
        .sortedByDescending(ProjectRecoveryPoint::committedAtEpochSeconds)
        .take(10)
        .toList()
