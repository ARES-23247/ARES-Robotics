package org.firstinspires.ftc.teamcode

import com.areslib.action.RobotAction
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.math.geometry.Translation2d
import com.areslib.pathing.PathPlannerParser
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.sequencer.Task
import com.areslib.sequencer.TaskExecutor
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.state.Alliance
import com.areslib.state.DriveState
import com.areslib.state.ObstacleType
import com.areslib.state.RobotFieldObstacle
import com.areslib.state.RobotFieldPoint
import com.areslib.state.RobotState
import com.areslib.math.estimation.PoseEstimatorSnapshot
import org.firstinspires.ftc.teamcode.dsl.FtcDelegateStatusBridge
import org.firstinspires.ftc.teamcode.dsl.FtcDriveMotionKind
import org.firstinspires.ftc.teamcode.dsl.FtcFieldEnvelope
import org.firstinspires.ftc.teamcode.dsl.FtcRotateToHeadingTask
import org.firstinspires.ftc.teamcode.dsl.classifyFtcDriveMotion
import org.firstinspires.ftc.teamcode.dsl.generatedDriveFieldComponents
import org.firstinspires.ftc.teamcode.dsl.composeFtcDriveLifecycle
import org.firstinspires.ftc.teamcode.dsl.isFtcRobotPathSweepCollisionFree
import org.firstinspires.ftc.teamcode.dsl.isFtcRobotPoseWithinField
import org.firstinspires.ftc.teamcode.dsl.isFtcRobotSweepCollisionFree
import org.firstinspires.ftc.teamcode.dsl.pointInPolygon
import org.firstinspires.ftc.teamcode.dsl.segmentDistance
import org.firstinspires.ftc.teamcode.dsl.segmentsIntersect
import org.firstinspires.ftc.teamcode.dsl.validateFtcAutonomousBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtcGeneratedRuntimeTest {
    @Test
    fun `drive is deadline for during actions and arrival starts afterward`() {
        val drive = RecordingTask("drive", completeAtMs = 10L)
        val during = RecordingTask("during", completeAtMs = Long.MAX_VALUE)
        val arrival = RecordingTask("arrival", completeAtMs = 0L)
        val executor = TaskExecutor().apply {
            addTask(composeFtcDriveLifecycle(drive, listOf(during), listOf(arrival)))
        }
        val state = RobotState()

        executor.update(state, 0L)
        assertEquals(1, drive.initializations)
        assertEquals(1, during.initializations)
        assertEquals(0, arrival.initializations)

        executor.update(state, 10L)
        assertEquals(false, drive.lastInterrupted)
        assertEquals(true, during.lastInterrupted)
        assertEquals(1, arrival.initializations)
        assertEquals(0, executor.size)
    }

    @Test
    fun `rotated robot footprint must remain entirely inside field`() {
        val envelope = FtcFieldEnvelope(
            fieldWidthMeters = 3.0,
            fieldHeightMeters = 3.0,
            robotLengthMeters = 1.0,
            robotWidthMeters = 0.4,
        )

        assertTrue(isFtcRobotPoseWithinField(Pose2d(1.0, 0.0, Rotation2d(0.0)), envelope))
        assertFalse(isFtcRobotPoseWithinField(Pose2d(1.01, 0.0, Rotation2d(0.0)), envelope))
        assertTrue(isFtcRobotPoseWithinField(Pose2d(1.25, 0.0, Rotation2d(Math.PI / 2.0)), envelope))
        assertFalse(isFtcRobotPoseWithinField(Pose2d(1.31, 0.0, Rotation2d(Math.PI / 2.0)), envelope))
    }

    @Test
    fun `bounds preflight traverses called routines and rejects unsafe goals`() {
        val entry = AutonomousCatalogEntry(
            entryId = "match",
            displayName = "Match",
            routineId = "root",
            startingPose = RoutinePose(0.0, 0.0, 0.0),
        )
        val routines = mapOf(
            "root" to RoutineDocument(
                documentId = "root",
                name = "Root",
                steps = listOf(RoutineStep.call("nested")),
            ),
            "nested" to RoutineDocument(
                documentId = "nested",
                name = "Nested",
                steps = listOf(
                    RoutineStep.driveTo(
                        RoutineDriveStep(RoutinePose(1.7, 0.0, 0.0)),
                    ),
                ),
            ),
        )
        val errors = validateFtcAutonomousBounds(
            entry = entry,
            routines = routines,
            envelope = FtcFieldEnvelope(3.6576, 3.6576, 0.45, 0.45),
            selectedAlliance = Alliance.RED,
            obstacles = emptyList(),
        )

        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("drive target leaves"))
    }

    @Test
    fun `same pose is immediate while same position with new heading rotates`() {
        val start = Pose2d(0.2, -0.3, Rotation2d(0.25))

        assertEquals(FtcDriveMotionKind.IMMEDIATE, classifyFtcDriveMotion(start, start))
        assertEquals(
            FtcDriveMotionKind.ROTATE,
            classifyFtcDriveMotion(start, Pose2d(start.x, start.y, Rotation2d(1.0))),
        )
        assertEquals(
            FtcDriveMotionKind.TRANSLATE,
            classifyFtcDriveMotion(start, Pose2d(start.x + 0.1, start.y, start.heading)),
        )
    }

    @Test
    fun `heading-only task commands CCW rotation and always emits a zero ending`() {
        val task = FtcRotateToHeadingTask(targetHeadingRadians = 1.0, maxOmegaRadiansPerSecond = 2.0)
        val state = RobotState(
            drive = DriveState(
                poseEstimator = PoseEstimatorSnapshot(estimatedPoseHeading = 0.25),
                measuredAngularVelocityRadiansPerSecond = 0.0,
            )
        )
        task.initialize(state)

        val moving = task.execute(state, 0L).single() as RobotAction.JoystickDriveIntent
        assertEquals(0.0, moving.targetXVelocity, 0.0)
        assertEquals(0.0, moving.targetYVelocity, 0.0)
        assertTrue(moving.targetAngularVelocity > 0.0)
        val stopped = task.end(state, interrupted = true).single() as RobotAction.JoystickDriveIntent
        assertEquals(0.0, stopped.targetAngularVelocity, 0.0)
    }

    @Test
    fun `swept footprint rejects an obstacle between safe endpoints`() {
        val envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4)
        val start = Pose2d(-1.0, 0.0, Rotation2d())
        val end = Pose2d(1.0, 0.0, Rotation2d())
        val obstacle = RobotFieldObstacle(
            id = "center",
            x = 0.0,
            y = 0.0,
            width = 0.2,
            height = 0.8,
            isBlocking = true,
            obstacleType = ObstacleType.BLOCKING,
        )

        assertTrue(isFtcRobotPoseWithinField(start, envelope))
        assertTrue(isFtcRobotPoseWithinField(end, envelope))
        assertFalse(isFtcRobotSweepCollisionFree(start, end, envelope, listOf(obstacle)))
        assertTrue(isFtcRobotSweepCollisionFree(start, end, envelope, emptyList()))
    }

    @Test
    fun `path preflight rejects an obstacle the straight chord safely bypasses`() {
        val envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4)
        val robotRadius = 0.5 * Math.hypot(0.4, 0.4)
        // A middle waypoint pulls the Hermite curve above the start->end chord. The FTC drive
        // task currently generates 2-point (straight) paths, but the preflight helper must
        // validate the driven geometry generically so intermediate waypoints cannot reopen
        // the chord/spline divergence trap.
        val path = PathPlannerParser.generatePath(
            points = listOf(Translation2d(0.0, 0.0), Translation2d(0.5, 1.4), Translation2d(1.0, 0.0)),
            startHeading = Rotation2d(),
            endHeading = Rotation2d(),
        )
        val apex = path.points.maxByOrNull { it.pose.y }!!.pose
        assertTrue(
            "test premise: bow apex (${apex.y} m) must clear the chord by more than the robot radius",
            apex.y - 0.05 > robotRadius,
        )
        val obstacle = RobotFieldObstacle(
            id = "bow",
            x = apex.x,
            y = apex.y,
            width = 0.1,
            height = 0.1,
            isBlocking = true,
            obstacleType = ObstacleType.BLOCKING,
        )
        val start = Pose2d(0.0, 0.0, Rotation2d())
        val end = Pose2d(1.0, 0.0, Rotation2d())

        // The old chord-only preflight would have approved this drive...
        assertTrue(isFtcRobotSweepCollisionFree(start, end, envelope, listOf(obstacle)))
        // ...but the robot would have driven the curve straight through the obstacle.
        assertFalse(isFtcRobotPathSweepCollisionFree(path, envelope, listOf(obstacle)))
    }

    @Test
    fun `two-point paths stay straight so chord and path preflight agree`() {
        val envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4)
        val start = Pose2d(-1.0, 0.0, Rotation2d())
        val end = Pose2d(1.0, 0.0, Rotation2d())
        // Control points are derived from neighboring waypoints, so a 2-point Hermite path is
        // collinear with its chord regardless of the requested headings.
        val path = PathPlannerParser.generatePath(
            points = listOf(Translation2d(start.x, start.y), Translation2d(end.x, end.y)),
            startHeading = Rotation2d(Math.PI / 2),
            endHeading = Rotation2d(-Math.PI / 2),
        )
        val obstacle = RobotFieldObstacle(
            id = "center",
            x = 0.0,
            y = 0.0,
            width = 0.2,
            height = 0.4,
            isBlocking = true,
            obstacleType = ObstacleType.BLOCKING,
        )

        assertEquals(
            isFtcRobotSweepCollisionFree(start, end, envelope, listOf(obstacle)),
            isFtcRobotPathSweepCollisionFree(path, envelope, listOf(obstacle)),
        )
        assertFalse(isFtcRobotPathSweepCollisionFree(path, envelope, listOf(obstacle)))
    }

    @Test
    fun `path preflight accepts a clear spline and rejects an empty path`() {
        val envelope = FtcFieldEnvelope(4.0, 4.0, 0.4, 0.4)
        val start = Pose2d(-1.0, 0.0, Rotation2d())
        val end = Pose2d(1.0, 0.0, Rotation2d())
        val path = PathPlannerParser.generatePath(
            points = listOf(Translation2d(start.x, start.y), Translation2d(end.x, end.y)),
            startHeading = start.heading,
            endHeading = end.heading,
        )
        assertTrue(isFtcRobotPathSweepCollisionFree(path, envelope, emptyList()))

        val emptyPath = com.areslib.pathing.Path(points = emptyList(), events = emptyList())
        assertFalse(isFtcRobotPathSweepCollisionFree(emptyPath, envelope, emptyList()))
    }

    @Test
    fun `point in polygon handles interior, exterior, and non-finite vertices`() {
        val square = listOf(
            RobotFieldPoint(-1.0, -1.0),
            RobotFieldPoint(1.0, -1.0),
            RobotFieldPoint(1.0, 1.0),
            RobotFieldPoint(-1.0, 1.0),
        )
        assertTrue(pointInPolygon(0.0, 0.0, square))
        assertTrue(pointInPolygon(0.99, 0.0, square))
        assertFalse(pointInPolygon(1.01, 0.0, square))
        assertFalse(pointInPolygon(2.0, 2.0, square))
        // Non-finite vertices are treated as blocking (conservative), matching sweep behavior.
        val withNan = square + RobotFieldPoint(Double.NaN, Double.NaN)
        assertTrue(pointInPolygon(5.0, 5.0, withNan))
    }

    @Test
    fun `segment intersection detects crossings, parallels, and shared endpoints`() {
        assertTrue(segmentsIntersect(0.0, 0.0, 2.0, 2.0, 0.0, 2.0, 2.0, 0.0))
        assertTrue(segmentsIntersect(-1.0, 0.0, 1.0, 0.0, 0.0, -1.0, 0.0, 1.0))
        assertFalse(segmentsIntersect(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 1.0))
        // T-intersection at an endpoint still counts.
        assertTrue(segmentsIntersect(0.0, 0.0, 2.0, 0.0, 1.0, -1.0, 1.0, 0.0))
    }

    @Test
    fun `segment distance measures point clearance to a finite segment`() {
        assertEquals(1.0, segmentDistance(0.0, 0.0, 2.0, 0.0, 1.0, 1.0, 1.0, 2.0), 1e-9)
        assertEquals(0.0, segmentDistance(0.0, 0.0, 2.0, 0.0, 2.0, 0.0, 2.0, 1.0), 1e-9)
        assertEquals(0.0, segmentDistance(0.0, 0.0, 2.0, 0.0, 0.5, 0.0, 0.5, 5.0), 1e-9)
    }

    @Test
    fun `drive wrapper mirrors first failed or cancelled child status once`() {
        val state = RobotState()
        val failedOwner = RecordingTask("failed-owner", Long.MAX_VALUE)
        val failedChild = RecordingTask("failed-child", Long.MAX_VALUE)
        failedOwner.initialize(state)
        failedChild.initialize(state)
        val failedBridge = FtcDelegateStatusBridge(failedOwner)
        TaskStateMachine.markFailed(failedChild)
        assertEquals(TaskStatus.FAILED, failedBridge.propagate(failedChild))
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(failedOwner))
        TaskStateMachine.transitionTo(failedChild, TaskStatus.CANCELLED)
        failedBridge.propagate(failedChild)
        assertEquals("A later observation cannot replace the first terminal propagation", TaskStatus.FAILED, failedBridge.terminalStatus)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(failedOwner))

        val cancelledOwner = RecordingTask("cancelled-owner", Long.MAX_VALUE)
        val cancelledChild = RecordingTask("cancelled-child", Long.MAX_VALUE)
        cancelledOwner.initialize(state)
        cancelledChild.initialize(state)
        TaskStateMachine.transitionTo(cancelledChild, TaskStatus.CANCELLED)
        val cancelledBridge = FtcDelegateStatusBridge(cancelledOwner)
        assertEquals(TaskStatus.CANCELLED, cancelledBridge.propagate(cancelledChild))
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(cancelledOwner))
    }

    private class RecordingTask(
        override val name: String,
        private val completeAtMs: Long,
    ) : Task {
        var initializations = 0
        var lastInterrupted: Boolean? = null

        override fun initialize(state: RobotState): List<RobotAction> {
            initializations++
            return super.initialize(state)
        }

        override fun isCompleted(state: RobotState, elapsedMs: Long): Boolean = elapsedMs >= completeAtMs

        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            lastInterrupted = interrupted
            return super.end(state, interrupted)
        }
    }
}

