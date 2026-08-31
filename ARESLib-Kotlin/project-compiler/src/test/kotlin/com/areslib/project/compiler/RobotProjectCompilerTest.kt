package com.areslib.project.compiler

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.project.model.RobotProjectAssembler
import com.areslib.project.model.RobotProjectSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class RobotProjectCompilerTest {
    @Test
    fun `lowering creates sorted typed IR and a canonical content fingerprint`() {
        val effective = RobotProjectAssembler.assemble(snapshot(), ControllerInputPlatform.FTC)

        val first = RobotProjectCompiler.lower(effective, ControllerInputPlatform.FTC)
        val second = RobotProjectCompiler.lower(effective, ControllerInputPlatform.FTC)

        assertEquals("lightbot", first.projectId.value)
        assertEquals(listOf("lights.cycleBackward", "lights.cycleForward"), first.actions.map { it.key.value })
        assertEquals(first.canonicalProjectSha256, second.canonicalProjectSha256)
        assertTrue(first.canonicalProjectSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `compiler rejects invalid projects and a cross league target`() {
        val valid = RobotProjectAssembler.assemble(snapshot(), ControllerInputPlatform.FTC)
        val invalid = RobotProjectAssembler.assemble(snapshot().copy(baseCapabilityCatalog = null))

        assertThrows(IllegalArgumentException::class.java) {
            RobotProjectCompiler.lower(valid, ControllerInputPlatform.FRC)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RobotProjectCompiler.lower(invalid, ControllerInputPlatform.FTC)
        }
    }

    @Test
    fun `verification manifest binds canonical project and rendered artifact hashes`() {
        val project = RobotProjectCompiler.lower(
            RobotProjectAssembler.assemble(snapshot(), ControllerInputPlatform.FTC),
            ControllerInputPlatform.FTC,
        )
        val entry = ProjectArtifactManifestEntry(
            ProjectArtifactId("project.runtime"),
            "main/GeneratedAresProject.kt",
            ProjectArtifactSourceSet.MAIN,
            ProjectArtifactOwnership.GENERATED_DO_NOT_EDIT,
            ProjectArtifactKind.PROJECT_RUNTIME,
            testSha256("generated source"),
        )

        val first = ProjectVerificationManifestBuilder.build(project, listOf(entry))
        val changed = ProjectVerificationManifestBuilder.build(
            project,
            listOf(entry.copy(contentSha256 = testSha256("different source"))),
        )

        assertNotEquals(first.manifestSha256, changed.manifestSha256)
        assertTrue(ProjectVerificationManifestCodec.encode(first).contains(first.canonicalProjectSha256))
        assertTrue(ProjectVerificationManifestCodec.encode(first).contains(first.manifestSha256))
    }

    private fun snapshot() = RobotProjectSnapshot(
        projectRoot = "C:/robot/lightbot",
        metadata = AresProjectMetadataDocument(
            projectId = "lightbot",
            identity = AresProjectIdentityDocument("23247", "2026", "Lightbot", "Lightbot"),
            league = AresLeague.FTC,
            coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.46,
            robotWidthMeters = 0.46,
            fieldLengthMeters = 3.6576,
            fieldWidthMeters = 3.6576,
            runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
        ),
        baseCapabilityCatalog = CapabilityCatalogDocument(
            projectId = "lightbot",
            actions = listOf(
                ActionDescriptor("lights.cycleForward", "Cycle forward", "Advance the color"),
                ActionDescriptor("lights.cycleBackward", "Cycle backward", "Reverse the color"),
            ),
        ),
    )

    private fun testSha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
