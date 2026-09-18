package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.shared.models.League
import com.areslib.project.AresProjectMetadataCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves real generation interruption and recovery using actual application orchestration
 * and the Gradle wrapper:
 *
 * 1. Controlled intermediate-write failure:
 *    - The real generator writes intermediate files (`GeneratedAresProject.kt`) before failing.
 *    - Diagnostics accurately report the failure.
 *    - Canonical `.ares` documents and user-owned source remain intact.
 *    - Downstream verification builds reject stale/unverified output.
 *    - Removing the obstruction and retrying through [ProjectBuildService] cleanly recovers.
 *
 * 2. Cancellation while generator is running:
 *    - Starting real generation and killing the active build terminates child processes cleanly.
 *    - Diagnostics capture the cancellation.
 *    - Canonical documents and user-owned code remain uncorrupted.
 *    - Retrying generation succeeds and passes verification.
 */
class ProjectGenerationRecoveryIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val temporaryDirectory get() = temporaryFolder.root.toPath()

    @Test
    fun `controlled intermediate-write failure leaves partial output, rejects stale build, preserves documents, and recovers cleanly`() = runBlocking {
        val workspace = temporaryDirectory.resolve("failure-recovery-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureConsumerSdk(project)

        // Mutate field configuration to verify generated output carries this change
        val metadataFile = File(project, ".ares/project.json")
        val originalMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
        val modifiedMetadata = originalMetadata.copy(fieldLengthMeters = 3.52, fieldWidthMeters = 3.52)
        saveConsumerMetadata(project, modifiedMetadata)

        // Provide a custom USER-OWNED extension file
        val teamExtensionsFile = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        val userExtensionContent = buildString {
            appendLine("// ARES OWNERSHIP: USER-OWNED")
            appendLine("package org.firstinspires.ftc.teamcode.extensions")
            appendLine()
            appendLine("object TeamRobotExtensions {")
            appendLine("    const val RECOVERY_MARKER = \"INTERMEDIATE_WRITE_TEST_USER_OWNED\"")
            appendLine("}")
        }
        teamExtensionsFile.writeText(userExtensionContent)

        // Intermediate-write obstruction: create a directory at the verification manifest path.
        // AresProjectCodegenCli writes GeneratedAresProject.kt first, then fails at syncVerificationManifest.
        val manifestObstruction = File(project, "build/generated/ares/verification/ares-project-verification.json").apply {
            mkdirs()
        }
        assertTrue(manifestObstruction.isDirectory, "Manifest path must be obstructed by a directory")

        val canonicalBefore = consumerCanonicalSnapshot(project)
        val runtimeFile = File(project, "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")
        assertFalse(runtimeFile.exists(), "Partial output must be produced by this run, not inherited")
        val driver = ConsumerRoundtripBuild("write-recovery")
        val buildService = driver.service
        try {
            // Trigger generation with intermediate obstruction
            driver.generate(project)

            // 1. Generation must report FAILED with diagnostics
            assertEquals(AresGenerationPhase.FAILED, buildService.aresGenerationState.value.phase)
            val diagnostic = buildService.aresGenerationState.value.message
            assertTrue(
                diagnostic.contains("Verification manifest") ||
                    diagnostic.contains("collides with a non-file") ||
                    diagnostic.contains("non-zero exit value") ||
                    diagnostic.contains("exit value 1") ||
                    diagnostic.contains("exit code") ||
                    diagnostic.contains("AresProjectCodegenCli"),
                "Diagnostic must capture the generation failure: $diagnostic"
            )

            // 2. Establish that intermediate write occurred on disk before the failure
            val generatedRuntime = File(project, "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")
            assertTrue(generatedRuntime.isFile, "GeneratedAresProject.kt must have been written before the manifest failure")
            assertTrue(generatedRuntime.length() > 0L, "Generated runtime file must not be empty")
            val runtimeContent = generatedRuntime.readText()
            assertTrue(runtimeContent.contains("3.52"), "Generated runtime must contain mutated value 3.52 from before failure")

            // 3. Canonical .ares documents and user-owned code must remain intact
            val reloadedMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
            assertEquals(3.52, reloadedMetadata.fieldLengthMeters, "Canonical project.json must remain intact")
            assertEquals(userExtensionContent, teamExtensionsFile.readText(), "USER-OWNED extension must remain intact")
            assertEquals(canonicalBefore, consumerCanonicalSnapshot(project))

            // runBuild regenerates first. Invoke the actual check-only Gradle task separately to
            // prove incomplete existing outputs are rejected, without silently repairing them.
            assertTrue(driver.verifyExistingFiles(project) != 0, "Check-only verification must reject the incomplete manifest")
            val checkOnlyLog = File(project, "build/roundtrip-verify-existing.log").readText()
            assertTrue(checkOnlyLog.contains("ares-project-verification.json"), "Verification must fail at the obstructed manifest")
            assertTrue(checkOnlyLog.contains(":TeamCode:verifyAresProject FAILED"), "The verifier itself must reject existing output")
            assertFalse(checkOnlyLog.contains("> Task :TeamCode:prepareAresSubsystemPlumbing"), "Check-only verification must not regenerate output")
            assertEquals(runtimeContent, generatedRuntime.readText(), "Check-only verification must leave existing runtime output unchanged")
            assertTrue(manifestObstruction.isDirectory, "Check-only verification must not repair the manifest")

            // 4. Stale-output rejection: verification build must fail because generated outputs are unverified
            driver.verify(project)
            assertEquals(
                BuildExecutionPhase.FAILED,
                buildService.processState.value.buildExecution.phase,
                "Verification build must fail when verification manifest is missing/obstructed"
            )

            // 5. Clean recovery: remove the obstruction and re-run generation
            assertTrue(manifestObstruction.deleteRecursively(), "Must successfully remove obstruction directory")
            driver.generate(project)

            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)
            assertTrue(manifestObstruction.isFile, "Verification manifest must now be written as a valid file")
            assertEquals(0, driver.verifyExistingFiles(project), "Check-only verification must pass after recovery")

            // 6. Verification build succeeds after clean recovery
            driver.verify(project)
            assertEquals(
                BuildExecutionPhase.SUCCEEDED,
                buildService.processState.value.buildExecution.phase,
                "Verification build must succeed after clean recovery: ${buildService.processState.value.buildExecution.message}"
            )
            assertEquals(0, buildService.processState.value.buildExecution.exitCode)
        } finally {
            buildService.shutdownAndJoin()
        }
    }

    @Test
    fun `cancellation after real codegen writes output terminates Gradle and recovers cleanly on retry`() = runBlocking {
        val workspace = temporaryDirectory.resolve("cancellation-recovery-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureConsumerSdk(project)

        // Mutate configuration
        val metadataFile = File(project, ".ares/project.json")
        val originalMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
        val modifiedMetadata = originalMetadata.copy(fieldLengthMeters = 3.60, fieldWidthMeters = 3.60)
        saveConsumerMetadata(project, modifiedMetadata)

        val teamExtensionsFile = File(project, "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/extensions/TeamRobotExtensions.kt")
        val userExtensionContent = buildString {
            appendLine("// ARES OWNERSHIP: USER-OWNED")
            appendLine("package org.firstinspires.ftc.teamcode.extensions")
            appendLine()
            appendLine("object TeamRobotExtensions {")
            appendLine("    const val RECOVERY_MARKER = \"CANCELLATION_TEST_USER_OWNED\"")
            appendLine("}")
        }
        teamExtensionsFile.writeText(userExtensionContent)

        val canonicalBefore = consumerCanonicalSnapshot(project)
        val script = File(project, "TeamCode/build.gradle")
        val originalScript = script.readText()
        val pidFile = File(project, ".ares/local/roundtrip-generation.pid")
        val releaseFile = File(project, ".ares/local/roundtrip-generation.release")
        // Test-only task-completion barrier: actual codegen must finish writing before we can cancel.
        // This proves cancellation of the live Gradle operation, not interruption inside the CLI writer.
        script.appendText("\n" + """

            tasks.named("generateAresProject").configure {
                doLast {
                    def marker = rootProject.file(".ares/local/roundtrip-generation.pid")
                    marker.parentFile.mkdirs()
                    marker.text = Long.toString(ProcessHandle.current().pid())
                    while (!rootProject.file(".ares/local/roundtrip-generation.release").isFile()) {
                        Thread.sleep(20L)
                    }
                }
            }
        """.trimIndent())
        var generationPid: Long? = null
        val driver = ConsumerRoundtripBuild("cancel-recovery")
        val buildService = driver.service
        try {
            // Start real generation
            buildService.generateAresProject(project.path, League.FTC)

            // A RUNNING state alone can be published before any child process exists.
            generationPid = withTimeout(180_000L) {
                var readyPid: Long? = null
                while (readyPid == null) {
                    readyPid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
                    delay(10)
                }
                readyPid
            }
            assertTrue(ProcessHandle.of(requireNotNull(generationPid)).map { it.isAlive }.orElse(false))
            val writtenRuntime = File(project, "TeamCode/build/generated/ares/main/kotlin/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt")
            assertTrue(writtenRuntime.isFile)
            assertTrue(writtenRuntime.readText().contains("3.6"))

            // Cancel the active build
            buildService.killActiveBuildAndJoin()
            withTimeout(10_000L) {
                while (ProcessHandle.of(requireNotNull(generationPid)).map { it.isAlive }.orElse(false)) delay(20)
            }
            script.writeText(originalScript)

            // 1. Verify cancellation status and process cleanup
            assertFalse(buildService.processState.value.buildRunning, "Build running flag must be cleared after cancellation")
            assertEquals(AresGenerationPhase.FAILED, buildService.aresGenerationState.value.phase)
            val cancelMsg = buildService.aresGenerationState.value.message
            assertTrue(
                cancelMsg.contains("cancel", ignoreCase = true),
                "Generation state message must indicate cancellation: $cancelMsg"
            )

            // 2. Canonical documents and user-owned code must be intact
            val reloadedMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
            assertEquals(3.60, reloadedMetadata.fieldLengthMeters, "Canonical project.json must remain intact after cancellation")
            assertEquals(userExtensionContent, teamExtensionsFile.readText(), "USER-OWNED extension must remain intact after cancellation")
            assertEquals(canonicalBefore, consumerCanonicalSnapshot(project))

            // 3. Clean recovery: re-run generation to completion
            driver.generate(project)

            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)
            val manifestFile = File(project, "build/generated/ares/verification/ares-project-verification.json")
            assertTrue(manifestFile.isFile, "Verification manifest must exist after generation recovery")

            // 4. Verify resulting project with a complete verification build
            driver.verify(project)
            assertEquals(
                BuildExecutionPhase.SUCCEEDED,
                buildService.processState.value.buildExecution.phase,
                "Verification build must succeed after cancellation recovery: ${buildService.processState.value.buildExecution.message}"
            )
            assertEquals(0, buildService.processState.value.buildExecution.exitCode)
        } finally {
            releaseFile.parentFile.mkdirs()
            releaseFile.writeText("release test barrier")
            script.writeText(originalScript)
            buildService.shutdownAndJoin()
        }
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
