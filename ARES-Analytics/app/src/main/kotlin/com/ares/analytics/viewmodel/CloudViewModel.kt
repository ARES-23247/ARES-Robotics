package com.ares.analytics.viewmodel

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.OAuthService
import com.ares.analytics.service.AuthState
import com.ares.analytics.service.SyncEngineService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.RobotLogImportOutcome
import com.ares.analytics.service.RobotLogIngestionService
import com.ares.analytics.service.RobotLogSource
import com.ares.analytics.shared.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

@Serializable
data class RobotLogFileInfo(
    val name: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val lastModifiedFmt: String,
    val synced: Boolean,
    val isActive: Boolean? = false
)

data class RobotRun(
    val runId: String,
    val files: List<RobotLogFileInfo>,
    val totalSizeBytes: Long,
    val lastModifiedMs: Long,
    val lastModifiedFmt: String,
    val allSynced: Boolean,
    val isActive: Boolean = false
)

private val ROBOT_LOG_RUN_UUID = Regex(
    "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
private val ROBOT_LOG_TIMESTAMP_SECOND = Regex("\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}")

/** Stable grouping key shared by telemetry and action logs, with a legacy timestamp fallback. */
internal fun robotLogRunKey(fileName: String): String {
    ROBOT_LOG_RUN_UUID.find(fileName)?.value?.let { return it.lowercase() }
    ROBOT_LOG_TIMESTAMP_SECOND.find(fileName)?.value?.let { return "legacy-$it" }
    return fileName
}

data class SessionSyncInfo(
    val summary: SessionSummary,
    val isLocal: Boolean,
    val isRemote: Boolean
)

data class CloudState(
    val sessions: List<SessionSyncInfo> = emptyList(),
    val cloudLogs: List<SessionSummary> = emptyList(),
    val robotRuns: List<RobotRun> = emptyList(),
    val isSyncing: Boolean = false,
    val isFetchingRobotLogs: Boolean = false,
    val isUploadingRobotLog: String? = null,
    val isDeletingCloudLog: String? = null,
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false,
    val uploadLogs: List<String> = emptyList()
)

sealed class CloudIntent {

    object RefreshCloudLogs : CloudIntent()

    object RefreshRobotLogs : CloudIntent()

    data class PerformDeltaSync(val teamId: String, val seasonId: String) : CloudIntent()

    data class UploadRobotRun(val runId: String, val teamId: String, val seasonId: String, val robotId: String) : CloudIntent()

    data class UploadMultipleRobotRuns(val runIds: List<String>, val teamId: String, val seasonId: String, val robotId: String) : CloudIntent()

    data class DeleteRobotRun(val runId: String, val deleteToken: String) : CloudIntent()

    data class DeleteMultipleRobotRuns(val runIds: List<String>, val deleteToken: String) : CloudIntent()

    data class DeleteCloudLog(val sessionId: String, val teamId: String) : CloudIntent()

    object ClearError : CloudIntent()

    // Database / Cloud Sync Manager Intents

    data class UploadSession(val sessionId: String) : CloudIntent()

    data class DownloadSession(val summary: SessionSummary) : CloudIntent()

    data class DownloadMultipleSessions(val summaries: List<SessionSummary>) : CloudIntent()

    data class DeleteSessionLocal(val sessionId: String) : CloudIntent()

    data class DeleteMultipleLocalSessions(val sessionIds: List<String>) : CloudIntent()

    data class DeleteSessionRemote(val sessionId: String, val teamId: String) : CloudIntent()

    data class DeleteMultipleRemoteSessions(val sessionIdsAndTeamIds: List<Pair<String, String>>) : CloudIntent()
}

/**
 * Orchestrates local sessions, robot-hosted logs, and cloud synchronization.
 * Robot downloads are archived inside the active workspace before parsing. Source files remain
 * on the robot until the operator uses an explicit, confirmed delete action after verification.
 */
class CloudViewModel(
    private val databaseService: DatabaseService,
    private val syncEngineService: SyncEngineService,
    private val oauthService: OAuthService,
    private val nt4ClientService: Nt4ClientService,
    private val robotLogIngestionService: RobotLogIngestionService,
    private val workspaceConfig: WorkspaceConfig,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(CloudState())
    val state: StateFlow<CloudState> = _state.asStateFlow()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30 * 60 * 1000L
            connectTimeoutMillis = 60 * 1000L
            socketTimeoutMillis = 30 * 60 * 1000L
        }
    }

    init {
        checkAuth()
        onIntent(CloudIntent.RefreshCloudLogs)
        onIntent(CloudIntent.RefreshRobotLogs)
    }

    private fun checkAuth() {
        val hasToken = oauthService.authState.value is AuthState.Authenticated
        _state.update { it.copy(isAuthenticated = hasToken) }
    }

    private fun getRobotIp(): String {
        val ip = nt4ClientService.serverIp
        if (ip.isBlank() || ip == "0.0.0.0") return "127.0.0.1"
        return ip
    }

    private fun logUpload(message: String) {
        _state.update { it.copy(uploadLogs = it.uploadLogs + message) }
    }

    fun onIntent(intent: CloudIntent) {
        scope.launch {
            when (intent) {
                is CloudIntent.RefreshCloudLogs -> {
                    checkAuth()
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        val localSessions = databaseService.getSessionsForWorkspace(
                            workspaceConfig.teamId,
                            workspaceConfig.seasonId,
                            workspaceConfig.robotId,
                        )
                        val localSummariesMap = databaseService.getAllSessionSummaries()
                            .filter { it.matches(workspaceConfig) }
                            .associateBy { it.sessionId }
                        val remoteSummaries = syncEngineService.getRemoteSummaries()
                            .filter { it.matches(workspaceConfig) }
                        val allSessionIds = (localSessions.map { it.sessionId } + remoteSummaries.map { it.sessionId }).toSet()
                        val sessionsList = allSessionIds.map { id ->
                            val localSession = localSessions.find { it.sessionId == id }
                            val remoteSummary = remoteSummaries.find { it.sessionId == id }
                            val localSummary = localSummariesMap[id]
                            val summary = remoteSummary
                                ?: localSummary
                                ?: SessionSummary(
                                    sessionId = id,
                                    teamId = localSession?.teamId ?: "unknown",
                                    seasonId = localSession?.seasonId ?: "unknown",
                                    robotId = localSession?.robotId ?: "unknown",
                                    createdAt = localSession?.createdAt ?: System.currentTimeMillis(),
                                    durationMs = localSession?.durationMs ?: 0L,
                                    tags = localSession?.tags ?: emptyList(),
                                    matchNumber = localSession?.matchNumber,
                                    allianceColor = localSession?.allianceColor,
                                    fileSizeBytes = 0L
                                )

                            SessionSyncInfo(
                                summary = summary,
                                isLocal = localSession != null,
                                isRemote = remoteSummary != null
                            )
                        }.sortedByDescending { it.summary.createdAt }

                        _state.update { it.copy(sessions = sessionsList, cloudLogs = remoteSummaries, isSyncing = false) }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Failed to load database state") }
                    }
                }
                is CloudIntent.RefreshRobotLogs -> {
                    fetchRobotLogs()
                }
                is CloudIntent.PerformDeltaSync -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        syncEngineService.performDeltaSync(intent.teamId, intent.seasonId)
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Sync failed") }
                    }
                }
                is CloudIntent.UploadRobotRun -> {
                    _state.update { it.copy(isUploadingRobotLog = intent.runId, errorMessage = null, uploadLogs = listOf("Starting upload for run ${intent.runId}...")) }
                    try {
                        val run = _state.value.robotRuns.find { it.runId == intent.runId }
                        if (run != null) {
                            logUpload("1/3: Downloading and archiving ${run.files.size} raw files from robot at ${getRobotIp()}...")
                            val outcome = importRobotRun(run, intent.teamId, intent.seasonId, intent.robotId)
                            val session = outcome.session
                            when (outcome) {
                                is RobotLogImportOutcome.Imported -> logUpload(
                                    "2/3: Archived ${outcome.archivedFiles.size} raw files and parsed session ${session.sessionId}."
                                )
                                is RobotLogImportOutcome.AlreadyImported -> logUpload(
                                    "2/3: This exact run was already imported as ${session.sessionId}; reusing it."
                                )
                            }

                            logUpload("3/3: Uploading the local session bundle and syncing metadata...")
                            try {
                                syncEngineService.uploadSession(session.sessionId)
                                syncEngineService.performDeltaSync(intent.teamId, intent.seasonId)
                                logUpload("Cloud sync completed successfully.")
                            } catch (e: CancellationException) { throw e } catch (syncEx: Exception) {
                                logUpload("Local import is safe, but cloud sync failed: ${syncEx.message}")
                                _state.update { current ->
                                    current.copy(errorMessage = "Imported locally but cloud sync failed: ${syncEx.message}")
                                }
                            }
                            logUpload("Robot files were kept; delete them explicitly after verifying the archived import.")
                            fetchRobotLogs()
                            onIntent(CloudIntent.RefreshCloudLogs)
                        } else {
                            logUpload("Run not found in local state.")
                            _state.update { it.copy(isUploadingRobotLog = null) }
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        logUpload("CRITICAL FATAL: ${e.message}")
                        _state.update { it.copy(errorMessage = e.message ?: "Upload failed", isUploadingRobotLog = null) }
                    } finally {
                        _state.update { it.copy(isUploadingRobotLog = null) }
                    }
                }
                is CloudIntent.UploadMultipleRobotRuns -> {
                    _state.update { it.copy(isUploadingRobotLog = "BATCH", errorMessage = null, uploadLogs = listOf("Starting batch upload for ${intent.runIds.size} runs...")) }
                    try {
                        val runsToUpload = _state.value.robotRuns.filter { it.runId in intent.runIds }
                        for ((index, run) in runsToUpload.withIndex()) {
                            logUpload("=== [${index + 1}/${runsToUpload.size}] Uploading Run: ${run.runId} ===")
                            try {
                                val outcome = importRobotRun(run, intent.teamId, intent.seasonId, intent.robotId)
                                logUpload(
                                    when (outcome) {
                                        is RobotLogImportOutcome.Imported -> "Archived and imported as ${outcome.session.sessionId}."
                                        is RobotLogImportOutcome.AlreadyImported -> "Already imported as ${outcome.session.sessionId}; retrying cloud upload only."
                                    }
                                )
                                syncEngineService.uploadSession(outcome.session.sessionId)
                                logUpload("Cloud upload completed. Robot files were kept for explicit deletion.")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Per-run isolation: a failure here (parse/parse-throw/etc.)
                                // must NOT abort the remaining runs. Robot files are retained
                                // regardless; deleting source data is a separate confirmed action.
                                val msg = "Run ${run.runId} aborted: ${e.message ?: e.javaClass.simpleName}"
                                logUpload("  -> $msg")
                            }
                        }
                        logUpload("Batch upload finished! Syncing metadata...")
                        syncEngineService.performDeltaSync(intent.teamId, intent.seasonId)
                        fetchRobotLogs()
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(errorMessage = "Batch upload failed: ${e.message}", isUploadingRobotLog = null) }
                    } finally {
                        _state.update { it.copy(isUploadingRobotLog = null) }
                    }
                }
                is CloudIntent.DeleteRobotRun -> {
                    try {
                        val run = _state.value.robotRuns.find { it.runId == intent.runId }
                        if (run != null) {
                            withContext(Dispatchers.IO) {
                                for (file in run.files) {
                                    deleteRobotFile(file.name, intent.deleteToken)
                                }
                            }
                            fetchRobotLogs()
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(errorMessage = e.message ?: "Delete request failed") }
                    }
                }
                is CloudIntent.DeleteMultipleRobotRuns -> {
                    try {
                        val runsToDelete = _state.value.robotRuns.filter { it.runId in intent.runIds }
                        withContext(Dispatchers.IO) {
                            for (run in runsToDelete) {
                                for (file in run.files) {
                                    deleteRobotFile(file.name, intent.deleteToken)
                                }
                            }
                        }
                        fetchRobotLogs()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(errorMessage = e.message ?: "Delete request failed") }
                    }
                }

                is CloudIntent.DeleteCloudLog -> {
                    _state.update { it.copy(isDeletingCloudLog = intent.sessionId, errorMessage = null) }
                    try {
                        syncEngineService.deleteCloudSession(intent.sessionId, intent.teamId)
                        _state.update { it.copy(isDeletingCloudLog = null) }
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: SecurityException) {
                        _state.update { it.copy(isDeletingCloudLog = null, errorMessage = "Permission denied") }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isDeletingCloudLog = null, errorMessage = e.message ?: "Delete failed") }
                    }
                }
                is CloudIntent.UploadSession -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        syncEngineService.uploadSession(intent.sessionId)
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Upload failed") }
                    }
                }
                is CloudIntent.DownloadSession -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        syncEngineService.downloadSession(intent.summary)
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Download failed") }
                    }
                }
                is CloudIntent.DownloadMultipleSessions -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        for (summary in intent.summaries) {
                            syncEngineService.downloadSession(summary)
                        }
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Batch download failed") }
                    }
                }
                is CloudIntent.DeleteSessionLocal -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        databaseService.deleteSession(intent.sessionId)
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Local delete failed") }
                    }
                }
                is CloudIntent.DeleteMultipleLocalSessions -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        for (sessionId in intent.sessionIds) {
                            databaseService.deleteSession(sessionId)
                        }
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Batch local delete failed") }
                    }
                }
                is CloudIntent.DeleteSessionRemote -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        syncEngineService.deleteCloudSession(intent.sessionId, intent.teamId)
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Remote delete failed") }
                    }
                }
                is CloudIntent.DeleteMultipleRemoteSessions -> {
                    _state.update { it.copy(isSyncing = true, errorMessage = null) }
                    try {
                        for (item in intent.sessionIdsAndTeamIds) {
                            syncEngineService.deleteCloudSession(item.first, item.second)
                        }
                        onIntent(CloudIntent.RefreshCloudLogs)
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        e.printStackTrace()
                        _state.update { it.copy(isSyncing = false, errorMessage = e.message ?: "Batch remote delete failed") }
                    }
                }
                is CloudIntent.ClearError -> {
                    _state.update { it.copy(errorMessage = null) }
                }
            }
        }
    }

    private suspend fun fetchRobotLogs() {
        _state.update { it.copy(isFetchingRobotLogs = true, errorMessage = null) }
        try {
            val logs: List<RobotLogFileInfo> = withContext(Dispatchers.IO) {
                httpClient.get("http://${getRobotIp()}:5002/api/logs").body()
            }
            val runs = logs.groupBy { robotLogRunKey(it.name) }.map { (runId, files) ->
                RobotRun(
                    runId = runId,
                    files = files,
                    totalSizeBytes = files.sumOf { it.sizeBytes },
                    lastModifiedMs = files.maxOf { it.lastModifiedMs },
                    lastModifiedFmt = files.maxByOrNull { it.lastModifiedMs }?.lastModifiedFmt ?: "",
                    allSynced = files.all { it.synced },
                    isActive = files.any { it.isActive == true }
                )
            }.sortedByDescending { it.lastModifiedMs }

            _state.update { it.copy(robotRuns = runs, isFetchingRobotLogs = false) }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            e.printStackTrace()
            _state.update { it.copy(robotRuns = emptyList(), isFetchingRobotLogs = false, errorMessage = "Failed to fetch logs: ${e.message}") }
        }
    }

    private suspend fun importRobotRun(
        run: RobotRun,
        teamId: String,
        seasonId: String,
        robotId: String,
    ): RobotLogImportOutcome = robotLogIngestionService.importRun(
        robotBaseUrl = "http://${getRobotIp()}:5002",
        sources = run.files.map { file ->
            RobotLogSource(
                name = file.name,
                sizeBytes = file.sizeBytes,
                lastModifiedMs = file.lastModifiedMs,
            )
        },
        workspace = workspaceConfig,
        teamId = teamId,
        seasonId = seasonId,
        robotId = robotId,
    )

    private suspend fun deleteRobotFile(fileName: String, token: String) {
        require(token.length >= MIN_ROBOT_DELETE_TOKEN_LENGTH) {
            "Robot log-delete token must contain at least $MIN_ROBOT_DELETE_TOKEN_LENGTH characters"
        }
        httpClient.preparePost("http://${getRobotIp()}:5002/api/delete") {
            parameter("file", fileName)
            header("X-ARES-Delete-Token", token)
        }.execute { response ->
            check(response.status.isSuccess()) {
                "Robot rejected deletion of $fileName with HTTP ${response.status.value}"
            }
        }
    }

    /**
     * Final teardown — closes the HttpClient owned by this view model. CloudViewModel is
     * constructed in MainScreen (not ServiceRegistry), so MainScreen's onDispose must call this.
     */
    fun dispose() {
        try {
            httpClient.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private companion object {
        const val MIN_ROBOT_DELETE_TOKEN_LENGTH = 16
    }
}

private fun SessionSummary.matches(workspace: WorkspaceConfig): Boolean =
    teamId == workspace.teamId &&
        seasonId == workspace.seasonId &&
        robotId == workspace.robotId
