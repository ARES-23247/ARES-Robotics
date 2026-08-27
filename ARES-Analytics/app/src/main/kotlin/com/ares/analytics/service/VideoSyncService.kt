package com.ares.analytics.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Match video playback and telemetry log frame synchronization service.
 *
 * Synchronizes video playback timestamps ($t_{\text{video}}$) with telemetry log playhead timestamps ($t_{\text{log}}$)
 * by maintaining an alignment time offset ($t_{\text{offset}}$).
 *
 * ### Alignment Mechanics:
 * $$t_{\text{log}} = t_{\text{video}} + t_{\text{offset}} \implies t_{\text{video}} = t_{\text{log}} - t_{\text{offset}}$$
 *
 * ### Thread Safety & Performance Guarantees:
 * Collects replay frame updates asynchronously on `Dispatchers.Default`, emitting synchronized video playhead values via [StateFlow].
 *
 * @param replayEngineService Replay engine supplying log timestamp updates.
 *
 * @see ReplayEngineService
 */
class VideoSyncService(private val replayEngineService: ReplayEngineService) {

    private val _videoFile = MutableStateFlow<File?>(null)
    val videoFile: StateFlow<File?> = _videoFile.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(120000L) // Default 2 minutes
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _currentVideoTimeMs = MutableStateFlow(0L)
    val currentVideoTimeMs: StateFlow<Long> = _currentVideoTimeMs.asStateFlow()

    // Alignment offset: logTimeMs = videoTimeMs + logOffsetMs
    // Therefore: videoTimeMs = logTimeMs - logOffsetMs
    private val _logOffsetMs = MutableStateFlow(0L)
    val logOffsetMs: StateFlow<Long> = _logOffsetMs.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var syncJob: Job? = null

    init {
        // Sync video time to log replay playhead updates
        syncJob = serviceScope.launch {
            replayEngineService.currentFrame.collect { frame ->
                if (frame != null) {
                    val logTimeMs = frame.timestampMs
                    val calculatedVideoTime = logTimeMs - _logOffsetMs.value
                    _currentVideoTimeMs.value = calculatedVideoTime.coerceIn(0L, _videoDurationMs.value)
                }
            }
        }
    }

    fun loadVideo(file: File) {
        _videoFile.value = file
        // Mock video duration based on file size if no metadata decoder is available
        val estimatedDuration = (file.length() / (1024 * 1024) * 2000L).coerceIn(30000L, 300000L)
        _videoDurationMs.value = estimatedDuration
        _currentVideoTimeMs.value = 0L
    }

    fun setVideoDuration(durationMs: Long) {
        _videoDurationMs.value = durationMs
    }

    fun alignTimestamp(videoTimeMs: Long, logTimeMs: Long) {
        _logOffsetMs.value = logTimeMs - videoTimeMs
    }

    fun adjustOffset(deltaMs: Long) {
        _logOffsetMs.value += deltaMs
    }

    fun play() {
        replayEngineService.play()
    }

    fun pause() {
        replayEngineService.pause()
    }

    fun seekVideo(videoTimeMs: Long) {
        val clamped = videoTimeMs.coerceIn(0L, _videoDurationMs.value)
        _currentVideoTimeMs.value = clamped

        // Seek the log using the replay session's absolute time range.
        val targetLogTimeMs = clamped + _logOffsetMs.value
        val sessionStart = replayEngineService.sessionStartTimestampMs.value
        val sessionDuration = replayEngineService.sessionDurationMs.value
        val percentage = if (sessionDuration > 0L) {
            (targetLogTimeMs - sessionStart).toDouble() / sessionDuration.toDouble()
        } else {
            0.0
        }
        replayEngineService.scrubTo(percentage.coerceIn(0.0, 1.0))
    }

    fun dispose() {
        syncJob?.cancel()
        serviceScope.cancel()
    }
}
