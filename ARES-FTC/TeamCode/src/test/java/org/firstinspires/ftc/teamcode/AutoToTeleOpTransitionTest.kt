package org.firstinspires.ftc.teamcode

import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.state.Alliance
import com.areslib.util.PoseStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoToTeleOpTransitionTest {

    @Before
    fun setUp() {
        PoseStorage.hasValidPose = false
        PoseStorage.currentPose = Pose2d(0.0, 0.0, Rotation2d(0.0))
        PoseStorage.alliance = Alliance.RED
    }

    @Test
    fun testAutonomousToTeleOpPosePersistence() {
        // 1. Initially, PoseStorage has no valid pose
        assertFalse("PoseStorage should initially be invalid", PoseStorage.hasValidPose)

        // 2. Simulate Autonomous end pose handoff
        val endAutoPose = Pose2d(1.25, -0.85, Rotation2d(Math.toRadians(45.0)))
        PoseStorage.currentPose = endAutoPose
        PoseStorage.hasValidPose = true

        assertTrue("PoseStorage should now hold valid pose from Auto", PoseStorage.hasValidPose)
        assertEquals(1.25, PoseStorage.currentPose.x, 1e-4)
        assertEquals(-0.85, PoseStorage.currentPose.y, 1e-4)
        assertEquals(Math.toRadians(45.0), PoseStorage.currentPose.heading.radians, 1e-4)

        // 3. Simulate TeleOp start restoring pose from PoseStorage
        val restoredPose = if (PoseStorage.hasValidPose) {
            PoseStorage.currentPose
        } else {
            Pose2d(0.0, 0.0, Rotation2d(0.0))
        }

        assertEquals("TeleOp should restore exact pose handed off from Autonomous", endAutoPose.x, restoredPose.x, 1e-4)
        assertEquals("TeleOp should restore exact pose handed off from Autonomous", endAutoPose.y, restoredPose.y, 1e-4)
        assertEquals("TeleOp should restore exact pose handed off from Autonomous", endAutoPose.heading.radians, restoredPose.heading.radians, 1e-4)
    }

    @Test
    fun testAlliancePersistenceAcrossAutoTeleOpBoundary() {
        // Simulate what AresAutoBase.closeRobot writes at auto end:
        //   PoseStorage.alliance = robot.base.store.state.drive.alliance
        PoseStorage.alliance = Alliance.BLUE
        PoseStorage.hasValidPose = true

        // Simulate what ARESMecanumTeleOp.setup reads:
        //   if (PoseStorage.hasValidPose) dispatch(SetAlliance(PoseStorage.alliance))
        val restoredAlliance = if (PoseStorage.hasValidPose) PoseStorage.alliance else Alliance.RED
        assertEquals(
            "TeleOp should restore the alliance persisted by Autonomous",
            Alliance.BLUE, restoredAlliance
        )
    }

    @Test
    fun testTeleOpFallbackWhenNoAutoPosePersisted() {
        // Driver runs TeleOp directly without running Auto
        PoseStorage.hasValidPose = false

        val initialPose = if (PoseStorage.hasValidPose) {
            PoseStorage.currentPose
        } else {
            Pose2d(0.0, 0.0, Rotation2d(0.0))
        }

        val initialAlliance = if (PoseStorage.hasValidPose) PoseStorage.alliance else Alliance.RED

        assertEquals(0.0, initialPose.x, 1e-4)
        assertEquals(0.0, initialPose.y, 1e-4)
        assertEquals(0.0, initialPose.heading.radians, 1e-4)
        assertEquals(Alliance.RED, initialAlliance)
    }
}
