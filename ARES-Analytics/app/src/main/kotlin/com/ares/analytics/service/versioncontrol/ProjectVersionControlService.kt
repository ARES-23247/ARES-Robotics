package com.ares.analytics.service.versioncontrol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

enum class ProjectChangeKind { ADDED, MODIFIED, DELETED, RENAMED, CONFLICT }

data class ProjectChange(val path: String, val kind: ProjectChangeKind)

data class ProjectVersion(
    val commitId: String,
    val message: String,
    val authorName: String,
    val committedAtEpochSeconds: Long,
)

/** Optional boundary used by zero-code editors to checkpoint only the canonical files they saved. */
fun interface ProjectCheckpointRecorder {
    suspend fun checkpoint(projectPath: String, label: String, pathScopes: Set<String>): ProjectBackupPlan?

    companion object {
        val NONE = ProjectCheckpointRecorder { _, _, _ -> null }
    }
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
 * Review-first local Git history for canonical ARES robot projects.
 *
 * JGit is embedded, so local history does not require a separate Git installation. Remote backup
 * synchronization and reviewed recovery have separate current-contract owners.
 */
class ProjectVersionControlService internal constructor(
    private val onBackupRelevantChange: (String) -> Unit,
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
            val recoveryPoints = lastCommit?.let { listProjectRecoveryPoints(git, it) }.orEmpty()
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

    private fun validateIdentity(name: String, email: String) {
        require(name.trim().length in 2..80) { "Enter the student or team member name used for saved versions." }
        require(email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            "Enter a valid email address for saved versions."
        }
    }

    companion object {
        private const val MAX_VISIBLE_VERSIONS = 20
        private const val AUTOMATIC_HISTORY_AUTHOR_NAME = "ARES Robotics Studio"
        private const val AUTOMATIC_HISTORY_AUTHOR_EMAIL = "local-history@aresfirst.org"
        private const val INITIAL_PROJECT_COMMIT_MESSAGE = "Create robot project with ARES Robotics Studio"
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

internal fun isExcludedArchivePath(path: String): Boolean {
    val segments = path.replace('\\', '/').lowercase(Locale.ROOT).split('/').filter(String::isNotEmpty)
    return segments.any { it in setOf(".git", ".gradle", "build", ".idea", ".vscode", "out") } ||
        segments.lastOrNull() in setOf("local.properties", ".ds_store", "thumbs.db")
}
