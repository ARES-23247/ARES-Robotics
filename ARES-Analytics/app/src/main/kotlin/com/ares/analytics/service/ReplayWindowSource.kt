package com.ares.analytics.service

import com.ares.analytics.shared.models.TelemetryFrame

/** A complete ordered window and the latched values immediately before it. */
internal data class ReplayWindow(
    val sessionId: String,
    val startMs: Long,
    val endMs: Long,
    val baseline: List<TelemetryFrame>,
    val frames: List<TelemetryFrame>,
)

internal fun interface ReplayWindowSource {
    suspend fun load(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow
}

internal class DatabaseReplayWindowSource(private val database: DatabaseService) : ReplayWindowSource {
    override suspend fun load(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow {
        val baseline = if (startMs > sessionStartMs) database.getLatestTelemetryBefore(sessionId, startMs) else emptyList()
        val frames = loadTelemetryWindowPages(database, sessionId, startMs, endMs, 50_000)
        return ReplayWindow(sessionId, startMs, endMs, baseline, frames)
    }
}

internal suspend fun loadTelemetryWindowPages(
    databaseService: DatabaseService,
    sessionId: String,
    startMs: Long,
    endMs: Long,
    pageSize: Int,
): List<TelemetryFrame> {
    require(pageSize > 0)
    val frames = ArrayList<TelemetryFrame>()
    var offset = 0L
    do {
        val page = databaseService.getTelemetryRangeBatched(
            sessionId = sessionId,
            startMs = startMs,
            endMs = endMs,
            limit = pageSize.toLong(),
            offset = offset,
        )
        frames.addAll(page)
        offset += page.size
    } while (page.size == pageSize)
    return frames
}
