package com.ares.analytics.ui.components.dashboard

import com.ares.analytics.ui.components.pathplanner.Waypoint
import com.ares.analytics.viewmodel.LivePoseState
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldViewerCardPoseTest {
    @Test
    fun `simulator estimator overlay remains the real EKF instead of truth`() {
        val liveState = LivePoseState(
            trueX = 4.0,
            trueY = 5.0,
            trueHeading = 0.4,
            hasTruePoseData = true,
            ekfX = 3.8,
            ekfY = 4.7,
            ekfHeading = 0.35,
        )

        assertEquals(Waypoint(3.8, 4.7, 0.35), fieldEstimatedPose(liveState))
    }

    @Test
    fun `live simulator pose replaces buffered head when tracer is hidden`() {
        val buffered = Waypoint(1.0, 2.0, 0.1)
        val live = Waypoint(1.2, 2.3, 0.2)

        assertEquals(listOf(live), fieldRobotPath(listOf(buffered), live, tracerEnabled = false))
    }

    @Test
    fun `live simulator pose is appended as current tracer head`() {
        val buffered = Waypoint(1.0, 2.0, 0.1)
        val live = Waypoint(1.2, 2.3, 0.2)

        assertEquals(
            listOf(buffered, live),
            fieldRobotPath(listOf(buffered), live, tracerEnabled = true),
        )
    }
}
