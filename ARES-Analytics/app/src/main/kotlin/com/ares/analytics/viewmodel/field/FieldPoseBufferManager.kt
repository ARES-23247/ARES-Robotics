package com.ares.analytics.viewmodel.field

import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.CoroutineDispatcher
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
    private val livePoseFlow: MutableStateFlow<LivePoseState>,
    samplingDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val poseBuffer = ArrayDeque<Waypoint>(MAX_TRACE_SAMPLES)

    init {
        // Trace sampling must not fall behind a busy Compose render or dashboard layout pass.
        scope.launch(samplingDispatcher) {
            while (true) {
                delay(50)
                samplePose()
            }
        }
    }

    @Synchronized
    private fun samplePose() {
        val current = livePoseFlow.value
        if (!current.isConnected) return
        val x = if (current.hasTruePoseData) current.trueX else current.ekfX ?: return
        val y = if (current.hasTruePoseData) current.trueY else current.ekfY ?: return
        val heading = if (current.hasTruePoseData) current.trueHeading else current.ekfHeading ?: return
        if (!x.isFinite() || !y.isFinite() || !heading.isFinite()) return
        val last = poseBuffer.lastOrNull()
        val moved = last == null || kotlin.math.hypot(last.x - x, last.y - y) > MIN_TRANSLATION_METERS
        when {
            moved -> appendPose(Waypoint(x, y, heading))
            last.headingRad != heading -> replaceLastPose(Waypoint(x, y, heading))
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

    @Synchronized
    fun clearTrace() {
        poseBuffer.clear()
        stateFlow.update { it.copy(poseHistory = emptyList()) }
    }

    private companion object {
        const val MAX_TRACE_SAMPLES = 150
        const val MIN_TRANSLATION_METERS = 0.01
    }
}
