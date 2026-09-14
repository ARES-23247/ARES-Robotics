package org.aresfirst.starter.frc

import com.areslib.input.InputFrame
import com.areslib.frc.runtime.FrcControllerPortSampler
import com.areslib.util.RobotClock
import edu.wpi.first.hal.HAL
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterBridgeLeaseAuditTest {
    private fun frame(sequence: Double, vx: Double = 0.0) =
        doubleArrayOf(2.0, 7.0, sequence, sequence, vx, 0.0, 0.0, 24.0)

    private inline fun withInstance(block: (NetworkTableInstance) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        val wasMocked = RobotClock.isMocked
        val previousMs = RobotClock.currentTimeMillis()
        try {
            RobotClock.useMockTime(1000L)
            NetworkTableInstance.create().use(block)
        } finally {
            DriverStationSim.resetData()
            DriverStationSim.notifyNewData()
            if (wasMocked) RobotClock.useMockTime(previousMs) else RobotClock.useSystemTime()
        }
    }

    @Test fun `controller sampling expires commands even when the bridge update stops`() = withInstance { instance ->
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), 1001L))
        val bridge = FrcStudioSimulationBridge(instance = instance, gate = gate)
        try {
            instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
                mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                bridge.update(1001L)
                assertTrue(DriverStationSim.getEnabled())
                RobotClock.useMockTime(1300L)
                val controller = InputFrame()
                // The sampler timestamp has a different origin from the millisecond lease clock.
                bridge.sampleInto(0, controller, -77L)
                assertTrue(controller.isConnected)
                RobotClock.useMockTime(1502L)
                bridge.sampleInto(0, controller, 99L)
                assertFalse(controller.isConnected)
                assertEquals(0.0, controller.axis(1))
                assertFalse(DriverStationSim.getEnabled())
            }
        } finally { bridge.close() }
    }

    @Test fun `fresh queued motion cannot revive a lease that expired between updates`() = withInstance { instance ->
        val gate = FrcStudioDriveFrameGate()
        assertTrue(gate.accept(frame(0.0), 1000L))
        assertTrue(gate.accept(frame(1.0, vx = 2.0), 1020L))
        val bridge = FrcStudioSimulationBridge(instance = instance, gate = gate)
        try {
            instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
                instance.getDoubleArrayTopic("ARES/Input/driveFrame").publish().use { drive ->
                    mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                    bridge.update(1020L)
                    assertTrue(DriverStationSim.getEnabled())
                    drive.set(frame(2.0, vx = 3.0))
                    bridge.update(1600L)
                    assertFalse(DriverStationSim.getEnabled())
                    drive.set(frame(3.0))
                    bridge.update(1601L)
                    assertTrue(DriverStationSim.getEnabled())
                    assertEquals(FrcStudioSimulationBridge.DRIVER_STATION_TELEOP_ENABLED,
                        instance.getEntry(FrcStudioSimulationBridge.DRIVER_STATION_STATE_TOPIC).getString(""))
                }
            }
        } finally { bridge.close() }
    }

    @Test fun `old queued neutral and motion frames cannot receive a brand new lease`() = withInstance { instance ->
        // Synthetic transport clock keeps old timestamps positive even immediately after native startup.
        val nowMicros = 1_000_000L
        val bridge = FrcStudioSimulationBridge(instance = instance, transportTimeMicros = { nowMicros })
        try {
            instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
                instance.getDoubleArrayTopic("ARES/Input/driveFrame").publish().use { drive ->
                    mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                    drive.set(frame(0.0), nowMicros - 700_000L)
                    drive.set(frame(1.0, vx = 2.0), nowMicros - 600_000L)
                    bridge.update(1000L)
                    assertFalse(DriverStationSim.getEnabled())
                    assertEquals(-1.0, instance.getEntry("ARES/Control/DriveInputAck").getDoubleArray(doubleArrayOf())[2])
                }
            }
        } finally { bridge.close() }
    }

    @Test fun `queue age shortens the remaining receiver lease instead of resetting it`() = withInstance { instance ->
        // Synthetic transport clock keeps old timestamps positive even immediately after native startup.
        val nowMicros = 1_000_000L
        val bridge = FrcStudioSimulationBridge(instance = instance, transportTimeMicros = { nowMicros })
        try {
            instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
                instance.getDoubleArrayTopic("ARES/Input/driveFrame").publish().use { drive ->
                    mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                    drive.set(frame(0.0), nowMicros - 400_000L)
                    drive.set(frame(1.0, vx = 2.0), nowMicros - 390_000L)
                    bridge.update(1000L)
                    assertTrue(DriverStationSim.getEnabled())
                    val ack = instance.getEntry("ARES/Control/DriveInputAck").getDoubleArray(doubleArrayOf())
                    assertEquals(390.0, ack[4])
                    RobotClock.useMockTime(1111L)
                    val controller = InputFrame()
                    bridge.sampleInto(0, controller, 123L)
                    assertFalse(controller.isConnected)
                    assertFalse(DriverStationSim.getEnabled())
                }
            }
        } finally { bridge.close() }
    }

    @Test fun `mode transitions preserve autonomous independence and fallback controller ownership`() = withInstance { instance ->
        var prepared = -1
        var samples = 0
        val fallback = object : FrcControllerPortSampler {
            override fun prepare(port: Int) { prepared = port }
            override fun sampleInto(port: Int, frame: InputFrame, nowNanos: Long) {
                samples++
                frame.beginSample(connected = true, reportedAxisCount = 1, sampleTimeNanos = nowNanos)
                frame.setAxis(0, 0.25)
            }
        }
        val bridge = FrcStudioSimulationBridge(instance = instance, fallbackSampler = fallback)
        try {
            bridge.prepare(1)
            assertEquals(1, prepared)
            val controller = InputFrame()
            bridge.update(1000L)
            assertFalse(DriverStationSim.getEnabled())
            bridge.sampleInto(0, controller, 1L)
            assertEquals(1, samples)
            instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
                mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_AUTONOMOUS)
                bridge.update(1001L)
                assertTrue(DriverStationSim.getEnabled())
                assertTrue(DriverStationSim.getAutonomous())
                bridge.sampleInto(0, controller, 2L)
                assertEquals(2, samples)
                mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
                bridge.update(1002L)
                assertFalse(DriverStationSim.getEnabled())
                assertFalse(DriverStationSim.getAutonomous())
                bridge.sampleInto(0, controller, 3L)
                assertFalse(controller.isConnected)
                assertEquals(2, samples)
                bridge.sampleInto(1, controller, 4L)
                assertTrue(controller.isConnected)
                assertEquals(3, samples)
                mode.set("invalid mode")
                bridge.update(1003L)
                assertFalse(DriverStationSim.getEnabled())
                assertEquals(FrcStudioSimulationBridge.DRIVER_STATION_DISABLED,
                    instance.getEntry(FrcStudioSimulationBridge.DRIVER_STATION_STATE_TOPIC).getString(""))
            }
            bridge.close()
            assertThrows(IllegalStateException::class.java) { bridge.update(1004L) }
        } finally { bridge.close() }
    }

    @Test fun `teleop sampling requires both canonical mode flags and a live Driver Station enable`() = withInstance { instance ->
        instance.getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC).publish().use { mode ->
            mode.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_TELEOP)
            for (flags in listOf(0.0, 8.0, 16.0, 24.0)) {
                val gate = FrcStudioDriveFrameGate()
                assertTrue(gate.accept(frame(0.0).also { it[7] = flags }, 1000L))
                val bridge = FrcStudioSimulationBridge(instance = instance, gate = gate)
                try {
                    bridge.update(1000L)
                    assertEquals(flags == 24.0, DriverStationSim.getEnabled())
                    val controller = InputFrame()
                    bridge.sampleInto(0, controller, 100L)
                    assertEquals(flags == 24.0, controller.isConnected)
                    DriverStationSim.setEnabled(false)
                    DriverStationSim.notifyNewData()
                    bridge.sampleInto(0, controller, 101L)
                    assertFalse(controller.isConnected)
                } finally { bridge.close() }
            }
        }
    }
}
