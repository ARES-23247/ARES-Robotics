package com.ares.analytics.ui.components.subsystems

import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsystemFaultLabModelTest {
    private val homed = SubsystemTemplates.create(
        SubsystemTemplate.HOMED_MECHANISM,
        "lift",
        "Lift",
        SubsystemPlatform.FTC,
    )

    @Test
    fun `healthy contract permits bounded output while every applicable fault commands neutral`() {
        assertTrue(evaluateSubsystemFaultScenario(homed, SubsystemFaultScenario.HEALTHY).outputPermitted)
        listOf(
            SubsystemFaultScenario.STALE_FEEDBACK,
            SubsystemFaultScenario.INVALID_FEEDBACK,
            SubsystemFaultScenario.CONFIGURATION_FAILURE,
            SubsystemFaultScenario.NOT_HOMED,
            SubsystemFaultScenario.FAILED_OUTPUT_WRITE,
        ).forEach { scenario ->
            assertFalse("$scenario must fail closed", evaluateSubsystemFaultScenario(homed, scenario).outputPermitted)
        }
    }

    @Test
    fun `requirements not selected are explained without pretending they block motion`() {
        val sensor = SubsystemTemplates.create(
            SubsystemTemplate.DISTANCE_SENSOR,
            "range",
            "Range",
            SubsystemPlatform.FTC,
        )

        val homing = evaluateSubsystemFaultScenario(sensor, SubsystemFaultScenario.NOT_HOMED)
        assertTrue(homing.outputPermitted)
        assertTrue(homing.status.contains("not enabled"))
    }

    @Test
    fun `failed write recovery explains the explicit neutral handshake`() {
        val result = evaluateSubsystemFaultScenario(homed, SubsystemFaultScenario.FAILED_OUTPUT_WRITE)

        assertFalse(result.outputPermitted)
        assertTrue(result.status.contains("latched"))
        assertTrue(result.recovery.contains("neutral recovery"))
        assertTrue(result.recovery.contains("new target"))
    }
}
