package org.firstinspires.ftc.teamcode.opmodes

import com.areslib.Store
import com.areslib.action.RobotAction
import com.areslib.networktables.NT4Server
import com.areslib.util.RobotClock
import com.areslib.util.PoseStorage
import com.areslib.math.estimation.PoseEstimator
import com.areslib.telemetry.RobotStatusTracker
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase
import org.firstinspires.ftc.teamcode.dsl.FtcGeneratedProjectRuntime
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.mockito.Mockito.*

class ARESRemoteDriveLoopTest {
    private val oldValid = PoseStorage.hasValidPose
    private val oldTags = PoseEstimator.activeTags
    private val oldMode = RobotStatusTracker.activeOpMode
    private val instanceField = NT4Server::class.java.getDeclaredField("serverInstance").apply { isAccessible = true }
    private lateinit var server: NT4Server
    private lateinit var mode: ARESRemoteDriveOpMode
    private lateinit var robot: AresRobot
    private val snapshots = mutableListOf<RobotAction.JoystickDriveIntent>()
    private val originals = mutableListOf<RobotAction.JoystickDriveIntent>()
    private var driveDispatchFailure: Exception? = null

    @Before fun setup() {
        check(NT4Server.getInstance() == null) { "Test requires no running NT4 server" }
        RobotClock.useMockTime(1000L)
        NT4Server.resetSharedState()
        server = NT4Server(java.net.InetSocketAddress("127.0.0.1", 0), org.java_websocket.drafts.Draft_6455())
        instanceField.set(null, server) // Registry access only: start() is never called.
        robot = mock(AresRobot::class.java, RETURNS_DEEP_STUBS)
        val store = mock(Store::class.java) { invocation ->
            if (invocation.method.name == "dispatch") {
                val action = invocation.arguments[0]
                if (action is RobotAction.JoystickDriveIntent) {
                    originals.add(action)
                    snapshots.add(action.copy())
                    driveDispatchFailure?.let { throw it }
                }
            }
            RETURNS_DEFAULTS.answer(invocation)
        }
        `when`(robot.base.store).thenReturn(store)
        `when`(robot.base.drive.maxSpeedMps).thenReturn(4.0)
        `when`(robot.base.drive.maxAngularSpeedRadiansPerSecond).thenReturn(8.0)
        mode = spy(ARESRemoteDriveOpMode())
        doReturn(robot).`when`(mode).buildRobot()
        mode.gamepad1 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.gamepad2 = mock(com.qualcomm.robotcore.hardware.Gamepad::class.java)
        mode.telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
        AresTeleOpBase::class.java.getDeclaredField("generatedRuntime").apply {
            isAccessible = true
            set(mode, mock(FtcGeneratedProjectRuntime::class.java))
        }
        PoseStorage.hasValidPose = false
        mode.init()
        mode.start()
    }

    @After fun cleanup() {
        if (::server.isInitialized && NT4Server.getInstance() === server) {
            instanceField.set(null, null)
            NT4Server.resetSharedState()
        }
        RobotClock.useSystemTime()
        PoseStorage.hasValidPose = oldValid
        PoseEstimator.activeTags = oldTags
        RobotStatusTracker.activeOpMode = oldMode
    }

    private fun publish(sequence: Int, vx: Double = 0.0, fieldRelative: Boolean = true) {
        server.putTopic("ARES/Input/driveFrame", doubleArrayOf(2.0, 44.0, sequence.toDouble(), sequence.toDouble(), vx, 0.0, 0.0, if (fieldRelative) 16.0 else 0.0))
    }
    private fun tick(time: Long): RobotAction.JoystickDriveIntent {
        RobotClock.useMockTime(time)
        mode.loop()
        return snapshots.last()
    }

    @Test fun `real loop rejects retained motion handles handshake and expires exact lease`() {
        publish(1, 1.0)
        assertEquals(0.0, tick(1000L).targetXVelocity, 0.0)
        publish(2)
        assertEquals(0.0, tick(1001L).targetXVelocity, 0.0)
        publish(3, 1.0, fieldRelative = false)
        val moving = tick(1010L)
        assertEquals(1.0, moving.targetXVelocity, 0.0)
        assertFalse(moving.isFieldCentric)
        assertEquals(1.0, tick(1209L).targetXVelocity, 0.0)
        assertEquals(0.0, tick(1210L).targetXVelocity, 0.0)
        assertTrue(originals.all { it === originals.first() })
        assertEquals(5, snapshots.size)
    }

    @Test fun `telemetry failure zeros and requires neutral before retained movement can resume`() {
        publish(1)
        tick(1000L)
        publish(2, 1.0)
        assertEquals(1.0, tick(1010L).targetXVelocity, 0.0)
        doThrow(IllegalStateException("telemetry failure")).doNothing().`when`(robot).addTelemetry("Status", "DRIVING")
        assertEquals(0.0, tick(1100L).targetXVelocity, 0.0)
        assertEquals(0.0, tick(1110L).targetXVelocity, 0.0)
        publish(3)
        assertEquals(0.0, tick(1120L).targetXVelocity, 0.0)
        publish(4, 0.5)
        assertEquals(0.5, tick(1130L).targetXVelocity, 0.0)
    }

    @Test fun `first status reports neutral handshake at clock zero`() {
        publish(1)
        tick(0L)
        verify(robot).addTelemetry("Status", "V2 NEUTRAL HANDSHAKE ACCEPTED")
    }

    @Test fun `oversized frame cannot reuse its valid prefix and invalidates motion`() {
        publish(1)
        tick(1000L)
        publish(2, 1.0)
        assertEquals(1.0, tick(1010L).targetXVelocity, 0.0)
        server.putTopic("ARES/Input/driveFrame", doubleArrayOf(2.0, 44.0, 3.0, 3.0, 1.0, 0.0, 0.0, 16.0, 99.0))
        assertEquals(0.0, tick(1020L).targetXVelocity, 0.0)
        publish(4, 1.0)
        assertEquals(0.0, tick(1030L).targetXVelocity, 0.0)
    }

    @Test fun `failed error telemetry cannot prevent neutral robot update`() {
        publish(1)
        tick(1000L)
        publish(2, 1.0)
        tick(1010L)
        doThrow(IllegalStateException("display failed")).`when`(robot).addTelemetry("Status", "DRIVING")
        doThrow(IllegalStateException("error display failed")).`when`(robot).addTelemetry("Status", "WATCHDOG ERROR: display failed")
        assertEquals(0.0, tick(1100L).targetXVelocity, 0.0)
        assertEquals(3, mockingDetails(robot).invocations.count { it.method.name == "update" })
    }

    @Test fun `failed neutral dispatch closes robot and preserves primary failure`() {
        publish(1)
        tick(1000L)
        publish(2, 1.0)
        tick(1010L)
        val failure = IllegalStateException("dispatch failed")
        val cleanupFailure = IllegalStateException("close failed")
        driveDispatchFailure = failure
        doThrow(cleanupFailure).`when`(robot).close()
        assertSame(failure, runCatching { tick(1020L) }.exceptionOrNull())
        verify(robot).close()
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertEquals(0.0, snapshots.last().targetXVelocity, 0.0)
        assertEquals(2, mockingDetails(robot).invocations.count { it.method.name == "update" })
        val attempts = snapshots.size
        driveDispatchFailure = null
        assertSame(failure, runCatching { tick(1030L) }.exceptionOrNull())
        assertEquals(attempts, snapshots.size)
        verify(robot, times(1)).close()
    }
}
