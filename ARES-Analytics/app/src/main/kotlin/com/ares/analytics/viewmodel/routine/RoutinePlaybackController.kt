package com.ares.analytics.viewmodel.routine

import com.ares.analytics.viewmodel.PathPlannerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class RoutinePlaybackController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PathPlannerState>,
) {
    private var playbackJob: Job? = null

    fun cancel() {
        playbackJob?.cancel()
    }

    fun togglePlayback() {
        val preview = state.value
        if (preview.routinePreviewWarning != null || preview.trajectory == null || preview.estimatedDuration <= 0.0) {
            state.update { it.copy(isPlaying = false, playbackTime = 0.0) }
            return
        }
        val currentlyPlaying = state.value.isPlaying
        if (currentlyPlaying) {
            state.update { it.copy(isPlaying = false) }
            playbackJob?.cancel()
        } else {
            if (state.value.playbackTime >= state.value.estimatedDuration) {
                state.update { it.copy(playbackTime = 0.0) }
            }
            state.update { it.copy(isPlaying = true) }
            playbackJob = scope.launch {
                var lastNs = System.nanoTime()
                while (state.value.isPlaying) {
                    delay(16)
                    val nowNs = System.nanoTime()
                    val dt = ((nowNs - lastNs) / 1_000_000_000.0).coerceIn(0.0, 0.25)
                    lastNs = nowNs
                    var completed = false
                    state.update { current ->
                        val nextTime = current.playbackTime + dt
                        if (nextTime >= current.estimatedDuration) {
                            completed = true
                            current.copy(playbackTime = current.estimatedDuration, isPlaying = false)
                        } else {
                            current.copy(playbackTime = nextTime)
                        }
                    }
                    if (completed) break
                }
            }
        }
    }
}
