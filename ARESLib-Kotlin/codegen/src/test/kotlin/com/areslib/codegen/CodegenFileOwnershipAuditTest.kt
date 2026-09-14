package com.areslib.codegen

import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.project.*
import com.areslib.subsystem.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodegenFileOwnershipAuditTest {
    @TempDir lateinit var root: Path

    @Test
    fun `subsystem generation preserves explicitly user owned source`() {
        val project = prepare()
        AresProjectCodegenCli.run(project.arguments)
        val target = project.generatedRoot.resolve("arm/ArmState.kt")
        val custom = "// ARES OWNERSHIP: USER-OWNED\nclass CustomState\n"
        Files.writeString(target, custom)
        assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(project.arguments) }
        assertEquals(custom, Files.readString(target))
    }

    @Test
    fun `unknown source collision is detected before deleting obsolete output`() {
        val project = prepare()
        AresProjectCodegenCli.run(project.arguments)
        val target = project.generatedRoot.resolve("arm/ArmState.kt")
        val custom = "package handwritten\nclass CustomState\n"
        Files.writeString(target, custom)
        val obsolete = project.generatedRoot.resolve("Obsolete.kt")
        Files.writeString(obsolete, "// ARES OWNERSHIP: GENERATED - DO NOT EDIT\nclass Obsolete\n")
        val manifest = project.generatedRoot.resolve(".ares-subsystems-manifest")
        val priorManifest = Files.readString(manifest) + "Obsolete.kt\n"
        Files.writeString(manifest, priorManifest)
        assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(project.arguments) }
        assertEquals(custom, Files.readString(target))
        assertTrue(Files.isRegularFile(obsolete))
        assertEquals(priorManifest, Files.readString(manifest))
    }

    @Test
    fun `removing a descriptor does not delete a file converted to user ownership`() {
        val project = prepare()
        AresProjectCodegenCli.run(project.arguments)
        val target = project.generatedRoot.resolve("arm/ArmState.kt")
        val custom = "// ARES OWNERSHIP: USER-OWNED\nclass RetainedState\n"
        Files.writeString(target, custom)
        Files.delete(root.resolve(".ares/subsystems/arm.aressubsystem"))
        assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(project.arguments) }
        assertEquals(custom, Files.readString(target))
    }

    @Test
    fun `check mode cannot materialize missing editable starters`() {
        val project = prepare(SubsystemImplementationKind.GENERATED_STARTER)
        AresProjectCodegenCli.run(project.arguments + "--apply-subsystem-starters")
        val starter = project.starterRoot.resolve("arm/ArmState.kt")
        assertTrue(Files.isRegularFile(starter))
        Files.delete(starter)
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectCodegenCli.run(project.arguments + arrayOf("--check", "--apply-subsystem-starters"))
        }
        assertTrue(Files.notExists(starter))
    }

    @Test
    fun `project runtime output cannot replace a user owned file`() {
        val project = prepare()
        val output = root.resolve("build/generated/project/GeneratedAresProject.kt")
        Files.createDirectories(output.parent)
        val custom = "// ARES OWNERSHIP: USER-OWNED\nclass MyRuntime\n"
        Files.writeString(output, custom)
        assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(project.arguments) }
        assertEquals(custom, Files.readString(output))
    }

    @Test
    fun `runtime output cannot escape through a linked parent directory`() {
        val selected = Files.createDirectory(root.resolve("selected"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val project = prepare(projectRoot = selected)
        withDirectoryLink(selected.resolve("linked"), outside) { linked ->
            val arguments = project.arguments.copyOf()
            arguments[arguments.indexOf("--output") + 1] = linked.resolve("Outside.kt").toString()
            assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(arguments) }
            assertTrue(Files.notExists(outside.resolve("Outside.kt")))
        }
    }

    @Test
    fun `editable starters cannot escape through a linked parent directory`() {
        val selected = Files.createDirectory(root.resolve("selected"))
        val outside = Files.createDirectory(root.resolve("outside"))
        withDirectoryLink(selected.resolve("linked"), outside) {
            val file = GeneratedSubsystemFile("linked/State.kt", "// ARES OWNERSHIP: GENERATED STARTER\nclass State",
                artifact = SubsystemArtifact.STATE, group = SubsystemArtifactGroup.DOMAIN,
                ownership = SubsystemArtifactOwnership.GENERATED_STARTER, description = "Test starter")
            assertThrows(IllegalArgumentException::class.java) { SubsystemStarterReconciler.apply(selected, listOf(file)) }
            assertTrue(Files.notExists(outside.resolve("State.kt")))
        }
    }

    @Test
    fun `manifest cleanup cannot delete through a linked parent directory`() {
        val selected = Files.createDirectory(root.resolve("selected"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val content = "// ARES OWNERSHIP: GENERATED - DO NOT EDIT\nclass Outside\n"
        Files.writeString(outside.resolve("Outside.kt"), content)
        Files.writeString(selected.resolve(".ares-subsystems-manifest"), "linked/Outside.kt\n")
        withDirectoryLink(selected.resolve("linked"), outside) {
            assertThrows(IllegalArgumentException::class.java) {
                GeneratedSourceSetSynchronizer.sync(selected, emptyMap(), false, ".ares-subsystems-manifest")
            }
            assertEquals(content, Files.readString(outside.resolve("Outside.kt")))
        }
    }

    @Test
    fun `starter link targets contained inside the selected root remain usable`() {
        val selected = Files.createDirectory(root.resolve("selected"))
        val actual = Files.createDirectory(selected.resolve("actual"))
        withDirectoryLink(selected.resolve("linked"), actual) {
            val content = "// ARES OWNERSHIP: GENERATED STARTER\nclass State"
            val file = GeneratedSubsystemFile("linked/State.kt", content,
                artifact = SubsystemArtifact.STATE, group = SubsystemArtifactGroup.DOMAIN,
                ownership = SubsystemArtifactOwnership.GENERATED_STARTER, description = "Test starter")
            SubsystemStarterReconciler.apply(selected, listOf(file))
            assertEquals(content, Files.readString(actual.resolve("State.kt")))
        }
    }

    private fun withDirectoryLink(link: Path, target: Path, block: (Path) -> Unit) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Junctions support a real Windows containment test without symbolic-link privileges.
            // Both locations are owned descendants of this test's temporary directory.
            for (path in listOf(link, target)) {
                require(path.startsWith(root) && path.toString().none { it in "\"\r\n&|<>^%!" })
            }
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Temporary junction creation timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
            }
        } else Files.createSymbolicLink(link, target)
        try { block(link) } finally { Files.deleteIfExists(link) }
    }

    @Test
    fun `file parent conflicts preserve obsolete output and manifest`() {
        Files.writeString(root.resolve("Blocked.kt"), "user content")
        assertSourceSetConflictPreservesPrevious(linkedMapOf("Blocked.kt/Child.kt" to "child"))
        assertEquals("user content", Files.readString(root.resolve("Blocked.kt")))
    }

    @Test
    fun `planned file and directory conflicts fail before source set changes`() {
        assertSourceSetConflictPreservesPrevious(linkedMapOf("Node.kt" to "node", "Node.kt/Child.kt" to "child"))
        assertTrue(Files.notExists(root.resolve("Node.kt")))
    }

    private fun assertSourceSetConflictPreservesPrevious(expected: Map<String, String>) {
        val obsolete = root.resolve("Obsolete.kt")
        val manifest = root.resolve(".ares-subsystems-manifest")
        val content = "// ARES OWNERSHIP: GENERATED - DO NOT EDIT\nclass Obsolete\n"
        Files.writeString(obsolete, content)
        Files.writeString(manifest, "Obsolete.kt\n")
        assertThrows(IllegalArgumentException::class.java) {
            GeneratedSourceSetSynchronizer.sync(root, expected, false, manifest.fileName.toString())
        }
        assertEquals(content, Files.readString(obsolete))
        assertEquals("Obsolete.kt\n", Files.readString(manifest))
    }

    private data class Project(val arguments: Array<String>, val generatedRoot: Path, val starterRoot: Path)

    @Test
    fun `verification manifest output preserves unrelated existing files`() {
        val project = prepare()
        val target = root.resolve("Notes.kt")
        val custom = "// ARES OWNERSHIP: USER-OWNED\nclass Notes\n"
        Files.writeString(target, custom)
        assertThrows(IllegalArgumentException::class.java) {
            AresProjectCodegenCli.run(project.arguments + arrayOf("--verification-manifest-output", target.toString()))
        }
        assertEquals(custom, Files.readString(target))
    }

    @Test
    fun `verification manifest can regenerate after canonical documents change`() {
        val project = prepare()
        AresProjectCodegenCli.run(project.arguments)
        val target = root.resolve("build/generated/ares/verification/ares-project-verification.json")
        val previous = Files.readString(target)
        val verification = root.resolve("build/generated/tests/project/GeneratedAresProjectContractTest.kt")
        val verificationSource = Files.readString(verification)
        assertEquals("// ARES OWNERSHIP: GENERATED - DO NOT EDIT", verificationSource.lineSequence().first())
        // Previously emitted verification files had indentation before this ownership marker.
        Files.writeString(verification, "            " + verificationSource)
        val metadata = root.resolve(".ares/project.json")
        val document = AresProjectMetadataCodec.decode(Files.readString(metadata))
        Files.writeString(metadata, AresProjectMetadataCodec.encode(document.copy(robotLengthMeters = 0.5)))
        AresProjectCodegenCli.run(project.arguments)
        assertNotEquals(previous, Files.readString(target))
        AresProjectCodegenCli.run(project.arguments + "--check")
    }

    @Test
    fun `invalid layout names are rejected before creating any build output`() {
        for ((index, option) in listOf("--subsystems-package", "--drivebase-package", "--superstructure-package").withIndex()) {
            val selected = Files.createDirectory(root.resolve("project$index"))
            val project = prepare(projectRoot = selected)
            val arguments = project.arguments.toMutableList()
            val existing = arguments.indexOf(option)
            if (existing >= 0) arguments[existing + 1] = "org.when"
            else arguments += listOf(option, "org.when")
            assertThrows(IllegalArgumentException::class.java) { AresProjectCodegenCli.run(arguments.toTypedArray()) }
            assertTrue(Files.notExists(selected.resolve("build")), "$option must be checked before output")
        }
    }

    private fun prepare(
        kind: SubsystemImplementationKind = SubsystemImplementationKind.DECLARATIVE_GENERATED,
        projectRoot: Path = root,
    ): Project {
        val ares = Files.createDirectories(projectRoot.resolve(".ares/subsystems")).parent
        Files.writeString(ares.resolve("project.json"), AresProjectMetadataCodec.encode(AresProjectMetadataDocument(
            projectId = "audit", identity = AresProjectIdentityDocument("99999", "2026", "audit", "Audit"),
            league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
            robotLengthMeters = 0.45, robotWidthMeters = 0.45, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
            runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
        )))
        Files.writeString(ares.resolve("action-catalog.json"), CapabilityCatalogCodec.encode(CapabilityCatalogDocument(projectId = "audit")))
        val subsystem = SubsystemTemplates.createWithOwnership(SubsystemTemplate.SIMPLE_ACTUATOR,
            "arm", "Arm", SubsystemPlatform.FTC, implementationKind = kind)
        Files.writeString(ares.resolve("subsystems/arm.aressubsystem"), SubsystemDocumentCodec.encode(subsystem))
        val generated = projectRoot.resolve("build/generated/subsystems")
        val starter = projectRoot.resolve("src/main/kotlin")
        return Project(arrayOf(
            "--project", projectRoot.toString(),
            "--output", projectRoot.resolve("build/generated/project/GeneratedAresProject.kt").toString(),
            "--package", "org.example.audit", "--platform", "FTC",
            "--subsystems-package", "org.example.audit.subsystems",
            "--subsystems-starter-output", starter.toString(),
            "--subsystems-generated-output", generated.toString(),
            "--subsystems-generated-test-output", projectRoot.resolve("build/generated/tests").toString(),
        ), generated, starter)
    }
}
