package com.ares.analytics.service

import com.ares.analytics.service.db.TelemetryExportCursor
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

internal class DatabaseReplayWindowSource(
    private val database: DatabaseService,
    private val maxWindowFrames: Int = MAX_WINDOW_FRAMES,
    private val pageSize: Int = DEFAULT_WINDOW_PAGE_SIZE,
) : ReplayWindowSource {
    init {
        require(maxWindowFrames > 0) { "maxWindowFrames must be positive" }
        require(pageSize > 0) { "pageSize must be positive" }
    }

    override suspend fun load(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow {
        val baseline = if (startMs > sessionStartMs) database.getLatestTelemetryBefore(sessionId, startMs) else emptyList()
        val frames = ArrayList<TelemetryFrame>()
        var cursor: TelemetryExportCursor? = null
        var truncated = false
        while (frames.size < maxWindowFrames) {
            val remaining = maxWindowFrames - frames.size
            val fetchSize = minOf(pageSize, remaining + 1)
            val page = database.getTelemetryWindowPageAfter(
                sessionId = sessionId,
                startMs = startMs,
                endMs = endMs,
                after = cursor,
                limit = fetchSize,
            )
            if (page.isEmpty()) break
            if (page.size > remaining) {
                frames.addAll(page.subList(0, remaining))
                truncated = true
                break
            }
            frames.addAll(page)
            if (page.size < fetchSize) break
            val last = page[page.lastIndex]
            cursor = TelemetryExportCursor(last.timestampUs, last.sampleOrder, last.key)
        }
        if (!truncated || frames.isEmpty()) {
            return ReplayWindow(sessionId, startMs, endMs, baseline, frames)
        }
        // Shorten effective endMs to the last complete timestamp strictly before the truncated boundary
        // so replay advances seamlessly without splitting a multi-topic timestamp frame.
        val cutoffTsMs = frames[frames.lastIndex].timestampMs
        var keepCount = frames.size
        while (keepCount > 0 && frames[keepCount - 1].timestampMs == cutoffTsMs) {
            keepCount--
        }
        return if (keepCount > 0) {
            val trimmed = ArrayList(frames.subList(0, keepCount))
            val effectiveEndMs = trimmed[trimmed.lastIndex].timestampMs.coerceAtLeast(startMs)
            ReplayWindow(sessionId, startMs, effectiveEndMs, baseline, trimmed)
        } else {
            ReplayWindow(sessionId, startMs, cutoffTsMs.coerceAtLeast(startMs), baseline, frames)
        }
    }

    companion object {
        internal const val MAX_WINDOW_FRAMES = 100_000
        internal const val DEFAULT_WINDOW_PAGE_SIZE = 10_000
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
    var cursor: TelemetryExportCursor? = null
    do {
        val page = databaseService.getTelemetryWindowPageAfter(
            sessionId = sessionId,
            startMs = startMs,
            endMs = endMs,
            after = cursor,
            limit = pageSize,
        )
        frames.addAll(page)
        if (page.isNotEmpty()) {
            val last = page[page.lastIndex]
            cursor = TelemetryExportCursor(last.timestampUs, last.sampleOrder, last.key)
        }
    } while (page.size == pageSize)
    return frames
}
