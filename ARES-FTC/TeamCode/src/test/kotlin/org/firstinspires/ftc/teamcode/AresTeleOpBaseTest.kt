package org.firstinspires.ftc.teamcode

import com.qualcomm.robotcore.hardware.HardwareMap
import com.qualcomm.robotcore.hardware.DcMotorEx
import com.qualcomm.robotcore.hardware.VoltageSensor
import com.qualcomm.hardware.gobilda.GoBildaPinpointDriver
import com.qualcomm.hardware.limelightvision.Limelight3A
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.teamcode.dsl.AresTeleOpBase
import org.firstinspires.ftc.teamcode.dsl.AresAutoBase
import org.firstinspires.ftc.teamcode.opmodes.ARESMecanumDiagnostic
import com.areslib.ftc.dsl.FtcTeleOpBuilder
import com.areslib.ftc.photon.AresFtcRuntimeOptionsProvider
import com.areslib.ftc.toState
import com.areslib.ftc.update
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

import com.areslib.telemetry.GamepadState
import com.areslib.telemetry.RobotStatusTracker

/**
 * Integration tests for the generic TeleOp lifecycle and FTC gamepad snapshot adapter.
 * The lifecycle test controls SDK-private start flags because the desktop mock has no Driver Station.
 */
class AresTeleOpBaseTest {
    @Test
    fun `team OpModes read generated runtime policy during pre-init`() {
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(AresTeleOpBase::class.java))
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(AresAutoBase::class.java))
        assertTrue(AresFtcRuntimeOptionsProvider::class.java.isAssignableFrom(ARESMecanumDiagnostic::class.java))
    }

    @Test
    fun testAresTeleOpBaseLifecycle() {
        com.areslib.telemetry.RobotStatusTracker.isEnabled = false
        val fl = Mockito.mock(DcMotorEx::class.java)
        val fr = Mockito.mock(DcMotorEx::class.java)
        val bl = Mockito.mock(DcMotorEx::class.java)
        val br = Mockito.mock(DcMotorEx::class.java)
        val pinpoint = Mockito.mock(GoBildaPinpointDriver::class.java)
        val limelight = Mockito.mock(Limelight3A::class.java)
        val voltageSensor = Mockito.mock(VoltageSensor::class.java)
        Mockito.`when`(voltageSensor.voltage).thenReturn(12.5)

        val mockHardwareMap = Mockito.mock(HardwareMap::class.java)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "fl")).thenReturn(fl)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "fr")).thenReturn(fr)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "rl")).thenReturn(bl)
        Mockito.`when`(mockHardwareMap.get(DcMotorEx::class.java, "rr")).thenReturn(br)
        Mockito.`when`(mockHardwareMap.get(GoBildaPinpointDriver::class.java, "pinpoint")).thenReturn(pinpoint)
        Mockito.`when`(mockHardwareMap.get(Limelight3A::class.java, "limelight")).thenReturn(limelight)
        
        @Suppress("UNCHECKED_CAST")
        Mockito.`when`(mockHardwareMap.getAll(VoltageSensor::class.java)).thenReturn(listOf(voltageSensor))
        val mockTelemetry = Mockito.mock(Telemetry::class.java)

        val opMode = object : AresTeleOpBase() {
            init {
                this.hardwareMap = mockHardwareMap
                this.telemetry = mockTelemetry
            }

            override fun define(): FtcTeleOpBuilder<org.firstinspires.ftc.teamcode.opmodes.AresRobot> {
                return teleOp {
                    setup { }
                    everyLoop { }
                }
            }

        }

        // Configure gamepad inputs to trigger reset branch coverage (gamepad1.y = true)
        val gamepad = Gamepad()
        gamepad.y = true
        opMode.gamepad1 = gamepad
        opMode.gamepad2 = Gamepad()

        try {
            opMode.init()
            opMode.init_loop()
            assertFalse(RobotStatusTracker.isEnabled)
            opMode.start()
            opMode.loop()
        } finally {
            opMode.stop()
            com.areslib.networktables.NT4Instance.defaultInstance.closeServer()
        }
    }
    @Test
    fun testGamepadExtensionCoverage() {
        val gamepad = Gamepad()
        gamepad.left_stick_x = 0.1f
        gamepad.left_stick_y = 0.2f
        gamepad.right_stick_x = 0.3f
        gamepad.right_stick_y = 0.4f
        gamepad.left_trigger = 0.5f
        gamepad.right_trigger = 0.6f
        gamepad.a = true
        gamepad.b = false
        gamepad.x = true
        gamepad.y = false
        gamepad.dpad_up = true
        gamepad.dpad_down = false
        gamepad.dpad_left = true
        gamepad.dpad_right = false
        gamepad.left_bumper = true
        gamepad.right_bumper = false
        gamepad.left_stick_button = true
        gamepad.right_stick_button = false
        gamepad.start = true
        gamepad.back = false
        val state = gamepad.toState()
        assertEquals(0.1f, state.leftStickX, 1e-4f)
        assertEquals(0.2f, state.leftStickY, 1e-4f)
        assertEquals(0.3f, state.rightStickX, 1e-4f)
        assertEquals(0.4f, state.rightStickY, 1e-4f)
        assertEquals(0.5f, state.leftTrigger, 1e-4f)
        assertEquals(0.6f, state.rightTrigger, 1e-4f)
        assertTrue(state.a)
        assertFalse(state.b)
        assertTrue(state.x)
        assertFalse(state.y)
        assertTrue(state.dpadUp)
        assertFalse(state.dpadDown)
        assertTrue(state.dpadLeft)
        assertFalse(state.dpadRight)
        assertTrue(state.leftBumper)
        assertFalse(state.rightBumper)
        assertTrue(state.leftStickButton)
        assertFalse(state.rightStickButton)
        assertTrue(state.start)
        assertFalse(state.back)
        val state2 = com.areslib.telemetry.GamepadState()
        state2.update(gamepad)
        assertEquals(0.1f, state2.leftStickX, 1e-4f)
        assertEquals(0.2f, state2.leftStickY, 1e-4f)
        assertEquals(0.3f, state2.rightStickX, 1e-4f)
        assertEquals(0.4f, state2.rightStickY, 1e-4f)
        assertEquals(0.5f, state2.leftTrigger, 1e-4f)
        assertEquals(0.6f, state2.rightTrigger, 1e-4f)
        assertTrue(state2.a)
        assertFalse(state2.b)
        assertTrue(state2.x)
        assertFalse(state2.y)
        assertTrue(state2.dpadUp)
        assertFalse(state2.dpadDown)
        assertTrue(state2.dpadLeft)
        assertFalse(state2.dpadRight)
        assertTrue(state2.leftBumper)
        assertFalse(state2.rightBumper)
        assertTrue(state2.leftStickButton)
        assertFalse(state2.rightStickButton)
        assertTrue(state2.start)
        assertFalse(state2.back)
    }
}
