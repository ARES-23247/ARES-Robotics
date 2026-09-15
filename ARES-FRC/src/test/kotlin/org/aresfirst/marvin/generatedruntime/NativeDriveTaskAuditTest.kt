package org.aresfirst.marvin.generatedruntime

import com.areslib.action.RobotAction
import com.areslib.frc.FrcSwerveRobot
import com.areslib.math.geometry.Pose2d
import com.areslib.math.geometry.Rotation2d
import com.areslib.pathing.NamedCommands
import com.areslib.pathing.CommandKey
import com.areslib.pathing.TrajectoryLimits
import com.areslib.pathing.TrajectoryPreset
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.RoutineDriveMarker
import com.areslib.routine.RoutineDriveStep
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineAlliance
import com.areslib.sequencer.*
import com.areslib.state.Alliance
import com.areslib.state.RobotState
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import org.aresfirst.marvin.marvin.MarvinConfig
import org.aresfirst.marvin.robot.FrcAutoCapabilities
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NativeDriveTaskAuditTest {
    private val robots = mutableListOf<FrcSwerveRobot>()
    private val tasks = mutableListOf<Task>()
    private val sink = object : ITelemetry {
        override fun putNumber(key: String, value: Double) {}
        override fun putBoolean(key: String, value: Boolean) {}
        override fun putString(key: String, value: String) {}
        override fun putDoubleArray(key: String, value: DoubleArray) {}
        override fun getNumber(key: String, defaultValue: Double) = defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = defaultValue
        override fun getString(key: String, defaultValue: String) = defaultValue
    }
    @BeforeEach fun setup() {
        RobotClock.useMockTime(1000L)
        NamedCommands.clear()
        FrcAutoCapabilities.register()
    }
    @AfterEach fun cleanup() {
        try {
            tasks.forEach { task -> runCatching { task.end(RobotState(), true) }; runCatching { task.releaseRuntimeState() } }
            assertAll(robots.map { robot -> org.junit.jupiter.api.function.Executable { robot.close() } })
        } finally {
            NamedCommands.clear()
            RobotClock.useSystemTime()
        }
    }
    private fun adapter(initial: RobotState = RobotState()): Pair<FrcSwerveRobot, FrcGeneratedRoutineCapabilities> {
        val robot = FrcSwerveRobot(isSimulation = true, baseTelemetry = sink, initialState = initial).also { robots.add(it) }
        robot.store.dispatch(RobotAction.PoseUpdate(2.0, 2.0, 0.0, isReset = true, timestampMs = 1000L))
        return robot to FrcGeneratedRoutineCapabilities(robot).apply {
            configure(AutonomousCatalogEntry(entryId = "test", displayName = "Test", routineId = "test",
                startingPose = RoutinePose(2.0, 2.0, 0.0), authoredAlliance = RoutineAlliance.BLUE), Alliance.BLUE)
        }
    }
    private fun task(adapter: FrcGeneratedRoutineCapabilities, step: RoutineDriveStep = RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0))) =
        adapter.createDriveTask(step).also { tasks.add(it) }

    private class Probe : Task {
        override val name = "delegate probe"
        var executes = 0
        var releases = 0
        var complete = false
        var endFailure: Throwable? = null
        var releaseFailure: Throwable? = null
        var failAtEnd = false
        override fun isCompleted(state: RobotState, elapsedMs: Long) = complete
        override fun execute(state: RobotState, elapsedMs: Long): List<RobotAction> {
            executes++
            return emptyList()
        }
        override fun end(state: RobotState, interrupted: Boolean): List<RobotAction> {
            endFailure?.let { throw it }
            if (failAtEnd) TaskStateMachine.markFailed(this)
            return super.end(state, interrupted)
        }
        override fun releaseRuntimeState() {
            releases++
            super.releaseRuntimeState()
            releaseFailure?.let { throw it }
        }
    }
    private fun inject(task: Task, state: RobotState, probe: Probe) {
        task.initialize(state)
        val field = task.javaClass.getDeclaredField("delegate").apply { isAccessible = true }
        val old = field.get(task) as Task
        old.end(state, true)
        old.releaseRuntimeState()
        probe.initialize(state)
        field.set(task, probe)
    }

    @Test fun `native drive and attached actions reserve resources before initialization`() {
        val (_, adapter) = adapter()
        val plain = task(adapter)
        assertEquals(TaskResources.DRIVE, plain.requiredResources)
        assertThrows(IllegalArgumentException::class.java) { ParallelTaskGroup(listOf(plain, task(adapter))) }
        val step = RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0),
            markers = listOf(RoutineDriveMarker(0.5, "shooter.prepare")),
            duringActionKeys = listOf("intake.collect"), arrivalActionKeys = listOf("shooter.feedWhenReady"))
        val mixed = task(adapter, step)
        val expected = TaskResources.DRIVE or FrcAutoCapabilities.SHOOTER_PREPARE.requiredResources or
            FrcAutoCapabilities.INTAKE_COLLECT.requiredResources or FrcAutoCapabilities.SHOOTER_FEED_WHEN_READY.requiredResources
        assertEquals(expected, mixed.requiredResources)
        assertThrows(IllegalArgumentException::class.java) {
            ParallelTaskGroup(listOf(mixed, FrcAutoCapabilities.actionShooterStop()))
        }
    }

    @Test fun `drive task owns action lists and keeps its resource declaration stable`() {
        val (robot, adapter) = adapter()
        val arrivals = mutableListOf("intake.stop")
        val wrapper = task(adapter, RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), arrivalActionKeys = arrivals))
        arrivals.clear()
        wrapper.initialize(robot.store.state)
        val delegate = wrapper.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(wrapper) as Task
        assertTrue(delegate is SequentialTaskGroup, "Mutating the caller list erased the arrival action")
        assertEquals(TaskResources.DRIVE or TaskResources.INTAKE or TaskResources.FLOOR, wrapper.requiredResources)
    }

    @Test fun `expired wrapper never executes its delegate`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter).withTimeout(5L)
        val probe = Probe()
        inject(wrapper, robot.store.state, probe)
        wrapper.execute(robot.store.state, 6L)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(wrapper))
        assertEquals(0, probe.executes)
    }

    @Test fun `delegate cancellation reaches the enclosing task lifecycle`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter)
        val probe = Probe()
        inject(wrapper, robot.store.state, probe)
        TaskStateMachine.transitionTo(probe, TaskStatus.CANCELLED)
        assertFalse(wrapper.isCompleted(robot.store.state, 0L))
        assertEquals(TaskStatus.CANCELLED, TaskStateMachine.getStatus(wrapper))
        wrapper.execute(robot.store.state, 0L)
        assertEquals(0, probe.executes)
    }

    @Test fun `failed delegate end releases ownership and cannot complete the wrapper`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter)
        val probe = Probe().apply { endFailure = IllegalStateException("end failed") }
        inject(wrapper, robot.store.state, probe)
        assertSame(probe.endFailure, assertThrows(IllegalStateException::class.java) { wrapper.end(robot.store.state, false) })
        assertEquals(1, probe.releases)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(wrapper))
        wrapper.releaseRuntimeState()
        assertEquals(1, probe.releases, "Released delegate must not be retained or released again")
    }

    @Test fun `failed delegate release still removes wrapper callbacks and ownership`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter)
        val probe = Probe().apply { releaseFailure = IllegalStateException("release failed") }
        inject(wrapper, robot.store.state, probe)
        var callbacks = 0
        wrapper.onComplete { callbacks++ }
        assertSame(probe.releaseFailure, assertThrows(IllegalStateException::class.java) { wrapper.releaseRuntimeState() })
        TaskCallbacks.invokeComplete(wrapper)
        assertEquals(0, callbacks, "Wrapper retained callback after delegate release failed")
        wrapper.releaseRuntimeState()
        assertEquals(1, probe.releases)
    }

    @Test fun `trajectory uses the pose at initialization and retains marker and arrival semantics`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter, RoutineDriveStep(RoutinePose(4.0, 2.0, 0.0),
            markers = listOf(RoutineDriveMarker(0.0, "shooter.prepare"), RoutineDriveMarker(1.0, "shooter.stop")),
            arrivalActionKeys = listOf("intake.stop")))
        robot.store.dispatch(RobotAction.PoseUpdate(3.0, 2.0, 0.0, isReset = true, timestampMs = 1001L))
        val switch = wrapper.initialize(robot.store.state).filterIsInstance<RobotAction.SwitchPath>().single()
        assertEquals(3.0, switch.path.points.first().pose.x, 1e-9)
        assertEquals(4.0, switch.path.points.last().pose.x, 1e-9)
        assertEquals(2, switch.path.events.size)
        assertEquals(0.0, switch.path.events.first().triggerDistanceMeters, 1e-9)
        assertEquals(switch.path.points.last().distanceMeters, switch.path.events.last().triggerDistanceMeters, 1e-9)
        val resources = wrapper.requiredResources
        wrapper.end(robot.store.state, true)
        wrapper.releaseRuntimeState()
        assertEquals(resources, wrapper.requiredResources)
    }

    @Test fun `rotated footprint field bounds agree with independent transformed corners`() {
        val length = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_LENGTH
        val width = com.areslib.math.coordinate.CoordinateTransformers.FRC_FIELD_WIDTH
        val halfX = MarvinConfig.ROBOT_BUMPER_LENGTH_METERS / 2.0
        val halfY = MarvinConfig.ROBOT_BUMPER_WIDTH_METERS / 2.0
        for (heading in listOf(0.0, Math.PI / 4.0, Math.PI / 2.0, -2.3)) {
            for (x in listOf(0.2, 0.6, length / 2.0, length - 0.2)) {
                for (y in listOf(0.2, 0.6, width / 2.0, width - 0.2)) {
                    val corners = listOf(-halfX, halfX).flatMap { dx -> listOf(-halfY, halfY).map { dy ->
                        (x + dx * Math.cos(heading) - dy * Math.sin(heading)) to
                            (y + dx * Math.sin(heading) + dy * Math.cos(heading))
                    } }
                    val expected = corners.all { (cx, cy) -> cx in 0.0..length && cy in 0.0..width }
                    assertEquals(expected, runCatching {
                        requireFrcRoutinePoseInsideField(Pose2d(x, y, Rotation2d(heading)), "test")
                    }.isSuccess, "Pose ($x,$y,$heading)")
                }
            }
        }
    }

    @Test fun `end preserves failure priority and observes a delegate failing during end`() {
        val (robot, adapter) = adapter()
        val failedWrapper = task(adapter)
        val cancelledChild = Probe()
        inject(failedWrapper, robot.store.state, cancelledChild)
        TaskStateMachine.markFailed(failedWrapper)
        TaskStateMachine.transitionTo(cancelledChild, TaskStatus.CANCELLED)
        failedWrapper.end(robot.store.state, true)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(failedWrapper))
        val wrapper = task(adapter)
        inject(wrapper, robot.store.state, Probe().apply { failAtEnd = true })
        wrapper.end(robot.store.state, false)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(wrapper))
    }

    @Test fun `registration resource drift is rejected before initializing the path`() {
        val (robot, adapter) = adapter()
        val wrapper = task(adapter, RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0),
            markers = listOf(RoutineDriveMarker(0.5, "shooter.prepare"))))
        val originalMask = wrapper.requiredResources
        NamedCommands.register(FrcAutoCapabilities.SHOOTER_PREPARE.copy(requiredResources = TaskResources.DRIVE)) { Probe() }
        assertThrows(IllegalArgumentException::class.java) { wrapper.initialize(robot.store.state) }
        assertEquals(originalMask, wrapper.requiredResources)
        assertNull(wrapper.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(wrapper))
    }

    @Test fun `presets scale valid limits and invalid acceleration falls back to defaults`() {
        val defaults = RobotState()
        for (acceleration in listOf(8.0, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val (robot, adapter) = adapter(defaults.copy(tuning = defaults.tuning.copy(
                drive = defaults.tuning.drive.copy(pathAccelerationLimit = acceleration))))
            val method = adapter.javaClass.getDeclaredMethod("trajectoryLimits", TrajectoryPreset::class.java)
                .apply { isAccessible = true }
            for ((preset, scale) in listOf(TrajectoryPreset.SAFE to 0.45, TrajectoryPreset.BALANCED to 0.7,
                TrajectoryPreset.FAST to 0.9, TrajectoryPreset.ADAPTIVE to 0.6)) {
                val limits = method.invoke(adapter, preset) as TrajectoryLimits
                val a = (if (acceleration.isFinite() && acceleration > 0.0) acceleration else 3.0) * scale
                assertEquals(robot.drive.maxSpeedMps * scale, limits.maxVelocityMps, 1e-12)
                assertEquals(a, limits.maxAccelerationMps2, 1e-12)
                assertEquals(a * 4.0, limits.maxJerkMps3, 1e-12)
                assertEquals(a * 0.75, limits.maxCentripetalAccelerationMps2, 1e-12)
                assertEquals(robot.drive.maxAngularSpeedRadiansPerSecond * scale, limits.maxAngularVelocityRps, 1e-12)
                assertEquals(a / FrcGeneratedRoutineCapabilities.DRIVE_RADIUS_METERS, limits.maxAngularAccelerationRps2, 1e-12)
            }
        }
    }

    @Test fun `invalid presets engines and overflowing derived limits cannot initialize a path`() {
        val (robot, adapter) = adapter()
        for (step in listOf(
            RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), motionPresetKey = "unknown"),
            RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), preferredEngineKey = "unknown"),
            RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), preferredEngineKey = "online-replan"),
            RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), preferredEngineKey = "dynamics_optimized"),
        )) {
            val wrapper = task(adapter, step)
            assertThrows(RuntimeException::class.java) { wrapper.initialize(robot.store.state) }
            assertNull(wrapper.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(wrapper))
        }
        val defaults = RobotState()
        val (hugeRobot, hugeAdapter) = adapter(defaults.copy(tuning = defaults.tuning.copy(
            drive = defaults.tuning.drive.copy(pathAccelerationLimit = Double.MAX_VALUE))))
        assertThrows(IllegalArgumentException::class.java) { task(hugeAdapter).initialize(hugeRobot.store.state) }
        for (engine in listOf("jerk-limited", "jerk_limited")) {
            val wrapper = task(adapter, RoutineDriveStep(RoutinePose(3.0, 2.0, 0.0), preferredEngineKey = engine))
            assertTrue(wrapper.initialize(robot.store.state).any { it is RobotAction.SwitchPath })
            wrapper.end(robot.store.state, true)
        }
    }
}
