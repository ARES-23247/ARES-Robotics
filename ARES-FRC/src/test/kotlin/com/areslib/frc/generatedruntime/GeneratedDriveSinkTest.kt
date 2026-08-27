package com.areslib.frc.generatedruntime

import com.areslib.frc.robot.FrcAutoCapabilities
import com.areslib.state.Alliance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Semantics of the generated teleop drive sink that the production wiring now activates:
 * season-limit scaling, RED translation mirroring, non-finite fail-closed bounding, and the
 * assist-authority emission gate.
 */
class GeneratedDriveSinkTest {
    @Test
    fun `blue keeps translation and scales to season limits`() {
        val out = DoubleArray(3)
        generatedSwerveTeleopCommand(0.5, -1.0, 0.25, Alliance.BLUE, out)
        assertEquals(0.5 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS, out[0], 1e-9)
        assertEquals(-1.0 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS, out[1], 1e-9)
        assertEquals(0.25 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_ROTATION_RPS, out[2], 1e-9)
    }

    @Test
    fun `red mirrors translation but never rotation`() {
        val out = DoubleArray(3)
        generatedSwerveTeleopCommand(0.5, -0.5, 0.25, Alliance.RED, out)
        assertEquals(-0.5 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS, out[0], 1e-9)
        assertEquals(0.5 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_DRIVE_SPEED_MPS, out[1], 1e-9)
        assertEquals(0.25 * FrcGeneratedRoutineCapabilities.MAX_TELEOP_ROTATION_RPS, out[2], 1e-9)
    }

    @Test
    fun `non-finite and out-of-range axes fail closed to bounded zero`() {
        val out = DoubleArray(3)
        generatedSwerveTeleopCommand(Double.NaN, Double.POSITIVE_INFINITY, 7.0, Alliance.BLUE, out)
        assertEquals(0.0, out[0], 0.0)
        assertEquals(0.0, out[1], 0.0)
        assertEquals(FrcGeneratedRoutineCapabilities.MAX_TELEOP_ROTATION_RPS, out[2], 1e-9)
    }

    @Test
    fun `emission gate suppresses scheme drive while an assist owns the frame`() {
        val dispatched = mutableListOf<com.areslib.action.RobotAction>()
        var assistActive = false
        var driveEmissions = 0
        val capabilities = object : com.areslib.frc.generated.GeneratedAresProjectCapabilities by FrcAutoCapabilities {
            override fun onDriveCommand(vx: Double, vy: Double, omega: Double, active: Boolean) {
                driveEmissions++
            }
        }
        val runtime = FrcGeneratedControlsRuntime(
            stateProvider = { com.areslib.state.RobotState() },
            dispatch = dispatched::add,
            capabilities = capabilities,
            driveEmissionGate = { !assistActive },
        )

        runtime.update()
        val emissionsWhileAssistIdle = driveEmissions

        assistActive = true
        runtime.update()
        val emissionsWhileAssistActive = driveEmissions

        assistActive = false
        runtime.update()
        val emissionsAfterAssistReleases = driveEmissions

        assertEquals(1, emissionsWhileAssistIdle)
        assertEquals(1, emissionsWhileAssistActive, "assist-owned frames must not emit scheme drive")
        assertEquals(2, emissionsAfterAssistReleases, "scheme drive resumes when the assist releases")
        assertTrue(dispatched.isEmpty())
        assertFalse(runtime.controlsSource.isBlank())
    }
}
