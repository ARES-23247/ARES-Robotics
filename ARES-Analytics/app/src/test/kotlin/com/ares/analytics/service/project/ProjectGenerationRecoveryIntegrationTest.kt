package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.BuildExecutionPhase
import com.ares.analytics.service.ManagedToolchainPaths
import com.ares.analytics.service.ProjectBuildService
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

    private val candidateRepoUri = System.getProperty("ares.repository.uri")
        ?: "file:///C:/Users/david/dev/robotics/ARES-Robotics/.codex-validation/reviewed-project-roundtrip/ARESLib-Kotlin/build/release-repository"
    private val candidateVersion = System.getProperty("ares.version")
        ?: "19.1.3-rc.roundtrip.dbb5b9f.1"

    @Test
    fun `controlled intermediate-write failure leaves partial output, rejects stale build, preserves documents, and recovers cleanly`() = runBlocking {
        val workspace = temporaryDirectory.resolve("failure-recovery-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureSdk(project)

        // Mutate field configuration to verify generated output carries this change
        val metadataFile = File(project, ".ares/project.json")
        val originalMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
        val modifiedMetadata = originalMetadata.copy(fieldLengthMeters = 3.52, fieldWidthMeters = 3.52)
        metadataFile.writeText(AresProjectMetadataCodec.encode(modifiedMetadata))

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

        val buildService = ProjectBuildService(
            aresRepositoryUri = candidateRepoUri,
            aresVersion = candidateVersion,
        )
        try {
            // Trigger generation with intermediate obstruction
            buildService.generateAresProject(project.path, League.FTC)
            awaitGenerationFinished(buildService)

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

            // 4. Stale-output rejection: verification build must fail because generated outputs are unverified
            buildService.runBuild(project.path, League.FTC)
            awaitBuildFinished(buildService)
            assertEquals(
                BuildExecutionPhase.FAILED,
                buildService.processState.value.buildExecution.phase,
                "Verification build must fail when verification manifest is missing/obstructed"
            )

            // 5. Clean recovery: remove the obstruction and re-run generation
            assertTrue(manifestObstruction.deleteRecursively(), "Must successfully remove obstruction directory")
            buildService.generateAresProject(project.path, League.FTC)
            awaitGenerationFinished(buildService)

            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)
            assertTrue(manifestObstruction.isFile, "Verification manifest must now be written as a valid file")

            // 6. Verification build succeeds after clean recovery
            buildService.runBuild(project.path, League.FTC)
            awaitBuildFinished(buildService)
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
    fun `real generation cancellation terminates process, preserves documents, and recovers cleanly on retry`() = runBlocking {
        val workspace = temporaryDirectory.resolve("cancellation-recovery-workspace").toFile().apply { mkdirs() }
        val project = extractBundledBioBuzz(workspace)
        configureSdk(project)

        // Mutate configuration
        val metadataFile = File(project, ".ares/project.json")
        val originalMetadata = AresProjectMetadataCodec.decode(metadataFile.readText())
        val modifiedMetadata = originalMetadata.copy(fieldLengthMeters = 3.60, fieldWidthMeters = 3.60)
        metadataFile.writeText(AresProjectMetadataCodec.encode(modifiedMetadata))

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

        val buildService = ProjectBuildService(
            aresRepositoryUri = candidateRepoUri,
            aresVersion = candidateVersion,
        )
        try {
            // Start real generation
            buildService.generateAresProject(project.path, League.FTC)

            // Wait until the build operation is actively running
            withTimeout(30_000L) {
                while (!buildService.processState.value.buildRunning ||
                    buildService.aresGenerationState.value.phase != AresGenerationPhase.RUNNING
                ) {
                    delay(10)
                }
            }

            // Cancel the active build
            buildService.killActiveBuildAndJoin()

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

            // 3. Clean recovery: re-run generation to completion
            buildService.generateAresProject(project.path, League.FTC)
            awaitGenerationFinished(buildService)

            assertEquals(AresGenerationPhase.SUCCEEDED, buildService.aresGenerationState.value.phase)
            val manifestFile = File(project, "build/generated/ares/verification/ares-project-verification.json")
            assertTrue(manifestFile.isFile, "Verification manifest must exist after generation recovery")

            // 4. Verify resulting project with a complete verification build
            buildService.runBuild(project.path, League.FTC)
            awaitBuildFinished(buildService)
            assertEquals(
                BuildExecutionPhase.SUCCEEDED,
                buildService.processState.value.buildExecution.phase,
                "Verification build must succeed after cancellation recovery: ${buildService.processState.value.buildExecution.message}"
            )
            assertEquals(0, buildService.processState.value.buildExecution.exitCode)
        } finally {
            buildService.shutdownAndJoin()
        }
    }

    private suspend fun awaitGenerationFinished(service: ProjectBuildService, timeoutMs: Long = 180_000L) = withTimeout(timeoutMs) {
        while (!service.processState.value.buildRunning && service.aresGenerationState.value.phase != AresGenerationPhase.RUNNING) {
            delay(10)
        }
        while (service.processState.value.buildRunning || service.aresGenerationState.value.phase == AresGenerationPhase.RUNNING) {
            delay(20)
        }
    }

    private suspend fun awaitBuildFinished(service: ProjectBuildService, timeoutMs: Long = 240_000L) = withTimeout(timeoutMs) {
        while (!service.processState.value.buildRunning && service.processState.value.buildExecution.phase != BuildExecutionPhase.RUNNING) {
            delay(10)
        }
        while (service.processState.value.buildRunning || service.processState.value.buildExecution.phase == BuildExecutionPhase.RUNNING) {
            delay(20)
        }
    }

    private fun configureSdk(project: File) {
        val sdk = ManagedToolchainPaths.resolveAndroidSdk() ?: File(System.getProperty("user.home"), "AppData/Local/Android/Sdk")
        if (sdk.isDirectory) {
            File(project, "local.properties").writeText("sdk.dir=${sdk.path.replace('\\', '/')}\n")
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
