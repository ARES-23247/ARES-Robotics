package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.areslib.codegen.AresProjectCodegenCli
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresLeague
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectRoundtripIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val temporaryDirectory get() = temporaryFolder.root.toPath()
    private val exporter = ProjectArchiveExporter()

    @Test
    fun `biobuzz project round-trip preserves settings, user-owned extensions, and deterministic generation`(): Unit = runBlocking {
        val workspace = temporaryDirectory.resolve("biobuzz-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        assertTrue(File(project, ".ares/project.json").isFile)

        // 1. Initial snapshot validation: confirm all settings retain their intended meaning
        val initialSession = ProjectSession()
        val initialSnapshot = initialSession.snapshot(project.path, ControllerInputPlatform.FTC)
        val initialDocs = initialSnapshot.documents.query

        assertEquals(AresLeague.FTC, initialDocs.metadata?.league)
        assertEquals("biobuzz-reference", initialDocs.metadata?.projectId)
        assertEquals("BIOBUZZ Bot", initialDocs.metadata?.identity?.displayName)
        assertEquals("23247", initialDocs.metadata?.identity?.teamId)
        assertEquals("BIOBUZZ", initialDocs.metadata?.identity?.robotId)

        // Subsystems: intake and shooter
        val subsystemIds: List<String> = initialDocs.subsystems.map { it.documentId }.sorted()
        assertEquals(listOf("biobuzz-intake", "biobuzz-shooter"), subsystemIds)

        // Drivetrains: starter mecanum
        assertEquals(1, initialDocs.drivetrains.size)
        assertEquals("starter-mecanum", initialDocs.drivetrains.first().drivebaseId)

        // Tuning: canonical checked-in profile
        assertTrue(initialDocs.tuningProfiles.isNotEmpty())

        // Controls: ftc driver profile & scheme
        assertTrue(initialDocs.controllerProfiles.any { it.documentId == "ftc-driver" })
        assertTrue(initialDocs.controlSchemes.any { it.documentId == "driver" })

        // Field dimensions in metadata
        assertEquals(3.6576, initialDocs.metadata?.fieldLengthMeters)
        assertEquals(3.6576, initialDocs.metadata?.fieldWidthMeters)

        val startersDir = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems")

        // 2. Introduce genuine USER-OWNED extensions in TeamRobotExtensions and custom subsystem source
        val teamExtensionsFile = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        assertTrue(teamExtensionsFile.isFile)
        val teamExtensionsOriginalContent = teamExtensionsFile.readText()
        assertTrue(teamExtensionsOriginalContent.contains("// ARES OWNERSHIP: USER-OWNED"))

        val customizedTeamExtensions = buildString {
            appendLine("// ARES OWNERSHIP: USER-OWNED")
            appendLine("package org.firstinspires.ftc.teamcode.extensions")
            appendLine()
            appendLine("object TeamRobotExtensions {")
            appendLine("    fun customTeamExtension(): String = \"PRESERVED_USER_EXTENSIONS\"")
            appendLine("}")
        }
        teamExtensionsFile.writeText(customizedTeamExtensions)

        val customSubsystemFile = File(startersDir, "CustomUserSubsystem.kt").apply {
            parentFile.mkdirs()
            writeText(
                buildString {
                    appendLine("// ARES OWNERSHIP: USER-OWNED")
                    appendLine("package org.firstinspires.ftc.teamcode.subsystems")
                    appendLine()
                    appendLine("class CustomUserSubsystem {")
                    appendLine("    fun customAction(): String = \"PRESERVED_CUSTOM_ACTION\"")
                    appendLine("}")
                },
            )
        }
        val customSubsystemContent = customSubsystemFile.readText()

        // 3. Export project via ProjectArchiveExporter
        val exportArchive = temporaryDirectory.resolve("biobuzz-export.aresproject.zip").toFile()
        val exportResult = exporter.export(project.path, exportArchive.path)
        assertTrue(exportArchive.isFile)
        assertTrue(exportResult.fileCount > 0)

        // 4. Safely extract archive to a fresh directory
        val extractedDir = temporaryDirectory.resolve("biobuzz-extracted").toFile()
        val extractedProject = exporter.extract(exportArchive.path, extractedDir.path)
        assertEquals(extractedDir.canonicalPath, extractedProject.canonicalPath)
        assertTrue(File(extractedProject, ".ares/project.json").isFile)

        // 5. Open extracted project in a fresh ProjectSession
        val extractedSession = ProjectSession()
        val extractedSnapshot = extractedSession.snapshot(extractedProject.path, ControllerInputPlatform.FTC)

        // Assert exact canonical fingerprint equality
        assertEquals(
            initialSnapshot.revision.canonicalContentSha256,
            extractedSnapshot.revision.canonicalContentSha256,
            "Exported and extracted canonical .ares documents must have identical composite SHA-256 fingerprint",
        )

        // Verify extracted USER-OWNED files match byte-for-byte
        val extractedCustomSubsystem = File(extractedProject, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems/CustomUserSubsystem.kt")
        assertTrue(extractedCustomSubsystem.isFile)
        assertEquals(customSubsystemContent, extractedCustomSubsystem.readText(), "USER-OWNED subsystem file must be preserved byte-for-byte in export/extract")

        val extractedTeamExtensions = File(extractedProject, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        assertTrue(extractedTeamExtensions.isFile)
        assertEquals(customizedTeamExtensions, extractedTeamExtensions.readText(), "USER-OWNED extensions must be preserved byte-for-byte in export/extract")

        // 6. Regenerate Kotlin source in the extracted project
        val extractedGeneratedMainDir = File(extractedProject, "build/generated/ares/main/kotlin")
        val extractedGeneratedTestDir = File(extractedProject, "build/generated/ares/test/kotlin")
        val extractedDrivebaseDir = File(extractedProject, "build/generated/ares/drivebase/kotlin")
        val extractedStartersDir = File(extractedProject, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems")
        val extractedRuntimeOutputFile = File(extractedGeneratedMainDir, "org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")

        val extractedCodegenArgs = arrayOf(
            "--project", extractedProject.absolutePath,
            "--output", extractedRuntimeOutputFile.absolutePath,
            "--package", "org.firstinspires.ftc.teamcode.generated",
            "--platform", "FTC",
            "--subsystems-starter-output", extractedStartersDir.absolutePath,
            "--subsystems-generated-output", extractedGeneratedMainDir.absolutePath,
            "--subsystems-generated-test-output", extractedGeneratedTestDir.absolutePath,
            "--subsystems-package", "org.firstinspires.ftc.teamcode.subsystems",
            "--drivebase-output", extractedDrivebaseDir.absolutePath,
            "--drivebase-package", "org.firstinspires.ftc.teamcode.generated.drivebase",
            "--ftc-zero-code-runtime",
        )

        val firstRun = AresProjectCodegenCli.run(extractedCodegenArgs)
        assertTrue(extractedRuntimeOutputFile.isFile)
        assertEquals(customSubsystemContent, extractedCustomSubsystem.readText(), "Codegen must never overwrite USER-OWNED subsystem file")
        assertEquals(customizedTeamExtensions, extractedTeamExtensions.readText(), "Codegen must never overwrite USER-OWNED extensions")

        // 7. Verify check-only verification succeeds
        AresProjectCodegenCli.run(extractedCodegenArgs + "--check")

        // 8. Determinism check: Repeated generation produces byte-for-byte identical output and hashes
        val secondRun = AresProjectCodegenCli.run(extractedCodegenArgs)
        assertEquals(firstRun.source, secondRun.source, "Repeated generation must produce byte-identical source")
        assertEquals(firstRun.contentHash, secondRun.contentHash, "Repeated generation must produce identical content hash")
        assertEquals(customSubsystemContent, extractedCustomSubsystem.readText())
        assertEquals(customizedTeamExtensions, extractedTeamExtensions.readText())
    }

    @Test
    fun `modified generated output fails verification and regeneration restores it`(): Unit = runBlocking {
        val workspace = temporaryDirectory.resolve("failure-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)

        val generatedMainDir = File(project, "build/generated/ares/main/kotlin")
        val generatedTestDir = File(project, "build/generated/ares/test/kotlin")
        val drivebaseDir = File(project, "build/generated/ares/drivebase/kotlin")
        val startersDir = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/subsystems")
        val runtimeOutputFile = File(generatedMainDir, "org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")
        val manifestFile = File(project, "build/generated/ares/verification/ares-project-verification.json")

        val codegenArgs = arrayOf(
            "--project", project.absolutePath,
            "--output", runtimeOutputFile.absolutePath,
            "--package", "org.firstinspires.ftc.teamcode.generated",
            "--platform", "FTC",
            "--subsystems-starter-output", startersDir.absolutePath,
            "--subsystems-generated-output", generatedMainDir.absolutePath,
            "--subsystems-generated-test-output", generatedTestDir.absolutePath,
            "--subsystems-package", "org.firstinspires.ftc.teamcode.subsystems",
            "--drivebase-output", drivebaseDir.absolutePath,
            "--drivebase-package", "org.firstinspires.ftc.teamcode.generated.drivebase",
            "--ftc-zero-code-runtime",
        )

        // Setup starters first
        AresProjectCodegenCli.run(codegenArgs + arrayOf("--subsystems-only", "--apply-subsystem-starters"))

        // Complete a clean generation run first
        AresProjectCodegenCli.run(codegenArgs)
        assertTrue(runtimeOutputFile.isFile)
        assertTrue(manifestFile.isFile)

        // Model stale output after a completed generation. This does not interrupt the generator.
        val canonicalBefore = ProjectSession().snapshot(project.path, ControllerInputPlatform.FTC).revision.canonicalContentSha256
        runtimeOutputFile.writeText(runtimeOutputFile.readText() + "\n// intermediate partial edit\n")

        // Consumer verification check must fail closed immediately
        val checkFailure = assertFailsWith<IllegalArgumentException> {
            AresProjectCodegenCli.run(codegenArgs + "--check")
        }
        assertTrue(
            checkFailure.message?.contains("Generated source is stale") == true ||
                checkFailure.message?.contains("verification manifest is stale") == true,
            "Verification must fail closed when outputs are partially written or manifest is mismatched",
        )

        // Canonical .ares documents must remain intact and unchanged
        val session = ProjectSession()
        val snapshot = session.snapshot(project.path, ControllerInputPlatform.FTC)
        assertEquals("BIOBUZZ Bot", snapshot.documents.query.metadata?.identity?.displayName)
        assertEquals(canonicalBefore, snapshot.revision.canonicalContentSha256)

        // Re-running full generation restores everything to a clean, synchronized state
        val recoveryRun = AresProjectCodegenCli.run(codegenArgs)
        assertTrue(recoveryRun.source.isNotBlank())
        assertTrue(manifestFile.isFile)

        // Subsequent check-only verification succeeds cleanly
        AresProjectCodegenCli.run(codegenArgs + "--check")
    }

    @Test
    fun `interrupted multi-document transaction recovers baseline on next project session load`(): Unit = runBlocking {
        val workspace = temporaryDirectory.resolve("tx-recovery-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)

        val subsystemsDir = File(project, ".ares/subsystems")
        val baselineFiles = subsystemsDir.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue(baselineFiles.isNotEmpty())

        val intakeFile = File(project, ".ares/subsystems/biobuzz-intake.aressubsystem")
        assertTrue(intakeFile.isFile, "BioBuzz must have biobuzz-intake subsystem")
        val baselineIntakeContent = intakeFile.readText()

        val newUncommittedFile = File(project, ".ares/subsystems/uncommitted-new.aressubsystem")

        // Simulate an uncommitted transaction interrupted before COMMITTED marker
        val txDir = File(project, ".ares/recovery/transactions/interrupted-tx-12345").apply { mkdirs() }
        baselineFiles.forEach { file ->
            val relPath = file.relativeTo(project).invariantSeparatorsPath
            val backup = File(txDir, "baseline/$relPath").apply { parentFile.mkdirs() }
            file.copyTo(backup)
        }

        val manifestTsv = buildString {
            append("V\t2\n")
            append("S\t.ares/subsystems\n")
            baselineFiles.map { it.relativeTo(project).invariantSeparatorsPath }.sorted().forEach { relPath ->
                append("F\t$relPath\n")
            }
        }
        val manifestBody = manifestTsv
        val manifestWithChecksum = manifestBody + "H\t${com.ares.analytics.util.Sha256.hex(manifestBody)}\n"
        File(txDir, "manifest.tsv").writeText(manifestWithChecksum)

        // Inject partial intermediate writes: modify baseline file and write new uncommitted file
        intakeFile.writeText(baselineIntakeContent + "\n{\"corrupt\": true}")
        newUncommittedFile.writeText("{\"uncommitted\": true}")
        assertTrue(newUncommittedFile.isFile)

        // Open project in ProjectSession: loadStable() automatically executes ProjectMutationTransaction.recover()
        val session = ProjectSession()
        val recoveredSnapshot = session.snapshot(project.path, ControllerInputPlatform.FTC)

        // Assert baseline file was restored and uncommitted new file was deleted
        assertEquals(baselineIntakeContent, intakeFile.readText(), "Interrupted transaction baseline must be restored")
        assertFalse(newUncommittedFile.exists(), "Uncommitted new file must be deleted during transaction recovery")
        assertFalse(txDir.exists(), "Incomplete transaction directory must be cleaned up after recovery")
        assertEquals("BIOBUZZ Bot", recoveredSnapshot.documents.query.metadata?.identity?.displayName)
        assertEquals(
            listOf("biobuzz-intake", "biobuzz-shooter"),
            recoveredSnapshot.documents.query.subsystems.map { it.documentId }.sorted(),
        )
    }

    private fun extractBundledBioBuzz(targetDirectory: File): File {
        val archiveName = "ARES-BIOBUZZ-Example-${BuildConfig.BIOBUZZ_EXAMPLE_VERSION}"
        val resourceStream = requireNotNull(javaClass.getResourceAsStream("/project-templates/$archiveName.zip")) {
            "Missing bundled resource: /project-templates/$archiveName.zip"
        }
        ZipInputStream(resourceStream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(targetDirectory, entry.name).canonicalFile
                require(output.toPath().startsWith(targetDirectory.canonicalFile.toPath()))
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        return File(targetDirectory, archiveName)
    }
}
