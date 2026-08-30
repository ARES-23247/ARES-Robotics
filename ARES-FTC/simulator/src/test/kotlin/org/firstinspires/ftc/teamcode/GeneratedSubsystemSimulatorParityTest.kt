package org.firstinspires.ftc.teamcode

import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.hardware.HardwareRegistry
import com.qualcomm.robotcore.hardware.HardwareMap
import org.firstinspires.ftc.teamcode.opmodes.installGeneratedSubsystems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class GeneratedSubsystemSimulatorParityTest {
    @Test
    fun `desktop hardware map installs the same generated instances into lifecycle ownership`() {
        val hardwareMap = HardwareMap()
        val subsystem = RecordingSubsystem()
        val registered = mutableListOf<Subsystem>()
        val hardwareRegistry = HardwareRegistry()

        installGeneratedSubsystems(hardwareMap, hardwareRegistry, registered::add) { receivedMap, receivedRegistry ->
            assertSame(hardwareMap, receivedMap)
            assertSame(hardwareRegistry, receivedRegistry)
            listOf(subsystem)
        }

        registered.single().readSensors(Store(), 1_000L)
        registered.single().writeOutputs(RobotState(), 0.5)
        registered.single().close()
        assertEquals(listOf("read:1000", "write:0.5", "close"), subsystem.events)
    }

    private class RecordingSubsystem : Subsystem {
        val events = mutableListOf<String>()

        override fun readSensors(store: Store, timestampMs: Long) {
            events += "read:$timestampMs"
        }

        override fun writeOutputs(state: RobotState, scale: Double) {
            events += "write:$scale"
        }

        override fun close() {
            events += "close"
        }
    }
}
