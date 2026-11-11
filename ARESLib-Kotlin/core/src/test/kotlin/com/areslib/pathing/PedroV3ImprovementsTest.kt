package com.areslib.pathing

import com.areslib.action.RobotAction
import com.areslib.control.drivetrain.HolonomicDriveController
import com.areslib.control.feedback.PIDController
import com.areslib.math.estimation.PoseEstimator
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.estimation.PoseEstimatorState
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Pose3d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Rotation3d
import com.areslib.math.geometry.Translation2d
import com.areslib.math.geometry.Translation3d
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineHeadingMode
import com.areslib.routine.RoutineManager
import com.areslib.routine.RoutineRequestResult
import com.areslib.routine.RoutineRuntimeBindings
import com.areslib.routine.RoutineStartPolicy
import com.areslib.routine.routine
import com.areslib.routine.toKotlinDsl
import com.areslib.sequencer.FollowPathTask
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskResources
import com.areslib.state.DriveState
import com.areslib.state.RobotState
import com.areslib.Store
import com.areslib.state.VisionMeasurement
import com.areslib.state.VisionSolverType
import com.areslib.subsystem.DrivetrainSubsystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.atan2
import kotlin.math.hypot

class PedroV3ImprovementsTest {

    @Test
    fun `PIDController gain scheduling switches between small and large error Kp`() {
        val pid = PIDController(p = 2.0, i = 0.0, d = 0.0)
        pid.configureGainSchedule(largeKp = 4.0, threshold = 0.15)

        val smallErrorEffort = pid.calculate(measurement = 0.0, setpoint = 0.10, dtSeconds = 0.02)
        assertEquals(0.20, smallErrorEffort, 1e-6)

        val largeErrorEffort = pid.calculate(measurement = 0.0, setpoint = 0.20, dtSeconds = 0.02)
        assertEquals(0.80, largeErrorEffort, 1e-6)
    }

    @Test
    fun `HolonomicDriveController prioritizes cross-track correction over along-track cruise`() {
        val controller = HolonomicDriveController(
            xController = PIDController(p = 5.0, i = 0.0, d = 0.0),
            yController = PIDController(p = 5.0, i = 0.0, d = 0.0),
            thetaController = PIDController(p = 2.0, i = 0.0, d = 0.0),
            maxOutputMps = 2.0,
            prioritizeCorrectiveEffort = true
        )

        val speeds = controller.calculateDirect(
            currentX = 0.0,
            currentY = -0.30,
            currentHeadingRad = 0.0,
            targetX = 0.0,
            targetY = 0.0,
            targetHeadingRad = 0.0,
            targetVelocityMps = 2.0,
            dtSeconds = 0.02,
            pathTangentRadians = 0.0,
            curvature = 0.0
        )

        assertEquals(1.50, speeds.vyMetersPerSecond, 1e-4)
        assertEquals(kotlin.math.sqrt(4.0 - 2.25), speeds.vxMetersPerSecond, 1e-4)
        assertEquals(2.0, hypot(speeds.vxMetersPerSecond, speeds.vyMetersPerSecond), 1e-4)
    }

    @Test
    fun `ForesightLookaheadEstimator projects stopping pose and triggers pathSkip for FAST preset`() {
        val fastConfig = ForesightConfig.forPreset(TrajectoryPreset.FAST, decelRateMps2 = 2.0)
        val safeConfig = ForesightConfig.forPreset(TrajectoryPreset.SAFE, decelRateMps2 = 2.0)
        assertTrue(fastConfig.enabled)
        assertFalse(safeConfig.enabled)

        val estimator = ForesightLookaheadEstimator(fastConfig)
        assertTrue(
            estimator.updateFieldRelative(
                currentX = 0.70,
                currentY = 0.0,
                currentHeadingRad = 0.0,
                fieldVxMps = 2.0,
                fieldVyMps = 0.0,
                omegaRadPerSec = 0.0
            )
        )
        assertEquals(1.0, estimator.stoppingDistanceMeters, 1e-6)
        assertEquals(1.70, estimator.predictedStopX, 1e-6)
        assertTrue(
            estimator.shouldTriggerPathSkip(
                currentX = 0.70,
                currentY = 0.0,
                endPose = Pose2d(1.65, 0.0, Rotation2d(0.0)),
                handoffRadiusMeters = 0.15
            )
        )
    }

