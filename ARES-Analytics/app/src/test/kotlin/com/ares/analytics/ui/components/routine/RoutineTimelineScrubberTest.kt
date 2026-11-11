package com.ares.analytics.ui.components.routine

import com.ares.analytics.shared.models.Trajectory
import com.ares.analytics.shared.models.TrajectoryState
import com.ares.analytics.viewmodel.PathPlannerState
import com.ares.analytics.viewmodel.routine.RoutinePlaybackController
import com.ares.analytics.viewmodel.routine.RoutinePreviewAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RoutineTimelineScrubberTest {

    @Test
    fun `sampleRoutineKinematicsAt interpolates pose velocity acceleration and active actions`() {
        val trajectory = Trajectory(
            states = listOf(
                TrajectoryState(timeSeconds = 0.0, x = 0.0, y = 0.0, headingRad = 0.0, velocity = 0.0),
                TrajectoryState(timeSeconds = 1.0, x = 1.0, y = 2.0, headingRad = PI / 2.0, velocity = 2.0),
                TrajectoryState(timeSeconds = 2.0, x = 3.0, y = 2.0, headingRad = PI, velocity = 1.0),
            ),
            durationSeconds = 2.0,
        )
        val previewActions = listOf(
            RoutinePreviewAction(
                timeSeconds = 0.5,
                stepId = "step_1",
                actionKey = "intake.acquire",
                arguments = emptyMap(),
            ),
            RoutinePreviewAction(
                timeSeconds = 1.5,
                stepId = "step_2",
                actionKey = "shooter.fire",
                arguments = emptyMap(),
            )
        )

        val (sample, active) = sampleRoutineKinematicsAt(
            trajectory = trajectory,
            previewActions = previewActions,
            timeSeconds = 0.5,
        )

        assertNotNull(sample)
        assertTrue(abs(sample.xMeters - 0.5) < 1e-6)
        assertTrue(abs(sample.yMeters - 1.0) < 1e-6)
        assertTrue(abs(sample.headingDegrees - 45.0) < 1e-4)
        assertTrue(abs(sample.velocityMps - 1.0) < 1e-6)
        assertTrue(abs(sample.accelerationMps2 - 2.0) < 1e-6)
        assertTrue(abs(sample.angularVelocityDegPerSec - 90.0) < 1e-4)
        assertEquals(1, active.size)
        assertEquals("intake.acquire", active.single().actionKey)

        val poseSummary = formatDualPoseSummary(sample)
        val kinSummary = formatDualKinematicsSummary(sample)
        assertTrue(poseSummary.contains("(0.50 m, 1.00 m)"))
        assertTrue(poseSummary.contains("(19.7 in, 39.4 in)"))
        assertTrue(kinSummary.contains("1.00 m/s (3.3 ft/s)"))
        assertTrue(kinSummary.contains("2.00 m/s² (6.6 ft/s²)"))
    }

    @Test
    fun `RoutinePlaybackController seekTo and setSpeed clamp and update state`() {
        val stateFlow = MutableStateFlow(
            PathPlannerState(
                estimatedDuration = 6.5,
                isPlaying = true,
                playbackTime = 1.2,
                playbackSpeed = 1.0,
            )
        )
        val controller = RoutinePlaybackController(CoroutineScope(Dispatchers.Unconfined), stateFlow)

        controller.seekTo(4.25)
        assertFalse(stateFlow.value.isPlaying)
        assertEquals(4.25, stateFlow.value.playbackTime)

        controller.seekTo(99.0)
        assertEquals(6.5, stateFlow.value.playbackTime)

        controller.seekTo(-3.0)
        assertEquals(0.0, stateFlow.value.playbackTime)

        controller.setSpeed(0.5)
        assertEquals(0.5, stateFlow.value.playbackSpeed)

        controller.setSpeed(10.0)
        assertEquals(4.0, stateFlow.value.playbackSpeed)
    }
}
