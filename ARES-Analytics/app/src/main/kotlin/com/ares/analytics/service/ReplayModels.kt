package com.ares.analytics.service

/** Playback state. A selected historical session remains a replay source in every state. */
enum class ReplayState { PLAYING, PAUSED, STOPPED, ENDED }

/** Loading state is separate from playback so the UI can explain an empty or failed recording. */
enum class ReplayLoadState { IDLE, LOADING, READY, EMPTY, ERROR }

/**
 * One atomically committed replay snapshot.
 *
 * [timestampMs] is the most recent source sample at or before [playheadMs]. Every value in the
 * maps is reconstructed at that same logical instant. [sequence] changes for every successful
 * commit, including a seek to an instant whose values happen to equal the previous snapshot.
 */
data class ReplayFrame(
    val timestampMs: Long,
    val values: Map<String, Double>,
    val stringValues: Map<String, String> = emptyMap(),
    val sessionId: String = "",
    val playheadMs: Long = timestampMs,
    val sequence: Long = 0L,
)

/** Human-readable replay identity and bounds for source/status UI. */
data class ReplaySessionInfo(
    val sessionId: String,
    val startTimestampMs: Long,
    val endTimestampMs: Long,
    val sampleInstantCount: Int,
    val actionCount: Int,
    val topicCount: Int,
    val teamId: String? = null,
    val seasonId: String? = null,
    val robotId: String? = null,
)

fun interface ReplayClock {
    /** Monotonic elapsed time; it is never interpreted as a wall-clock timestamp. */
    fun nowMs(): Long
}

object SystemReplayClock : ReplayClock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
}


data class ReplayCacheMetrics(
    val windowStartMs: Long = -1,
    val windowEndMs: Long = -1,
    val cachedFrames: Int = 0,
    val hasPrefetchedWindow: Boolean = false,
    val windowLoads: Long = 0,
    val prefetchHits: Long = 0,
    val truncatedWindows: Long = 0,
)
