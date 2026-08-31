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
import org.junit.jupiter.api.Test

class FrcGeneratedControlsRuntimeTest {
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
