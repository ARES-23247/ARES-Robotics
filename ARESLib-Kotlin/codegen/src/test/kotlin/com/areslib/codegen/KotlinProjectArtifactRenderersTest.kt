package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.project.compiler.ProjectArtifactKind
import com.areslib.project.compiler.RobotProjectCompiler
import com.areslib.project.model.RobotProjectAssembler
import com.areslib.project.model.RobotProjectSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KotlinProjectArtifactRenderersTest {
    @Test
    fun `empty superstructure projects retain the generated registry contract`() {
        val project = RobotProjectCompiler.lower(
            RobotProjectAssembler.assemble(
                RobotProjectSnapshot(
                    projectRoot = "C:/robot/minimal",
                    metadata = AresProjectMetadataDocument(
                        projectId = "minimal",
                        identity = AresProjectIdentityDocument("23247", "2026", "Minimal", "Minimal"),
                        league = AresLeague.FTC,
                        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                        robotLengthMeters = 0.46,
                        robotWidthMeters = 0.46,
                        fieldLengthMeters = 3.6576,
                        fieldWidthMeters = 3.6576,
                        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
                    ),
                    baseCapabilityCatalog = CapabilityCatalogDocument(projectId = "minimal"),
                ),
                ControllerInputPlatform.FTC,
            ),
            ControllerInputPlatform.FTC,
        )

        val artifacts = SuperstructureKotlinArtifactRenderer.render(
            project = project,
            relativePathPrefix = "generated/main",
            packageName = "org.example.generated",
            subsystemRegistryFqn = "org.example.generated.GeneratedSubsystemRegistry",
        )

        assertEquals(1, artifacts.size)
        assertEquals(ProjectArtifactKind.SUPERSTRUCTURE_REGISTRY, artifacts.single().plan.kind)
        assertTrue(artifacts.single().plan.relativePath.endsWith("GeneratedSuperstructureRegistry.kt"))
        assertTrue(artifacts.single().content.contains("object GeneratedSuperstructureRegistry"))
    }
}
