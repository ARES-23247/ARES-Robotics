package org.firstinspires.ftc.teamcode

import com.areslib.Store
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.reducer.rootReducer
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import org.firstinspires.ftc.teamcode.opmodes.robot.AresSuperstructureController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/** Platform-owned alliance input behavior; mechanism behavior is generated from Builder documents. */
class AresSuperstructureControllerTest {
    @Before
    fun useDeterministicClock() {
        RobotClock.useMockTime(1_000L)
    }

    @After
    fun restoreClock() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `alliance intent dispatches through the root reducer`() {
        val (base, controller) = controller(Alliance.RED)

        controller.toggleAlliance()

        assertEquals(Alliance.BLUE, base.store.state.drive.alliance)
    }

    @Test
    fun `rapid duplicate alliance intent is debounced`() {
        val (base, controller) = controller(Alliance.RED)

        controller.toggleAlliance()
        controller.toggleAlliance()

        assertEquals(Alliance.BLUE, base.store.state.drive.alliance)
    }

    @Test
    fun `alliance intent is accepted after the debounce interval`() {
        val (base, controller) = controller(Alliance.RED)
        controller.toggleAlliance()
        RobotClock.setMockTimeMs(1_250L)

        controller.toggleAlliance()

        assertEquals(Alliance.RED, base.store.state.drive.alliance)
    }

    private fun controller(alliance: Alliance): Pair<FtcMecanumRobot, AresSuperstructureController> {
        val base = Mockito.mock(FtcMecanumRobot::class.java, Mockito.RETURNS_DEEP_STUBS)
        Mockito.`when`(base.store).thenReturn(Store(RobotState(drive = DriveState(alliance = alliance)), ::rootReducer))
        return base to AresSuperstructureController(base)
    }
}