    @Test
    fun `FollowPathTask completes early on Foresight pathSkip when predictivePathSkip is enabled`() {
        val robotPose = Pose2d(0.85, 0.0, Rotation2d(0.0))
        val drivetrain = object : DrivetrainSubsystem {
            override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) {}
            override fun getEstimatedPose(): Pose2d = robotPose
            override fun readSensors(store: Store, timestampMs: Long) {}
            override fun writeOutputs(state: RobotState, scale: Double) {}
        }
        val follower = HolonomicPathFollower.forPreset(drivetrain, TrajectoryPreset.FAST, decelRateMps2 = 2.5)
        val path = Path(
            listOf(
                PathPoint(Pose2d(0.0, 0.0, Rotation2d(0.0)), velocityMps = 1.5, distanceMeters = 0.0),
                PathPoint(Pose2d(1.0, 0.0, Rotation2d(0.0)), velocityMps = 1.5, distanceMeters = 1.0)
            )
        )
        val task = FollowPathTask(
            follower = follower,
            path = path,
            mirrorForAlliance = false,
            holdVelocity = true,
            predictivePathSkip = true,
            handoffRadiusMeters = 0.20
        )

        var state = RobotState(
            drive = DriveState(
                measuredFieldXVelocityMetersPerSecond = 1.5,
                measuredFieldYVelocityMetersPerSecond = 0.0,
                measuredAngularVelocityRadiansPerSecond = 0.0,
                measuredMotionValid = true,
                poseEstimator = PoseEstimatorSnapshot(
                    estimatedPoseX = 0.85,
                    estimatedPoseY = 0.0,
                    estimatedPoseHeading = 0.0
                )
            )
        )
        task.initialize(state)
        state = state.copy(
            pathState = state.pathState.copy(activePath = path, currentDistanceMeters = 0.85)
        )
        assertTrue(task.isCompleted(state, elapsedMs = 100L))
    }

    @Test
    fun `JerkLimitedTrajectoryProvider applies TANGENTIAL and POINT_TOWARDS heading modes`() {
        val limits = TrajectoryLimits(
            maxVelocityMps = 2.0,
            maxAccelerationMps2 = 2.0,
            maxJerkMps3 = 10.0,
            maxCentripetalAccelerationMps2 = 2.0,
            maxAngularVelocityRps = 4.0,
            maxAngularAccelerationRps2 = 8.0
        )
        val lookAt = Translation2d(2.0, 2.0)
        val pointTowardsResult = JerkLimitedTrajectoryProvider.generate(
            TrajectoryRequest(
                waypoints = listOf(
                    Pose2d(0.0, 0.0, Rotation2d(0.0)),
                    Pose2d(2.0, 0.0, Rotation2d(0.0))
                ),
                driveModel = DriveModel.MECANUM,
                preset = TrajectoryPreset.BALANCED,
                limits = limits,
                headingMode = RoutineHeadingMode.POINT_TOWARDS,
                lookAtPoint = lookAt
            )
        )
        assertTrue(pointTowardsResult.isSuccess)
        val midState = pointTowardsResult.trajectory!!.states[pointTowardsResult.trajectory!!.states.size / 2]
        val expectedHeading = atan2(lookAt.y - midState.pose.y, lookAt.x - midState.pose.x)
        assertEquals(expectedHeading, midState.pose.heading.radians, 1e-4)
    }

    @Test
    fun `RoutineManager PREEMPT_AND_SUSPEND pauses active routine and resumes it on completion`() {
        val events = mutableListOf<String>()
        class TrackedDriveTask(private val label: String, private val totalTicks: Int) : Task {
            override val name: String = label
            override val requiredResources: Long = TaskResources.DRIVE
            var ticks = 0
            override fun initialize(state: RobotState): List<RobotAction> {
                super.initialize(state)
                events += "$label:init"
                return emptyList()
            }
            override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
                super.execute(state, elapsedMs)
                ticks++
                events += "$label:exec:$ticks"
                return emptyList()
            }
            override fun pause(state: RobotState): List<RobotAction> {
                events += "$label:pause"
                return emptyList()
            }
            override fun resume(state: RobotState): List<RobotAction> {
                events += "$label:resume"
                return emptyList()
            }
            override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = ticks >= totalTicks
        }

        val docA = routine("routine-a") { action("act.a") }
        val docB = routine("routine-b") { action("act.b") }
        val state = RobotState()
        val manager = RoutineManager(
            bindings = RoutineRuntimeBindings(
                createActionTask = { key, _ ->
                    when (key) {
                        "act.a" -> TrackedDriveTask("A", totalTicks = 2)
                        "act.b" -> TrackedDriveTask("B", totalTicks = 1)
                        else -> null
                    }
                },
                createCondition = { _, _ -> null },
                resourcesForAction = { setOf("drivetrain") }
            ),
            stateProvider = { state },
            dispatch = {}
        )
        manager.replaceDocuments(listOf(docA, docB))

        val startA = manager.request("routine-a", RoutineStartPolicy.RESTART_EXISTING)
        assertTrue(startA is RoutineRequestResult.Accepted)
        manager.update() // A tick 1

        val startB = manager.request("routine-b", RoutineStartPolicy.PREEMPT_AND_SUSPEND)
        assertTrue(startB is RoutineRequestResult.Accepted)
        assertEquals(1, manager.suspendedCount)
        assertEquals(1, manager.activeCount)

        manager.update() // B tick 1: initializes B and runs execute (ticks = 1)
        manager.update() // B tick 2: observes B isCompleted = true, finishes B, and resumes A!
        assertEquals(0, manager.suspendedCount)
        assertEquals(1, manager.activeCount)

        manager.update() // A tick 2: runs execute (ticks = 2)
        manager.update() // A tick 3: observes A isCompleted = true, finishes A
        assertEquals(0, manager.activeCount)
        assertTrue(events.contains("A:pause"))
        assertTrue(events.contains("A:resume"))
    }

    @Test
    fun `PoseEstimator 2D planar vision observation updates XY without corrupting IMU heading`() {
        var state = PoseEstimatorState(
            estimatedPoseX = 0.0,
            estimatedPoseY = 0.0,
            estimatedPoseHeading = 0.25
        )
        state = PoseEstimator.addOdometryObservationDirect(
            state = state,
            timestampMs = 1000L,
            deltaX = 0.25,
            deltaY = 0.10,
            deltaHeadingRad = 0.0
        )

        val measurement = VisionMeasurement(
            timestampMs = 1000L,
            targetPose = Pose3d(
                Translation3d(0.20, -0.05, 0.0),
                Rotation3d(0.0, 0.0, 1.75)
            ),
            tagId = 1,
            ambiguity = 0.02,
            tagCount = 1,
            solverType = VisionSolverType.MEGATAG2
        )

        state = PoseEstimator.addVisionMeasurementDirect(
            state = state,
            measurement = measurement,
            visionStdDevX = 0.05,
            visionStdDevY = 0.05,
            visionStdDevHeading = 0.05,
            numTags = 1,
            scaleStdDevX = false,
            scaleStdDevY = false,
            scaleStdDevHeading = false,
            observeHeading = false
        )

        assertTrue(state.lastMeasurementAccepted)
        assertTrue(state.estimatedPoseX < 0.24)
        assertTrue(state.estimatedPoseY < 0.05)
        assertEquals(0.25, state.estimatedPoseHeading, 1e-6)
        assertEquals(0.0, state.lastInnovationTheta, 1e-6)
    }

    @Test
    fun `RoutineDsl and AresRoutineCodec round-trip headingMode and blendHandoff`() {
        val doc = routine("spline-demo") {
            driveTo(xMeters = 1.2, yMeters = 0.8, headingDegrees = 45.0) {
                pointTowards(2.5, 1.0)
                blendIntoNext()
            }
        }
        val json = AresRoutineCodec.encode(doc)
        val decoded = AresRoutineCodec.decode(json)
        assertEquals(doc, decoded)
        val dsl = decoded.toKotlinDsl()
        assertTrue(dsl.contains("pointTowards(2.5, 1.0)"))
        assertTrue(dsl.contains("blendIntoNext(true)"))
    }

    @Test
    fun `ForesightLookaheadEstimator clamps translational arc bending to translational stop time`() {
        val estimator = ForesightLookaheadEstimator(
            ForesightConfig(enabled = true, decelRateMps2 = 2.5, angularDecelRateRadPerSec2 = 2.0, scalar = 1.0)
        )
        // Translational stop time t_v = 0.05 / 2.5 = 0.02s, whereas rotational stop time t_w = 4.0 / 2.0 = 2.0s.
        assertTrue(
            estimator.updateFieldRelative(
                currentX = 0.0,
                currentY = 0.0,
                currentHeadingRad = 0.0,
                fieldVxMps = 0.05,
                fieldVyMps = 0.0,
                omegaRadPerSec = 4.0,
            )
        )
        // Translational stop vector should barely deflect in Y (< 2e-5 m), rather than curving by 4 rad!
        assertTrue(estimator.predictedStopX > 0.00049)
        assertTrue(kotlin.math.abs(estimator.predictedStopY) < 0.0001)
    }
}
