package com.areslib.subsystem

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubsystemPolicyTest {
    @Test
    fun `identifier policy rejects Kotlin keywords and unstable names`() {
        assertTrue("armPosition".isUsableSubsystemKotlinIdentifier())
        assertFalse("when".isUsableSubsystemKotlinIdentifier())
        assertFalse("ArmPosition".isUsableSubsystemKotlinIdentifier())
        assertTrue("two-joint-arm".matches(SUBSYSTEM_STABLE_ID))
        assertFalse("TwoJointArm".matches(SUBSYSTEM_STABLE_ID))
        assertTrue("TwoJointArm".matches(SUBSYSTEM_PASCAL_CASE))
    }

    @Test
    fun `project paths stay repository relative and Kotlin sources are explicit`() {
        assertTrue("src/main/kotlin/org/example/Arm.kt".isSafeSubsystemProjectRelativeKotlinPath())
        assertFalse("../Arm.kt".isSafeSubsystemProjectRelativeKotlinPath())
        assertFalse("/src/main/kotlin/Arm.kt".isSafeSubsystemProjectRelativeKotlinPath())
        assertFalse("src\\main\\kotlin\\Arm.kt".isSafeSubsystemProjectRelativeKotlinPath())
        assertFalse("docs/arm.md".isSafeSubsystemProjectRelativeKotlinPath())
        assertTrue("docs/arm.md".isSafeSubsystemProjectRelativePath())
    }

    @Test
    fun `unit aliases normalize without silently converting incompatible dimensions`() {
        assertTrue(SubsystemUnits.controlUnitsCompatible("radians", "rad"))
        assertFalse(SubsystemUnits.controlUnitsCompatible("degrees", "rad"))
        assertTrue(SubsystemUnits.canRepresentVelocity("rotations/second"))
        assertTrue(SubsystemUnits.canRepresentAcceleration("m/s²"))
        assertTrue(SubsystemUnits.isCanonicalAngle("radians"))
        assertFalse(SubsystemUnits.isCanonicalAngle("degrees"))
    }

    @Test
    fun `motor measurement scale validates every physical ratio`() {
        assertEquals(
            0.1 / (28.0 * 5.0),
            SubsystemUnits.motorMeasurementScale(28.0, 5.0, 0.1),
            1e-12,
        )
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemUnits.motorMeasurementScale(0.0, 5.0, 0.1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SubsystemUnits.motorMeasurementScale(28.0, Double.NaN, 0.1)
        }
    }

    @Test
    fun `XRP hand authored registration uses Python factories without Gradle metadata`() {
        val document = SubsystemDocument(
            documentId = "custom-arm",
            displayName = "Custom arm",
            kotlinTypeName = "CustomArm",
            platform = SubsystemPlatform.XRP,
            implementation = SubsystemImplementationDocument(
                kind = SubsystemImplementationKind.HAND_AUTHORED,
                ownership = SubsystemSourceOwnership.USER_OWNED,
                sourceFiles = listOf("extensions/custom_arm.py"),
                pythonModuleName = "extensions.custom_arm",
                pythonFactoryName = "create_subsystem",
                pythonSimulationFactoryName = "create_simulated_subsystem",
                simulation = SubsystemSimulationDocument(SubsystemSimulationSupport.HAND_AUTHORED_SIMULATOR),
            ),
            generateMockIo = false,
            generateTest = false,
        )

        assertFalse(
            SubsystemSchema.validate(document).any { it.path.startsWith("implementation") },
            SubsystemSchema.validate(document).joinToString(),
        )
        val kotlinRegistration = document.copy(
            implementation = document.implementation.copy(modulePath = ":", subsystemClassName = "demo.CustomArm"),
        )
        assertTrue(SubsystemSchema.validate(kotlinRegistration).any { it.path == "implementation" })
    }
}
