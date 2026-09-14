package org.aresfirst.starter.frc

import com.areslib.state.RobotFieldManager
import com.areslib.action.RobotAction
import com.areslib.Store
import com.areslib.state.RobotState
import com.areslib.subsystem.Subsystem
import com.areslib.telemetry.ITelemetry
import com.areslib.util.RobotClock
import edu.wpi.first.hal.HAL
import edu.wpi.first.networktables.NetworkTableInstance
import edu.wpi.first.wpilibj.simulation.DriverStationSim
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterRobotLifecycleAuditTest {
    private class Telemetry : ITelemetry {
        lateinit var runtime: StarterRobotRuntime
        var factoryAttempts = 0
        var closes = 0
        var failureTopic: String? = null
        val failure = IllegalStateException("late startup publication failed")
        val values = HashMap<String, Any>()
        override fun putNumber(key: String, value: Double) { values[key] = value }
        override fun putBoolean(key: String, value: Boolean) { values[key] = value }
        override fun putString(key: String, value: String) {
            if (key == failureTopic) throw failure
            values[key] = value
        }
        override fun putDoubleArray(key: String, value: DoubleArray) { values[key] = value.copyOf() }
        override fun getNumber(key: String, defaultValue: Double) = values[key] as? Double ?: defaultValue
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String) = values[key] as? String ?: defaultValue
        override fun close() { closes++ }
    }

    private inline fun withRobot(factoryFailure: Throwable? = null, block: (AresStarterRobot, Telemetry) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        DriverStationSim.resetData()
        val instance = NetworkTableInstance.getDefault()
        // This test JVM owns its default instance. Disable both listening protocols before RobotBase starts.
        instance.startServer("", "127.0.0.1", 0, 0)
        val previousField = RobotFieldManager.activeConfig
        val wasMocked = RobotClock.isMocked
        val previousMs = RobotClock.currentTimeMillis()
        RobotClock.useMockTime(1000L)
        val telemetry = Telemetry()
        val created = ArrayList<StarterRobotRuntime>()
        val robot = AresStarterRobot {
            telemetry.factoryAttempts++
            factoryFailure?.let { throw it }
            StarterRobotRuntime(telemetry = telemetry).also { telemetry.runtime = it; created += it }
        }
        try {
            assertTrue(instance.networkMode.contains(NetworkTableInstance.NetworkMode.kServer))
            block(robot, telemetry)
        } finally {
            telemetry.failureTopic = null
            try { robot.close() } finally {
                try { closeStarterResources(created.map { AutoCloseable { it.close() } }) } finally {
                    instance.stopServer()
                    RobotFieldManager.setActiveConfig(previousField)
                    DriverStationSim.resetData()
                    DriverStationSim.notifyNewData()
                    if (wasMocked) RobotClock.useMockTime(previousMs) else RobotClock.useSystemTime()
                }
            }
        }
    }

    @Test fun `native robot can initialize and close without starting a competition loop`() = withRobot { robot, telemetry ->
        robot.robotInit()
        assertEquals("Idle", telemetry.getString("ARES/Auto/Status", ""))
        robot.close()
        assertEquals(1, telemetry.closes)
        assertDoesNotThrow { robot.close() }
    }

    @Test fun `late startup failure closes the runtime before escaping robotInit`() = withRobot { robot, telemetry ->
        telemetry.failureTopic = "ARES/Auto/AvailableDocuments"
        assertSame(telemetry.failure, assertThrows(IllegalStateException::class.java) { robot.robotInit() })
        assertEquals(1, telemetry.closes)
    }

    @Test fun `closed robot cannot initialize or invoke lifecycle callbacks again`() = withRobot { robot, telemetry ->
        robot.robotInit()
        robot.close()
        assertThrows(IllegalStateException::class.java) { robot.robotInit() }
        assertThrows(IllegalStateException::class.java) { robot.simulationPeriodic() }
        assertThrows(IllegalStateException::class.java) { robot.teleopInit() }
        assertEquals(1, telemetry.closes)
    }

    @Test fun `initializing twice cannot replace an owned runtime`() = withRobot { robot, telemetry ->
        robot.robotInit()
        assertThrows(IllegalStateException::class.java) { robot.robotInit() }
        robot.close()
        assertEquals(1, telemetry.closes)
    }

    @Test fun `every lifecycle callback rejects use before initialization and after close`() = withRobot { robot, telemetry ->
        val callbacks = listOf(robot::robotPeriodic, robot::teleopInit, robot::teleopPeriodic,
            robot::autonomousInit, robot::autonomousPeriodic, robot::disabledInit, robot::testInit,
            robot::simulationInit, robot::simulationPeriodic)
        callbacks.forEach { assertThrows(IllegalStateException::class.java) { it() } }
        robot.robotInit()
        robot.close()
        callbacks.forEach { assertThrows(IllegalStateException::class.java) { it() } }
        assertEquals(1, telemetry.closes)
    }

    @Test fun `mode callback publication failure closes all ownership and becomes terminal`() = withRobot { robot, telemetry ->
        robot.robotInit()
        telemetry.failureTopic = "ARES/Auto/Status"
        assertSame(telemetry.failure, assertThrows(IllegalStateException::class.java) { robot.teleopInit() })
        assertEquals(1, telemetry.closes)
        assertEquals(0, telemetry.failure.suppressed.size)
        assertThrows(IllegalStateException::class.java) { robot.teleopPeriodic() }
        assertThrows(IllegalStateException::class.java) { telemetry.runtime.update() }
    }

    @Test fun `periodic publication failure closes the whole robot`() = withRobot { robot, telemetry ->
        robot.robotInit()
        telemetry.failureTopic = "SysId/SupportedMechanisms"
        assertSame(telemetry.failure, assertThrows(IllegalStateException::class.java) { robot.robotPeriodic() })
        assertEquals(1, telemetry.closes)
        assertThrows(IllegalStateException::class.java) { robot.simulationPeriodic() }
    }

    @Test fun `ordinary mode transitions preserve one runtime and neutral drive intent`() = withRobot { robot, telemetry ->
        robot.robotInit()
        robot.simulationInit()
        robot.robotPeriodic()
        robot.teleopInit()
        robot.teleopPeriodic()
        robot.disabledInit()
        robot.testInit()
        robot.autonomousPeriodic() // No selection started; must remain inert.
        assertEquals(0.0, telemetry.runtime.store.state.drive.xVelocityMetersPerSecond)
        assertEquals(0.0, telemetry.runtime.store.state.drive.yVelocityMetersPerSecond)
        assertEquals(0.0, telemetry.runtime.store.state.drive.angularVelocityRadiansPerSecond)
        assertEquals(0, telemetry.closes)
    }

    @Test fun `simulation preserves a twenty millisecond interval at a large clock origin`() = withRobot { robot, telemetry ->
        robot.robotInit()
        withEnabledSimulation {
            val origin = 1L shl 60
            RobotClock.useMockTime(origin)
            robot.simulationInit()
            telemetry.runtime.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.0, 0.0, isFieldCentric = true))
            robot.simulationPeriodic()
            val before = telemetry.getNumber("ARES/TruePose/0", -1.0)
            RobotClock.useMockTime(origin + 20L)
            robot.simulationPeriodic()
            assertEquals(0.02, telemetry.getNumber("ARES/TruePose/0", -1.0) - before, 1e-9)
        }
    }

    private inline fun withEnabledSimulation(block: () -> Unit) {
        NetworkTableInstance.getDefault().getStringTopic(FrcStudioSimulationBridge.DRIVER_STATION_COMMAND_TOPIC)
            .publish().use { publisher ->
                publisher.set(FrcStudioSimulationBridge.DRIVER_STATION_ENABLE_AUTONOMOUS)
                try { block() } finally { publisher.set(FrcStudioSimulationBridge.DRIVER_STATION_DISABLE) }
            }
    }

    @Test fun `disabled simulation cannot integrate a previously enabled drive intent`() = withRobot { robot, telemetry ->
        robot.robotInit()
        robot.simulationInit()
        robot.simulationPeriodic()
        val before = telemetry.getNumber("ARES/TruePose/0", -1.0)
        telemetry.runtime.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2, isFieldCentric = true))
        RobotClock.useMockTime(1020L)
        robot.simulationPeriodic()
        assertEquals(before, telemetry.getNumber("ARES/TruePose/0", -1.0))
        assertEquals(0.0, telemetry.runtime.store.state.drive.xVelocityMetersPerSecond)
    }

    @Test fun `disable clears Redux drive intent before the next simulation tick`() = withRobot { robot, telemetry ->
        robot.robotInit()
        telemetry.runtime.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.5, 0.2, isFieldCentric = true))
        robot.disabledInit()
        val drive = telemetry.runtime.store.state.drive
        assertEquals(0.0, drive.xVelocityMetersPerSecond)
        assertEquals(0.0, drive.yVelocityMetersPerSecond)
        assertEquals(0.0, drive.angularVelocityRadiansPerSecond)
    }

    @Test fun `simulation bounds stalls and rejects missing repeated rewound and overflowing intervals`() = withRobot { robot, telemetry ->
        robot.robotInit()
        withEnabledSimulation {
            telemetry.runtime.store.dispatch(RobotAction.JoystickDriveIntent(1.0, 0.0, 0.0, isFieldCentric = true))
            fun sample(time: Long): Double {
                RobotClock.useMockTime(time)
                robot.simulationPeriodic()
                return telemetry.getNumber("ARES/TruePose/0", -1.0)
            }
            val initial = sample(0L) // Missing simulationInit must not integrate an invented frame.
            assertEquals(initial + 0.02, sample(20L), 1e-9)
            assertEquals(initial + 0.02, sample(20L), 1e-9)
            assertEquals(initial + 0.02, sample(10L), 1e-9)
            assertEquals(initial + 0.07, sample(1010L), 1e-9) // One-second stall remains bounded.
            assertEquals(initial + 0.07, sample(Long.MIN_VALUE + 100), 1e-9)
            assertEquals(initial + 0.07, sample(Long.MAX_VALUE - 100), 1e-9) // Subtraction overflows.
            assertEquals(initial + 0.07, sample(Long.MIN_VALUE + 100), 1e-9) // Signed clock wrap.
            assertEquals(initial + 0.09, sample(Long.MIN_VALUE + 120), 1e-9)
        }
    }

    @Test fun `runtime factory failure makes startup terminal without claiming untransferred telemetry`() {
        val failure = IllegalStateException("runtime construction failed")
        withRobot(failure) { robot, telemetry ->
            assertSame(failure, assertThrows(IllegalStateException::class.java) { robot.robotInit() })
            assertThrows(IllegalStateException::class.java) { robot.robotInit() }
            assertEquals(1, telemetry.factoryAttempts)
            assertEquals(0, telemetry.closes)
        }
    }

    @Test fun `robot close preserves primary failure while closing mechanisms and disabling the bridge`() = withRobot { robot, telemetry ->
        robot.robotInit()
        val cleanup = IllegalStateException("mechanism close failed")
        var neutralWrites = 0
        var closes = 0
        telemetry.runtime.registerSubsystem(object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) = Unit
            override fun writeOutputs(state: RobotState, scale: Double) { assertEquals(0.0, scale); neutralWrites++ }
            override fun close() { closes++; throw cleanup }
        })
        telemetry.failureTopic = "ARES/Auto/Status"
        assertSame(telemetry.failure, assertThrows(IllegalStateException::class.java) { robot.close() })
        assertEquals(listOf(cleanup), telemetry.failure.suppressed.toList())
        assertTrue(neutralWrites > 0)
        assertEquals(1, closes)
        assertEquals(1, telemetry.closes)
        assertTrue(edu.wpi.first.wpilibj.DriverStation.isDisabled())
        assertDoesNotThrow { robot.close() }
    }

    @Test fun `native callbacks run the explicit no-motion autonomous entry and propagate alliance`() = withRobot { robot, telemetry ->
        robot.robotInit()
        NetworkTableInstance.getDefault().getStringTopic("ARES/Auto/Requested").publish().use { selection ->
            selection.set("do-nothing")
            DriverStationSim.setAllianceStationId(edu.wpi.first.hal.AllianceStationID.Red1)
            DriverStationSim.notifyNewData()
            robot.robotPeriodic()
            assertEquals(com.areslib.state.Alliance.RED, telemetry.runtime.store.state.drive.alliance)
            robot.autonomousInit()
            assertEquals("do-nothing", telemetry.getString("ARES/Auto/Selected", ""))
            assertEquals("Running", telemetry.getString("ARES/Auto/Status", ""))
            robot.autonomousPeriodic()
            assertEquals("Complete", telemetry.getString("ARES/Auto/Status", ""))
            assertEquals(0.0, telemetry.runtime.store.state.drive.xVelocityMetersPerSecond)
            DriverStationSim.setAllianceStationId(edu.wpi.first.hal.AllianceStationID.Blue1)
            DriverStationSim.notifyNewData()
            robot.robotPeriodic()
            assertEquals(com.areslib.state.Alliance.BLUE, telemetry.runtime.store.state.drive.alliance)
        }
    }
}
