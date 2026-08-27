package com.ares.analytics.viewmodel.field

import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Samples live poses into a bounded trace while suppressing sub-centimeter duplicates. */
class FieldPoseBufferManager(
    private val scope: CoroutineScope,
    private val stateFlow: MutableStateFlow<FieldViewerState>,
    private val livePoseFlow: MutableStateFlow<LivePoseState>
) {
    private val poseBuffer = ArrayDeque<Waypoint>(MAX_TRACE_SAMPLES)

    init {
        // Trace sampling must not fall behind a busy Compose render or dashboard layout pass.
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(50)
                val currentLiveState = livePoseFlow.value
                // Use simulator TruePose if available, otherwise fall back to EKF data from real robot
                val x = if (currentLiveState.hasTruePoseData) currentLiveState.trueX else (currentLiveState.ekfX ?: currentLiveState.trueX)
                val y = if (currentLiveState.hasTruePoseData) currentLiveState.trueY else (currentLiveState.ekfY ?: currentLiveState.trueY)
                val heading = if (currentLiveState.hasTruePoseData) currentLiveState.trueHeading else (currentLiveState.ekfHeading ?: currentLiveState.trueHeading)

                val lastWp = poseBuffer.lastOrNull()
                val moved = lastWp == null ||
                    kotlin.math.abs(lastWp.x - x) > MIN_TRANSLATION_METERS ||
                    kotlin.math.abs(lastWp.y - y) > MIN_TRANSLATION_METERS
                when {
                    moved -> appendPose(Waypoint(x, y, heading))
                    lastWp.headingRad != heading -> replaceLastPose(Waypoint(x, y, heading))
                }
            }
        }
    }

    private fun appendPose(pose: Waypoint) {
        if (poseBuffer.size == MAX_TRACE_SAMPLES) poseBuffer.removeFirst()
        poseBuffer.addLast(pose)
        publishTrace()
    }

    private fun replaceLastPose(pose: Waypoint) {
        poseBuffer.removeLast()
        poseBuffer.addLast(pose)
        publishTrace()
    }

    private fun publishTrace() {
        val snapshot = poseBuffer.toList()
        stateFlow.update { it.copy(poseHistory = snapshot) }
    }

    fun clearTrace() {
        poseBuffer.clear()
        stateFlow.update { it.copy(poseHistory = emptyList()) }
    }

    private companion object {
        const val MAX_TRACE_SAMPLES = 150
        const val MIN_TRANSLATION_METERS = 0.01
    }
}
