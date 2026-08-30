package com.ares.analytics.service.versioncontrol

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
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import java.io.File
import java.time.Instant
import java.util.Locale

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

/** Owns automatic-backup preference, debouncing, retry policy, state, and worker lifetime. */
class ProjectBackupAutoSyncService internal constructor(
    private val inspectProject: suspend (String) -> ProjectBackupPlan,
    private val pushBackup: suspend (String) -> ProjectBackupPlan,
    private val epochSeconds: () -> Long = { Instant.now().epochSecond },
    private val workerDelay: suspend (Long) -> Unit = { milliseconds -> delay(milliseconds) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<String>(Channel.CONFLATED)
    private val worker: Job = scope.launch { processRequests() }
    private val _state = MutableStateFlow(ProjectBackupAutoSyncState())
    val state: StateFlow<ProjectBackupAutoSyncState> = _state.asStateFlow()

    suspend fun load(projectPath: String): ProjectBackupAutoSyncState {
        val root = requireCanonicalProjectRoot(projectPath)
        return stateFor(root).also { _state.value = it }
    }

    suspend fun setEnabled(projectPath: String, enabled: Boolean): ProjectBackupAutoSyncState {
        val root = requireCanonicalProjectRoot(projectPath)
        Git.open(root).use { git ->
            require(git.repository.resolve(Constants.HEAD) != null) {
                "Save the first local project version before enabling automatic GitHub backup."
            }
            val config = git.repository.config
            config.setBoolean(CONFIG_SECTION, null, CONFIG_NAME, enabled)
            config.save()
        }
        _state.value = stateFor(root)
        if (enabled) schedule(root.path)
        return _state.value
    }

    fun schedule(projectPath: String) {
        val root = runCatching { requireCanonicalProjectRoot(projectPath) }.getOrNull() ?: return
        if (!isEnabled(root)) return
        _state.value = ProjectBackupAutoSyncState(
            projectPath = root.path,
            enabled = true,
            status = ProjectBackupAutoSyncStatus.SCHEDULED,
            message = "A local version was saved. GitHub backup is queued.",
            lastSuccessEpochSeconds = _state.value.lastSuccessEpochSeconds,
        )
        requests.trySend(root.path)
    }

    fun markSynchronized(projectPath: String) {
        val root = runCatching { requireCanonicalProjectRoot(projectPath) }.getOrNull() ?: return
        if (!isEnabled(root)) return
        _state.value = ProjectBackupAutoSyncState(
            projectPath = root.path,
            enabled = true,
            status = ProjectBackupAutoSyncStatus.UP_TO_DATE,
            message = "GitHub backup is up to date.",
            lastSuccessEpochSeconds = epochSeconds(),
        )
    }

    fun pauseForSignedOutAccount() {
        val current = _state.value
        if (current.enabled) {
            _state.value = current.copy(
                status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                message = "Automatic backup is paused. Sign in with GitHub again to continue.",
            )
        }
    }

    suspend fun closeAndJoin() {
        requests.close()
        worker.cancelAndJoin()
        scope.cancel()
    }

    private suspend fun processRequests() {
        for (firstPath in requests) {
            var projectPath = firstPath
            do {
                workerDelay(DEBOUNCE_MS)
                val newer = requests.tryReceive().getOrNull()
                if (newer != null) projectPath = newer
            } while (newer != null)
            runSync(projectPath)
        }
    }

    private suspend fun runSync(projectPath: String) {
        val root = runCatching { requireCanonicalProjectRoot(projectPath) }.getOrElse { failure ->
            _state.value = ProjectBackupAutoSyncState(
                projectPath = projectPath,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                message = failure.message ?: "The project is no longer available.",
            )
            return
        }
        if (!isEnabled(root)) {
            _state.value = disabledState(root)
            return
        }
        val plan = inspectProject(root.path)
        when {
            plan.destination == null -> {
                _state.value = ProjectBackupAutoSyncState(
                    root.path,
                    true,
                    ProjectBackupAutoSyncStatus.WAITING_FOR_DESTINATION,
                    "Automatic backup is on. Choose an approved GitHub repository to begin syncing.",
                )
                return
            }
            plan.changes.isNotEmpty() -> {
                _state.value = ProjectBackupAutoSyncState(
                    root.path,
                    true,
                    ProjectBackupAutoSyncStatus.WAITING_FOR_LOCAL_SAVE,
                    "Automatic backup is waiting for the remaining project changes to be saved locally.",
                )
                return
            }
        }
        for (attempt in RETRY_DELAYS_MS.indices) {
            _state.value = ProjectBackupAutoSyncState(
                projectPath = root.path,
                enabled = true,
                status = ProjectBackupAutoSyncStatus.SYNCING,
                message = "Syncing the latest saved version to GitHub…",
                lastSuccessEpochSeconds = _state.value.lastSuccessEpochSeconds,
            )
            try {
                pushBackup(root.path)
                markSynchronized(root.path)
                return
            } catch (failure: Exception) {
                if (!isRecoverableFailure(failure)) {
                    _state.value = ProjectBackupAutoSyncState(
                        projectPath = root.path,
                        enabled = true,
                        status = ProjectBackupAutoSyncStatus.ATTENTION_REQUIRED,
                        message = failure.message ?: "GitHub backup needs attention.",
                        lastSuccessEpochSeconds = _state.value.lastSuccessEpochSeconds,
                    )
                    return
                }
                val hasRetry = attempt < RETRY_DELAYS_MS.lastIndex
                _state.value = ProjectBackupAutoSyncState(
                    projectPath = root.path,
                    enabled = true,
                    status = ProjectBackupAutoSyncStatus.OFFLINE_RETRY,
                    message = if (hasRetry) {
                        "GitHub is temporarily unreachable. ARES will retry automatically."
                    } else {
                        "GitHub is still unreachable. Your local versions are safe; use Sync backup now when the connection returns."
                    },
                    lastSuccessEpochSeconds = _state.value.lastSuccessEpochSeconds,
                )
                if (!hasRetry) return
                workerDelay(RETRY_DELAYS_MS[attempt])
            }
        }
    }

    private suspend fun stateFor(root: File): ProjectBackupAutoSyncState {
        if (!File(root, ".git").isDirectory || !isEnabled(root)) return disabledState(root)
        val plan = inspectProject(root.path)
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
                _state.value.lastSuccessEpochSeconds,
            )
        }
    }

    private fun disabledState(root: File) = ProjectBackupAutoSyncState(
        projectPath = root.path,
        enabled = false,
        status = ProjectBackupAutoSyncStatus.DISABLED,
        message = "Automatic GitHub backup is off. Local history still saves versions on this computer.",
    )

    private fun isEnabled(root: File): Boolean {
        if (!File(root, ".git").isDirectory) return false
        return Git.open(root).use { git ->
            git.repository.config.getBoolean(CONFIG_SECTION, null, CONFIG_NAME, false)
        }
    }

    private fun isRecoverableFailure(failure: Throwable): Boolean {
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
            "temporary failure",
            "failed to connect",
            "network unavailable",
        ).any(message::contains)
    }

    private companion object {
        const val CONFIG_SECTION = "aresBackup"
        const val CONFIG_NAME = "autoSync"
        const val DEBOUNCE_MS = 5_000L
        val RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)
    }
}
