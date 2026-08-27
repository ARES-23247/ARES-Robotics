package com.ares.analytics.service.versioncontrol

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.writeFileAtomically
import com.ares.analytics.shared.AppJson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.util.io.DisabledOutputStream
import org.slf4j.LoggerFactory
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

data class ProjectArchiveResult(
    val destinationPath: String,
    val fileCount: Int,
    val uncompressedBytes: Long,
    val skippedSensitivePaths: List<String>,
)

enum class ProjectBackupAutoSyncStatus {
    DISABLED,
    WAITING_FOR_DESTINATION,
    WAITING_FOR_LOCAL_SAVE,
    SCHEDULED,
    SYNCING,
    UP_TO_DATE,
    OFFLINE_RETRY,
    ATTENTION_REQUIRED,
}

/** Plain-language state for the optional, project-scoped GitHub backup automation. */
data class ProjectBackupAutoSyncState(
    val projectPath: String = "",
    val enabled: Boolean = false,
    val status: ProjectBackupAutoSyncStatus = ProjectBackupAutoSyncStatus.DISABLED,
    val message: String = "Automatic GitHub backup is off.",
    val lastSuccessEpochSeconds: Long? = null,
)

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

sealed class GitHubConnectionState {
    object Disconnected : GitHubConnectionState()
    data class Unavailable(val message: String) : GitHubConnectionState()
    data class AwaitingUser(
        val userCode: String,
        val verificationUri: String,
        val expiresAtEpochSeconds: Long,
    ) : GitHubConnectionState()
    data class Connected(val login: String) : GitHubConnectionState()
    data class Error(val message: String) : GitHubConnectionState()
}

@Serializable
internal data class StoredGitHubAppCredential(
    val schemaVersion: Int,
    val accessToken: String,
    val accessTokenExpiresAtEpochSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtEpochSeconds: Long,
    val login: String,
)

private class LegacyGitHubCredentialException : IllegalStateException()

/**
 * Review-first local Git history plus an optional permission-scoped GitHub App backup.
 *
 * JGit is embedded, so local history does not require a separate Git installation. GitHub App
 * user tokens are protected outside the project and never appear in remotes or process arguments.
 * Every synchronization revalidates the stable installation/repository identity and current
 * private/write permissions before sending bytes.
 */
