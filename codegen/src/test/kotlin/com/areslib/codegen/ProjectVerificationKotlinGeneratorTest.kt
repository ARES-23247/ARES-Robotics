package com.areslib.codegen

import com.areslib.controls.ControllerInputPlatform
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ProjectVerificationKotlinGeneratorTest {
    @Test
    fun `emits deterministic FTC contract suite with chunk-safe documents`() {
        val largeProject = "{\"payload\":\"${"x".repeat(8_100)}\"}"
        val request = ProjectVerificationCodegenRequest(
            packageName = "org.example.generated",
            platform = ControllerInputPlatform.FTC,
            projectJson = largeProject,
            catalogJson = "{}",
            drivetrainJson = listOf("{}"),
            subsystemJson = emptyList(),
            superstructureJson = emptyList(),
            controllerProfileJson = emptyList(),
            controlSchemeJson = emptyList(),
            routineJson = emptyList(),
            autonomousCatalogJson = null,
        )

        val first = ProjectVerificationKotlinGenerator.generate(request)
        val second = ProjectVerificationKotlinGenerator.generate(request)

        assertEquals(first, second)
        assertEquals("project/GeneratedAresProjectContractTest.kt", first.relativePath)
        assertContains(first.content, "import org.junit.Test")
        assertContains(first.content, "fun `${ProjectGeneratedTestNames.PROJECT_IDENTITY}`()")
        assertContains(first.content, "); append(")
        assertContains(first.content, "ARES OWNERSHIP: GENERATED - DO NOT EDIT")
    }

    @Test
    fun `emits JUnit five imports for FRC`() {
        val output = ProjectVerificationKotlinGenerator.generate(
            ProjectVerificationCodegenRequest(
                packageName = "org.example.generated",
                platform = ControllerInputPlatform.FRC,
                projectJson = "{}",
                catalogJson = "{}",
                drivetrainJson = emptyList(),
                subsystemJson = emptyList(),
                superstructureJson = emptyList(),
                controllerProfileJson = emptyList(),
                controlSchemeJson = emptyList(),
                routineJson = emptyList(),
                autonomousCatalogJson = null,
            ),
        ).content

        assertContains(output, "import org.junit.jupiter.api.Test")
        assertContains(output, "assertTrue(errors.isEmpty())")
    }
}
