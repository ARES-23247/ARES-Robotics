// SPDX-License-Identifier: AGPL-3.0-or-later
package com.ares.analytics.viewmodel.field

import com.ares.analytics.viewmodel.FieldViewerState
import com.ares.analytics.viewmodel.LivePoseState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FieldPoseBufferManagerTest {
    @Test
    fun `sampling ignores absent disconnected incomplete and nonfinite poses`() = runTest {
        val state = MutableStateFlow(FieldViewerState())
        val pose = MutableStateFlow(LivePoseState())
        FieldPoseBufferManager(backgroundScope, state, pose, StandardTestDispatcher(testScheduler))
        runCurrent()
        for (invalid in listOf(LivePoseState(), LivePoseState(isConnected = true),
            LivePoseState(isConnected = true, ekfX = 1.0),
            LivePoseState(ekfX = 1.0, ekfY = 2.0, ekfHeading = 0.1),
            LivePoseState(isConnected = true, hasTruePoseData = true, trueX = Double.NaN))) {
            pose.value = invalid
            advanceTimeBy(50); runCurrent()
            assertTrue(state.value.poseHistory.isEmpty())
        }
        pose.value = LivePoseState(isConnected = true, ekfX = 1.0, ekfY = 2.0, ekfHeading = 0.1)
        advanceTimeBy(50); runCurrent()
        assertEquals(1.0, state.value.poseHistory.single().x)
        assertEquals(2.0, state.value.poseHistory.single().y)
        assertEquals(0.1, state.value.poseHistory.single().headingRad)
    }

    @Test
    fun `diagonal translation uses distance while heading changes replace the last point`() = runTest {
        val state = MutableStateFlow(FieldViewerState())
        val pose = MutableStateFlow(LivePoseState(isConnected = true, hasTruePoseData = true))
        FieldPoseBufferManager(backgroundScope, state, pose, StandardTestDispatcher(testScheduler))
        runCurrent(); advanceTimeBy(50); runCurrent()
        pose.value = pose.value.copy(trueX = 0.009, trueY = 0.009)
        advanceTimeBy(50); runCurrent()
        assertEquals(2, state.value.poseHistory.size)
        pose.value = pose.value.copy(trueHeading = 0.4)
        advanceTimeBy(50); runCurrent()
        assertEquals(2, state.value.poseHistory.size)
        assertEquals(0.4, state.value.poseHistory.last().headingRad)
        advanceTimeBy(50); runCurrent()
        assertEquals(2, state.value.poseHistory.size)
    }

    @Test
    fun `retention removes oldest samples and clearing resets the sampling baseline`() = runTest {
        val state = MutableStateFlow(FieldViewerState(isRedAlliance = false))
        val pose = MutableStateFlow(LivePoseState(isConnected = true, hasTruePoseData = true))
        val manager = FieldPoseBufferManager(backgroundScope, state, pose, StandardTestDispatcher(testScheduler))
        runCurrent()
        repeat(155) { index ->
            pose.value = pose.value.copy(trueX = index.toDouble())
            advanceTimeBy(50); runCurrent()
        }
        assertEquals(150, state.value.poseHistory.size)
        assertEquals(5.0, state.value.poseHistory.first().x)
        assertEquals(154.0, state.value.poseHistory.last().x)
        manager.clearTrace()
        assertTrue(state.value.poseHistory.isEmpty())
        assertEquals(false, state.value.isRedAlliance)
        advanceTimeBy(50); runCurrent()
        assertEquals(154.0, state.value.poseHistory.single().x)
    }
}
