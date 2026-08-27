package com.areslib.simulation

import com.areslib.project.schema.AresControllerTarget
import com.areslib.project.schema.AresProjectTarget
import com.areslib.project.schema.AresSimulatorTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulationProjectPlanTest {
    @Test
    fun `FTC target selects FTC OpMode product`() {
        val plan = SimulationProjectPlanner.plan(
            target = AresProjectTarget(AresControllerTarget.FTC_CONTROL_HUB, AresSimulatorTarget.FTC),
            drivetrain = null,
            subsystems = emptyList(),
        )

        assertTrue(plan.isSupported)
        assertEquals(SimulationProductId.FTC_DESKTOP_OPMODE, plan.product.id)
        assertTrue(SimulationCapability.FTC_OPMODE_LIFECYCLE in plan.requiredCapabilities)
    }

    @Test
    fun `FRC target selects WPILib product`() {
        val plan = SimulationProjectPlanner.plan(
            target = AresProjectTarget(AresControllerTarget.FRC_ROBORIO, AresSimulatorTarget.FRC),
            drivetrain = null,
            subsystems = emptyList(),
        )

        assertTrue(plan.isSupported)
        assertEquals(SimulationProductId.FRC_WPILIB_DESKTOP, plan.product.id)
        assertTrue(SimulationCapability.FRC_TIMED_ROBOT_LIFECYCLE in plan.requiredCapabilities)
    }

    @Test
    fun `mismatched controller and simulator fail closed`() {
        val plan = SimulationProjectPlanner.plan(
            target = AresProjectTarget(AresControllerTarget.FTC_CONTROL_HUB, AresSimulatorTarget.FRC),
            drivetrain = null,
            subsystems = emptyList(),
        )

        assertFalse(plan.isSupported)
        assertEquals("controller_simulator_mismatch", plan.issues.single().code)
    }
}
