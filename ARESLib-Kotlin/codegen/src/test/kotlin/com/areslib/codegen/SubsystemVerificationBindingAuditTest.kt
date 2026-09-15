package com.areslib.codegen

import com.areslib.subsystem.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SubsystemVerificationBindingAuditTest {
    @Test
    fun `each FTC and FRC template verification entry resolves to one emitted test`() {
        for (platform in listOf(SubsystemPlatform.FTC, SubsystemPlatform.FRC)) {
            for (template in SubsystemTemplate.entries.filter { it.supportsPlatform(platform) }) {
                val document = SubsystemTemplates.create(template, "probe", "Probe", platform)
                val generated = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(platform, "audit.probe"))
                val tests = generated.single { it.artifact == SubsystemArtifact.CONTRACT_TEST }.content
                val emittedMethods = Regex("fun `([^`]+)`\\(\\)").findAll(tests).map { it.groupValues[1] }.toList()
                val checks = subsystemVerificationContract(document)
                assertEquals(checks.size, checks.map { it.id }.distinct().size, "$platform $template")
                assertEquals(checks.map { it.id }.sorted(), checks.map { it.id })
                for (check in checks) {
                    if (check.evidence == SubsystemVerificationEvidence.GENERATED_BEHAVIOR_TEST) {
                        assertNotNull(check.testMethodName, check.id)
                        assertEquals(1, emittedMethods.count { it == check.testMethodName }, "$platform $template ${check.id}")
                    } else {
                        assertNull(check.testMethodName, check.id)
                    }
                }
                assertEquals(SubsystemVerificationEvidence.COMPILED_GENERATED_CODE, checks.single { it.category == SubsystemVerificationCategory.ALLOCATION }.evidence)
            }
        }
    }

    @Test
    fun `editable starter without generated tests emits no promised generated evidence`() {
        val document = SubsystemTemplates.createWithOwnership(
            SubsystemTemplate.SIMPLE_ACTUATOR, "probe", "Probe", SubsystemPlatform.FRC,
            implementationKind = SubsystemImplementationKind.GENERATED_STARTER,
        ).copy(generateTest = false)
        assertTrue(SubsystemSchema.validate(document).isEmpty())
        assertTrue(subsystemVerificationContract(document).isEmpty())
        val generated = SubsystemKotlinGenerator.generate(document, SubsystemKotlinCodegenTarget(SubsystemPlatform.FRC, "audit.probe"))
        assertFalse(generated.any { it.artifact == SubsystemArtifact.CONTRACT_TEST })
    }
}
