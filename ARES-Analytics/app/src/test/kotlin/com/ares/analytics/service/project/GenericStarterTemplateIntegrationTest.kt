package com.ares.analytics.service.project

import com.ares.analytics.shared.models.League
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end check for the generic, hardware-neutral FTC and FRC starter candidates.
 *
 * Unlike [OfficialProjectTemplateIntegrationTest], this test accepts local source archives whose
 * release repositories do not exist yet. It still exercises the production SHA-256 verification,
 * protected staging, identity personalization, generated-project validation, tests, simulation
 * tests, and packaging path before a candidate can be promoted to the official pinned list.
 */
class GenericStarterTemplateIntegrationTest {
    @Test
    fun `generic starter candidates create and build fresh FTC and FRC projects`() = runBlocking {
        val archiveDirectory = propertyOrEnvironment(
            "ares.genericStarterArchiveDir",
            "ARES_GENERIC_STARTER_ARCHIVE_DIR",
        )?.let(::File)
        val outputDirectory = propertyOrEnvironment(
            "ares.genericStarterOutputDir",
            "ARES_GENERIC_STARTER_OUTPUT_DIR",
        )?.let(::File)
        val validationRepository = propertyOrEnvironment(
            "ares.genericStarterValidationRepository",
            "ARES_GENERIC_STARTER_VALIDATION_REPOSITORY",
        )?.let(::validationRepositoryUri)
        val validationVersion = propertyOrEnvironment(
            "ares.genericStarterValidationVersion",
            "ARES_GENERIC_STARTER_VALIDATION_VERSION",
        )
        val templateVersion = propertyOrEnvironment(
            "ares.genericStarterTemplateVersion",
            "ARES_GENERIC_STARTER_TEMPLATE_VERSION",
        ) ?: "9.8.0"

        assumeTrue(archiveDirectory?.isDirectory == true && outputDirectory != null)
        require(validationRepository == null || !validationVersion.isNullOrBlank()) {
            "Set ARES_GENERIC_STARTER_VALIDATION_VERSION with the isolated validation repository."
        }

        val archiveRoot = requireNotNull(archiveDirectory).canonicalFile
        val output = requireNotNull(outputDirectory).canonicalFile
        output.mkdirs()
        val archives = mapOf(
            League.FTC to File(archiveRoot, "ftc.zip"),
            League.FRC to File(archiveRoot, "frc.zip"),
            League.XRP to File(archiveRoot, "xrp.zip"),
        )
        assertTrue(archives.values.all(File::isFile), "Create ftc.zip, frc.zip, and xrp.zip before this check.")

        val templates = League.entries.map { league ->
            val archive = requireNotNull(archives[league])
            val hash = sha256(archive)
            RobotProjectTemplate(
                id = "ares-${league.name.lowercase()}-generic-$templateVersion-${hash.take(12)}",
                displayName = "ARES ${league.name} Generic Starter",
                league = league,
                artifactVersion = templateVersion,
                aresVersion = templateVersion,
                revision = hash.take(16),
                archiveUrl = archive.toURI().toASCIIString(),
                archiveSha256 = hash,
                deploymentPolicy = RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
            )
        }
        val service = RobotProjectTemplateService(
            cacheDirectory = File(output, "cache"),
            templates = templates,
            archiveDownloader = { template, destination ->
                requireNotNull(archives[template.league]).copyTo(destination, overwrite = true)
            },
        )

        League.entries.forEach { league ->
            val destination = File(output, league.name.lowercase())
            if (destination.exists()) destination.deleteRecursively()
            val result = service.create(
                RobotProjectCreationRequest(
                    parentDirectory = output,
                    folderName = destination.name,
                    league = league,
                    teamId = "9988",
                    seasonId = "2027",
                    robotId = "Fresh${league.name}Robot",
                    robotName = "Fresh ${league.name} Robot",
                ),
            )
            assertTrue(result.destination.isDirectory)
            assertEquals(
                RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
                result.template.deploymentPolicy,
            )
            assertTrue(File(result.destination, ".ares/template-provenance.json").isFile)
            if (league == League.FTC) assertTrue(File(result.destination, "local.properties").isFile)
            if (validationRepository != null) {
                validateGeneratedProject(
                    project = result.destination,
                    league = league,
                    repositoryUri = validationRepository,
                    validationVersion = requireNotNull(validationVersion),
                )
            }
        }
    }

    private fun validateGeneratedProject(
        project: File,
        league: League,
        repositoryUri: String,
        validationVersion: String,
    ) {
        val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
        val command = buildList {
            if (windows) addAll(listOf("cmd.exe", "/c", "gradlew.bat")) else add("./gradlew")
            when (league) {
                League.FTC -> addAll(
                    listOf(
                        "generateAresProject",
                        ":TeamCode:verifyAresProject",
                        ":TeamCode:testDebugUnitTest",
                        ":simulator:test",
                        ":TeamCode:runVerification",
                        ":TeamCode:assembleDebug",
                    ),
                )
                League.FRC -> addAll(listOf("generateAresProject", "verifyAresProject", "test", "build"))
                League.XRP -> addAll(listOf("generateAresProject", "test"))
            }
            add("-ParesVersion=$validationVersion")
            add("-ParesRepository=$repositoryUri")
            addAll(listOf("--refresh-dependencies", "--no-parallel", "--no-daemon", "--console=plain"))
        }
        val log = File(project, "build/generic-starter-validation.log")
        log.parentFile.mkdirs()
        val process = ProcessBuilder(command)
            .directory(project)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        check(process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "${league.name} generic starter validation timed out. See ${log.path}."
        }
        check(process.exitValue() == 0) {
            "${league.name} generic starter validation failed with exit ${process.exitValue()}:\n${logTail(log)}"
        }
    }

    private fun logTail(file: File): String {
        val tail = ArrayDeque<String>(80)
        file.useLines { lines ->
            lines.forEach { line ->
                if (tail.size == 80) tail.removeFirst()
                tail.addLast(line)
            }
        }
        return tail.joinToString("\n")
    }

    private companion object {
        fun propertyOrEnvironment(property: String, environment: String): String? =
            System.getProperty(property) ?: System.getenv(environment)

        fun validationRepositoryUri(value: String): String {
            val file = if (value.startsWith("file:", ignoreCase = true)) File(URI(value)) else File(value)
            require(file.isDirectory) { "Generic-starter validation repository does not exist: $value" }
            return file.canonicalFile.toURI().toASCIIString()
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}
