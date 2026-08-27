package com.areslib.ftc.runtime

import com.areslib.state.RobotState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FtcDriveAssistModesTest {
    @Test
    fun `position hold requires explicit enable and fresh valid motion feedback`() {
        val modes = FtcDriveAssistModes()
        val healthy = RobotState().copy(
            drive = RobotState().drive.copy(
                measuredMotionValid = true,
                poseEstimator = RobotState().drive.poseEstimator.copy(lastObservationTimestampMs = 100L),
            ),
        )

        assertFalse(modes.positionHoldAllowed(healthy, nowMs = 120L, staleFeedbackTimeoutMs = 50L))
        modes.positionHoldEnabled = true
        assertTrue(modes.positionHoldAllowed(healthy, nowMs = 120L, staleFeedbackTimeoutMs = 50L))
        assertFalse(modes.positionHoldAllowed(healthy, nowMs = 151L, staleFeedbackTimeoutMs = 50L))
        assertFalse(
            modes.positionHoldAllowed(
                healthy.copy(drive = healthy.drive.copy(measuredMotionValid = false)),
                nowMs = 120L,
                staleFeedbackTimeoutMs = 50L,
            ),
        )
    }
}
