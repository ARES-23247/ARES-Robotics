package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemTemplateOwnershipAuditTest {
    @Test
    fun `declarative ownership preserves the intake simulation contract`() {
        for (platform in SubsystemPlatform.entries) {
            val document = SubsystemTemplates.create(
                SubsystemTemplate.INTAKE_CONVEYOR, "ownership-intake", "OwnershipIntake", platform,
            )
            assertInteraction(document)
            assertEquals(SubsystemImplementationKind.DECLARATIVE_GENERATED, document.implementation.kind)
            assertEquals(SubsystemSourceOwnership.GENERATED_DO_NOT_EDIT, document.implementation.ownership)
            assertTrue(document.generateMockIo)
            assertTrue(document.generateTest)
        }
    }

    @Test
    fun `editable starter ownership preserves the intake simulation contract`() {
        for (platform in SubsystemPlatform.entries) {
            val document = SubsystemTemplates.createWithOwnership(
                SubsystemTemplate.INTAKE_CONVEYOR, "ownership-intake", "OwnershipIntake", platform,
                implementationKind = SubsystemImplementationKind.GENERATED_STARTER,
            )
            assertInteraction(document)
            assertEquals(SubsystemImplementationKind.GENERATED_STARTER, document.implementation.kind)
            assertEquals(SubsystemSourceOwnership.GENERATED_STARTER, document.implementation.ownership)
        }
    }

    private fun assertInteraction(document: SubsystemDocument) {
        val simulation = document.implementation.simulation
        assertEquals(SubsystemSimulationSupport.GENERATED_MOCK, simulation.support)
        assertEquals(SimInteractionRole.CONVEYOR_INDEXER, simulation.interaction.role)
        assertEquals("motor", simulation.interaction.triggerActuatorId)
        assertEquals(1.0, simulation.interaction.triggerThreshold)
    }
}