class GeneratedDriveCommandTest {
    @Test
    fun `FTC simulator owns OpMode behavior and does not import FRC lifecycle or vendor APIs`() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(java.io.File(workingDirectory).canonicalFile, java.io.File::getParentFile)
            .first { java.io.File(it, "TeamCode").isDirectory && java.io.File(it, ".ares/project.json").isFile }
        val sources = java.io.File(root, "simulator/src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .joinToString("\n") { it.readText() }

        assertFalse(sources.contains("import edu.wpi.first.wpilibj.TimedRobot"))
        assertFalse(sources.contains("import com.ctre.phoenix"))
    }

    @Test
    fun `Lightbot delegates generated scheduling and keeps mechanical output out of source`() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        val root = generateSequence(java.io.File(workingDirectory).canonicalFile, java.io.File::getParentFile)
            .first { java.io.File(it, "TeamCode").isDirectory && java.io.File(it, ".ares/project.json").isFile }
        val runtime = java.io.File(
            root,
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/FtcGeneratedProjectRuntime.kt",
        ).readText()

        assertTrue(runtime.contains("GeneratedProjectControlRuntime"))
        assertTrue(runtime.contains("GeneratedAresProject.runtimeDefinition"))
        assertFalse(runtime.contains("private val directTaskExecutor"))
        val autoHost = java.io.File(
            root,
            "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/dsl/AresAutoDSL.kt",
        ).readText()
        assertTrue(autoHost.contains("FtcGeneratedAutonomousOpMode"))
        assertFalse(autoHost.contains("override fun loop()"))
        assertFalse(java.io.File(root, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated").exists())
    }

    @Test
    fun `blue alliance mirrors both translation axes but never rotation`() {
        val red = generatedDriveFieldComponents(0.25, -0.5, Alliance.RED)
        val blue = generatedDriveFieldComponents(0.25, -0.5, Alliance.BLUE)

        assertEquals(0.25, red.first, 1e-9)
        assertEquals(-0.5, red.second, 1e-9)
        assertEquals(-0.25, blue.first, 1e-9)
        assertEquals(0.5, blue.second, 1e-9)
    }

    @Test
    fun `checked-in scheme binds every drive axis and an explicit neutral recovery chord`() {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        var dir: java.io.File? = java.io.File(workingDirectory)
        var schemeFile: java.io.File? = null
        while (dir != null) {
            val current = dir
            val candidate = java.io.File(current, ".ares/controls/driver.arescontrols")
            if (candidate.isFile) {
                schemeFile = candidate
                break
            }
            dir = current.parentFile
        }
        val scheme = requireNotNull(schemeFile).let {
            com.areslib.controls.ControlSchemeCodec.decode(it.readText())
        }
        val axes = scheme.bindings
            .map { it.target }
            .filter { it.kind == com.areslib.controls.ControlTargetKind.DRIVE }
        assertEquals(
            "starter scheme binds vx, vy, and omega exactly once each",
            listOf("omega", "vx", "vy"),
            axes.map { it.key }.sorted(),
        )
        val recovery = scheme.bindings.single { it.target.key == "drivetrain.recoverNeutral" }
        assertEquals(com.areslib.controls.ControlTargetKind.ACTION, recovery.target.kind)
        assertEquals(com.areslib.controls.ControlSourceKind.CHORD, recovery.source.kind)
        assertEquals(listOf("back", "start"), recovery.source.controlIds)
        assertEquals(com.areslib.controls.ControlEvent.PRESS, recovery.event)
        assertTrue(recovery.suppressConstituentBindings)
        assertTrue(recovery.priority > 0)
        assertTrue(
            com.areslib.controls.validateControlScheme(
                scheme,
                com.areslib.controls.ControlValidationContext(
                    profileControls = mapOf(
                        "ftc-driver" to setOf(
                            "left_stick_x", "left_stick_y", "right_stick_x", "right_stick_y",
                            "a", "b", "x", "y", "left_bumper", "right_bumper", "left_trigger", "right_trigger",
                            "dpad_up", "dpad_down", "dpad_left", "dpad_right", "back", "start",
                            "left_stick_button", "right_stick_button",
                        ),
                    ),
                ),
            ).none { it.severity == com.areslib.controls.ControlValidationSeverity.ERROR },
        )
    }
}
