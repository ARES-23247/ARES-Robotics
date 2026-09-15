package com.areslib.sequencer

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.hardware.actuator.IndicatorLightColor as Color
import com.areslib.math.geometry.Pose2d
import com.areslib.pathing.HolonomicPathFollower
import com.areslib.pathing.Path
import com.areslib.pathing.PathPoint
import com.areslib.state.RobotState
import com.areslib.subsystem.DrivetrainSubsystem
import com.areslib.util.RobotClock
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class RobotSequenceIntegrationTest {
    private val state = RobotState()
    @Test fun `nested condition wait and deadline lighting sequence completes through executor`() {
        RobotClock.useMockTime(0)
        var ready = false
        val root = robotSequence {
            setIndicator("light", Color.GREEN)
            waitUntil { ready }
            sequence { waitFor(2.milliseconds) }
            deadline(TimeWaitTask(5)) { blinkIndicator("light", Color.RED, Color.BLUE, 100.milliseconds, 2.milliseconds) }
        }
        val executor = TaskExecutor(); executor.addTask(root)
        try {
            assertEquals(Color.GREEN.position, (executor.update(state, 0).single() as RobotAction.SetIndicatorLight).position)
            ready = true
            executor.update(state, 1)
            assertEquals(Color.RED.position, (executor.update(state, 3).single() as RobotAction.SetIndicatorLight).position)
            assertEquals(Color.BLUE.position, (executor.update(state, 4).single() as RobotAction.SetIndicatorLight).position)
            assertEquals(Color.RED.position, (executor.update(state, 8).single() as RobotAction.SetIndicatorLight).position)
            assertEquals(TaskStatus.COMPLETED, TaskStateMachine.getStatus(root))
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(state); root.reset(); RobotClock.useSystemTime() }
    }

    @Test fun `follow path builder rejects empty path and initializes valid drive task`() {
        var stops = 0
        val drive = object : DrivetrainSubsystem {
            override fun setChassisSpeeds(vx: Double, vy: Double, omega: Double) { if (vx == 0.0 && vy == 0.0 && omega == 0.0) stops++ }
            override fun getEstimatedPose() = Pose2d()
            override fun readSensors(store: Store, timestampMs: Long) = Unit
            override fun writeOutputs(state: RobotState, scale: Double) = Unit
            override fun close() = Unit
        }
        val follower = HolonomicPathFollower(drive)
        assertFailsWith<IllegalArgumentException> { robotSequence { followPath(Path(emptyList()), follower) } }
        val path = Path(listOf(PathPoint(Pose2d(), 0.0, 0.0), PathPoint(Pose2d(1.0, 0.0), 0.0, 1.0)))
        val root = robotSequence { followPath(path, follower) }
        try {
            assertEquals(TaskResources.DRIVE, root.requiredResources)
            assertTrue(root.initialize(state).single() is RobotAction.SwitchPath)
        } finally { root.end(state, true); root.reset() }
        assertTrue(stops >= 2)
    }

    @Test fun `captured builder mutation cannot alter an already built nested routine`() {
        lateinit var parent: RobotSequence
        lateinit var child: RobotSequence
        val root = robotSequence {
            parent = this
            sequence { child = this; setIndicator("light", Color.RED) }
        }
        parent.setIndicator("light", Color.BLUE)
        child.setIndicator("light", Color.GREEN)
        val executor = TaskExecutor(); executor.addTask(root)
        try {
            val actions = executor.update(state, 0)
            assertEquals(Color.RED.position, (actions.single() as RobotAction.SetIndicatorLight).position)
            assertEquals(0, executor.size)
        } finally { executor.cancelAll(state); root.reset() }
    }

    @Test fun `invalid distances names and empty nested groups are rejected at build time`() {
        for (meters in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertFailsWith<IllegalArgumentException> { robotSequence { waitForDistance(meters) } }
        assertFailsWith<IllegalArgumentException> { robotSequence { setIndicator(" ", Color.RED) } }
        assertFailsWith<IllegalArgumentException> { robotSequence { blinkIndicator(" ", Color.RED, duration = 1.milliseconds) } }
        assertFailsWith<IllegalArgumentException> { robotSequence { parallel {} } }
        assertFailsWith<IllegalArgumentException> { robotSequence { race {} } }
        assertFailsWith<IllegalArgumentException> { robotSequence { sequence {} } }
        assertFailsWith<IllegalArgumentException> { robotSequence { deadline(TimeWaitTask(0)) {} } }
    }
}
