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
        override val configurationValid: Boolean get() { configurationReads++; return configured }
        override val homed: Boolean get() { homingReads++; return referenced }
        override var lastTuningApplySuccessful = true
        override fun homeAtKnownZero(): Boolean { homes++; referenced = true; return true }
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
}
