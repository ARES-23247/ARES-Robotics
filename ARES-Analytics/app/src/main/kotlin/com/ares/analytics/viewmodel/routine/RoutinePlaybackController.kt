package com.ares.analytics.viewmodel.routine

import com.ares.analytics.service.project.persistence.BiobuzzAutoBundle
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.newRoutine
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.catalog.CapabilityContext
import com.areslib.hardware.actuator.IndicatorLightColor
import com.areslib.hardware.actuator.PrismPwmPreset
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.routine.RoutineStepKind
import com.areslib.routine.RoutineValidationSeverity
import com.areslib.subsystem.SubsystemDocumentCodec
import com.areslib.subsystem.SubsystemHardwareKind
import com.areslib.subsystem.SubsystemVisualAnchor
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.subsystemIndicatorCycleBackwardActionKey
import com.areslib.subsystem.subsystemIndicatorCycleForwardActionKey
import com.areslib.subsystem.subsystemTargetActionKey
import com.areslib.subsystem.subsystemTargetCapabilities
import java.io.File
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

    fun seekTo(timeSeconds: Double) {
        playbackJob?.cancel()
        state.update { current ->
            val maxTime = current.estimatedDuration.coerceAtLeast(0.0)
            val clamped = if (timeSeconds.isFinite()) timeSeconds.coerceIn(0.0, maxTime) else 0.0
            current.copy(isPlaying = false, playbackTime = clamped)
        }
    }

    fun setSpeed(multiplier: Double) {
        val clamped = if (multiplier.isFinite()) multiplier.coerceIn(0.25, 4.0) else 1.0
        state.update { it.copy(playbackSpeed = clamped) }
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
                    state.update { current ->
                        if (!current.isPlaying) return@update current
                        val nextTime = current.playbackTime + dt * current.playbackSpeed
                        if (nextTime >= current.estimatedDuration) {
                            current.copy(playbackTime = current.estimatedDuration, isPlaying = false)
                        } else {
                            current.copy(playbackTime = nextTime)
                        }
                    }
                }
            }
        }
    }
}
