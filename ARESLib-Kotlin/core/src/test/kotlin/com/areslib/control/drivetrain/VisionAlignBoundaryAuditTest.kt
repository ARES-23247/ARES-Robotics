package com.areslib.control.drivetrain

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.state.RobotState
import com.areslib.state.VisionAlignTuningState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionState
import com.areslib.state.snapshot
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisionAlignBoundaryAuditTest {
    @AfterEach fun restoreClock() = RobotClock.useSystemTime()

    private val tuning = VisionAlignTuningState(targetDistanceMeters = 1.0,
        clampTranslationX = 10.0, clampTranslationY = 10.0, clampRotation = 100.0)

    private fun state(time: Long, tag: Int = 7, x: Double = 1.0, z: Double = 2.0,
                      yaw: Double = 0.0, gains: VisionAlignTuningState = tuning): RobotState {
        val measurement = VisionMeasurement(timestampMs = time, tagId = tag,
            robotPoseTargetSpace = Pose3d(Translation3d(x, 0.0, z), Rotation3d(0.0, -yaw, 0.0)))
        return RobotState(vision = VisionState(measurements = listOf(measurement.snapshot()))).let {
            it.copy(tuning = it.tuning.copy(visionAlign = gains))
        }
    }

    private fun command(controller: VisionAlignController, state: RobotState, tag: Int = 7) =
        assertNotNull(controller.calculate(state, tag, true))

    private fun sameMotion(expected: RobotAction.JoystickDriveIntent, actual: RobotAction.JoystickDriveIntent) {
        assertEquals(expected.targetXVelocity, actual.targetXVelocity, 1e-12)
        assertEquals(expected.targetYVelocity, actual.targetYVelocity, 1e-12)
        assertEquals(expected.targetAngularVelocity, actual.targetAngularVelocity, 1e-12)
    }

    @Test fun `switching target starts with that target's own history`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, state(1000))
        RobotClock.useMockTime(1020)
        val next = state(1020, tag = 8, x = -1.0, yaw = 0.5)
        sameMotion(command(VisionAlignController(), next, 8), command(controller, next, 8))
    }

    @Test fun `reacquisition does not retain integral from before target loss`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, state(1000))
        RobotClock.useMockTime(1200)
        command(controller, state(1200))
        RobotClock.useMockTime(1220)
        command(controller, RobotState())
        RobotClock.useMockTime(1240)
        val next = state(1240)
        sameMotion(command(VisionAlignController(), next), command(controller, next))
    }

    @Test fun `search beginning at zero completes both sweeps at their boundaries`() {
        RobotClock.useMockTime(0)
        val controller = VisionAlignController()
        val empty = RobotState()
        assertEquals(-0.85, command(controller, empty).targetAngularVelocity)
        RobotClock.useMockTime(1200)
        assertEquals(0.85, command(controller, empty).targetAngularVelocity)
        RobotClock.useMockTime(3600)
        assertEquals(0.0, command(controller, empty).targetAngularVelocity)
    }

    @Test fun `elapsed search overflow cannot restart motion`() {
        RobotClock.useMockTime(Long.MIN_VALUE + 10)
        val controller = VisionAlignController()
        command(controller, RobotState())
        RobotClock.useMockTime(Long.MAX_VALUE - 10)
        assertEquals(0.0, command(controller, RobotState()).targetAngularVelocity)
    }

    @Test fun `future timestamp that wraps subtraction is rejected`() {
        RobotClock.useMockTime(Long.MIN_VALUE)
        val result = command(VisionAlignController(), state(Long.MAX_VALUE))
        assertEquals(0.0, result.targetXVelocity)
        assertEquals(0.0, result.targetYVelocity)
    }

    @Test fun `zero elapsed time does not accumulate integral or differentiate`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        val input = state(1000)
        val initial = command(controller, input)
        repeat(50) { sameMotion(initial, command(controller, input)) }
    }

    @Test fun `clock rewind discards old filter and derivative history`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, state(1000))
        RobotClock.useMockTime(500)
        val input = state(500, x = -1.0, yaw = 0.5)
        sameMotion(command(VisionAlignController(), input), command(controller, input))
    }

    @Test fun `lateral cap constrains the vector without changing direction`() {
        RobotClock.useMockTime(1000)
        val result = command(VisionAlignController(), state(1000,
            gains = tuning.copy(clampTranslationX = 0.5, clampTranslationY = 0.1)))
        assertEquals(0.1, result.targetYVelocity, 1e-12)
        assertEquals(result.targetXVelocity, result.targetYVelocity, 1e-12)
        assertTrue(hypot(result.targetXVelocity, result.targetYVelocity) <= 0.5)
    }

    @Test fun `finite gain overflow neutralizes and permits valid recovery`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        val result = command(controller, state(1000, x = 3.0,
            gains = tuning.copy(kpTranslation = Double.MAX_VALUE)))
        assertEquals(0.0, result.targetXVelocity)
        assertEquals(0.0, result.targetYVelocity)
        assertEquals(0.0, result.targetAngularVelocity)
        RobotClock.useMockTime(1020)
        val next = state(1020)
        sameMotion(command(VisionAlignController(), next), command(controller, next))
    }

    @Test fun `heading term overflow cannot combine infinities into NaN`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        val gains = tuning.copy(kpRotation = Double.MAX_VALUE, kdRotation = Double.MAX_VALUE,
            maxHeadingChangeRad = 4.0, alphaHeading = 1.0)
        command(controller, state(1000, x = 0.0, yaw = -3.0, gains = gains))
        RobotClock.useMockTime(1020)
        val result = command(controller, state(1020, x = 0.0, yaw = -2.0, gains = gains))
        assertTrue(result.targetAngularVelocity.isFinite())
        assertEquals(0.0, result.targetAngularVelocity)
    }

    @Test fun `release clears search exhaustion and motion history`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, RobotState())
        RobotClock.useMockTime(5000)
        assertEquals(0.0, command(controller, RobotState()).targetAngularVelocity)
        assertNull(controller.calculate(RobotState(), 7, false))
        assertEquals(-0.85, command(controller, RobotState()).targetAngularVelocity)
    }

    @Test fun `freshness edge is exclusive at 250 milliseconds`() {
        RobotClock.useMockTime(1000)
        assertTrue(command(VisionAlignController(), state(751)).targetXVelocity > 0.0)
        assertEquals(0.0, command(VisionAlignController(), state(750)).targetXVelocity)
    }

    @Test fun `quarter turn rotates forward error onto robot right`() {
        RobotClock.useMockTime(1000)
        val result = command(VisionAlignController(), state(1000, x = 0.0, yaw = Math.PI / 2.0))
        assertEquals(0.0, result.targetXVelocity, 1e-12)
        assertEquals(-1.0, result.targetYVelocity, 1e-12)
        assertTrue(result.targetAngularVelocity < 0.0)
        assertEquals(1000L, result.timestampMs)
        assertFalseFieldCentric(result)
    }

    private fun assertFalseFieldCentric(command: RobotAction.JoystickDriveIntent) {
        assertEquals(false, command.isFieldCentric)
    }

    @Test fun `translation filter uses its convex recurrence and yaw jumps are rejected`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, state(1000, x = 0.0))
        RobotClock.useMockTime(1020)
        val result = command(controller, state(1020, x = 2.0, z = 3.0, yaw = 1.0))
        // Rejected yaw jump leaves phi=0; EMA from (1,0) to (2,2) at alpha=.4.
        assertEquals(1.4, result.targetXVelocity, 1e-12)
        assertEquals(0.8, result.targetYVelocity, 1e-12)
    }

    @Test fun `heading derivative uses shortest arc at the pi boundary`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        val gains = tuning.copy(alphaHeading = 1.0, maxHeadingChangeRad = 4.0,
            kpRotation = 0.0, kdRotation = 1.0, ksRotational = 0.0)
        // rotationY is an Euler pitch in [-pi/2, pi/2]. Combine it with target bearing
        // to obtain heading errors on opposite sides of pi without folding the input yaw.
        val depth = 3.0 * kotlin.math.tan(0.01)
        command(controller, state(1000, x = 3.0, z = depth, yaw = -Math.PI / 2.0, gains = gains))
        RobotClock.useMockTime(1020)
        val result = command(controller, state(1020, x = -3.0, z = depth, yaw = Math.PI / 2.0, gains = gains))
        assertEquals(1.0, result.targetAngularVelocity, 1e-10)
    }

    @Test fun `search rewind starts a new bounded clock interval and new target resets direction`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        command(controller, state(1000))
        RobotClock.useMockTime(1020)
        assertEquals(0.85, command(controller, RobotState()).targetAngularVelocity)
        assertEquals(-0.85, command(controller, RobotState(), 8).targetAngularVelocity)
        RobotClock.useMockTime(500)
        assertEquals(-0.85, command(controller, RobotState(), 8).targetAngularVelocity)
        RobotClock.useMockTime(4100)
        assertEquals(0.0, command(controller, RobotState(), 8).targetAngularVelocity)
    }

    @Test fun `search duration sum saturates and zero duration search is neutral`() {
        RobotClock.useMockTime(1)
        val controller = VisionAlignController()
        val empty = RobotState().let { it.copy(tuning = it.tuning.copy(visionAlign = tuning.copy(
            searchFirstSweepMs = Long.MAX_VALUE, searchSecondSweepMs = Long.MAX_VALUE))) }
        assertEquals(-0.85, command(controller, empty).targetAngularVelocity)
        RobotClock.useMockTime(Long.MAX_VALUE)
        assertEquals(-0.85, command(controller, empty).targetAngularVelocity)
        val noSearch = empty.copy(tuning = empty.tuning.copy(visionAlign = tuning.copy(
            searchFirstSweepMs = -1, searchSecondSweepMs = 0)))
        assertEquals(0.0, command(controller, noSearch).targetAngularVelocity)
    }

    @Test fun `returned actions retain their values after later calculations`() {
        RobotClock.useMockTime(1000)
        val controller = VisionAlignController()
        val first = command(controller, state(1000))
        val snapshot = first.copy()
        RobotClock.useMockTime(1020)
        command(controller, state(1020, x = -1.0))
        assertEquals(snapshot, first)
    }
}
