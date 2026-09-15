package com.areslib.ftc.drivetrain

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.ftc.MockDcMotorEx
import com.areslib.hardware.HardwareRegistry
import com.areslib.math.geometry.Pose2d
import com.areslib.sequencer.TaskCallbacks
import com.areslib.sequencer.TaskStateMachine
import com.areslib.sequencer.TaskStatus
import com.areslib.subsystem.DriveSubsystem
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.HardwareMap
import kotlin.test.*

class MecanumTrajectoryLifecycleAuditTest {
    private val registry = HardwareRegistry()
    private val store = Store()
    private val follower = MecanumTrajectoryFollower(DriveSubsystem(store))
    private val target = Pose2d(1.0, 0.5)
    private val hardware = MecanumHardwareIO(object : HardwareMap() {
        private val motors = Array(4) { MockDcMotorEx() }
        @Suppress("UNCHECKED_CAST")
        override fun <T> get(classOrType: Class<out T>, deviceName: String): T =
            motors[listOf("fl", "fr", "rl", "rr").indexOf(deviceName)] as T
        override fun <T> getAll(classOrType: Class<out T>): List<T> = emptyList()
    }, registry)

    @BeforeTest fun startClock() { RobotClock.useMockTime(1000L) }

    @AfterTest fun close() {
        store.actionListener = null
        try { follower.driveToPose(store, hardware, target, false) }
        finally {
            registry.closeAll()
            registry.clear()
            RobotClock.useSystemTime()
        }
    }

    @Test fun `unreachable request finishes once and waits for a new edge`() {
        follower.driveToPose(store, hardware, Pose2d(100.0, 100.0), true, false)
        assertNull(follower.activePathfindTask)
        assertTrue(follower.wasPathfindRequested)
        follower.driveToPose(store, hardware, target, true, false)
        assertNull(follower.activePathfindTask, "holding a failed request must not retry planning")
        follower.driveToPose(store, hardware, target, false, false)
        follower.driveToPose(store, hardware, target, true, false)
        assertNotNull(follower.activePathfindTask)
    }

    @Test fun `delegate timeout ends and releases failed task in the same frame`() {
        follower.driveToPose(store, hardware, target, true, false)
        val task = assertNotNull(follower.activePathfindTask)
        var failures = 0
        task.onFail { failures++ }
        RobotClock.useMockTime(17_000L)
        follower.driveToPose(store, hardware, target, true, false)
        assertNull(follower.activePathfindTask)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
        assertEquals(1, failures)
        follower.driveToPose(store, hardware, target, true, false)
        assertEquals(1, failures)
    }

    @Test fun `initialization dispatch exception neutralizes and does not retry while held`() {
        val original = IllegalStateException("test dispatch failure")
        val commands = mutableListOf<RobotAction.JoystickDriveIntent>()
        store.actionListener = { action ->
            if (action is RobotAction.SwitchPath) throw original
            if (action is RobotAction.JoystickDriveIntent) commands.add(action)
        }
        assertSame(original, assertFailsWith<IllegalStateException> {
            follower.driveToPose(store, hardware, target, true, false)
        })
        assertNull(follower.activePathfindTask)
        assertTrue(follower.wasPathfindRequested)
        assertTrue(commands.size >= 2, "cleanup must issue another neutral command after the failed dispatch")
        assertEquals(0.0, commands.last().targetXVelocity)
        assertEquals(0.0, commands.last().targetYVelocity)
        assertEquals(0.0, commands.last().targetAngularVelocity)
        follower.driveToPose(store, hardware, target, true, false)
        assertNull(follower.activePathfindTask)
    }

    @Test fun `release clears callback metadata and remains idle when cleanup throws`() {
        follower.driveToPose(store, hardware, target, true, false)
        val task = assertNotNull(follower.activePathfindTask)
        var completions = 0
        task.onComplete { completions++ }
        val original = IllegalStateException("test stop failure")
        store.actionListener = { if (it is RobotAction.JoystickDriveIntent) throw original }
        assertSame(original, assertFailsWith<IllegalStateException> {
            follower.driveToPose(store, hardware, target, false, false)
        })
        assertNull(follower.activePathfindTask)
        assertFalse(follower.wasPathfindRequested)
        TaskCallbacks.invokeComplete(task)
        assertEquals(0, completions, "interrupted cleanup must discard registered callbacks")
    }

    @Test fun `clock reversal fails and finishes instead of keeping a dead path`() {
        follower.driveToPose(store, hardware, target, true, false)
        val task = assertNotNull(follower.activePathfindTask)
        RobotClock.useMockTime(999L)
        follower.driveToPose(store, hardware, target, true, false)
        assertNull(follower.activePathfindTask)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }
    @Test fun `elapsed overflow fails and finishes in the same frame`() {
        RobotClock.useMockTime(-1000L)
        follower.driveToPose(store, hardware, target, true, false)
        val task = assertNotNull(follower.activePathfindTask)
        RobotClock.useMockTime(Long.MAX_VALUE)
        follower.driveToPose(store, hardware, target, true, false)
        assertNull(follower.activePathfindTask)
        assertEquals(TaskStatus.FAILED, TaskStateMachine.getStatus(task))
    }

}
