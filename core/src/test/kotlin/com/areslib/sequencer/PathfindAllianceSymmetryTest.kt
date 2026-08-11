package com.areslib.sequencer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.Costmap
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PathfindAllianceSymmetryTest {
    private val follower = HolonomicPathFollower(NoOpDrivetrain())
    private val costmap = Costmap(6.0, 6.0, 0.1, Translation2d(-3.0, -3.0))

    @Test
    fun `red-authored mirrored field reflects target for blue`() {
        val end = initializeTarget(FieldSymmetry.MIRRORED)

        assertEquals(1.0, end.x, 1e-6)
        assertEquals(-2.0, end.y, 1e-6)
        assertEquals(-0.25, end.heading.radians, 1e-6)
    }

    @Test
    fun `red-authored rotational field rotates target for blue`() {
        val end = initializeTarget(FieldSymmetry.ROTATIONAL)

        assertEquals(-1.0, end.x, 1e-6)
        assertEquals(-2.0, end.y, 1e-6)
        assertEquals(com.areslib.math.wrapAngle(0.25 + Math.PI), end.heading.radians, 1e-6)
    }

    private fun initializeTarget(symmetry: FieldSymmetry): Pose2d {
        val task = PathfindToPoseTask(
            targetPose = Pose2d(1.0, 2.0, Rotation2d(0.25)),
            follower = follower,
            costmap = costmap,
            mirrorForAlliance = true,
            symmetry = symmetry,
            authoredAlliance = Alliance.RED
        )
        val state = RobotState(drive = DriveState(alliance = Alliance.BLUE))
        val switchPath = task.initialize(state).single() as RobotAction.SwitchPath
        return switchPath.path.points.last().pose
    }

    private class NoOpDrivetrain : DrivetrainSubsystem {
        override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) = Unit
        override fun getEstimatedPose(): Pose2d = Pose2d()
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
    }
}
