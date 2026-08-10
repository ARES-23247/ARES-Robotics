package com.areslib.subsystem

import com.areslib.Store
import com.areslib.state.RobotState
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DslBuilderTest {

    @Test
    fun `test standard aresRobot builder`() {
        val robot = aresRobot {
            // Default initialization
        }
        
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
    }

    @Test
    fun `test aresRobot builder with custom initial state`() {
        val robot = aresRobot {
            initialState = RobotState(
                drive = com.areslib.state.DriveState(
                    xVelocityMetersPerSecond = 5.0
                )
            )
        }
        
        assertEquals(5.0, robot.store.state.drive.xVelocityMetersPerSecond)
    }

    @Test
    fun `test aresRobot builder with lambda block initial state`() {
        val robot = aresRobot {
            initialState {
                // Since RobotState has read-only fields, the block compiles but doesn't change properties.
                // This validates that the builder's lambda DSL function runs without exception.
            }
        }
        
        assertEquals(0.0, robot.store.state.drive.xVelocityMetersPerSecond)
    }

    @Test
    fun testAresRobotLifecycle() {
        val robot = aresRobot {}
        var readSensorsCalled = false
        var writeOutputsCalled = false
        var closeCalled = false

        val sub = object : Subsystem {
            override fun readSensors(store: Store, timestampMs: Long) {
                readSensorsCalled = true
            }
            override fun writeOutputs(state: RobotState, scale: Double) {
                writeOutputsCalled = true
            }
            override fun close() {
                closeCalled = true
            }
        }

        robot.registerSubsystem(sub)
        assertEquals(1, robot.getRegisteredSubsystems().size)

        robot.readAllSensors(1234L)
        kotlin.test.assertTrue(readSensorsCalled)

        robot.writeAllOutputs(0.8)
        kotlin.test.assertTrue(writeOutputsCalled)

        robot.safeAll()
        robot.closeSubsystems()
        kotlin.test.assertTrue(closeCalled)
    }
}
