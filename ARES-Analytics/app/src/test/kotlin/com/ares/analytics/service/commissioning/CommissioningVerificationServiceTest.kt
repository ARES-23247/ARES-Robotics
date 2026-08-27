package com.ares.analytics.service.commissioning

import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.SubsystemTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommissioningVerificationServiceTest {
    @Test
    fun `position template verifies deterministic safety scenarios`() {
        val subsystem = SubsystemTemplates.create(
            template = SubsystemTemplate.POSITION_CONTROLLED_MECHANISM,
            documentId = "arm",
            kotlinTypeName = "Arm",
            platform = SubsystemPlatform.FTC,
        )

        val result = CommissioningVerificationService().verify(listOf(subsystem))

        assertEquals(CommissioningSimulationStatus.VERIFIED, result.status)
        assertTrue(result.controllerCount > 0)
        assertTrue(result.scenarioCount >= 8)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun `project without generated controllers is not mislabeled verified`() {
        val subsystem = SubsystemTemplates.create(
            template = SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM,
            documentId = "range",
            kotlinTypeName = "Range",
            platform = SubsystemPlatform.FRC,
        )

        val result = CommissioningVerificationService().verify(listOf(subsystem))

        assertEquals(CommissioningSimulationStatus.NOT_AVAILABLE, result.status)
        assertEquals(0, result.controllerCount)
    }
}
