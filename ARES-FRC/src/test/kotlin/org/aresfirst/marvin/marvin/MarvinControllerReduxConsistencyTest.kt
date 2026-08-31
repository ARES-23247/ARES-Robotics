package org.aresfirst.marvin.marvin

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarvinControllerReduxConsistencyTest {
    @BeforeEach
    fun useMockClock() = RobotClock.useMockTime(1_000L)

    @AfterEach
    fun restoreClock() = RobotClock.useSystemTime()

    @Test
    fun `teleop feeder transfer is bounded and cannot repeat while trigger remains held`() {
        val store = Store(
            RobotState(superstructure = SuperstructureState(custom = MarvinState()))
        ) { state, action -> MarvinReducer.reduce(state, action) }
        val feeder = MarvinFeederController(store)

        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = true)
        assertTrue(store.state.superstructure.marvin.transferActive)
        assertEquals(1_000L, store.state.superstructure.marvin.transferStartedAtMs)
        assertFalse(store.state.superstructure.marvin.transferConsumedForTrigger)
        assertEquals(MarvinConfig.FEEDER_SHOOT_SPEED_RPS, store.state.superstructure.marvin.feeder.targetVelocityRps)

        RobotClock.useMockTime(1_450L)
        // A reconstructed controller must continue the timestamp retained in Redux rather than
        // granting a new 450 ms window.
        MarvinFeederController(store).updateFeeders(
            rpmAligned = true,
            headingAligned = true,
            cowlReady = true
        )
        assertFalse(store.state.superstructure.marvin.transferActive)
        assertEquals(-1L, store.state.superstructure.marvin.transferStartedAtMs)
        assertTrue(store.state.superstructure.marvin.transferConsumedForTrigger)
        assertEquals(0.0, store.state.superstructure.marvin.feeder.targetVelocityRps)

        RobotClock.useMockTime(2_000L)
        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = true)
        assertFalse(store.state.superstructure.marvin.transferActive)

        feeder.cancelTransfer()
        assertFalse(store.state.superstructure.marvin.transferConsumedForTrigger)
        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = true)
        assertTrue(store.state.superstructure.marvin.transferActive)

        RobotClock.useMockTime(1_999L)
        feeder.updateFeeders(rpmAligned = true, headingAligned = true, cowlReady = true)
        assertFalse(store.state.superstructure.marvin.transferActive, "Clock rollback must fail closed")
    }
}
