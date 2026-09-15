package org.aresfirst.marvin

import com.areslib.frc.FrcSwerveRobot
import com.areslib.state.RobotState
import com.areslib.state.SuperstructureState
import edu.wpi.first.hal.HAL
import org.aresfirst.marvin.hardware.FrcMechanismConfigurationStatus
import org.aresfirst.marvin.hardware.FrcMechanismHomingStatus
import org.aresfirst.marvin.hardware.FrcFlywheelTuningStatus
import org.aresfirst.marvin.marvin.MarvinReducer
import org.aresfirst.marvin.marvin.MarvinState
import org.aresfirst.marvin.marvin.marvin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrcMechanismCommissioningControllerTest {
    private class Device : FrcMechanismConfigurationStatus, FrcMechanismHomingStatus, FrcFlywheelTuningStatus {
        var configured = true
        var referenced = true
        var configurationReads = 0
        var homingReads = 0
        var homes = 0
        var homeThrows = false
        var homeSucceeds = true
        var configurationThrows = false
        var homingThrows = false
        var onHome: () -> Unit = {}
        override val configurationValid: Boolean get() {
            configurationReads++
            check(!configurationThrows) { "Synthetic configuration read failure" }
            return configured
        }
        override val homed: Boolean get() {
            homingReads++
            check(!homingThrows) { "Synthetic homing read failure" }
            return referenced
        }
        override var lastTuningApplySuccessful = true
        override fun homeAtKnownZero(): Boolean {
            homes++
            onHome()
            check(!homeThrows) { "Synthetic home failure" }
            if (homeSucceeds) referenced = true
            return homeSucceeds
        }
    }

    private fun exercise(block: (FrcSwerveRobot, Device, FrcMechanismCommissioningController) -> Unit) {
        assertTrue(HAL.initialize(500, 0))
        val robot = FrcSwerveRobot(
            isSimulation = true,
            initialState = RobotState(superstructure = SuperstructureState(custom = MarvinState())),
            reducer = MarvinReducer::reduce,
        )
        try {
            val device = Device()
            val controller = FrcMechanismCommissioningController(robot, arrayOf(device), true, arrayOf(device), device)
            block(robot, device, controller)
        } finally { robot.close() }
    }

    @Test fun `periodic checks read each cached health flag once and react to lost reference`() = exercise { _, device, controller ->
        device.configurationReads = 0
        device.homingReads = 0
        repeat(10) { controller.requirePeriodicHealth() }
        assertEquals(10, device.configurationReads)
        assertEquals(10, device.homingReads)
        assertTrue(controller.isHardwarePermitted())
        device.referenced = false
        assertThrows(IllegalStateException::class.java) { controller.requirePeriodicHealth() }
        assertFalse(controller.isHardwarePermitted())
    }

    @Test fun `healthy mode transitions cannot clear a latched fault but disabled recovery can`() = exercise { robot, device, controller ->
        controller.latchFault("synthetic fault")
        controller.applySafetyPolicy("test transition")
        assertFalse(controller.isHardwarePermitted())
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
        controller.handleHomingRequest(true, false, true)
        assertEquals(0, device.homes)
        controller.handleHomingRequest(true, true, false)
        assertEquals(0, device.homes, "Held combo cannot become a new recovery edge")
        controller.handleHomingRequest(false, true, false)
        controller.handleHomingRequest(true, true, false)
        assertEquals(1, device.homes)
        assertTrue(controller.isHardwarePermitted())
        controller.stopForDisable()
        assertFalse(controller.isHardwarePermitted())
    }

    @Test fun `configuration reset and failed tuning revoke permission`() = exercise { _, device, controller ->
        device.configured = false
        assertThrows(IllegalStateException::class.java) { controller.requirePeriodicHealth() }
        assertFalse(controller.isHardwarePermitted())
        device.configured = true
        controller.requirePeriodicHealth()
        device.lastTuningApplySuccessful = false
        assertThrows(IllegalStateException::class.java) { controller.requirePeriodicHealth() }
        assertFalse(controller.isHardwarePermitted())
    }

    @Test fun `throwing home revokes prior healthy permission and latches a fault`() = exercise { robot, device, controller ->
        device.homeThrows = true
        runCatching { controller.handleHomingRequest(true, true, false) }
        assertFalse(controller.isHardwarePermitted())
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
    }

    @Test fun `throwing home consumes the button edge before any retry`() = exercise { _, device, controller ->
        device.homeThrows = true
        runCatching { controller.handleHomingRequest(true, true, false) }
        device.homeThrows = false
        controller.handleHomingRequest(true, true, false)
        assertEquals(1, device.homes)
        controller.handleHomingRequest(false, true, false)
        controller.handleHomingRequest(true, true, false)
        assertEquals(2, device.homes)
        assertTrue(controller.isHardwarePermitted())
    }

    @Test fun `unsuccessful home cannot be overridden by an old homed flag`() = exercise { robot, device, controller ->
        device.homeSucceeds = false
        controller.handleHomingRequest(true, true, false)
        assertFalse(controller.isHardwarePermitted())
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
        controller.applySafetyPolicy("later mode transition")
        assertFalse(controller.isHardwarePermitted())
    }

    @Test fun `recovery inhibits before writes and catches health getter failures`() = exercise { robot, device, controller ->
        var inhibitedAtWrite = false
        device.onHome = { inhibitedAtWrite = robot.store.state.superstructure.marvin.mechanismSafetyInhibited }
        device.configurationThrows = true
        assertDoesNotThrow { controller.handleHomingRequest(true, true, false) }
        assertTrue(inhibitedAtWrite)
        assertFalse(controller.isHardwarePermitted())
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
        device.configurationThrows = false
        controller.handleHomingRequest(false, true, false)
        device.homingThrows = true
        assertDoesNotThrow { controller.handleHomingRequest(true, true, false) }
        assertFalse(controller.isHardwarePermitted())
        device.homingThrows = false
        controller.handleHomingRequest(false, true, false)
        controller.handleHomingRequest(true, true, false)
        assertTrue(controller.isHardwarePermitted())
    }

    @Test fun `one failed home cannot clear the fault when other devices succeed`() = exercise { robot, device, _ ->
        val second = Device()
        val controller = FrcMechanismCommissioningController(robot, arrayOf(device, second), true, arrayOf(device, second), device)
        device.homeSucceeds = false
        controller.handleHomingRequest(true, true, false)
        assertEquals(1, device.homes)
        assertEquals(1, second.homes)
        assertFalse(controller.isHardwarePermitted())
        assertTrue(robot.store.state.superstructure.marvin.mechanismSafetyFaultLatched)
    }
}
