package org.firstinspires.ftc.teamcode

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.teamcode.opmodes.installGeneratedSubsystems
import org.firstinspires.ftc.teamcode.opmodes.installGeneratedSuperstructures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GeneratedSubsystemInstallationTest {
    @Test
    fun `generated subsystems are registered in deterministic factory order`() {
        val hardwareMap = org.mockito.Mockito.mock(HardwareMap::class.java)
        val first = RecordingSubsystem()
        val second = RecordingSubsystem()
        val registered = mutableListOf<Subsystem>()
        val hardwareRegistry = HardwareRegistry()

        val installed = installGeneratedSubsystems(hardwareMap, hardwareRegistry, registered::add) { receivedMap, receivedRegistry ->
            assertSame(hardwareMap, receivedMap)
            assertSame(hardwareRegistry, receivedRegistry)
            listOf(first, second)
        }

        assertEquals(listOf(first, second), installed)
        assertEquals(listOf(first, second), registered)
    }

    @Test
    fun `required generated factory failure is never converted into an optional subsystem`() {
        val hardwareMap = org.mockito.Mockito.mock(HardwareMap::class.java)
        val registered = mutableListOf<Subsystem>()
        val hardwareRegistry = HardwareRegistry()
        val failure = IllegalStateException("required elevator motor is missing")

        val thrown = assertThrows(IllegalStateException::class.java) {
            installGeneratedSubsystems(hardwareMap, hardwareRegistry, registered::add) { _, _ -> throw failure }
        }

        assertSame(failure, thrown)
        assertEquals(emptyList<Subsystem>(), registered)
    }

    @Test
    fun `generated superstructures register after their factories complete`() {
        val first = RecordingSubsystem()
        val second = RecordingSubsystem()
        val registered = mutableListOf<Subsystem>()

        val installed = installGeneratedSuperstructures(registered::add) { listOf(first, second) }

        assertEquals(listOf(first, second), installed)
        assertEquals(listOf(first, second), registered)
    }

    private class RecordingSubsystem : Subsystem {
        override fun readSensors(store: Store, timestampMs: Long) = Unit
        override fun writeOutputs(state: RobotState, scale: Double) = Unit
    }
}
