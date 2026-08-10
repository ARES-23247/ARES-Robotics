package com.areslib.subsystem

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.reducer.rootReducer
import com.areslib.state.DriveMode
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HolonomicDriveFacadeTest {

    @Test
    fun testHolonomicDriveFacadeGetters() {
        val store = Store(RobotState(), ::rootReducer)
        val facade = MecanumDriveFacade(store)

        assertEquals(0.0, facade.xVelocity)
        assertEquals(0.0, facade.yVelocity)
        assertEquals(0.0, facade.angularVelocity)
        assertEquals(0.0, facade.odometryX)
        assertEquals(0.0, facade.odometryY)
        assertEquals(0.0, facade.odometryHeading)
        assertEquals(Pose2d(0.0, 0.0, Rotation2d()), facade.pose)
    }

    @Test
    fun testRobotRelativeDrive() {
        val actions = mutableListOf<RobotAction>()
        val store = Store(RobotState()) { state, action ->
            actions.add(action)
            rootReducer(state, action)
        }
        val facade = MecanumDriveFacade(store)

        facade.robotRelativeDrive(0.5, -0.3, 0.2)

        assertEquals(1, actions.size)
        val action = actions[0]
        assertTrue(action is RobotAction.JoystickDriveIntent)
        val driveIntent = action as RobotAction.JoystickDriveIntent
        assertEquals(0.5, driveIntent.targetXVelocity)
        assertEquals(-0.3, driveIntent.targetYVelocity)
        assertEquals(0.2, driveIntent.targetAngularVelocity)
        assertFalse(driveIntent.isFieldCentric)
    }

    @Test
    fun testFieldRelativeDrive() {
        val actions = mutableListOf<RobotAction>()
        val store = Store(RobotState()) { state, action ->
            actions.add(action)
            rootReducer(state, action)
        }
        val facade = MecanumDriveFacade(store)

        // Robot facing forward (0 heading), so fieldRelative maps 1-1 to robotRelative
        facade.fieldRelativeDrive(0.5, 0.0, 0.0)

        // Should find JoystickDriveIntent dispatched
        val intent = actions.filterIsInstance<RobotAction.JoystickDriveIntent>().lastOrNull()
        assertNotNull(intent)
        assertEquals(0.5, intent!!.targetXVelocity, 1e-6)
        assertEquals(0.0, intent.targetYVelocity, 1e-6)
    }

    @Test
    fun testHeadingLockLogic() {
        val store = Store(RobotState(), ::rootReducer)
        val facade = MecanumDriveFacade(store)

        // 1. Enable heading lock with zero turn speed -> sets heading target
        facade.fieldRelativeDrive(0.0, 0.0, 0.0, useHeadingLock = true)
        assertEquals(DriveMode.HEADING_HOLD, store.state.drive.driveMode)
        assertNotNull(store.state.drive.headingLockTargetRadians)

        // 2. Drive with useHeadingLock=true but non-zero omega -> unlocks heading
        facade.fieldRelativeDrive(0.0, 0.0, 0.5, useHeadingLock = true)
        assertEquals(DriveMode.TELEOP, store.state.drive.driveMode)
        assertNull(store.state.drive.headingLockTargetRadians)
    }

    @Test
    fun testFollowPath() {
        val store = Store(RobotState(), ::rootReducer)
        val facade = MecanumDriveFacade(store)

        val path = Path(
            points = listOf(
                PathPoint(pose = Pose2d(1.0, 2.0, Rotation2d.fromDegrees(90.0)), velocityMps = 0.0, distanceMeters = 0.0)
            )
        )

        facade.followPath(path)

        // Should update EKF pose to starting point of the path
        assertEquals(1.0, store.state.drive.poseEstimator.estimatedPose.x, 1e-6)
        assertEquals(2.0, store.state.drive.poseEstimator.estimatedPose.y, 1e-6)
        assertEquals(Math.PI / 2.0, store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)
    }
}
