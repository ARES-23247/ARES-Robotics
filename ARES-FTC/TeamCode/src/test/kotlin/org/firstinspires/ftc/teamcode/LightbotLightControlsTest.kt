package org.firstinspires.ftc.teamcode

import com.areslib.Store
import com.areslib.ftc.FtcMecanumRobot
import com.areslib.ftc.input.FtcInputFrameAdapter
import com.areslib.input.InputFrame
import com.areslib.state.RobotState
import com.areslib.util.RobotClock
import com.qualcomm.robotcore.hardware.Gamepad
import org.firstinspires.ftc.teamcode.dsl.FtcGeneratedProjectRuntime
import org.firstinspires.ftc.teamcode.opmodes.AresRobot
import org.firstinspires.ftc.teamcode.subsystems.indicator_lights.IndicatorLightsSubsystem
import org.firstinspires.ftc.teamcode.subsystems.indicator_lights.MockIndicatorLightsIO
import org.junit.Test
import org.junit.Assert.assertEquals
import org.mockito.Mockito

/** Real generated control scheme -> action scheduler -> Redux -> generated light IO. */
class LightbotLightControlsTest {
    @Test fun `D-pad changes each indicator independently once per press and stops safely`() {
        RobotClock.useMockTime(1000L)
        val store = Store(RobotState())
        val base = Mockito.mock(FtcMecanumRobot::class.java)
        val robot = Mockito.mock(AresRobot::class.java)
        Mockito.`when`(base.store).thenReturn(store)
        Mockito.`when`(robot.base).thenReturn(base)
        val runtime = FtcGeneratedProjectRuntime(robot)
        val io = MockIndicatorLightsIO()
        val lights = IndicatorLightsSubsystem(io)
        val gamepad = Mockito.mock(Gamepad::class.java)
        val adapter = FtcInputFrameAdapter(gamepad)
        val driver = InputFrame()
        val operator = InputFrame()
        fun sample() {
            RobotClock.useMockTime(RobotClock.currentTimeMillis() + 20L)
            io.refresh()
            lights.readSensors(store, RobotClock.currentTimeMillis())
            adapter.sampleInto(driver)
            runtime.updateControls(driver, operator, RobotClock.nanoTime(), emitDriveCommand = false)
            lights.writeOutputs(store.state, 1.0)
        }
        fun assertColors(left: Double, right: Double) {
            assertEquals(left, IndicatorLightsSubsystem.state(store.state).leftColor, 1e-9)
            assertEquals(right, IndicatorLightsSubsystem.state(store.state).rightColor, 1e-9)
            assertEquals(left, io.leftIndicatorCommand, 1e-9)
            assertEquals(right, io.rightIndicatorCommand, 1e-9)
        }
        try {
            sample() // Neutral connected frame arms the bindings.
            assertColors(0.472, 0.611) // Green / blue defaults.
            gamepad.dpad_right = true
            sample()
            assertColors(0.511, 0.611) // Left advances to cyan; right stays blue.
            repeat(15) { sample() }
            assertColors(0.511, 0.611) // Holding must not race through the colors.
            gamepad.dpad_right = false
            sample()
            gamepad.dpad_right = true
            sample()
            assertColors(0.611, 0.611)
            gamepad.dpad_right = false
            sample()
            gamepad.dpad_left = true
            sample()
            assertColors(0.511, 0.611)
            gamepad.dpad_left = false
            sample()
            gamepad.dpad_up = true
            sample()
            assertColors(0.511, 0.722) // Right advances to purple; left stays cyan.
            gamepad.dpad_up = false
            sample()
            gamepad.dpad_down = true
            sample()
            assertColors(0.511, 0.611)
            runtime.cancelAll("Stopped")
            lights.writeOutputs(store.state, 0.0)
            assertEquals(0.0, io.leftIndicatorCommand, 0.0)
            assertEquals(0.0, io.rightIndicatorCommand, 0.0)
        } finally {
            runtime.cancelAll("Test complete")
            lights.close()
            RobotClock.useSystemTime()
        }
    }
}
