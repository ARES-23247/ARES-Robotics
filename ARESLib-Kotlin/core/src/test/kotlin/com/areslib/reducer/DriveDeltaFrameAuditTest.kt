package com.areslib.reducer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.test.assertEquals

class DriveDeltaFrameAuditTest {
    @Test
    fun `robot local delta rotates into the raw odometry field frame`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(2.0, 3.0, PI / 2.0, timestampMs = 0L, isReset = true))
        store.dispatch(RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 20L))
        assertEquals(2.0, store.state.drive.odometryX, 1e-10)
        assertEquals(4.0, store.state.drive.odometryY, 1e-10)
    }

    @Test
    fun `finite turn integrates a twist arc and wraps raw heading`() {
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(RobotAction.PoseUpdate(0.0, 0.0, PI, timestampMs = 0L, isReset = true))
        store.dispatch(RobotAction.DriveHardwareUpdate(1.0, 0.0, 0.0, 1.0, 0.0, PI / 2.0, 20L))
        assertEquals(-2.0 / PI, store.state.drive.odometryX, 1e-10)
        assertEquals(-2.0 / PI, store.state.drive.odometryY, 1e-10)
        assertEquals(-PI / 2.0, store.state.drive.odometryHeading, 1e-10)
    }
}
