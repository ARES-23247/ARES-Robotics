package com.ares.analytics.service

import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Deterministic, read-only telemetry replay.
 *
 * DuckDB's `(timestamp_us, sample_order)` ordering is authoritative within a millisecond. The
 * engine publishes one immutable [ReplayFrame] per logical commit. It does not write to NT4,
 * mutate the live telemetry store, infer one localization source from another, or broadcast UDP.
 */
class ReplayEngineService internal constructor(
    private val databaseService: DatabaseService,
    private val nt4ClientService: Nt4ClientService?,
    private val clock: ReplayClock,
    replayDispatcher: CoroutineDispatcher,
    private val windowSource: ReplayWindowSource,
    private val windowDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(databaseService: DatabaseService, nt4ClientService: Nt4ClientService? = null,
        clock: ReplayClock = SystemReplayClock, replayDispatcher: CoroutineDispatcher = Dispatchers.Default
    ) : this(databaseService, nt4ClientService, clock, replayDispatcher, DatabaseReplayWindowSource(databaseService))
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(replayDispatcher + serviceJob)
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

    private var disposed = false
    private var playbackRequest = 0L
    private val playbackTime = ReplayPlaybackTime()
    private var sessionLoadJob: Deferred<LoadedSession>? = null
    private var replayJob: Job? = null
    private var prefetchJob: Deferred<ReplayWindow>? = null
    private var prefetchRange: LongRange? = null
    private var pendingRange: LongRange? = null
    private var windowLoadJob: Job? = null
    private var generation = 0L
    private var prefetchRequest = 0L
    private var windowRequest = 0L
    private var commitSequence = 0L
    private var currentSessionId = ""
    private var timestamps: List<Long> = emptyList()
    private var startTimestampMs = 0L
    private var endTimestampMs = 0L
    private var currentPlayheadMs = 0L
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
            check(!disposed) { "Replay engine is disposed" }
            generation += 1L
            stopLocked(resetToStart = false)
            resetSessionLocked(sessionId)
            _loadState.value = ReplayLoadState.LOADING
            generation
        }

        var loading: Deferred<LoadedSession>? = null
        try {
            val work = serviceScope.async(context = windowDispatcher, start = CoroutineStart.LAZY) {
                if (sessionId == Nt4ClientService.LIVE_SESSION_ID && nt4ClientService != null) {
                    check(nt4ClientService.flushPendingFrames()) {
                        "Cannot rewind live telemetry because pending frames could not be persisted"
                    }
                }
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
                        initialWindow = windowSource.load(
                            sessionId = sessionId,
                            startMs = first,
                            endMs = (first + WINDOW_LOOKAHEAD_MS).coerceAtMost(last),
                            sessionStartMs = first,
                        ),
                    )
                }
            }

            loading = work
            synchronized(lock) {
                if (requestedGeneration != generation || disposed) { work.cancel(); return }
                sessionLoadJob = work
            }
            val loaded = work.await()

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
            synchronized(lock) {
                if (requestedGeneration == generation && !disposed) {
                    resetSessionLocked("")
                    _loadState.value = ReplayLoadState.IDLE
                }
            }
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
        } finally {
            loading?.let { work ->
                work.cancel()
                withContext(NonCancellable) { work.join() }
                synchronized(lock) { if (sessionLoadJob === work) sessionLoadJob = null }
            }
        }
    }

    fun play() {
        synchronized(lock) {
            if (disposed || timestamps.isEmpty() || _loadState.value != ReplayLoadState.READY || _state.value == ReplayState.PLAYING) return
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
            playbackTime.reset(clock.nowMs())
            _state.value = ReplayState.PLAYING
            replayJob?.cancel()
            val request = ++playbackRequest
            replayJob = serviceScope.launch {
                while (true) {
                    val running = synchronized(lock) {
                        request == playbackRequest && _state.value == ReplayState.PLAYING && advancePlaybackLocked()
                    }
                    if (!running) return@launch
                    delay(PLAYBACK_TICK_MS)
                }
            }
        }
    }

    private fun advancePlaybackLocked(): Boolean {
        try {
            currentPlayheadMs = playbackTime.advance(clock.nowMs(), currentPlayheadMs,
                startTimestampMs, endTimestampMs, _speed.value, _looping.value)
            if (playbackTime.advanced) commitOrLoadLocked()
            if (playbackTime.ended) {
                _state.value = ReplayState.ENDED
                replayJob?.cancel(); replayJob = null
                return false
            }
            return true
        } catch (error: Exception) {
            pauseLockedForNavigation()
            cancelWindowRequestLocked()
            _state.value = ReplayState.PAUSED
            _loadState.value = ReplayLoadState.ERROR
            _loadError.value = error.message ?: "Replay clock failed"
            _isSeeking.value = false
            return false
        }
    }

    fun pause() = synchronized(lock) {
        if (_state.value != ReplayState.PLAYING) return@synchronized
        advancePlaybackLocked()
        if (_state.value == ReplayState.PLAYING) _state.value = ReplayState.PAUSED
        pauseLockedForNavigation()
    }

    /** Stops playback and returns to the first sample without leaving the selected replay source. */
    fun stop() = synchronized(lock) { stopLocked(resetToStart = true) }

    fun setSpeed(newSpeed: Double) {
        require(newSpeed.isFinite() && newSpeed in MIN_SPEED..MAX_SPEED) {
            "Replay speed must be finite and between ${MIN_SPEED}x and ${MAX_SPEED}x"
        }
        synchronized(lock) {
            if (disposed) return
            if (_state.value == ReplayState.PLAYING) advancePlaybackLocked()
            _speed.value = newSpeed
        }
    }

    fun setLooping(enabled: Boolean) = synchronized(lock) {
        if (disposed) return@synchronized
        if (_state.value == ReplayState.PLAYING) advancePlaybackLocked()
        _looping.value = enabled
    }

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
            playbackTime.reset(if (_state.value == ReplayState.PLAYING) clock.nowMs() else 0L)
            val clamped = percentage.coerceIn(0.0, 1.0)
            currentPlayheadMs = startTimestampMs + ((endTimestampMs - startTimestampMs) * clamped).toLong()
            commitOrLoadLocked()
        }
    }

    fun seekToTimestamp(timestampMs: Long) {
        synchronized(lock) {
            if (timestamps.isEmpty()) return
            playbackTime.reset(if (_state.value == ReplayState.PLAYING) clock.nowMs() else 0L)
            currentPlayheadMs = timestampMs.coerceIn(startTimestampMs, endTimestampMs)
            commitOrLoadLocked()
        }
    }

    fun dispose() = runBlocking { disposeAndJoin() }

    suspend fun disposeAndJoin() {
        synchronized(lock) {
            if (!disposed) {
                disposed = true
                stopLocked(resetToStart = false)
                generation += 1L
                resetSessionLocked("")
                _loadState.value = ReplayLoadState.IDLE
            }
        }
        // Includes superseded and initial readers still unwinding database work.
        serviceJob.cancelAndJoin()
    }

    private fun stopLocked(resetToStart: Boolean) {
        pauseLockedForNavigation()
        _state.value = ReplayState.STOPPED
        if (resetToStart && timestamps.isNotEmpty()) {
            currentPlayheadMs = startTimestampMs
            commitOrLoadLocked()
        }
    }

    private fun pauseLockedForNavigation() {
        playbackRequest += 1L
        replayJob?.cancel()
        replayJob = null
        playbackTime.reset(0L)
    }

    private fun resetSessionLocked(sessionId: String) {
        sessionLoadJob?.cancel()
        sessionLoadJob = null
        prefetchJob?.cancel()
        windowLoadJob?.cancel()
        prefetchJob = null
        prefetchRange = null
        pendingRange = null
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
            cancelWindowRequestLocked()
            commitAtPlayheadLocked()
        } else {
            requestWindowLocked(currentPlayheadMs)
        }
    }

    private fun cancelWindowRequestLocked() {
        windowRequest += 1L
        windowLoadJob?.cancel()
        windowLoadJob = null
        pendingRange = null
    }

    private fun requestWindowLocked(playheadMs: Long) {
        prefetchedWindow?.takeIf { playheadMs in it.startMs..it.endMs }?.let { ready ->
            cancelWindowRequestLocked()
            prefetchedWindow = null
            prefetchHitCount += 1L
            applyWindowLocked(ready)
            commitAtPlayheadLocked()
            scheduleForwardPrefetchLocked(ready, generation)
            return
        }
        // Playback may advance while IO runs. One window serves every target inside its bounds.
        if (windowLoadJob?.isActive == true && pendingRange?.contains(playheadMs) == true) return
        cancelWindowRequestLocked()
        val requestId = windowRequest
        val requestedGeneration = generation
        val requestedSession = currentSessionId
        val requestedSessionStart = startTimestampMs
        val sharedPrefetch = prefetchJob?.takeIf { !it.isCancelled && prefetchRange?.contains(playheadMs) == true }
        val requestedRange = if (sharedPrefetch != null) requireNotNull(prefetchRange) else {
            prefetchJob?.cancel()
            prefetchJob = null
            prefetchRange = null
            (playheadMs - WINDOW_HISTORY_MS).coerceAtLeast(startTimestampMs)..
                (playheadMs + WINDOW_LOOKAHEAD_MS).coerceAtMost(endTimestampMs)
        }
        pendingRange = requestedRange
        _isSeeking.value = true
        windowLoadJob = serviceScope.launch {
            try {
                val loaded = sharedPrefetch?.await() ?: withContext(windowDispatcher) {
                    windowSource.load(requestedSession, requestedRange.first, requestedRange.last, requestedSessionStart)
                }
                synchronized(lock) {
                    if (requestedGeneration != generation || requestId != windowRequest || requestedSession != currentSessionId) return@launch
                    windowLoadJob = null
                    pendingRange = null
                    if (sharedPrefetch != null) { prefetchedWindow = null; prefetchHitCount += 1L }
                    applyWindowLocked(loaded)
                    commitAtPlayheadLocked()
                    scheduleForwardPrefetchLocked(loaded, requestedGeneration)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(lock) {
                    if (requestedGeneration == generation && requestId == windowRequest && requestedSession == currentSessionId) {
                        windowLoadJob = null; pendingRange = null
                        pauseLockedForNavigation()
                        _state.value = ReplayState.PAUSED
                        _loadError.value = error.message ?: "Replay seek failed"
                        _loadState.value = ReplayLoadState.ERROR
                        _isSeeking.value = false
                    }
                }
            }
        }
    }

    private fun applyWindowLocked(window: ReplayWindow) {
        activeWindow = window
        lastTargetTimestamp = Long.MIN_VALUE
        lastFrameIndex = 0
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
        _loadError.value = null
        _loadState.value = ReplayLoadState.READY
        // Completion follows the committed snapshot and its playhead/progress metadata.
        _isSeeking.value = false
    }

    private fun applyFrameLocked(frame: TelemetryFrame) {
        val key = frame.key.removePrefix("/")
        numericValues[key] = frame.value
        val stringValue = frame.stringValue
        if (stringValue == null) stringValues.remove(key) else stringValues[key] = stringValue
    }

    private fun scheduleForwardPrefetchLocked(window: ReplayWindow, requestedGeneration: Long) {
        val prefetchId = ++prefetchRequest
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchRange = null
        prefetchedWindow = null
        publishCacheMetricsLocked()
        if (window.endMs >= endTimestampMs) return
        val nextStart = window.endMs + 1L
        val nextEnd = (nextStart + WINDOW_HISTORY_MS + WINDOW_LOOKAHEAD_MS).coerceAtMost(endTimestampMs)
        val session = currentSessionId
        val sessionStart = startTimestampMs
        prefetchRange = nextStart..nextEnd
        prefetchJob = serviceScope.async {
            val loaded = withContext(windowDispatcher) { windowSource.load(session, nextStart, nextEnd, sessionStart) }
            synchronized(lock) {
                if (requestedGeneration == generation && prefetchId == prefetchRequest &&
                    session == currentSessionId && prefetchRange == nextStart..nextEnd) {
                    prefetchedWindow = loaded
                    publishCacheMetricsLocked()
                }
            }
            loaded
        }
    }

    private fun publishCacheMetricsLocked() {
        val window = activeWindow
        val previous = _cacheMetrics.value
        if (previous.windowStartMs == (window?.startMs ?: -1L) && previous.windowEndMs == (window?.endMs ?: -1L) &&
            previous.cachedFrames == (window?.frames?.size ?: 0) && previous.hasPrefetchedWindow == (prefetchedWindow != null) &&
            previous.windowLoads == windowLoadCount && previous.prefetchHits == prefetchHitCount) return
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


    private companion object {
        const val WINDOW_HISTORY_MS = 2_500L
        const val WINDOW_LOOKAHEAD_MS = 5_000L
        const val DENSITY_BUCKETS = 100
        const val PLAYBACK_TICK_MS = 20L
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 8.0
    }
}
