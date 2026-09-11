package org.firstinspires.ftc.teamcode

import com.areslib.state.RobotState
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase
import org.firstinspires.ftc.teamcode.dsl.FtcGeneratedProjectRuntime
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.opmodes.ARESTuningTeleOp
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AresTeleOpConstructionTest {
    private fun mode() = ARESTuningTeleOp().apply {
        hardwareMap = mock(com.qualcomm.robotcore.hardware.HardwareMap::class.java)
        telemetry = mock(org.firstinspires.ftc.robotcore.external.Telemetry::class.java)
    }

    private fun assertRuntimeReleased(mode: AresTeleOpBase) {
        val field = AresTeleOpBase::class.java.getDeclaredField("generatedRuntime").apply { isAccessible = true }
        assertNull(field.get(mode))
    }

    @Test fun `successful setup transfers robot ownership without closing it`() {
        mockConstruction(AresRobot::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { robot, _ ->
            `when`(robot.base.store.state).thenReturn(RobotState())
        }.use { robots ->
            mockConstruction(FtcGeneratedProjectRuntime::class.java) { runtime, _ ->
                `when`(runtime.controlsSource).thenReturn("test")
            }.use { runtimes ->
                val mode = mode()
                val result = mode.buildRobot()
                assertSame(robots.constructed().single(), result)
                verify(result).addTelemetry("ARES/Controls/Source", "test")
                verify(result, never()).close()
                assertSame(result.base, mode.getBaseRobot(result))
                val field = AresTeleOpBase::class.java.getDeclaredField("generatedRuntime").apply { isAccessible = true }
                assertSame(runtimes.constructed().single(), field.get(mode))
            }
        }
    }

    @Test fun `runtime construction failure closes the already constructed robot`() {
        mockConstruction(AresRobot::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { robot, _ ->
            `when`(robot.base.store.state).thenReturn(RobotState())
        }.use { robots ->
            mockConstruction(FtcGeneratedProjectRuntime::class.java) { _, _ ->
                throw IllegalStateException("generated runtime failed")
            }.use {
                val mode = mode()
                assertNotNull(runCatching { mode.buildRobot() }.exceptionOrNull())
                val robot = robots.constructed().single()
                verify(robot).close()
                assertRuntimeReleased(mode)
            }
        }
    }

    @Test fun `post construction telemetry failure retains original error and suppresses close failure`() {
        val failure = IllegalStateException("telemetry setup failed")
        val closeFailure = IllegalStateException("close failed")
        mockConstruction(AresRobot::class.java, withSettings().defaultAnswer(RETURNS_DEEP_STUBS)) { robot, _ ->
            `when`(robot.base.store.state).thenReturn(RobotState())
            doThrow(failure).`when`(robot).addTelemetry("ARES/Controls/Source", "test")
            doThrow(closeFailure).`when`(robot).close()
        }.use { robots ->
            mockConstruction(FtcGeneratedProjectRuntime::class.java) { runtime, _ ->
                `when`(runtime.controlsSource).thenReturn("test")
            }.use {
                val mode = mode()
                assertSame(failure, runCatching { mode.buildRobot() }.exceptionOrNull())
                verify(robots.constructed().single()).close()
                assertEquals(listOf(closeFailure), failure.suppressed.toList())
                assertRuntimeReleased(mode)
            }
        }
    }
}
