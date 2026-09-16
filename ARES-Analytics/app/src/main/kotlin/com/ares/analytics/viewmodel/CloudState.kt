package com.ares.analytics.viewmodel

import com.ares.analytics.shared.models.DriveDestinationConfig
import com.ares.analytics.shared.models.SessionSummary
import kotlinx.serialization.Serializable

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

/** Stable grouping key shared by canonical telemetry and action logs. */
internal fun robotLogRunKey(fileName: String): String {
    ROBOT_LOG_RUN_UUID.find(fileName)?.value?.let { return it.lowercase() }
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

internal fun shouldLoadRemoteCloudIndex(
    isAuthenticated: Boolean,
    driveDestination: DriveDestinationConfig?,
): Boolean = isAuthenticated && driveDestination != null

internal fun robotLogRefreshFailureMessage(robotIp: String, error: Throwable): String {
    val root = generateSequence(error) { it.cause }.last()
    val detail = root.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    return buildString {
        append("[CloudViewModel] Robot log refresh failed for ")
        append(robotIp)
        append(": ")
        append(root::class.simpleName ?: "Error")
        if (detail.isNotEmpty()) append(" — ").append(detail)
    }
}
