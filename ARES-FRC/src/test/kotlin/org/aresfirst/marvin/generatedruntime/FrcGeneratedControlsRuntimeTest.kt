package org.aresfirst.marvin.generatedruntime

import com.areslib.action.RobotAction
import org.aresfirst.marvin.generated.GeneratedAresProjectCapabilities
import org.aresfirst.marvin.generated.GeneratedAresProject
import com.areslib.frc.runtime.FrcControllerPortSampler
import com.areslib.frc.runtime.FrcGeneratedProjectControlsRuntime
import org.aresfirst.marvin.robot.FrcAutoCapabilities
import com.areslib.input.InputFrame
import com.areslib.state.RobotState
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class FrcGeneratedControlsRuntimeTest {
    @Test
    fun `installed bindings preserve axis signs deadband and repeated commands and neutralize disconnect`() {
        var connected = true
        val axes = DoubleArray(6)
        val commands = mutableListOf<DoubleArray>()
        val sampler = object : FrcControllerPortSampler {
            override fun prepare(port: Int) { assertEquals(0, port) }
            override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
                frame.beginSample(connected, 6, 8, nowNanos)
                if (connected) axes.forEachIndexed { index, value -> frame.setAxis(index, value) }
            }
        }
        val capabilities = object : GeneratedAresProjectCapabilities by FrcAutoCapabilities {
            override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
                commands.add(doubleArrayOf(vx, vy, omega))
            }
        }
        val state = RobotState()
        val runtime = FrcGeneratedProjectControlsRuntime(
            definition = GeneratedAresProject.runtimeDefinition,
            stateProvider = { state },
            dispatch = { error("Axis bindings must not dispatch mechanism actions") },
            capabilities = capabilities,
            portSampler = sampler,
        )
        runtime.update() // Neutral sample arms analog bindings.
        axes[1] = -1.0
        axes[0] = 0.55
        axes[4] = -0.55
        repeat(2) {
            runtime.update()
            assertArrayEquals(doubleArrayOf(1.0, -0.5, 0.5), commands.last(), 1e-12)
        }
        axes.fill(0.1)
        runtime.update()
        assertArrayEquals(DoubleArray(3), commands.last(), 1e-12)
        axes.fill(-1.0)
        runtime.update()
        connected = false
        runtime.update()
        assertArrayEquals(DoubleArray(3), commands.last(), 1e-12)
        assertEquals(6, commands.size, "Unchanged inputs must still emit every enabled frame")
        runtime.cancelAll("test complete")
    }

    @Test
    fun `installed scheme prepares its port, emits drive each frame, and never dispatches without input`() {
        val dispatched = mutableListOf<RobotAction>()
        var preparedPort = -1
        var sampledFrames = 0
        var driveEmissions = 0
        val sampler = object : FrcControllerPortSampler {
            override fun prepare(port: Int) {
                preparedPort = port
            }
            override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
                sampledFrames++
            }
        }
        val capabilities = object : GeneratedAresProjectCapabilities by FrcAutoCapabilities {
            override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
                driveEmissions++
                assertEquals(true, active, "the checked-in scheme binds drivetrain axes")
            }
        }
        val runtime = FrcGeneratedProjectControlsRuntime(
            definition = GeneratedAresProject.runtimeDefinition,
            stateProvider = { RobotState() },
            dispatch = dispatched::add,
            capabilities = capabilities,
            portSampler = sampler,
        )

        assertEquals(1, runtime.activeControllerPortCount, "the checked-in scheme installs one driver port")
        assertEquals(0, preparedPort)
        assertTrue(runtime.controlsSource.startsWith("generated:driver:"), runtime.controlsSource)

        assertDoesNotThrow { runtime.update() }
        assertDoesNotThrow { runtime.cancelAll("test transition") }
        assertEquals(1, sampledFrames)
        assertEquals(1, driveEmissions)
        assertEquals(emptyList<RobotAction>(), dispatched)
    }
}
