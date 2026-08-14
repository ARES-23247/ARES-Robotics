package com.areslib.ftc.dsl

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FtcTeleOpDslTest {
    @Test
    fun `invalid pose storage restores documented red default instead of stale alliance`() {
        assertEquals(
            com.areslib.state.Alliance.RED,
            allianceForTeleOpRestore(false, com.areslib.state.Alliance.BLUE)
        )
        assertEquals(
            com.areslib.state.Alliance.BLUE,
            allianceForTeleOpRestore(true, com.areslib.state.Alliance.BLUE)
        )
    }

    @Test
    fun `minimal definition requires explicit periodic behavior`() {
        assertThrows(IllegalArgumentException::class.java) {
            teleOp<Any> { setup { } }
        }

        assertDoesNotThrow {
            teleOp<Any> { everyLoop { } }
        }
    }

    @Test
    fun `duplicate lifecycle phases fail instead of silently replacing callbacks`() {
        assertThrows(IllegalStateException::class.java) {
            teleOp<Any> {
                everyLoop { }
                everyLoop { }
            }
        }

        assertThrows(IllegalStateException::class.java) {
            teleOp<Any> {
                controls { }
                controls { }
                everyLoop { }
            }
        }
    }

    @Test
    fun `controls held during init require release and repress after start`() {
        var presses = 0
        var whilePressed = 0
        val opMode = object : FtcTeleOpBase<Unit>() {
            override fun define() = teleOp<Unit> {
                controls {
                    driver.leftBumper.onPress("test") { presses++ }
                    driver.leftBumper.whilePressed("test") { whilePressed++ }
                }
                everyLoop { }
            }

            override fun buildRobot() = Unit
            override fun getBaseRobot(robot: Unit) = null
            override fun updateRobot(
                robot: Unit,
                g1: com.areslib.telemetry.GamepadState,
                g2: com.areslib.telemetry.GamepadState,
            ) = Unit
            override fun closeRobot(robot: Unit) = Unit
        }

        opMode.init()
        opMode.gamepad1.left_bumper = true
        opMode.init_loop()
        opMode.init_loop()
        assertEquals(0, presses)
        assertEquals(0, whilePressed)

        opMode.start()
        opMode.loop()
        assertEquals(0, presses)
        assertEquals(0, whilePressed)

        opMode.gamepad1.left_bumper = false
        opMode.loop()
        opMode.gamepad1.left_bumper = true
        opMode.loop()
        assertEquals(1, presses)
        assertTrue(whilePressed > 0)
    }

    @Test
    fun `generated project controls run only while active and cancel before close`() {
        val events = mutableListOf<String>()
        val opMode = object : FtcTeleOpBase<Unit>() {
            override fun define() = teleOp<Unit> {
                everyLoop { events += "student-callback" }
            }

            override fun buildRobot() = Unit
            override fun getBaseRobot(robot: Unit) = null
            override fun updateRobot(
                robot: Unit,
                g1: com.areslib.telemetry.GamepadState,
                g2: com.areslib.telemetry.GamepadState,
            ) {
                events += "robot-update"
            }

            override fun updateProjectControls(
                robot: Unit,
                g1: com.areslib.telemetry.GamepadState,
                g2: com.areslib.telemetry.GamepadState,
            ) {
                events += "generated-controls"
            }

            override fun cancelProjectControls(robot: Unit) {
                events += "cancel-controls"
            }

            override fun closeRobot(robot: Unit) {
                events += "close-robot"
            }
        }

        opMode.init()
        opMode.init_loop()
        assertEquals(listOf("robot-update"), events)

        events.clear()
        opMode.start()
        opMode.loop()
        assertEquals(listOf("student-callback", "generated-controls", "robot-update"), events)

        events.clear()
        opMode.stop()
        assertEquals(listOf("cancel-controls", "close-robot"), events)
    }
}
