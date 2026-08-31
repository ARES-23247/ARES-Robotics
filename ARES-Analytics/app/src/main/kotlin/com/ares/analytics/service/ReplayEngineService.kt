package com.ares.analytics.service

import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.floor

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

data class ReplayCacheMetrics(
    val windowStartMs: Long = -1,
    val windowEndMs: Long = -1,
    val cachedFrames: Int = 0,
    val hasPrefetchedWindow: Boolean = false,
    val windowLoads: Long = 0,
    val prefetchHits: Long = 0,
    val truncatedWindows: Long = 0,
)

/**
 * Deterministic, read-only telemetry replay.
 *
 * DuckDB's `(timestamp_us, sample_order)` ordering is authoritative within a millisecond. The
 * engine publishes one immutable [ReplayFrame] per logical commit. It does not write to NT4,
 * mutate the live telemetry store, infer one localization source from another, or broadcast UDP.
 */
class ReplayEngineService(
    private val databaseService: DatabaseService,
    private val nt4ClientService: Nt4ClientService? = null,
    private val clock: ReplayClock = SystemReplayClock,
    replayDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val serviceScope = CoroutineScope(replayDispatcher + SupervisorJob())
    private val lock = Any()

    private val _state = MutableStateFlow(ReplayState.STOPPED)
    val state: StateFlow<ReplayState> = _state.asStateFlow()
    private val _loadState = MutableStateFlow(ReplayLoadState.IDLE)
    val loadState: StateFlow<ReplayLoadState> = _loadState.asStateFlow()
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    private val _sessionInfo = MutableStateFlow<ReplaySessionInfo?>(null)
    val sessionInfo: StateFlow<ReplaySessionInfo?> = _sessionInfo.asStateFlow()
    private val _currentFrame = MutableStateFlow<ReplayFrame?>(null)
    val currentFrame: StateFlow<ReplayFrame?> = _currentFrame.asStateFlow()
    private val _speed = MutableStateFlow(1.0)
    val speed: StateFlow<Double> = _speed.asStateFlow()
    private val _looping = MutableStateFlow(false)
    val looping: StateFlow<Boolean> = _looping.asStateFlow()
    private val _progress = MutableStateFlow(0.0)
    val progress: StateFlow<Double> = _progress.asStateFlow()
    private val _playheadTimestampMs = MutableStateFlow(0L)
    val playheadTimestampMs: StateFlow<Long> = _playheadTimestampMs.asStateFlow()
    private val _isSeeking = MutableStateFlow(false)
    val isSeeking: StateFlow<Boolean> = _isSeeking.asStateFlow()
    private val _telemetryDensity = MutableStateFlow<List<Float>>(emptyList())
    val telemetryDensity: StateFlow<List<Float>> = _telemetryDensity.asStateFlow()
    private val _sessionActions = MutableStateFlow<List<RobotActionRecord>>(emptyList())
    val sessionActions: StateFlow<List<RobotActionRecord>> = _sessionActions.asStateFlow()
    private val _sessionAnnotations = MutableStateFlow<List<SessionAnnotation>>(emptyList())
    val sessionAnnotations: StateFlow<List<SessionAnnotation>> = _sessionAnnotations.asStateFlow()
    private val _sessionStartTimestampMs = MutableStateFlow(0L)
    val sessionStartTimestampMs: StateFlow<Long> = _sessionStartTimestampMs.asStateFlow()
    private val _sessionDurationMs = MutableStateFlow(0L)
    val sessionDurationMs: StateFlow<Long> = _sessionDurationMs.asStateFlow()
    private val _cacheMetrics = MutableStateFlow(ReplayCacheMetrics())
    val cacheMetrics: StateFlow<ReplayCacheMetrics> = _cacheMetrics.asStateFlow()

    private var replayJob: Job? = null
    private var prefetchJob: Job? = null
    private var windowLoadJob: Job? = null
    private var generation = 0L
    private var windowRequest = 0L
    private var commitSequence = 0L
    private var currentSessionId = ""
    private var timestamps: List<Long> = emptyList()
    private var startTimestampMs = 0L
    private var endTimestampMs = 0L
    private var currentPlayheadMs = 0L
    private var fractionalPlaybackMs = 0.0
    private var activeWindow: ReplayWindow? = null
    private var prefetchedWindow: ReplayWindow? = null
    private var lastTargetTimestamp = Long.MIN_VALUE
    private var lastFrameIndex = 0
    private val numericValues = LinkedHashMap<String, Double>()
    private val stringValues = LinkedHashMap<String, String>()
    private var windowLoadCount = 0L
    private var prefetchHitCount = 0L

    suspend fun loadSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "Replay session ID must not be blank" }
        val requestedGeneration = synchronized(lock) {
            generation += 1L
            stopLocked(resetToStart = false)
            resetSessionLocked(sessionId)
            _loadState.value = ReplayLoadState.LOADING
            generation
        }

        try {
            if (sessionId == Nt4ClientService.LIVE_SESSION_ID && nt4ClientService != null) {
                check(nt4ClientService.flushPendingFrames()) {
                    "Cannot rewind live telemetry because pending frames could not be persisted"
                }
            }
            val loaded = withContext(Dispatchers.IO) {
                val frameTimestamps = databaseService.getDistinctTimestamps(sessionId)
                val topicCount = databaseService.getDistinctTelemetryKeys(sessionId).size
                val session = databaseService.getSessions().firstOrNull { it.sessionId == sessionId }
                val actions = databaseService.getActionsForSession(sessionId)
                val annotations = databaseService.getAnnotations(sessionId)
                val density = if (frameTimestamps.isEmpty()) emptyList() else {
                    databaseService.getTelemetryDensity(sessionId, buckets = DENSITY_BUCKETS)
                }
                if (frameTimestamps.isEmpty()) {
                    LoadedSession(frameTimestamps, actions, annotations, density, topicCount, session, null)
                } else {
                    val first = frameTimestamps.first()
                    val last = frameTimestamps.last()
                    LoadedSession(
                        timestamps = frameTimestamps,
                        actions = actions,
                        annotations = annotations,
                        density = density,
                        topicCount = topicCount,
                        session = session,
                        initialWindow = loadWindow(
                            sessionId = sessionId,
                            startMs = first,
                            endMs = (first + WINDOW_LOOKAHEAD_MS).coerceAtMost(last),
                            sessionStartMs = first,
                        ),
                    )
                }
            }

            synchronized(lock) {
                if (requestedGeneration != generation || currentSessionId != sessionId) return
                timestamps = loaded.timestamps
                _sessionActions.value = loaded.actions
                _sessionAnnotations.value = loaded.annotations
                _telemetryDensity.value = loaded.density
                if (loaded.timestamps.isEmpty()) {
                    _loadState.value = ReplayLoadState.EMPTY
                    _state.value = ReplayState.STOPPED
                    _isSeeking.value = false
                    return
                }
                startTimestampMs = loaded.timestamps.first()
                endTimestampMs = loaded.timestamps.last()
                currentPlayheadMs = startTimestampMs
                _sessionStartTimestampMs.value = startTimestampMs
                _sessionDurationMs.value = endTimestampMs - startTimestampMs
                applyWindowLocked(requireNotNull(loaded.initialWindow))
                commitAtPlayheadLocked()
                _sessionInfo.value = ReplaySessionInfo(
                    sessionId = sessionId,
                    startTimestampMs = startTimestampMs,
                    endTimestampMs = endTimestampMs,
                    sampleInstantCount = loaded.timestamps.size,
                    actionCount = loaded.actions.size,
                    topicCount = loaded.topicCount,
                    teamId = loaded.session?.teamId,
                    seasonId = loaded.session?.seasonId,
                    robotId = loaded.session?.robotId,
                )
                _loadState.value = ReplayLoadState.READY
                scheduleForwardPrefetchLocked(requireNotNull(loaded.initialWindow), requestedGeneration)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            synchronized(lock) {
                if (requestedGeneration == generation && currentSessionId == sessionId) {
                    _loadError.value = error.message ?: error::class.simpleName ?: "Replay load failed"
                    _loadState.value = ReplayLoadState.ERROR
                    _state.value = ReplayState.STOPPED
                    _isSeeking.value = false
                }
            }
        }
    }

    fun play() {
        synchronized(lock) {
            if (timestamps.isEmpty() || _loadState.value != ReplayLoadState.READY) return
            if (_state.value == ReplayState.PLAYING) return
            if (startTimestampMs == endTimestampMs) {
                currentPlayheadMs = startTimestampMs
                commitOrLoadLocked()
                _state.value = ReplayState.ENDED
                return
            }
            if (currentPlayheadMs >= endTimestampMs) {
                currentPlayheadMs = startTimestampMs
                commitOrLoadLocked()
            }
            fractionalPlaybackMs = 0.0
            _state.value = ReplayState.PLAYING
            replayJob?.cancel()
            replayJob = serviceScope.launch {
                var lastRealTime = clock.nowMs()
                while (true) {
                    synchronized(lock) {
                        if (_state.value != ReplayState.PLAYING) return@launch
                        val now = clock.nowMs()
                        val elapsed = (now - lastRealTime).coerceAtLeast(0L)
                        lastRealTime = now
                        val scaled = elapsed.toDouble() * _speed.value + fractionalPlaybackMs
                        val wholeMs = floor(scaled).toLong()
                        fractionalPlaybackMs = scaled - wholeMs.toDouble()
                        if (wholeMs <= 0L) return@synchronized
                        val requested = currentPlayheadMs + wholeMs
                        if (requested >= endTimestampMs) {
                            if (_looping.value && endTimestampMs > startTimestampMs) {
                                val duration = endTimestampMs - startTimestampMs
                                currentPlayheadMs = startTimestampMs + ((requested - startTimestampMs) % duration)
                                commitOrLoadLocked()
                            } else {
                                currentPlayheadMs = endTimestampMs
                                commitOrLoadLocked()
                                _state.value = ReplayState.ENDED
                                return@launch
                            }
                        } else {
                            currentPlayheadMs = requested
                            commitOrLoadLocked()
                        }
                    }
                    delay(PLAYBACK_TICK_MS)
                }
            }
        }
    }

    fun pause() {
        synchronized(lock) {
            if (_state.value != ReplayState.PLAYING) return
            _state.value = ReplayState.PAUSED
            replayJob?.cancel()
            replayJob = null
        }
    }

    /** Stops playback and returns to the first sample without leaving the selected replay source. */
    fun stop() = synchronized(lock) { stopLocked(resetToStart = true) }

    fun setSpeed(newSpeed: Double) {
        require(newSpeed.isFinite() && newSpeed in MIN_SPEED..MAX_SPEED) {
            "Replay speed must be finite and between ${MIN_SPEED}x and ${MAX_SPEED}x"
        }
        _speed.value = newSpeed
    }

    fun setLooping(enabled: Boolean) { _looping.value = enabled }

    fun stepForward() {
        synchronized(lock) {
            if (timestamps.isEmpty()) return
            pauseLockedForNavigation()
            val index = timestamps.binarySearch(currentPlayheadMs)
            val next = if (index >= 0) index + 1 else -index - 1
            if (next >= timestamps.size) {
                currentPlayheadMs = endTimestampMs
                _state.value = ReplayState.ENDED
            } else {
                currentPlayheadMs = timestamps[next]
                _state.value = ReplayState.PAUSED
            }
            commitOrLoadLocked()
        }
    }

    fun stepBackward() {
        synchronized(lock) {
            if (timestamps.isEmpty()) return
            pauseLockedForNavigation()
            val index = timestamps.binarySearch(currentPlayheadMs)
            val previous = if (index >= 0) index - 1 else -index - 2
            currentPlayheadMs = if (previous >= 0) timestamps[previous] else startTimestampMs
            _state.value = ReplayState.PAUSED
            commitOrLoadLocked()
        }
    }

    fun scrubTo(percentage: Double) {
        require(percentage.isFinite()) { "Replay percentage must be finite" }
        synchronized(lock) {
            if (timestamps.isEmpty()) return
            val clamped = percentage.coerceIn(0.0, 1.0)
            currentPlayheadMs = startTimestampMs + ((endTimestampMs - startTimestampMs) * clamped).toLong()
            commitOrLoadLocked()
        }
    }

    fun seekToTimestamp(timestampMs: Long) {
        synchronized(lock) {
            if (timestamps.isEmpty()) return
            currentPlayheadMs = timestampMs.coerceIn(startTimestampMs, endTimestampMs)
            commitOrLoadLocked()
        }
    }

    fun dispose() = runBlocking { disposeAndJoin() }

    suspend fun disposeAndJoin() {
        val jobs = synchronized(lock) {
            val activeJobs = listOfNotNull(replayJob, prefetchJob, windowLoadJob)
            stopLocked(resetToStart = false)
            generation += 1L
            activeJobs
        }
        jobs.forEach { it.cancelAndJoin() }
        serviceScope.cancel()
    }

    private fun stopLocked(resetToStart: Boolean) {
        replayJob?.cancel()
        replayJob = null
        _state.value = ReplayState.STOPPED
        fractionalPlaybackMs = 0.0
        if (resetToStart && timestamps.isNotEmpty()) {
            currentPlayheadMs = startTimestampMs
            commitOrLoadLocked()
        }
    }

    private fun pauseLockedForNavigation() {
        replayJob?.cancel()
        replayJob = null
        fractionalPlaybackMs = 0.0
    }

    private fun resetSessionLocked(sessionId: String) {
        prefetchJob?.cancel()
        windowLoadJob?.cancel()
        prefetchJob = null
        windowLoadJob = null
        currentSessionId = sessionId
        timestamps = emptyList()
        startTimestampMs = 0L
        endTimestampMs = 0L
        currentPlayheadMs = 0L
        activeWindow = null
        prefetchedWindow = null
        lastTargetTimestamp = Long.MIN_VALUE
        lastFrameIndex = 0
        numericValues.clear()
        stringValues.clear()
        commitSequence = 0L
        windowLoadCount = 0L
        prefetchHitCount = 0L
        _currentFrame.value = null
        _sessionInfo.value = null
        _sessionActions.value = emptyList()
        _sessionAnnotations.value = emptyList()
        _telemetryDensity.value = emptyList()
        _sessionStartTimestampMs.value = 0L
        _sessionDurationMs.value = 0L
        _playheadTimestampMs.value = 0L
        _progress.value = 0.0
        _isSeeking.value = false
        _loadError.value = null
        _cacheMetrics.value = ReplayCacheMetrics()
    }

    private fun commitOrLoadLocked() {
        val window = activeWindow
        if (window != null && currentPlayheadMs in window.startMs..window.endMs) {
            commitAtPlayheadLocked()
        } else {
            requestWindowLocked(currentPlayheadMs)
        }
    }

    private fun requestWindowLocked(playheadMs: Long) {
        prefetchedWindow?.takeIf { playheadMs in it.startMs..it.endMs }?.let { ready ->
            prefetchedWindow = null
            prefetchHitCount += 1L
            applyWindowLocked(ready)
            commitAtPlayheadLocked()
            scheduleForwardPrefetchLocked(ready, generation)
            return
        }
        windowLoadJob?.cancel()
        windowRequest += 1L
        val requestId = windowRequest
        val requestedGeneration = generation
        val requestedSession = currentSessionId
        val requestedStart = (playheadMs - WINDOW_HISTORY_MS).coerceAtLeast(startTimestampMs)
        val requestedEnd = (playheadMs + WINDOW_LOOKAHEAD_MS).coerceAtMost(endTimestampMs)
        val requestedSessionStart = startTimestampMs
        _isSeeking.value = true
        windowLoadJob = serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                loadWindow(requestedSession, requestedStart, requestedEnd, requestedSessionStart)
            }
            synchronized(lock) {
                if (requestedGeneration != generation || requestId != windowRequest || requestedSession != currentSessionId) {
                    return@launch
                }
                applyWindowLocked(loaded)
                _isSeeking.value = false
                windowLoadJob = null
                if (currentPlayheadMs in loaded.startMs..loaded.endMs) {
                    commitAtPlayheadLocked()
                    scheduleForwardPrefetchLocked(loaded, requestedGeneration)
                } else {
                    requestWindowLocked(currentPlayheadMs)
                }
            }
        }
    }

    private suspend fun loadWindow(sessionId: String, startMs: Long, endMs: Long, sessionStartMs: Long): ReplayWindow {
        val baseline = if (startMs > sessionStartMs) databaseService.getLatestTelemetryBefore(sessionId, startMs) else emptyList()
        val frames = loadTelemetryWindowPages(databaseService, sessionId, startMs, endMs, REPLAY_PAGE_SIZE)
        return ReplayWindow(sessionId, startMs, endMs, baseline, frames)
    }

    private fun applyWindowLocked(window: ReplayWindow) {
        activeWindow = window
        lastTargetTimestamp = Long.MIN_VALUE
        lastFrameIndex = 0
        numericValues.clear()
        stringValues.clear()
        window.baseline.forEach(::applyFrameLocked)
        windowLoadCount += 1L
        publishCacheMetricsLocked()
    }

    private fun commitAtPlayheadLocked() {
        val window = activeWindow ?: return
        if (timestamps.isEmpty()) return
        var timestampIndex = timestamps.binarySearch(currentPlayheadMs)
        if (timestampIndex < 0) timestampIndex = -timestampIndex - 2
        timestampIndex = timestampIndex.coerceIn(0, timestamps.lastIndex)
        val targetTimestamp = timestamps[timestampIndex]
        if (lastTargetTimestamp == Long.MIN_VALUE || targetTimestamp < lastTargetTimestamp) {
            lastFrameIndex = 0
            numericValues.clear()
            stringValues.clear()
            window.baseline.forEach(::applyFrameLocked)
        }
        lastTargetTimestamp = targetTimestamp
        while (lastFrameIndex < window.frames.size) {
            val frame = window.frames[lastFrameIndex]
            if (frame.timestampMs > targetTimestamp) break
            applyFrameLocked(frame)
            lastFrameIndex += 1
        }
        commitSequence += 1L
        val snapshot = ReplayFrame(
            timestampMs = targetTimestamp,
            values = numericValues.toMap(),
            stringValues = stringValues.toMap(),
            sessionId = currentSessionId,
            playheadMs = currentPlayheadMs,
            sequence = commitSequence,
        )
        _currentFrame.value = snapshot
        _playheadTimestampMs.value = currentPlayheadMs
        val duration = endTimestampMs - startTimestampMs
        _progress.value = if (duration <= 0L) 0.0 else {
            (currentPlayheadMs - startTimestampMs).toDouble() / duration.toDouble()
        }.coerceIn(0.0, 1.0)
        publishCacheMetricsLocked()
    }

    private fun applyFrameLocked(frame: TelemetryFrame) {
        val key = frame.key.removePrefix("/")
        numericValues[key] = frame.value
        val stringValue = frame.stringValue
        if (stringValue == null) stringValues.remove(key) else stringValues[key] = stringValue
    }

    private fun scheduleForwardPrefetchLocked(window: ReplayWindow, requestedGeneration: Long) {
        if (window.endMs >= endTimestampMs) return
        prefetchJob?.cancel()
        val nextStart = window.endMs + 1L
        val nextEnd = (nextStart + WINDOW_HISTORY_MS + WINDOW_LOOKAHEAD_MS).coerceAtMost(endTimestampMs)
        val session = currentSessionId
        val sessionStart = startTimestampMs
        prefetchJob = serviceScope.launch {
            val loaded = withContext(Dispatchers.IO) { loadWindow(session, nextStart, nextEnd, sessionStart) }
            synchronized(lock) {
                if (requestedGeneration == generation && session == currentSessionId) {
                    prefetchedWindow = loaded
                    publishCacheMetricsLocked()
                }
            }
        }
    }

    private fun publishCacheMetricsLocked() {
        val window = activeWindow
        _cacheMetrics.value = ReplayCacheMetrics(
            windowStartMs = window?.startMs ?: -1L,
            windowEndMs = window?.endMs ?: -1L,
            cachedFrames = window?.frames?.size ?: 0,
            hasPrefetchedWindow = prefetchedWindow != null,
            windowLoads = windowLoadCount,
            prefetchHits = prefetchHitCount,
        )
    }

    private data class LoadedSession(
        val timestamps: List<Long>,
        val actions: List<RobotActionRecord>,
        val annotations: List<SessionAnnotation>,
        val density: List<Float>,
        val topicCount: Int,
        val session: com.ares.analytics.shared.models.Session?,
        val initialWindow: ReplayWindow?,
    )

    private data class ReplayWindow(
        val sessionId: String,
        val startMs: Long,
        val endMs: Long,
        val baseline: List<TelemetryFrame>,
        val frames: List<TelemetryFrame>,
    )

    private companion object {
        const val WINDOW_HISTORY_MS = 2_500L
        const val WINDOW_LOOKAHEAD_MS = 5_000L
        const val REPLAY_PAGE_SIZE = 50_000
        const val DENSITY_BUCKETS = 100
        const val PLAYBACK_TICK_MS = 20L
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 8.0
    }
}
