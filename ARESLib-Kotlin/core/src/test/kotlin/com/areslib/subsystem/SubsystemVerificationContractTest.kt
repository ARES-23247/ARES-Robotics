package com.areslib.subsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubsystemVerificationContractTest {
    @Test
    fun `contract derives mandatory checks from selected safety behavior`() {
        val document = SubsystemTemplates.create(
            template = SubsystemTemplate.HOMED_MECHANISM,
            platform = SubsystemPlatform.FTC,
            documentId = "elevator",
            displayName = "Elevator",
            kotlinTypeName = "Elevator",
        )

        val checks = subsystemVerificationContract(document)

        assertTrue(checks.any { it.id == "elevator.state.safe-startup" })
        assertTrue(checks.any { it.category == SubsystemVerificationCategory.LIMITS_AND_HOMING })
        assertTrue(checks.any { it.testMethodName == SubsystemGeneratedTestNames.HOMING_DWELL })
        assertTrue(checks.any { it.testMethodName == SubsystemGeneratedTestNames.NEUTRAL_RECOVERY })
        assertTrue(checks.any { it.testMethodName == SubsystemGeneratedTestNames.GENERATED_ACTIONS })
        assertEquals(checks.map { it.id }.sorted(), checks.map { it.id })
    }

    @Test
    fun `tests cannot be selectively omitted while generated verification is enabled`() {
        val document = SubsystemTemplates.create(
            template = SubsystemTemplate.SENSOR_ONLY_SUBSYSTEM,
            platform = SubsystemPlatform.FRC,
            documentId = "range",
            displayName = "Range sensor",
            kotlinTypeName = "RangeSensor",
        )

        val checks = subsystemVerificationContract(document)

        assertTrue(checks.any { it.category == SubsystemVerificationCategory.STATE_AND_CONTROLS })
        assertTrue(checks.any { it.category == SubsystemVerificationCategory.HARDWARE_SIMULATION_PARITY })
        assertFalse(checks.any { it.category == SubsystemVerificationCategory.FAULT_RECOVERY })
    }

    @Test
    fun `editable starters may omit generated evidence but declarative runtimes may not`() {
        val base = SubsystemTemplates.create(
            template = SubsystemTemplate.SIMPLE_ACTUATOR,
            platform = SubsystemPlatform.FTC,
            documentId = "arm",
            displayName = "Arm",
            kotlinTypeName = "Arm",
        )

        assertTrue(subsystemVerificationContract(base).isNotEmpty())
        assertTrue(SubsystemSchema.validate(base.copy(generateTest = false)).any { it.path == "generateTest" })

        val teachingStarter = SubsystemTemplates.createWithOwnership(
            template = SubsystemTemplate.SIMPLE_ACTUATOR,
            platform = SubsystemPlatform.FTC,
            documentId = "teaching-arm",
            displayName = "Teaching arm",
            kotlinTypeName = "TeachingArm",
            implementationKind = SubsystemImplementationKind.GENERATED_STARTER,
        )
        assertTrue(subsystemVerificationContract(teachingStarter.copy(generateTest = false)).isEmpty())
    }
}
