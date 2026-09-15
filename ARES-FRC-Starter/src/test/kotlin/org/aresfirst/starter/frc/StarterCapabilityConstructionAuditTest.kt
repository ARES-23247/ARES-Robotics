package org.aresfirst.starter.frc

import com.areslib.action.RobotAction
import com.areslib.math.estimation.PoseEstimatorSnapshot
import com.areslib.math.geometry.Pose2d
import com.areslib.math.coordinate.FieldSymmetry
import com.areslib.routine.*
import com.areslib.sequencer.*
import com.areslib.state.*
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.aresfirst.starter.frc.generated.drivebase.GeneratedAresDrivebaseConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class StarterCapabilityConstructionAuditTest {
    private class Telemetry : ITelemetry {
        val strings = HashMap<String, String>()
        var stringFailure: Throwable? = null
        override fun putNumber(key: String, value: Double) = Unit
        override fun putBoolean(key: String, value: Boolean) = Unit
        override fun putString(key: String, value: String) { stringFailure?.let { throw it }; strings[key] = value }
        override fun putDoubleArray(key: String, value: DoubleArray) = Unit
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = strings[key] ?: defaultValue
    }
    private class Action : Task {
        override val name = "constructed action"
        var releases = 0
        var ends = 0
        var releaseFailure: Throwable? = null
        var completes = false
        var starts = 0
        override fun initialize(state: RobotState): List<RobotAction> { starts++; return super.initialize(state) }
        override fun isCompleted(state: RobotState, elapsedMs: Long) = completes
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> { ends++; return super.end(state, interrupted) }
        override fun releaseRuntimeState() { releases++; releaseFailure?.let { throw it }; super.releaseRuntimeState() }
    }
    private fun entry() = AutonomousCatalogEntry("drive", "Drive", routineId = "drive", startingPose = RoutinePose(1.0, 1.0, 0.0),
        authoredAlliance = RoutineAlliance.BLUE, mirrorForOppositeAlliance = true)
    private inline fun withRobot(block: (StarterRobotRuntime) -> Unit) {
        val field = RobotFieldManager.activeConfig
        val wasMocked = RobotClock.isMocked
        val old = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        RobotFieldManager.setActiveConfig(RobotFieldConfig(id = "audit-field", fieldType = FieldType.FRC,
            widthMeters = 20.0, heightMeters = 10.0, allianceSymmetry = FieldSymmetry.MIRRORED))
        val robot = StarterRobotRuntime(Telemetry())
        try { block(robot) } finally {
            try { robot.close() } finally {
                RobotFieldManager.setActiveConfig(field)
                if (wasMocked) RobotClock.useMockTime(old) else RobotClock.useSystemTime()
            }
        }
    }
    private fun assertNeutral(robot: StarterRobotRuntime) {
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, robot.store.state.drive.angularVelocityRadiansPerSecond)
    }

    @Test fun `teleop translation vector cannot exceed the generated speed limit`() = withRobot { robot ->
        StarterGeneratedCapabilities(robot, true).onDriveCommand(1.0, 1.0, 0.0, true)
        val drive = robot.store.state.drive
        assertTrue(hypot(drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond) <=
            GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND + 1e-12)
    }

    @Test fun `an invalid axis neutralizes the combined drive frame`() = withRobot { robot ->
        StarterGeneratedCapabilities(robot, true).onDriveCommand(Double.NaN, 0.8, 0.5, true)
        assertNeutral(robot)
    }

    @Test fun `autonomous drive construction obeys the same physical permit as teleop`() = withRobot { robot ->
        val capabilities = StarterGeneratedCapabilities(robot, false)
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        assertThrows(IllegalStateException::class.java) { capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0))) }
    }

    @Test fun `opposite alliance transforms use the configured field dimensions`() = withRobot { robot ->
        val capabilities = StarterGeneratedCapabilities(robot, true)
        capabilities.configureAutonomous(entry(), Alliance.RED)
        val pose = capabilities.transform(RoutinePose(2.0, 3.0, 0.25))
        assertEquals(18.0, pose.x, 1e-12)
        assertEquals(3.0, pose.y, 1e-12)
    }

    @Test fun `rotational fields transform both axes and preserve rotational heading semantics`() = withRobot { robot ->
        RobotFieldManager.setActiveConfig(RobotFieldManager.activeConfig.copy(allianceSymmetry = FieldSymmetry.ROTATIONAL))
        val capabilities = StarterGeneratedCapabilities(robot, true)
        capabilities.configureAutonomous(entry(), Alliance.RED)
        val pose = capabilities.transform(RoutinePose(2.0, 3.0, 0.25))
        assertEquals(18.0, pose.x, 1e-12)
        assertEquals(7.0, pose.y, 1e-12)
        assertEquals(0.25 - Math.PI, pose.heading.radians, 1e-12)
    }

    @Test fun `nonmirrored invalid raw pose cannot bypass transform validation`() = withRobot { _ ->
        assertThrows(IllegalArgumentException::class.java) {
            transformStarterFrcPose(RoutinePose(1.0, 1.0, Double.NaN), entry(), Alliance.BLUE)
        }
    }

    @Test fun `a missing later action releases earlier constructed action metadata`() = withRobot { robot ->
        val first = Action()
        var callbacks = 0
        first.onComplete { callbacks++ }
        val capabilities = StarterGeneratedCapabilities(robot, true) { key -> if (key == "first") first else null }
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        assertThrows(IllegalArgumentException::class.java) {
            capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0),
                duringActionKeys = listOf("first", "missing")))
        }
        TaskCallbacks.invokeComplete(first)
        assertEquals(0, callbacks)
        assertEquals(1, first.releases)
        assertEquals(0, first.ends)
        first.reset()
    }

    @Test fun `an action acquired for an invalid marker is released`() = withRobot { robot ->
        val child = Action()
        val capabilities = StarterGeneratedCapabilities(robot, true) { child }
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        assertThrows(IllegalArgumentException::class.java) {
            capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0), markers = listOf(RoutineDriveMarker(1.2, "action"))))
        }
        assertEquals(1, child.releases)
        assertEquals(0, child.ends)
        child.reset()
    }

    @Test fun `duplicate action identities across markers reject the construction`() = withRobot { robot ->
        val child = Action()
        val capabilities = StarterGeneratedCapabilities(robot, true) { child }
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        assertThrows(IllegalStateException::class.java) {
            capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0),
                markers = listOf(RoutineDriveMarker(0.25, "first"), RoutineDriveMarker(0.75, "second"))))
        }
        assertEquals(1, child.releases)
        child.reset()
    }

    @Test fun `construction refuses a task that is already running without releasing its owner`() = withRobot { robot ->
        val child = Action()
        child.initialize(robot.store.state)
        try {
            val capabilities = StarterGeneratedCapabilities(robot, true) { child }
            capabilities.configureAutonomous(entry(), Alliance.BLUE)
            assertThrows(IllegalStateException::class.java) {
                capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0), duringActionKeys = listOf("active")))
            }
            assertEquals(0, child.releases)
        } finally { child.reset() }
    }

    @Test fun `valid canonical commands retain direction on either alliance and preserve prior state`() = withRobot { robot ->
        val capabilities = StarterGeneratedCapabilities(robot, true)
        val speed = GeneratedAresDrivebaseConfig.MAX_LINEAR_SPEED_METERS_PER_SECOND
        for (alliance in Alliance.entries) {
            capabilities.configureAutonomous(entry(), alliance)
            capabilities.onDriveCommand(0.3, -0.4, 0.25, true)
            val previous = robot.store.state
            assertEquals(0.3 * speed, previous.drive.xVelocityMetersPerSecond, 1e-12)
            assertEquals(-0.4 * speed, previous.drive.yVelocityMetersPerSecond, 1e-12)
            capabilities.onDriveCommand(1.0, -1.0, 100.0, true)
            val drive = robot.store.state.drive
            assertEquals(speed, hypot(drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond), 1e-12)
            assertEquals(-drive.xVelocityMetersPerSecond, drive.yVelocityMetersPerSecond, 1e-12)
            assertEquals(GeneratedAresDrivebaseConfig.MAX_ANGULAR_SPEED_RADIANS_PER_SECOND,
                drive.angularVelocityRadiansPerSecond, 1e-12)
            assertEquals(0.3 * speed, previous.drive.xVelocityMetersPerSecond, 1e-12)
        }
    }

    @Test fun `every invalid axis and inactive or unpermitted frame clears prior motion`() = withRobot { robot ->
        val capabilities = StarterGeneratedCapabilities(robot, true)
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            for (axis in 0..2) {
                capabilities.onDriveCommand(0.5, -0.5, 0.5, true)
                val axes = doubleArrayOf(0.5, -0.5, 0.5).also { it[axis] = invalid }
                capabilities.onDriveCommand(axes[0], axes[1], axes[2], true)
                assertNeutral(robot)
            }
        }
        capabilities.onDriveCommand(0.5, -0.5, 0.5, true)
        capabilities.onDriveCommand(1.0, 1.0, 1.0, false)
        assertNeutral(robot)
        capabilities.onDriveCommand(0.5, -0.5, 0.5, true)
        StarterGeneratedCapabilities(robot, false).onDriveCommand(1.0, 1.0, 1.0, true)
        assertNeutral(robot)
    }

    @Test fun `autonomous configuration holds one field snapshot until cleared`() = withRobot { robot ->
        val capabilities = StarterGeneratedCapabilities(robot, true)
        capabilities.configureAutonomous(entry(), Alliance.RED)
        RobotFieldManager.setActiveConfig(RobotFieldManager.activeConfig.copy(widthMeters = 25.0,
            allianceSymmetry = FieldSymmetry.ROTATIONAL))
        val original = capabilities.transform(RoutinePose(2.0, 3.0, 0.25))
        assertEquals(18.0, original.x, 1e-12)
        assertEquals(3.0, original.y, 1e-12)
        capabilities.clearAutonomous()
        assertThrows(IllegalStateException::class.java) { capabilities.transform(RoutinePose(2.0, 3.0, 0.25)) }
        capabilities.configureAutonomous(entry(), Alliance.RED)
        assertEquals(23.0, capabilities.transform(RoutinePose(2.0, 3.0, 0.25)).x, 1e-12)
    }

    @Test fun `field symmetry is reversible and mirroring opt out preserves the authored pose`() = withRobot { _ ->
        val original = RoutinePose(2.0, 3.0, 0.25)
        for (symmetry in FieldSymmetry.entries) {
            val field = RobotFieldManager.activeConfig.copy(allianceSymmetry = symmetry)
            val mirrored = transformStarterFrcPose(original, entry(), Alliance.RED, field)
            val restored = transformStarterFrcPose(RoutinePose(mirrored.x, mirrored.y, mirrored.heading.radians),
                entry().copy(authoredAlliance = RoutineAlliance.RED), Alliance.BLUE, field)
            assertEquals(original.xMeters, restored.x, 1e-12)
            assertEquals(original.yMeters, restored.y, 1e-12)
            assertEquals(original.headingRadians, restored.heading.radians, 1e-12)
            val fixed = transformStarterFrcPose(original, entry().copy(mirrorForOppositeAlliance = false), Alliance.RED, field)
            assertEquals(Pose2d(2.0, 3.0, com.areslib.math.geometry.Rotation2d(0.25)), fixed)
        }
    }

    @Test fun `default FRC dimensions work while malformed fields and poses reject before task construction`() = withRobot { _ ->
        val field = RobotFieldConfig(fieldType = FieldType.FRC, allianceSymmetry = FieldSymmetry.MIRRORED)
        assertEquals(field.resolvedWidthMeters - 2.0,
            transformStarterFrcPose(RoutinePose(2.0, 3.0, 0.25), entry(), Alliance.RED, field).x, 1e-12)
        for (invalid in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            for (candidate in listOf(field.copy(widthMeters = invalid), field.copy(heightMeters = invalid))) {
                assertThrows(IllegalArgumentException::class.java) {
                    transformStarterFrcPose(RoutinePose(2.0, 3.0, 0.0), entry(), Alliance.BLUE, candidate)
                }
            }
        }
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            for (pose in listOf(RoutinePose(invalid, 3.0, 0.0), RoutinePose(2.0, invalid, 0.0), RoutinePose(2.0, 3.0, invalid))) {
                assertThrows(IllegalArgumentException::class.java) { transformStarterFrcPose(pose, entry(), Alliance.RED, field) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            transformStarterFrcPose(RoutinePose(2.0, 3.0, 0.0), entry(), Alliance.BLUE, field.copy(fieldType = FieldType.FTC))
        }
    }

    @Test fun `partial construction preserves factory failure and releases every acquired owner despite throwing hooks`() = withRobot { robot ->
        val primary = IllegalArgumentException("factory failed")
        val cleanup = IllegalStateException("cleanup failed")
        val marker = Action().also { it.releaseFailure = cleanup }
        val during = Action().also { it.releaseFailure = cleanup }
        val arrival = Action()
        val actions = mapOf("marker" to marker, "during" to during, "arrival" to arrival)
        var callbacks = 0
        actions.values.forEach { it.onComplete { callbacks++ }; it.onFail { callbacks++ }; it.withTimeout(1L) }
        val capabilities = StarterGeneratedCapabilities(robot, true) { actions[it] ?: throw primary }
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        try {
            assertSame(primary, assertThrows(IllegalArgumentException::class.java) {
                capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0),
                    markers = listOf(RoutineDriveMarker(0.5, "marker")), duringActionKeys = listOf("during"),
                    arrivalActionKeys = listOf("arrival", "missing")))
            })
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            actions.values.forEach {
                assertEquals(1, it.releases)
                assertEquals(0, it.ends)
                assertFalse(TaskTimeoutManager.isTimedOut(it, 100L))
                TaskCallbacks.invokeComplete(it)
                TaskCallbacks.invokeFail(it)
            }
            assertEquals(0, callbacks)
        } finally { actions.values.forEach { it.releaseFailure = null; it.reset() } }
    }

    @Test fun `constructed deadline starts arrivals only after drive settles and cancels unfinished companions`() = withRobot { robot ->
        val marker = Action().also { it.completes = true }
        val during = Action()
        val arrival = Action().also { it.completes = true }
        val actions = mapOf("marker" to marker, "during" to during, "arrival" to arrival)
        val capabilities = StarterGeneratedCapabilities(robot, true) { actions[it] }
        capabilities.configureAutonomous(entry(), Alliance.BLUE)
        val task = capabilities.createDriveTask(RoutineDriveStep(RoutinePose(2.0, 2.0, 0.0),
            markers = listOf(RoutineDriveMarker(0.0, "marker")), duringActionKeys = listOf("during"),
            arrivalActionKeys = listOf("arrival")))
        val executor = TaskExecutor().also { it.addTask(task) }
        fun state(now: Long) = robot.store.state.copy(drive = robot.store.state.drive.copy(measuredMotionValid = true,
            poseEstimator = PoseEstimatorSnapshot(estimatedPoseX = 2.0, estimatedPoseY = 2.0, lastObservationTimestampMs = now)))
        try {
            assertTrue(actions.values.all { it.starts == 0 })
            executor.update(state(1000L), 1000L)
            assertEquals(1, marker.starts)
            assertEquals(1, during.starts)
            assertEquals(0, arrival.starts)
            for (now in listOf(1020L, 1040L, 1060L, 1080L, 1100L)) {
                RobotClock.useMockTime(now)
                executor.update(state(now), now)
            }
            assertEquals(1, arrival.starts)
            assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(during))
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(task))
            assertEquals(0, executor.size)
        } finally {
            try { executor.cancelAll(state(RobotClock.currentTimeMillis())) }
            finally { task.reset(); actions.values.forEach { it.reset() } }
        }
    }

    @Test fun `marker metadata cancellation clears throwing child registries without hardware callbacks`() = withRobot { robot ->
        for (triggered in listOf(false, true)) {
            val child = Action()
            val marker = StarterFrcDriveMarkerTask(Pose2d(), 0.0, child)
            var callbacks = 0
            child.withTimeout(1L).onComplete { callbacks++ }
            val state = robot.store.state.copy(drive = robot.store.state.drive.copy(measuredMotionValid = true,
                poseEstimator = PoseEstimatorSnapshot(lastObservationTimestampMs = 1000L)))
            if (triggered) { marker.initialize(state); marker.execute(state, 0L) }
            val expected = IllegalStateException("child release failed")
            child.releaseFailure = expected
            try {
                assertSame(expected, assertThrows(IllegalStateException::class.java) { marker.releaseRuntimeState() })
                assertEquals(1, child.releases)
                assertEquals(0, child.ends)
                assertFalse(TaskTimeoutManager.isTimedOut(child, 100L))
                TaskCallbacks.invokeComplete(child)
                assertEquals(0, callbacks)
                assertEquals(if (triggered) TaskStatus.CANCELLED else TaskStatus.PENDING, TaskStateMachine.getStatus(child))
                marker.releaseRuntimeState()
                assertEquals(1, child.releases)
            } finally { child.releaseFailure = null; child.reset(); marker.reset() }
        }
    }

    private fun auto(robot: StarterRobotRuntime, cancel: ((String) -> Unit)? = null): Pair<StarterFrcAutonomousRuntime,
        com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime<org.aresfirst.starter.frc.generated.GeneratedAresProjectCapabilities>> {
        val capabilities = StarterGeneratedCapabilities(robot, true)
        val sampler = object : com.areslib.frc.runtime.FrcControllerPortSampler {
            override fun prepare(port: Int) = Unit
            override fun sampleInto(port: Int, frame: com.areslib.input.InputFrame, nowNanos: Long) = error("No sampling expected")
        }
        val controls = com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime(
            org.aresfirst.starter.frc.generated.GeneratedAresProject.runtimeDefinition,
            { robot.store.state }, robot.store::dispatch, capabilities, sampler)
        return StarterFrcAutonomousRuntime(robot, StarterDriveSimulation(), controls, capabilities, true,
            selectionProvider = { "do-nothing" }, cancelGenerated = cancel ?: controls::cancelAll) to controls
    }

    @Test fun `stop clears the last Redux drive intent even with no active drive task`() = withRobot { robot ->
        val (auto, controls) = auto(robot)
        try {
            robot.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2))
            auto.stop("Stop requested")
            assertNeutral(robot)
            assertTrue(auto.isFinishedForTest)
        } finally { controls.cancelAll("Test complete") }
    }

    @Test fun `stop remains terminal and neutral when generated cancellation throws`() = withRobot { robot ->
        var failure: Throwable? = null
        val (auto, controls) = auto(robot) { failure?.let { throw it } }
        try {
            auto.autonomousInit()
            assertFalse(auto.isFinishedForTest)
            robot.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2))
            val expected = IllegalStateException("Generated cancellation failed")
            failure = expected
            assertSame(expected, assertThrows(IllegalStateException::class.java) { auto.stop("Stop requested") })
            assertTrue(auto.isFinishedForTest)
            assertNeutral(robot)
            assertEquals("Stopped", robot.telemetry.getString("ARES/Auto/Status", ""))
        } finally { controls.cancelAll("Test complete") }
    }

    @Test fun `stop retains direct interruption while still neutralizing the command`() = withRobot { robot ->
        val wasInterrupted = Thread.interrupted()
        val expected = InterruptedException("cancel interrupted")
        val (auto, controls) = auto(robot) { throw expected }
        try {
            robot.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2))
            assertSame(expected, assertThrows(InterruptedException::class.java) { auto.stop("Stop requested") })
            assertTrue(Thread.currentThread().isInterrupted)
            assertNeutral(robot)
        } finally {
            Thread.interrupted()
            controls.cancelAll("Test complete")
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test fun `stop preserves cancellation as primary if status publication also fails`() = withRobot { robot ->
        val expected = IllegalStateException("cancel failed")
        val secondary = IllegalArgumentException("status failed")
        val telemetry = robot.telemetry as Telemetry
        val (auto, controls) = auto(robot) { throw expected }
        try {
            robot.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2))
            telemetry.stringFailure = secondary
            assertSame(expected, assertThrows(IllegalStateException::class.java) { auto.stop("Stop requested") })
            assertEquals(listOf(secondary), expected.suppressed.toList())
            assertNeutral(robot)
            assertTrue(auto.isFinishedForTest)
        } finally { telemetry.stringFailure = null; controls.cancelAll("Test complete") }
    }
}
