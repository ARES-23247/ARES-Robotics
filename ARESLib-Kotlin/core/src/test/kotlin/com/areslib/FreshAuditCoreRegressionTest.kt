package com.areslib

import com.areslib.action.RobotAction
import com.areslib.control.drivetrain.VisionAlignController
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation3d
import com.areslib.pathing.Costmap
import com.areslib.pathing.DriveModel
import com.areslib.pathing.ForesightConfig
import com.areslib.pathing.ForesightLookaheadEstimator
import com.areslib.pathing.JerkLimitedTrajectoryProvider
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPreset
import com.areslib.pathing.TrajectoryRequest
import com.areslib.reducer.DriveReducer
import com.areslib.reducer.rootReducer
import com.areslib.routine.RoutineHeadingMode
import com.areslib.state.DriveState
import com.areslib.state.FieldType
import com.areslib.state.Pose3dSnapshot
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotState
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionMeasurementSnapshot
import com.areslib.state.VisionSolverType
import com.areslib.state.VisionState
import com.areslib.util.RobotClock
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FreshAuditCoreRegressionTest {
    @AfterEach
    fun tearDown() {
        RobotClock.useSystemTime()
    }

    @Test
    fun `MegaTag2 vision update leaves estimator heading untouched`() {
        RobotClock.useMockTime(1_000L)
        val store = Store(RobotState(), ::rootReducer)
        store.dispatch(
            RobotAction.PoseUpdate(
                xMeters = 0.0,
                yMeters = 0.0,
                headingRadians = 0.75,
                timestampMs = 1_000L,
                isReset = true,
            )
        )
        val mt2 = VisionMeasurement(
            timestampMs = 1_000L,
            targetPose = Pose3d(Translation3d(0.2, 0.1, 0.0), Rotation3d(0.0, 0.0, -1.2)),
            tagId = 1,
            tagCount = 2,
            ambiguity = 0.05,
            solverType = VisionSolverType.MEGATAG2,
            stdDevXMeters = 0.2,
            stdDevYMeters = 0.2,
            stdDevHeadingRadians = 0.2,
            sourceId = "ll-front",
            frameId = 1L,
        )
        store.dispatch(RobotAction.VisionMeasurementsReceived(listOf(mt2), timestampMs = 1_000L))
        assertEquals(0.75, store.state.drive.poseEstimator.estimatedPose.heading.radians, 1e-6)
    }

    @Test
    fun `VisionAlignController selects newest fresh target measurement`() {
        RobotClock.useMockTime(1_030L)
        val align = VisionAlignController()
        val older = VisionMeasurementSnapshot(
            tagId = 1,
            robotPoseTargetSpace = Pose3dSnapshot(x = 0.0, y = 0.0, z = 2.0),
            timestampMs = 1_000L,
        )
        val newer = VisionMeasurementSnapshot(
            tagId = 1,
            robotPoseTargetSpace = Pose3dSnapshot(x = 0.5, y = 0.0, z = 2.0),
            timestampMs = 1_020L,
        )
        val state = RobotState(vision = VisionState(hasTarget = true, measurements = listOf(older, newer)))
        val output = align.calculate(state, targetTagId = 1, isAlignmentRequested = true)
        assertNotNull(output)
        assertTrue(output.targetYVelocity != 0.0, "Controller must use newer target with lateral offset x=0.5")
    }

    @Test
    fun `TrajectoryPlanning CONSTANT heading mode holds starting waypoint heading`() {
        val start = Pose2d(0.0, 0.0, Rotation2d(1.1))
        val end = Pose2d(2.0, 1.0, Rotation2d(-0.5))
        val result = JerkLimitedTrajectoryProvider.generate(
            TrajectoryRequest(
                waypoints = listOf(start, end),
                driveModel = DriveModel.SWERVE,
                preset = TrajectoryPreset.BALANCED,
                limits = TrajectoryLimits(3.0, 2.5, 10.0, 2.5, 4.0, 6.0),
                headingMode = RoutineHeadingMode.CONSTANT,
            )
        )
        val trajectory = assertNotNull(result.trajectory)
        val totalTime = trajectory.states.last().timeSeconds
        val mid = trajectory.sample(totalTime * 0.5)
        val fin = trajectory.sample(totalTime)
        assertEquals(1.1, mid.pose.heading.radians, 1e-6)
        assertEquals(1.1, fin.pose.heading.radians, 1e-6)
    }

    @Test
    fun `Costmap fromFieldConfig uses non-square field dimensions and guards non-finite queries`() {
        val config = RobotFieldConfig(
            id = "frc-test",
            name = "FRC Test",
            fieldType = FieldType.FRC,
            widthMeters = 16.54,
            heightMeters = 8.21,
        )
        val costmap = Costmap.fromFieldConfig(config, robotRadiusMeters = 0.0)
        assertEquals(16.54, costmap.widthMeters, 1e-6)
        assertEquals(8.21, costmap.heightMeters, 1e-6)
        assertTrue(costmap.isTraversable(12.0, 4.0))
        assertFalse(costmap.isTraversable(Double.NaN, 4.0))
        assertFalse(costmap.isTraversable(Double.POSITIVE_INFINITY, 1.0))
    }

    @Test
    fun `DriveHardwareUpdate populates measured velocities without clobbering commanded velocities`() {
        val state = DriveState(
            xVelocityMetersPerSecond = 2.5,
            yVelocityMetersPerSecond = -1.0,
            angularVelocityRadiansPerSecond = 0.4,
        )
        val next = DriveReducer.reduceDriveHardwareUpdate(
            state = state,
            action = RobotAction.DriveHardwareUpdate(
                xVelocity = 1.2,
                yVelocity = 0.3,
                angularVelocity = -0.2,
                deltaX = 0.024,
                deltaY = 0.006,
                deltaHeading = -0.004,
                timestampMs = 1_020L,
            ),
            updatedEstimator = null,
        )
        assertEquals(2.5, next.xVelocityMetersPerSecond, 1e-9)
        assertEquals(-1.0, next.yVelocityMetersPerSecond, 1e-9)
        assertEquals(0.4, next.angularVelocityRadiansPerSecond, 1e-9)
        assertTrue(next.measuredMotionValid)
        assertTrue(next.imuMeasurementsValid)
        assertEquals(-0.2, next.measuredAngularVelocityRadiansPerSecond, 1e-9)
    }

    @Test
    fun `ForesightLookaheadEstimator clamps chord scale on large braking turns`() {
        val estimator = ForesightLookaheadEstimator(
            ForesightConfig(enabled = true, decelRateMps2 = 0.5, angularDecelRateRadPerSec2 = 0.5, scalar = 1.0)
        )
        assertTrue(estimator.updateFieldRelative(0.0, 0.0, 0.0, 2.0, 0.0, 10.0))
        assertTrue(estimator.predictedStopX.isFinite() && estimator.predictedStopY.isFinite())
        assertTrue(estimator.stoppingDistanceMeters <= 4.0 + 1e-6)
    }
}
