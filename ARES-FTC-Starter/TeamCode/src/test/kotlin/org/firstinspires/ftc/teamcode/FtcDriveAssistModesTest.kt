// ARES OWNERSHIP: GENERATED STARTER
package org.firstinspires.ftc.teamcode

import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import org.firstinspires.ftc.teamcode.dsl.FtcDriveAssistModes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtcDriveAssistModesTest {
    @Test
    fun `rotation lock starts enabled and anti-push starts opt-in`() {
        val assists = FtcDriveAssistModes()

        assertTrue(assists.headingLockEnabled)
        assertFalse(assists.positionHoldEnabled)
    }

    @Test
    fun `anti-push requires enabled fresh valid pose feedback`() {
        val assists = FtcDriveAssistModes().apply { positionHoldEnabled = true }
        fun state(valid: Boolean, observationMs: Long) = RobotState(
            drive = DriveState(
                measuredMotionValid = valid,
                poseEstimator = PoseEstimatorSnapshot(lastObservationTimestampMs = observationMs),
            ),
        )

        assertTrue(assists.positionHoldAllowed(state(valid = true, observationMs = 900L), 1_000L, 250L))
        assertFalse(assists.positionHoldAllowed(state(valid = false, observationMs = 900L), 1_000L, 250L))
        assertFalse(assists.positionHoldAllowed(state(valid = true, observationMs = 700L), 1_000L, 250L))
        assertFalse(assists.positionHoldAllowed(state(valid = true, observationMs = 1_001L), 1_000L, 250L))
    }
}
