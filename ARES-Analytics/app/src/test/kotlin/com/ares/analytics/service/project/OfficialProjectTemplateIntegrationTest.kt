package com.ares.analytics.service.project

import com.ares.analytics.shared.models.League
import com.ares.analytics.service.versioncontrol.DefaultGitHubProjectApi
import com.ares.analytics.service.versioncontrol.ProjectBackupCredentialStore
import com.ares.analytics.service.versioncontrol.ProjectVersionControlService
import com.ares.analytics.service.versioncontrol.ProjectArchiveExporter
import com.ares.analytics.service.versioncontrol.ProjectGitHubCredentialRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in release check for the real pinned archives.
 *
 * Run with `ARES_OFFICIAL_TEMPLATE_ARCHIVE_DIR=<download-dir>` and
 * `ARES_OFFICIAL_TEMPLATE_OUTPUT_DIR=<empty-build-dir>` (or matching `-Dares.*` properties).
 * Set `ARES_OFFICIAL_TEMPLATE_VALIDATE_PROJECTS=true` to also run generation, verification,
 * tests, simulator tests, packaging, and the FTC headless drivetrain acceptance run inside the
 * emitted projects using their normal immutable dependency repositories. A local validation
 * repository and matching candidate version can be supplied with
 * `ARES_OFFICIAL_TEMPLATE_VALIDATION_REPOSITORY` and `ARES_OFFICIAL_TEMPLATE_VALIDATION_VERSION`
 * while developing an unpublished ARESLib version; supplying them also enables project validation.
 * Normal unit runs skip this network/release-artifact boundary.
 */
class OfficialProjectTemplateIntegrationTest {
    @Test
    fun `official pinned archives create source-valid FTC and FRC projects`() = runBlocking {
        val archiveDirectory = (
            System.getProperty("ares.officialTemplateArchiveDir")
                ?: System.getenv("ARES_OFFICIAL_TEMPLATE_ARCHIVE_DIR")
            )?.let(::File)
        val outputDirectory = (
            System.getProperty("ares.officialTemplateOutputDir")
                ?: System.getenv("ARES_OFFICIAL_TEMPLATE_OUTPUT_DIR")
            )?.let(::File)
        val validationRepository = (
            System.getProperty("ares.officialTemplateValidationRepository")
                ?: System.getenv("ARES_OFFICIAL_TEMPLATE_VALIDATION_REPOSITORY")
            )?.let(::validationRepositoryUri)
        val validationVersion = System.getProperty("ares.officialTemplateValidationVersion")
            ?: System.getenv("ARES_OFFICIAL_TEMPLATE_VALIDATION_VERSION")
        val validateProjects = validationRepository != null || (
            System.getProperty("ares.officialTemplateValidateProjects")
                ?: System.getenv("ARES_OFFICIAL_TEMPLATE_VALIDATE_PROJECTS")
            ).toBoolean()
        assumeTrue(archiveDirectory?.isDirectory == true && outputDirectory != null)
        require(validationRepository == null || !validationVersion.isNullOrBlank()) {
            "Set ARES_OFFICIAL_TEMPLATE_VALIDATION_VERSION with the isolated validation repository."
        }

        val archiveRoot = requireNotNull(archiveDirectory).canonicalFile
        val output = requireNotNull(outputDirectory).canonicalFile
        output.mkdirs()
        val archives = mapOf(
            League.FTC to File(archiveRoot, "ftc.zip"),
            League.FRC to File(archiveRoot, "frc.zip"),
        )
        assertTrue(archives.values.all(File::isFile), "Download both pinned archives before this release check.")
        val service = RobotProjectTemplateService(
            cacheDirectory = File(output, "cache"),
            archiveDownloader = { template, destination ->
                requireNotNull(archives[template.league]).copyTo(destination, overwrite = true)
            },
        )
        val history = localHistoryService()

        League.entries.forEach { league ->
            val destination = File(output, league.name.lowercase())
            if (destination.exists()) destination.deleteRecursively()
            val result = service.create(
                RobotProjectCreationRequest(
                    parentDirectory = output,
                    folderName = destination.name,
                    league = league,
                    teamId = "23247",
                    seasonId = "2026",
                    robotId = "TemplateCheck${league.name}",
                    robotName = "Template Check ${league.name}",
                ),
                prepareStagedProject = { staged -> history.initializeNewProject(staged.path) },
            )
            assertTrue(result.destination.isDirectory)
            assertTrue(File(result.destination, ".ares/template-provenance.json").isFile)
            val initialHistory = history.inspect(result.destination.path)
            assertTrue(initialHistory.changes.isEmpty())
            assertTrue(initialHistory.versions.single().message.contains("Create robot project"))
            if (league == League.FTC) assertTrue(File(result.destination, "local.properties").isFile)
            if (validateProjects) {
                validateGeneratedProject(result.destination, league, validationRepository, validationVersion)
            }

            File(result.destination, ".ares/acceptance-checkpoint.txt").writeText("${league.name} zero-code journey complete")
            val generatedRuntime = when (league) {
                League.FTC -> "TeamCode/src/main/java/org/firstinspires/ftc/teamcode/generated/GeneratedAresProject.kt"
                League.FRC -> "src/main/kotlin/org/aresfirst/starter/frc/generated/GeneratedAresProject.kt"
            }
            val checkpoint = history.checkpoint(
                result.destination.path,
                "Complete ${league.name} zero-code acceptance journey",
                setOf(".ares/acceptance-checkpoint.txt", generatedRuntime),
            )
            assertTrue(requireNotNull(checkpoint).changes.isEmpty())
            val archive = File(output, "${league.name.lowercase()}-portable.zip")
            if (archive.exists()) archive.delete()
            ProjectArchiveExporter().export(result.destination.path, archive.path)
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                assertTrue(".ares/acceptance-checkpoint.txt" in names)
                assertTrue(names.none { it.startsWith(".git/") || it.startsWith("build/") || it.contains("/build/") })
            }
        }
        history.closeAndJoin()
    }

    private fun validateGeneratedProject(
        project: File,
        league: League,
        repositoryUri: String?,
        validationVersion: String?,
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
            }
            validationVersion?.let { add("-ParesVersion=$it") }
            repositoryUri?.let { add("-ParesRepository=$it") }
            addAll(listOf("--no-parallel", "--no-daemon", "--console=plain"))
        }
        val log = File(project, "build/official-template-validation.log")
        log.parentFile.mkdirs()
        val process = ProcessBuilder(command)
            .directory(project)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .start()
        check(process.waitFor(8, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            "${league.name} official template validation timed out. See ${log.path}."
        }
        check(process.exitValue() == 0) {
            "${league.name} official template validation failed with exit ${process.exitValue()}:\n${logTail(log)}"
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
        fun localHistoryService() = ProjectVersionControlService(
            githubClientId = "",
            githubAppSlug = "",
            credentialRepository = ProjectGitHubCredentialRepository(
                object : ProjectBackupCredentialStore {
                    override fun read(): ByteArray? = null
                    override fun write(bytes: ByteArray) = Unit
                    override fun delete(): Boolean = true
                    override val protectionDescription: String = "acceptance fixture"
                },
            ),
            githubApi = DefaultGitHubProjectApi(),
            browserLauncher = {},
            pollDelay = {},
        )

        fun validationRepositoryUri(value: String): String {
            val file = if (value.startsWith("file:", ignoreCase = true)) File(URI(value)) else File(value)
            require(file.isDirectory) { "Official-template validation repository does not exist: $value" }
            return file.canonicalFile.toURI().toASCIIString()
        }
    }
}
