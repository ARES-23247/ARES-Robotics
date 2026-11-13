package com.ares.analytics.service

import com.ares.analytics.domain.pit.EventScheduleCodec
import com.ares.analytics.domain.pit.ScheduledMatchItem
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets

fun MatchInfo.toScheduledMatchItem(source: String = "cache"): ScheduledMatchItem = ScheduledMatchItem(
    matchNumber = matchNumber,
    compLevel = compLevel,
    redAlliance = redAlliance,
    blueAlliance = blueAlliance,
    scheduledTimeMs = scheduledTime,
    source = source,
)

fun ScheduledMatchItem.toMatchInfo(): MatchInfo = MatchInfo(
    matchNumber = matchNumber,
    compLevel = compLevel,
    redAlliance = redAlliance,
    blueAlliance = blueAlliance,
    scheduledTime = scheduledTimeMs,
)

/**
 * Offline-first local schedule cache backed by per-workspace atomic JSON files under
 * [baseDir]. Ensures pit turnaround and Match Strategy retain tournament schedules even when
 * venue Wi-Fi or cell service is unavailable.
 */
class EventScheduleStore(
    private val baseDir: File = AppDataPaths.file("schedules"),
) {
    private val ioLock = Any()

    fun scheduleFileFor(workspace: WorkspaceConfig): File {
        val rawId = workspace.id.ifBlank {
            "${workspace.league}-${workspace.teamId}-${workspace.robotId}-${workspace.seasonId}"
        }
        val safeName = rawId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "default" }
        return File(baseDir, "$safeName.json")
    }

    suspend fun loadCachedMatches(workspace: WorkspaceConfig): List<MatchInfo> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val file = scheduleFileFor(workspace)
            if (!file.isFile) return@synchronized emptyList()
            val text = runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull().orEmpty()
            EventScheduleCodec.parseJson(text).map(ScheduledMatchItem::toMatchInfo)
        }
    }

    suspend fun saveMatches(
        workspace: WorkspaceConfig,
        matches: List<MatchInfo>,
        source: String = "cache",
    ): List<MatchInfo> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val items = matches.filter { it.matchNumber > 0 }.map { it.toScheduledMatchItem(source) }
            val encoded = EventScheduleCodec.encodeJson(items)
            val file = scheduleFileFor(workspace)
            writeFileAtomically(file) { tmp ->
                tmp.writeText(encoded, StandardCharsets.UTF_8)
            }
            EventScheduleCodec.parseJson(encoded).map(ScheduledMatchItem::toMatchInfo)
        }
    }

    suspend fun importScheduleFile(workspace: WorkspaceConfig, file: File): List<MatchInfo> = withContext(Dispatchers.IO) {
        require(file.isFile) { "Schedule file does not exist: ${file.name}" }
        val text = file.readText(StandardCharsets.UTF_8)
        val parsed = if (file.extension.equals("json", ignoreCase = true) || text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) {
            EventScheduleCodec.parseJson(text)
        } else {
            EventScheduleCodec.parseCsv(text)
        }
        require(parsed.isNotEmpty()) { "No valid matches found in ${file.name}" }
        saveMatches(workspace, parsed.map(ScheduledMatchItem::toMatchInfo), source = file.extension.lowercase())
    }

    suspend fun upsertManualMatch(
        workspace: WorkspaceConfig,
        item: ScheduledMatchItem,
    ): List<MatchInfo> = withContext(Dispatchers.IO) {
        val current = loadCachedMatches(workspace).map { it.toScheduledMatchItem("cache") }
        val updated = current.filterNot {
            it.compLevel.equals(item.compLevel, ignoreCase = true) && it.matchNumber == item.matchNumber
        } + item.copy(source = "manual")
        saveMatches(workspace, updated.map(ScheduledMatchItem::toMatchInfo), source = "manual")
    }

    suspend fun removeMatch(
        workspace: WorkspaceConfig,
        compLevel: String,
        matchNumber: Int,
    ): List<MatchInfo> = withContext(Dispatchers.IO) {
        val remaining = loadCachedMatches(workspace).filterNot {
            it.compLevel.equals(compLevel, ignoreCase = true) && it.matchNumber == matchNumber
        }
        saveMatches(workspace, remaining, source = "manual")
    }

    suspend fun clearSchedule(workspace: WorkspaceConfig): List<MatchInfo> = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            val file = scheduleFileFor(workspace)
            if (file.exists()) file.delete()
            emptyList()
        }
    }
}