class ProjectVersionControlService internal constructor(
    private val githubClientId: String = BuildConfig.GITHUB_APP_CLIENT_ID,
    private val githubAppSlug: String = BuildConfig.GITHUB_APP_SLUG,
    private val credentialStore: ProjectBackupCredentialStore = createProjectBackupCredentialStore(),
    private val githubApi: GitHubProjectApi = DefaultGitHubProjectApi(),
    private val browserLauncher: (String) -> Unit = { uri -> Desktop.getDesktop().browse(URI(uri)) },
    private val pollDelay: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val remotePusher: (Git, String) -> Unit = ::pushWithJGit,
    private val remoteMainFetcher: (Git, String) -> ObjectId = ::fetchMainWithJGit,
    private val autoSyncDelay: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
) : ProjectCheckpointRecorder {
    init {
        configureJGitLogging()
    }

    private val githubMutex = Mutex()
    private val historyMutex = Mutex()
    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val autoSyncRequests = Channel<String>(Channel.CONFLATED)
    private val autoSyncJob: Job
    private val _githubState = MutableStateFlow<GitHubConnectionState>(initialGitHubState())
    val githubState: StateFlow<GitHubConnectionState> = _githubState.asStateFlow()
    private val _autoSyncState = MutableStateFlow(ProjectBackupAutoSyncState())
    val autoSyncState: StateFlow<ProjectBackupAutoSyncState> = _autoSyncState.asStateFlow()

    init {
        autoSyncJob = autoSyncScope.launch { processAutoSyncRequests() }
    }

    suspend fun inspect(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        buildPlan(requireProjectRoot(projectPath))
    }

    suspend fun initialize(projectPath: String, authorName: String, authorEmail: String): ProjectBackupPlan =
        withContext(Dispatchers.IO) {
            validateIdentity(authorName, authorEmail)
            val root = requireProjectRoot(projectPath)
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
            val root = requireProjectRoot(projectPath)
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

    /** Loads the local automation preference for the active workspace without contacting GitHub. */
    suspend fun loadAutoSync(projectPath: String): ProjectBackupAutoSyncState = withContext(Dispatchers.IO) {
        val root = requireProjectRoot(projectPath)
        val state = autoSyncStateFor(root)
        _autoSyncState.value = state
        state
    }

    /** Enables or disables online backup for this project only. It is always off by default. */
    suspend fun setAutoSyncEnabled(projectPath: String, enabled: Boolean): ProjectBackupAutoSyncState =
        withContext(Dispatchers.IO) {
            val root = requireProjectRoot(projectPath)
            Git.open(root).use { git ->
                require(git.repository.resolve(Constants.HEAD) != null) {
                    "Save the first local project version before enabling automatic GitHub backup."
                }
                val config = git.repository.config
                config.setBoolean(AUTO_SYNC_CONFIG_SECTION, null, AUTO_SYNC_CONFIG_NAME, enabled)
                config.save()
            }
            val state = autoSyncStateFor(root)
            _autoSyncState.value = state
            if (enabled) scheduleAutoSync(root)
            _autoSyncState.value
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
        val root = requireProjectRoot(projectPath)
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
        buildPlan(root).also { scheduleAutoSync(root) }
    }

    suspend fun signInToGitHub() = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            requireValidGitHubAppConfiguration()
            val authorization = githubApi.beginDeviceAuthorization(githubClientId)
            val expiresAt = epochSeconds() + authorization.expiresInSeconds
            _githubState.value = GitHubConnectionState.AwaitingUser(
                authorization.userCode,
                authorization.verificationUri,
                expiresAt,
            )
            browserLauncher(authorization.verificationUri)
            var interval = authorization.intervalSeconds.coerceAtLeast(MINIMUM_DEVICE_POLL_SECONDS)
            while (epochSeconds() < expiresAt) {
                pollDelay(interval * 1_000)
                when (val result = githubApi.pollDeviceAuthorization(githubClientId, authorization.deviceCode)) {
                    GitHubDevicePollResult.Pending -> Unit
                    GitHubDevicePollResult.SlowDown -> interval += 5
                    is GitHubDevicePollResult.Authorized -> {
                        val login = githubApi.currentLogin(result.tokens.accessToken)
                        val credential = credentialFrom(result.tokens, login)
                        storeCredential(credential)
                        _githubState.value = GitHubConnectionState.Connected(login)
                        return@serializedGitHubOperation
                    }
                    is GitHubDevicePollResult.Failed -> failGitHubSignIn(result.code)
                }
            }
            val message = "The GitHub sign-in code expired. Start sign-in again to receive a new code."
            _githubState.value = GitHubConnectionState.Error(message)
            error(message)
        }
    }

    suspend fun discoverGitHubDestinations(): GitHubBackupCatalog = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            loadCatalog(requireUsableCredential())
        }
    }

    suspend fun openGitHubAppInstallation() = withContext(Dispatchers.IO) {
        requireValidGitHubAppConfiguration()
        browserLauncher("https://github.com/apps/$githubAppSlug/installations/new")
    }

    suspend fun connectApprovedRepository(
        projectPath: String,
        installationId: Long,
        repositoryId: Long,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            val credential = requireUsableCredential()
            val catalog = loadCatalog(credential)
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
            val root = requireProjectRoot(projectPath)
            Git.open(root).use { git ->
                require(git.repository.resolve(Constants.HEAD) != null) {
                    "Save at least one local version before connecting an online backup."
                }
                require(git.status().call().isClean) {
                    "Save the current changes as a local version before connecting an online backup."
                }
                val origin = originUrl(git)
                require(origin == null || sameGitHubRepository(origin, repository.cloneUrl)) {
                    "This project already has a different origin remote. ARES will not replace it."
                }
                val addedOrigin = origin == null
                try {
                    if (addedOrigin) {
                        git.remoteAdd().setName(ORIGIN_REMOTE).setUri(URIish(repository.cloneUrl)).call()
                    }
                    writeDestination(git, account, repository)
                    invokeRemoteOperation(RemoteOperation.PUSH) {
                        remotePusher(git, credential.accessToken)
                    }
                } catch (failure: Exception) {
                    clearDestination(git)
                    if (addedOrigin) git.remoteRemove().setRemoteName(ORIGIN_REMOTE).call()
                    throw failure
                }
            }
            buildPlan(root).also { markAutoSyncSuccessIfEnabled(root) }
        }
    }

    suspend fun pushBackup(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            val credential = requireUsableCredential()
            val root = requireProjectRoot(projectPath)
            Git.open(root).use { git ->
                require(git.status().call().isClean) {
                    "Save the current changes as a local version before syncing GitHub."
                }
                val destination = readDestination(git)
                    ?: error("Choose an approved personal or team repository before syncing GitHub.")
                val (account, repository) = verifyDestinationAccess(credential, destination)
                val origin = originUrl(git)
                    ?: error("The saved GitHub destination has no origin remote. Choose the destination again.")
                require(sameGitHubRepository(origin, destination.cloneUrl)) {
                    "The origin remote changed after this destination was approved. ARES will not push."
                }
                if (!sameGitHubRepository(origin, repository.cloneUrl)) {
                    git.remoteSetUrl().setRemoteName(ORIGIN_REMOTE).setRemoteUri(URIish(repository.cloneUrl)).call()
                }
                writeDestination(git, account, repository)
                invokeRemoteOperation(RemoteOperation.PUSH) {
                    remotePusher(git, credential.accessToken)
                }
            }
            buildPlan(root).also { markAutoSyncSuccessIfEnabled(root) }
        }
    }

    /**
     * Fetches and reviews the selected GitHub backup without changing the working tree.
     * Only an unambiguous fast-forward can become restorable.
     */
    suspend fun previewGitHubRestore(projectPath: String): ProjectRestorePlan = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            val credential = requireUsableCredential()
            val root = requireProjectRoot(projectPath)
            Git.open(root).use { git -> prepareRestore(git, credential) }
        }
    }

    /** Applies the exact reviewed fast-forward and preserves the previous commit under an ARES safety ref. */
    suspend fun restoreFromGitHub(
        projectPath: String,
        expectedConfirmationToken: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            val credential = requireUsableCredential()
            val root = requireProjectRoot(projectPath)
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
            requireProjectRoot(root.path)
            buildPlan(root)
        }
    }

    /** Reviews a prior ARES-created safety point without changing project files. */
    suspend fun previewRecovery(
        projectPath: String,
        recoveryRefName: String,
    ): ProjectRecoveryPlan = withContext(Dispatchers.IO) {
        val root = requireProjectRoot(projectPath)
        Git.open(root).use { git -> prepareRecovery(git, recoveryRefName) }
    }

    /** Restores the exact reviewed safety point and first preserves the current version for redo. */
    suspend fun recoverToSafetyPoint(
        projectPath: String,
        recoveryRefName: String,
        expectedConfirmationToken: String,
    ): ProjectBackupPlan = withContext(Dispatchers.IO) {
        val root = requireProjectRoot(projectPath)
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
        requireProjectRoot(root.path)
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
            val root = requireProjectRoot(projectPath)
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
            buildPlan(root).also { scheduleAutoSync(root) }
        }
    }

    /** Stops the optional background synchronization worker during desktop shutdown. */
    suspend fun closeAndJoin() {
        autoSyncRequests.close()
        autoSyncJob.cancelAndJoin()
        autoSyncScope.cancel()
    }

    /** Exports a portable, credential-free project archive without Git metadata or build caches. */
    suspend fun exportProjectArchive(
        projectPath: String,
        destinationPath: String,
    ): ProjectArchiveResult = withContext(Dispatchers.IO) {
        val root = requireProjectRoot(projectPath)
        require(destinationPath.isNotBlank()) { "Choose where to save the project archive." }
        val destination = File(destinationPath).canonicalFile
        require(!destination.toPath().startsWith(root.toPath())) {
            "Save the project archive outside the robot project folder."
        }
        require(!destination.exists()) {
            "An archive already exists at that location. Choose a new name so ARES does not replace it."
        }
        val included = mutableListOf<Pair<File, String>>()
        val skippedSensitive = mutableListOf<String>()
        var totalBytes = 0L
        root.walkTopDown().onEnter { directory ->
            require(directory == root || !java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                "The project contains an unsupported directory link (${directory.relativeTo(root).invariantSeparatorsPath}). Remove it before exporting."
            }
            val relative = directory.relativeTo(root).invariantSeparatorsPath
            relative.isEmpty() || !isExcludedArchivePath(relative)
        }.forEach { file ->
            if (file == root || file.isDirectory) return@forEach
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (isExcludedArchivePath(relative)) return@forEach
            if (isSensitiveProjectPath(relative)) {
                skippedSensitive += relative
                return@forEach
            }
            require(!java.nio.file.Files.isSymbolicLink(file.toPath())) {
                "The project contains an unsupported link ($relative). Remove it before exporting."
            }
            require(file.isFile && file.length() <= MAX_ARCHIVE_FILE_BYTES) {
                "$relative is too large for a portable project archive."
            }
            totalBytes = Math.addExact(totalBytes, file.length())
            require(totalBytes <= MAX_ARCHIVE_PROJECT_BYTES) {
                "The project is too large for one portable archive. Remove build outputs or large recordings first."
            }
            included += file to relative
        }
        require(included.any { it.second == ".ares/project.json" }) {
            "The project archive is missing its canonical ARES project identity."
        }
        writeFileAtomically(destination) { temporary ->
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                included.sortedBy { it.second }.forEach { (file, relative) ->
                    val entry = ZipEntry(relative).apply { time = 0L }
                    zip.putNextEntry(entry)
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        ProjectArchiveResult(
            destinationPath = destination.path,
            fileCount = included.size,
            uncompressedBytes = totalBytes,
            skippedSensitivePaths = skippedSensitive.distinct().sorted(),
        )
    }

    suspend fun disconnectBackupDestination(projectPath: String): ProjectBackupPlan = withContext(Dispatchers.IO) {
        val root = requireProjectRoot(projectPath)
        Git.open(root).use { git ->
            require(readDestination(git) != null) {
                "This project has no ARES-managed GitHub destination to disconnect."
            }
            if (originUrl(git) != null) git.remoteRemove().setRemoteName(ORIGIN_REMOTE).call()
            clearDestination(git)
        }
        buildPlan(root).also { _autoSyncState.value = autoSyncStateFor(root) }
    }

    suspend fun disconnectGitHub() = withContext(Dispatchers.IO) {
        serializedGitHubOperation {
            check(credentialStore.delete()) { "GitHub credentials could not be removed from this computer." }
            _githubState.value = if (validGitHubAppConfiguration(githubClientId, githubAppSlug)) {
                GitHubConnectionState.Disconnected
            } else {
                GitHubConnectionState.Unavailable("Official GitHub App backup is not configured in this build.")
            }
            val currentAutoSync = _autoSyncState.value
            if (currentAutoSync.enabled) {
                _autoSyncState.value = currentAutoSync.copy(
                    status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                    message = "Automatic backup is paused. Sign in with GitHub again to continue.",
                )
            }
        }
    }

    private suspend fun <T> serializedGitHubOperation(block: suspend () -> T): T {
        githubMutex.lock()
        try {
            return block()
        } finally {
            githubMutex.unlock()
        }
    }

    private fun scheduleAutoSync(root: File) {
        if (!readAutoSyncEnabled(root)) return
        _autoSyncState.value = ProjectBackupAutoSyncState(
            projectPath = root.path,
            enabled = true,
            status = ProjectBackupAutoSyncStatus.SCHEDULED,
            message = "A local version was saved. GitHub backup is queued.",
            lastSuccessEpochSeconds = _autoSyncState.value.lastSuccessEpochSeconds,
        )
        autoSyncRequests.trySend(root.path)
    }

    private fun markAutoSyncSuccessIfEnabled(root: File) {
        if (!readAutoSyncEnabled(root)) return
        _autoSyncState.value = ProjectBackupAutoSyncState(
            projectPath = root.path,
            enabled = true,
            status = ProjectBackupAutoSyncStatus.UP_TO_DATE,
            message = "GitHub backup is up to date.",
            lastSuccessEpochSeconds = epochSeconds(),
        )
    }

    private suspend fun processAutoSyncRequests() {
        for (firstPath in autoSyncRequests) {
            var projectPath = firstPath
            do {
                autoSyncDelay(AUTO_SYNC_DEBOUNCE_MS)
                val newer = autoSyncRequests.tryReceive().getOrNull()
                if (newer != null) projectPath = newer
            } while (newer != null)
            runAutoSync(projectPath)
        }
    }

    private suspend fun runAutoSync(projectPath: String) {
        val root = runCatching { requireProjectRoot(projectPath) }.getOrElse { failure ->
            _autoSyncState.value = ProjectBackupAutoSyncState(
                projectPath = projectPath,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                message = failure.message ?: "The project is no longer available.",
            )
            return
        }
        if (!readAutoSyncEnabled(root)) {
            _autoSyncState.value = disabledAutoSyncState(root)
            return
        }
        val plan = buildPlan(root)
        if (plan.destination == null) {
            _autoSyncState.value = ProjectBackupAutoSyncState(
                projectPath = root.path,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.WAITING_FOR_DESTINATION,
                message = "Automatic backup is on. Choose an approved GitHub repository to begin syncing.",
            )
            return
        }
        if (plan.changes.isNotEmpty()) {
            _autoSyncState.value = ProjectBackupAutoSyncState(
                projectPath = root.path,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.WAITING_FOR_LOCAL_SAVE,
                message = "Automatic backup is waiting for the remaining project changes to be saved locally.",
            )
            return
        }
        for (attempt in 0 until AUTO_SYNC_RETRY_DELAYS_MS.size) {
            _autoSyncState.value = ProjectBackupAutoSyncState(
                projectPath = root.path,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.SYNCING,
                message = "Syncing the latest saved version to GitHub…",
                lastSuccessEpochSeconds = _autoSyncState.value.lastSuccessEpochSeconds,
            )
            try {
                pushBackup(root.path)
                _autoSyncState.value = ProjectBackupAutoSyncState(
                    projectPath = root.path,
                    enabled = true,
                    status = ProjectBackupAutoSyncStatus.UP_TO_DATE,
                    message = "GitHub backup is up to date.",
                    lastSuccessEpochSeconds = epochSeconds(),
                )
                return
            } catch (failure: Exception) {
                if (!isRecoverableAutoSyncFailure(failure)) {
                    _autoSyncState.value = ProjectBackupAutoSyncState(
                        projectPath = root.path,
                        enabled = true,
                        status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                        message = failure.message ?: "GitHub backup needs attention.",
                        lastSuccessEpochSeconds = _autoSyncState.value.lastSuccessEpochSeconds,
                    )
                    return
                }
                val hasRetry = attempt < AUTO_SYNC_RETRY_DELAYS_MS.lastIndex
                _autoSyncState.value = ProjectBackupAutoSyncState(
                    projectPath = root.path,
                    enabled = true,
                    status = ProjectBackupAutoSyncStatus.OFFLINE_RETRY,
                    message = if (hasRetry) {
                        "GitHub is temporarily unreachable. ARES will retry automatically."
                    } else {
                        "GitHub is still unreachable. Your local versions are safe; use Sync backup now when the connection returns."
                    },
                    lastSuccessEpochSeconds = _autoSyncState.value.lastSuccessEpochSeconds,
                )
                if (!hasRetry) return
                autoSyncDelay(AUTO_SYNC_RETRY_DELAYS_MS[attempt])
            }
        }
    }

    private fun autoSyncStateFor(root: File): ProjectBackupAutoSyncState {
        if (!File(root, ".git").isDirectory || !readAutoSyncEnabled(root)) return disabledAutoSyncState(root)
        val plan = buildPlan(root)
        return when {
            plan.destination == null -> ProjectBackupAutoSyncState(
                root.path,
                true,
                ProjectBackupAutoSyncStatus.WAITING_FOR_DESTINATION,
                "Automatic backup is on. Choose an approved GitHub repository to begin syncing.",
            )
            plan.changes.isNotEmpty() -> ProjectBackupAutoSyncState(
                root.path,
                true,
                ProjectBackupAutoSyncStatus.WAITING_FOR_LOCAL_SAVE,
                "Automatic backup is waiting for project changes to be saved locally.",
            )
            else -> ProjectBackupAutoSyncState(
                root.path,
                true,
                ProjectBackupAutoSyncStatus.UP_TO_DATE,
                "Automatic GitHub backup is on.",
                _autoSyncState.value.lastSuccessEpochSeconds,
            )
        }
    }

    private fun disabledAutoSyncState(root: File) = ProjectBackupAutoSyncState(
        projectPath = root.path,
        enabled = false,
        status = ProjectBackupAutoSyncStatus.DISABLED,
        message = "Automatic GitHub backup is off. Local history still saves versions on this computer.",
    )

    private fun readAutoSyncEnabled(root: File): Boolean {
        if (!File(root, ".git").isDirectory) return false
        return Git.open(root).use { git ->
            git.repository.config.getBoolean(AUTO_SYNC_CONFIG_SECTION, null, AUTO_SYNC_CONFIG_NAME, false)
        }
    }

    private fun loadCatalog(credential: StoredGitHubAppCredential): GitHubBackupCatalog = credentialAware {
        val accounts = githubApi.listInstallations(credential.accessToken)
            .distinctBy(GitHubBackupAccount::installationId)
            .sortedWith(compareBy(GitHubBackupAccount::kind, GitHubBackupAccount::login))
        val repositories = accounts.flatMap { account ->
            githubApi.listRepositories(credential.accessToken, account.installationId)
        }.distinctBy { it.installationId to it.repositoryId }
            .sortedWith(compareBy(GitHubBackupRepository::ownerLogin, GitHubBackupRepository::name))
        GitHubBackupCatalog(accounts, repositories)
    }

    private fun verifyDestinationAccess(
        credential: StoredGitHubAppCredential,
        destination: GitHubBackupDestination,
    ): Pair<GitHubBackupAccount, GitHubBackupRepository> {
        val catalog = loadCatalog(credential)
        val account = catalog.accounts.singleOrNull { it.installationId == destination.installationId }
            ?: error("The saved ${destination.ownerLogin} GitHub App installation is no longer accessible. Ask a team owner to restore it or change destination.")
        require(account.canWriteContents) {
            "The saved GitHub App installation no longer has Contents: write permission. Nothing was synchronized."
        }
        val repository = catalog.repositories.singleOrNull {
            it.installationId == destination.installationId && it.repositoryId == destination.repositoryId
        } ?: error("The saved repository is no longer granted to the ARES GitHub App. Nothing was synchronized.")
        require(repository.canUseForBackup) { repository.unavailableReason ?: "The saved repository cannot accept a backup." }
        return account to repository
    }

    private fun credentialAware(block: () -> GitHubBackupCatalog): GitHubBackupCatalog = try {
        block()
    } catch (failure: GitHubApiException) {
        if (failure.status == 401) invalidateCredential("GitHub access was revoked or expired. Saved access was cleared; sign in again.")
        throw failure
    }

    private fun requireUsableCredential(): StoredGitHubAppCredential {
        val credential = loadCredentialOrInvalidate()
        val now = epochSeconds()
        if (credential.refreshTokenExpiresAtEpochSeconds <= now + TOKEN_EXPIRY_SAFETY_SECONDS) {
            invalidateCredential("GitHub refresh access expired. Saved access was cleared; sign in again.")
        }
        if (credential.accessTokenExpiresAtEpochSeconds > now + TOKEN_EXPIRY_SAFETY_SECONDS) return credential
        val refreshed = try {
            githubApi.refreshUserAccessToken(githubClientId, credential.refreshToken)
        } catch (failure: GitHubAuthorizationException) {
            invalidateCredential(githubRefreshFailureMessage(failure.code))
        } catch (failure: GitHubApiException) {
            if (failure.status == 401) invalidateCredential("GitHub refresh access was revoked. Saved access was cleared; sign in again.")
            throw failure
        }
        return credentialFrom(refreshed, credential.login).also(::storeCredential)
    }

    private fun credentialFrom(tokens: GitHubUserTokens, login: String): StoredGitHubAppCredential {
        val now = epochSeconds()
        require(login.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "GitHub returned an invalid account identity." }
        require(tokens.accessToken.length in 20..2_048 && tokens.refreshToken.length in 20..2_048) {
            "GitHub returned an invalid credential. Sign-in was not saved."
        }
        return StoredGitHubAppCredential(
            schemaVersion = GITHUB_CREDENTIAL_SCHEMA_VERSION,
            accessToken = tokens.accessToken,
            accessTokenExpiresAtEpochSeconds = Math.addExact(now, tokens.expiresInSeconds),
            refreshToken = tokens.refreshToken,
            refreshTokenExpiresAtEpochSeconds = Math.addExact(now, tokens.refreshTokenExpiresInSeconds),
            login = login,
        )
    }

    private fun storeCredential(credential: StoredGitHubAppCredential) {
        credentialStore.write(
            AppJson.encodeToString(StoredGitHubAppCredential.serializer(), credential)
                .toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun loadCredentialOrInvalidate(): StoredGitHubAppCredential {
        val bytes = try {
            credentialStore.read()
        } catch (_: Exception) {
            invalidateCredential("Saved GitHub access was unreadable and has been cleared. Sign in again.")
        } ?: error("Sign in with GitHub before choosing or synchronizing a backup.")
        return try {
            decodeCredential(bytes)
        } catch (_: LegacyGitHubCredentialException) {
            invalidateCredential("An older broad GitHub OAuth credential was removed. Sign in again with the permission-scoped ARES GitHub App.")
        } catch (_: Exception) {
            invalidateCredential("Saved GitHub access was invalid and has been cleared. Sign in again.")
        }
    }

    private fun decodeCredential(bytes: ByteArray): StoredGitHubAppCredential {
        val text = bytes.toString(StandardCharsets.UTF_8)
        val root = JsonParser.parseString(text).asJsonObject
        val schemaVersion = root.get("schemaVersion")?.asInt ?: throw LegacyGitHubCredentialException()
        if (schemaVersion != GITHUB_CREDENTIAL_SCHEMA_VERSION) throw LegacyGitHubCredentialException()
        return AppJson.decodeFromString(StoredGitHubAppCredential.serializer(), text).also(::validateStoredCredential)
    }

    private fun validateStoredCredential(credential: StoredGitHubAppCredential) {
        require(credential.accessToken.length in 20..2_048 && credential.refreshToken.length in 20..2_048)
        require(credential.login.matches(Regex("[A-Za-z0-9-]{1,100}")))
        require(credential.accessTokenExpiresAtEpochSeconds > 0 && credential.refreshTokenExpiresAtEpochSeconds > 0)
    }

    private fun invalidateCredential(message: String): Nothing {
        credentialStore.delete()
        _githubState.value = GitHubConnectionState.Error(message)
        error(message)
    }

    private fun initialGitHubState(): GitHubConnectionState {
        if (!validGitHubAppConfiguration(githubClientId, githubAppSlug)) {
            return GitHubConnectionState.Unavailable(
                "Official GitHub App backup is not configured in this build. Local history is still available.",
            )
        }
        val bytes = try {
            credentialStore.read()
        } catch (_: Exception) {
            credentialStore.delete()
            return GitHubConnectionState.Error("Saved GitHub access was unreadable and has been cleared. Sign in again.")
        } ?: return GitHubConnectionState.Disconnected
        val credential = try {
            decodeCredential(bytes)
        } catch (_: LegacyGitHubCredentialException) {
            credentialStore.delete()
            return GitHubConnectionState.Error(
                "An older broad GitHub OAuth credential was removed. Sign in again with the permission-scoped ARES GitHub App.",
            )
        } catch (_: Exception) {
            credentialStore.delete()
            return GitHubConnectionState.Error("Saved GitHub access was invalid and has been cleared. Sign in again.")
        }
        return GitHubConnectionState.Connected(credential.login)
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
                remoteUrl = originUrl(git),
                destination = readDestination(git),
                confirmationToken = changes.takeIf { it.isNotEmpty() }?.let { contentBoundToken(root, it) },
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
            confirmationToken = restoreConfirmationToken(currentId, targetId, changes),
        )
    }

    private fun prepareRestore(
        git: Git,
        credential: StoredGitHubAppCredential,
    ): ProjectRestorePlan {
        require(git.status().call().isClean) {
            "Save the current changes as a local version before checking or restoring GitHub."
        }
        val destination = readDestination(git)
            ?: error("Choose an approved personal or team repository before checking GitHub versions.")
        val (_, repository) = verifyDestinationAccess(credential, destination)
        val origin = originUrl(git)
            ?: error("The saved GitHub destination has no origin remote. Choose the destination again.")
        require(sameGitHubRepository(origin, repository.cloneUrl)) {
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
                ?.let { restoreConfirmationToken(localId, remoteId, it) },
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
                    require(size <= MAX_REVIEWED_FILE_BYTES) {
                        "$path is too large for a reviewed GitHub restore."
                    }
                    totalBytes = Math.addExact(totalBytes, size)
                    require(totalBytes <= MAX_RESTORED_PROJECT_BYTES) {
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

    private fun restoreConfirmationToken(
        localId: ObjectId,
        remoteId: ObjectId,
        changes: List<ProjectChange>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(localId.name.toByteArray(StandardCharsets.US_ASCII))
        digest.update(remoteId.name.toByteArray(StandardCharsets.US_ASCII))
        changes.forEach { change ->
            digest.update(change.kind.name.toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(change.path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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

    private fun contentBoundToken(root: File, changes: List<ProjectChange>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        changes.forEach { change ->
            digest.update(change.kind.name.toByteArray())
            digest.update(0)
            digest.update(change.path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            val file = File(root, change.path).canonicalFile
            require(file.toPath().startsWith(root.canonicalFile.toPath())) { "A changed path escaped the project." }
            if (file.isFile) {
                require(file.length() <= MAX_REVIEWED_FILE_BYTES) {
                    "${change.path} is too large for reviewed project backup."
                }
                totalBytes += file.length()
                require(totalBytes <= MAX_REVIEWED_CHANGE_BYTES) {
                    "The pending change set is too large for one reviewed backup."
                }
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun writeDestination(
        git: Git,
        account: GitHubBackupAccount,
        repository: GitHubBackupRepository,
    ) {
        val config = git.repository.config
        config.setLong(BACKUP_CONFIG_SECTION, null, "installationId", account.installationId)
        config.setLong(BACKUP_CONFIG_SECTION, null, "repositoryId", repository.repositoryId)
        config.setString(BACKUP_CONFIG_SECTION, null, "owner", repository.ownerLogin)
        config.setString(BACKUP_CONFIG_SECTION, null, "repository", repository.name)
        config.setString(BACKUP_CONFIG_SECTION, null, "accountKind", account.kind.name)
        config.setString(BACKUP_CONFIG_SECTION, null, "cloneUrl", repository.cloneUrl)
        config.setString(BACKUP_CONFIG_SECTION, null, "webUrl", repository.webUrl)
        config.save()
    }

    private fun readDestination(git: Git): GitHubBackupDestination? {
        val config = git.repository.config
        val installationId = config.getLong(BACKUP_CONFIG_SECTION, null, "installationId", -1L)
        val repositoryId = config.getLong(BACKUP_CONFIG_SECTION, null, "repositoryId", -1L)
        val owner = config.getString(BACKUP_CONFIG_SECTION, null, "owner") ?: return null
        val repository = config.getString(BACKUP_CONFIG_SECTION, null, "repository") ?: return null
        val kind = config.getString(BACKUP_CONFIG_SECTION, null, "accountKind")
            ?.let { runCatching { GitHubAccountKind.valueOf(it) }.getOrNull() } ?: return null
        val cloneUrl = config.getString(BACKUP_CONFIG_SECTION, null, "cloneUrl") ?: return null
        val webUrl = config.getString(BACKUP_CONFIG_SECTION, null, "webUrl") ?: return null
        if (installationId <= 0 || repositoryId <= 0 || owner.isBlank() || repository.isBlank()) return null
        validateGitHubRepositoryUrl(cloneUrl)
        validateGitHubWebUrl(webUrl)
        val origin = originUrl(git) ?: return null
        if (!sameGitHubRepository(origin, cloneUrl)) return null
        return GitHubBackupDestination(installationId, repositoryId, owner, repository, kind, cloneUrl, webUrl)
    }

    private fun clearDestination(git: Git) {
        git.repository.config.apply {
            unsetSection(BACKUP_CONFIG_SECTION, null)
            save()
        }
    }

    private fun originUrl(git: Git): String? = git.remoteList().call()
        .singleOrNull { it.name == ORIGIN_REMOTE }
        ?.urIs?.singleOrNull()?.toString()

    private fun requireProjectRoot(projectPath: String): File {
        require(projectPath.isNotBlank()) { "Choose a robot project before opening Project Backup." }
        val root = File(projectPath).canonicalFile
        require(root.isDirectory) { "The selected robot project folder does not exist." }
        require(File(root, ".ares/project.json").isFile) { "The selected folder is not a canonical ARES robot project." }
        return root
    }

    private fun validateIdentity(name: String, email: String) {
        require(name.trim().length in 2..80) { "Enter the student or team member name used for saved versions." }
        require(email.trim().matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            "Enter a valid email address for saved versions."
        }
    }

    private fun requireValidGitHubAppConfiguration() {
        require(validGitHubAppConfiguration(githubClientId, githubAppSlug)) {
            "This ARES build has no GitHub App identity. Local history still works; install an official build configured for GitHub backup."
        }
    }

    private fun failGitHubSignIn(code: String): Nothing {
        val message = githubDeviceFailureMessage(code)
        _githubState.value = GitHubConnectionState.Error(message)
        error(message)
    }

    companion object {
        const val MAX_REVIEWED_FILE_BYTES = 20L * 1024L * 1024L
        const val MAX_REVIEWED_CHANGE_BYTES = 100L * 1024L * 1024L
        const val MAX_RESTORED_PROJECT_BYTES = 500L * 1024L * 1024L
        const val MAX_ARCHIVE_FILE_BYTES = 100L * 1024L * 1024L
        const val MAX_ARCHIVE_PROJECT_BYTES = 1024L * 1024L * 1024L
        private const val MAX_VISIBLE_VERSIONS = 20
        private const val MAX_VISIBLE_RECOVERY_POINTS = 10
        private const val MINIMUM_DEVICE_POLL_SECONDS = 5L
        private const val TOKEN_EXPIRY_SAFETY_SECONDS = 60L
        private const val ORIGIN_REMOTE = "origin"
        private const val BACKUP_CONFIG_SECTION = "aresBackup"
        private const val AUTO_SYNC_CONFIG_SECTION = "aresBackup"
        private const val AUTO_SYNC_CONFIG_NAME = "autoSync"
        private const val AUTO_SYNC_DEBOUNCE_MS = 5_000L
        private val AUTO_SYNC_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)
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

private fun isRecoverableAutoSyncFailure(failure: Throwable): Boolean {
    val chain = generateSequence(failure) { it.cause }.toList()
    if (chain.filterIsInstance<GitHubApiException>().any { it.status == 429 || it.status >= 500 }) return true
    if (chain.any {
            it is java.net.UnknownHostException || it is java.net.ConnectException ||
                it is java.net.SocketTimeoutException || it is java.net.SocketException
        }
    ) return true
    val message = chain.mapNotNull(Throwable::message).joinToString(" ").lowercase(Locale.ROOT)
    return listOf(
        "could not be reached",
        "timed out",
        "timeout",
        "connection reset",
        "connection refused",
        "network is unreachable",
        "could not resolve host",
    ).any(message::contains)
}

private enum class RemoteOperation { PUSH, FETCH }

/** JGit defaults to DEBUG under Logback's fallback configuration and otherwise prints local paths. */
internal fun configureJGitLogging() {
    val context = LoggerFactory.getILoggerFactory() as? ch.qos.logback.classic.LoggerContext ?: return
    context.getLogger("org.eclipse.jgit").level = ch.qos.logback.classic.Level.WARN
}

private const val GITHUB_CREDENTIAL_SCHEMA_VERSION = 2

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

private fun githubDeviceFailureMessage(code: String): String = when (code) {
    "expired_token" -> "The GitHub sign-in code expired. Start sign-in again."
    "access_denied" -> "GitHub sign-in was cancelled. Local project history is unchanged."
    "device_flow_disabled" -> "The ARES GitHub App is not enabled for device sign-in. Contact an ARES administrator."
    "incorrect_client_credentials" -> "This ARES build has an invalid GitHub App identity. Update the app or contact an administrator."
    "token_expiration_required" -> "The ARES GitHub App must use expiring user tokens. Contact an ARES administrator."
    else -> "GitHub could not complete sign-in ($code). Local project history is unchanged."
}

private fun githubRefreshFailureMessage(code: String): String = when (code) {
    "bad_refresh_token", "expired_token" ->
        "GitHub refresh access expired or was revoked. Saved access was cleared; sign in again."
    else -> "GitHub could not refresh access ($code). Saved access was cleared; sign in again."
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

private fun validateGitHubRepositoryUrl(raw: String) {
    val uri = runCatching { URI(raw) }.getOrElse { error("The GitHub repository URL is invalid.") }
    require(uri.scheme == "https" && uri.host.equals("github.com", ignoreCase = true) && uri.userInfo == null) {
        "Project Backup only accepts credential-free HTTPS repositories on github.com."
    }
    val segments = uri.path.trim('/').removeSuffix(".git").split('/')
    require(segments.size == 2 && segments.all { it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) {
        "The GitHub repository URL must identify one owner and repository."
    }
}

private fun validateGitHubWebUrl(raw: String) {
    validateGitHubRepositoryUrl(raw.removeSuffix("/") + if (raw.endsWith(".git")) "" else ".git")
}

private fun sameGitHubRepository(first: String, second: String): Boolean =
    githubRepositoryIdentity(first)?.equals(githubRepositoryIdentity(second), ignoreCase = true) == true

private fun githubRepositoryIdentity(raw: String): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme != "https" || !uri.host.equals("github.com", ignoreCase = true) || uri.userInfo != null) return null
    val segments = uri.path.trim('/').removeSuffix(".git").split('/')
    if (segments.size != 2 || segments.any(String::isBlank)) return null
    return segments.joinToString("/")
}
