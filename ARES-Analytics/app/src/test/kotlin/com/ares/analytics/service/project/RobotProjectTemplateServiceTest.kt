package com.ares.analytics.service.project

import com.ares.analytics.BuildConfig
import com.ares.analytics.shared.models.League
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.defaultDrivebase
import com.ares.analytics.service.drivebase.toCanonicalDrivebase
import com.ares.analytics.service.hardware.HardwareReviewRequest
import com.ares.analytics.service.hardware.HardwareSetupService
import com.ares.analytics.service.tuning.TuningProfileRepository
import com.areslib.catalog.CapabilityCatalogCodec
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.schema.ProjectDocumentId
import com.areslib.routine.AutonomousCatalogCodec
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.state.RobotFieldAprilTag
import com.areslib.state.RobotFieldConfig
import com.areslib.state.RobotFieldDocument
import com.areslib.state.RobotFieldElementType
import com.areslib.tuning.TuningProfileAuthority
import com.areslib.tuning.TuningProfileDocument
import com.areslib.tuning.TuningProfileDocumentCodec
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class RobotProjectTemplateServiceTest {
    @Test
    fun `official generic starters require hardware review before deployment`() {
        val service = RobotProjectTemplateService()

        listOf(League.FTC, League.FRC, League.XRP).forEach { league ->
            val template = service.templateFor(league)
            assertEquals(BuildConfig.ARES_VERSION, template.aresVersion)
            assertTrue(template.displayName.endsWith("Starter"))
            assertEquals(
                RobotProjectDeploymentPolicy.HARDWARE_REVIEW_REQUIRED,
                template.deploymentPolicy,
            )
        }
    }

    @Test
    fun `official Lightbot example is a separate simulation-only template`() {
        val service = RobotProjectTemplateService()
        val template = service.templateFor(League.FTC, RobotProjectTemplateKind.EXAMPLE)

        assertEquals("Lightbot", template.displayName)
        assertEquals(BuildConfig.ARES_VERSION, template.aresVersion)
        assertEquals(BuildConfig.LIGHTBOT_EXAMPLE_VERSION, template.id.substringAfterLast('-'))
        assertEquals(RobotProjectDeploymentPolicy.SIMULATION_ONLY_REFERENCE, template.deploymentPolicy)
        assertTrue(template.bundledResourcePath!!.contains("ARES-Lightbot-Example"))
    }

    @Test
    fun `long robot identities produce bounded collision-resistant runtime document ids`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-template-bounded-ids").toFile()
        try {
            val service = service(root, validFtcArchive())
            val parent = File(root, "robots").apply { mkdirs() }
            val result = service.create(
                request(parent, "long-identity").copy(
                    robotId = "TemplateCheckFtcWithAnIntentionallyLongStudentFacingRobotName",
                    robotName = "Template Check FTC With A Long Name",
                ),
            )

            val drivebase = DrivetrainDocumentCodec.decode(
                File(result.destination, ".ares/drivetrains/template.aresdrivetrain").readText(),
            )
            val tuning = TuningProfileRepository().load(result.destination.path).getOrThrow().profiles.single()

            ProjectDocumentId(drivebase.uid)
            ProjectDocumentId(tuning.uid)
            assertEquals(drivebase.uid, tuning.drivebaseUid)
            assertEquals(tuning.uid, drivebase.canonicalProfileUid)
            assertTrue(drivebase.uid.length <= 64)
            assertTrue(tuning.uid.length <= 64)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verified starter is staged personalized and published without merging`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-template-test").toFile()
        try {
            val archive = validFtcArchive()
            val service = service(root, archive)
            val parent = File(root, "robots").apply { mkdirs() }

            val result = service.create(
                request(parent, "student-robot").copy(authoringModel = AresProjectAuthoringModel.CODE_FIRST),
            )

            assertEquals(RobotProjectTemplateSource.VERIFIED_DOWNLOAD, result.source)
            assertTrue(result.destination.isDirectory)
            assertTrue(File(result.destination, "TeamCode/src/main/java/example/Robot.kt").isFile)
            assertFalse(File(result.destination, ".ares-robot.json").exists())
            val metadata = AresProjectMetadataCodec.decode(File(result.destination, ".ares/project.json").readText())
            assertEquals("team23247-studentbot", metadata.projectId)
            assertEquals("23247", metadata.identity.teamId)
            assertEquals("2026", metadata.identity.seasonId)
            assertEquals("StudentBot", metadata.identity.robotId)
            assertEquals("Student Robot", metadata.identity.displayName)
            assertEquals(AresProjectAuthoringModel.CODE_FIRST, metadata.authoringModel)
            assertEquals(
                metadata.projectId,
                CapabilityCatalogCodec.decode(File(result.destination, ".ares/action-catalog.json").readText()).projectId,
            )
            assertEquals(
                metadata.projectId,
                AutonomousCatalogCodec.decode(File(result.destination, ".ares/autonomous-catalog.json").readText()).projectId,
            )
            val provenance = File(result.destination, ".ares/template-provenance.json").readText()
            assertTrue(provenance.contains("fixture-revision"))
            assertTrue(provenance.contains("SIMULATION_ONLY_REFERENCE"))

            val drivebase = DrivetrainDocumentCodec.decode(
                File(result.destination, ".ares/drivetrains/template.aresdrivetrain").readText(),
            )
            val tuning = com.ares.analytics.service.tuning.TuningProfileRepository()
                .load(result.destination.path).getOrThrow().profiles.single()
            assertEquals("team23247-studentbot.drivebase.primary", drivebase.uid)
            assertEquals("team23247-studentbot.profile.competition", drivebase.canonicalProfileUid)
            assertEquals(drivebase.canonicalProfileUid, tuning.uid)
            assertEquals("team23247-studentbot", tuning.projectId)
            assertEquals(drivebase.uid, tuning.drivebaseUid)
            val localProperties = File(result.destination, "local.properties").readText()
            assertTrue(localProperties.startsWith("sdk.dir="))
            assertTrue(localProperties.contains("fixture-android-sdk"))
            assertNotNull(templateDeploymentBlockReason(result.destination))
            assertFalse(parent.listFiles().orEmpty().any { it.name.contains("ares-partial") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `staged project preparation completes before publish and rolls back atomically on failure`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-history-staging-test").toFile()
        try {
            val archive = validFtcArchive()
            val parent = File(root, "robots").apply { mkdirs() }
            val service = service(root, archive)

            val created = service.create(
                request(parent, "history-ready"),
                prepareStagedProject = { staging -> File(staging, ".history-ready").writeText("yes") },
            )
            assertEquals("yes", File(created.destination, ".history-ready").readText())

            val failure = assertFailsWith<IllegalStateException> {
                service.create(
                    request(parent, "history-failed"),
                    prepareStagedProject = { error("Local history could not be created") },
                )
            }
            assertTrue(failure.message.orEmpty().contains("Local history"))
            assertFalse(File(parent, "history-failed").exists())
            assertFalse(parent.listFiles().orEmpty().any { it.name.contains("history-failed.ares-partial") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cloud backed folder falls back to a non-replacing move`() {
        val root = Files.createTempDirectory("ares-project-cloud-move-test").toFile()
        try {
            val staging = File(root, ".student-robot.ares-partial-fixture").apply { mkdirs() }
            File(staging, "robot.txt").writeText("ready")
            val destination = File(root, "student-robot")
            val optionsByAttempt = mutableListOf<List<java.nio.file.CopyOption>>()

            publishProjectDirectory(staging.toPath(), destination.toPath()) { source, target, options ->
                optionsByAttempt += options.toList()
                if (optionsByAttempt.size == 1) {
                    throw IOException("Cloud provider rejected the atomic rename")
                }
                Files.move(source, target, *options)
            }

            assertEquals(listOf(StandardCopyOption.ATOMIC_MOVE), optionsByAttempt.single { it.isNotEmpty() })
            assertTrue(optionsByAttempt.last().isEmpty())
            assertFalse(staging.exists())
            assertEquals("ready", File(destination, "robot.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cloud fallback never replaces a destination that appears concurrently`() {
        val root = Files.createTempDirectory("ares-project-cloud-race-test").toFile()
        try {
            val staging = File(root, ".student-robot.ares-partial-fixture").apply { mkdirs() }
            File(staging, "staged.txt").writeText("staged")
            val destination = File(root, "student-robot")
            var attempts = 0

            assertFailsWith<FileAlreadyExistsException> {
                publishProjectDirectory(staging.toPath(), destination.toPath()) { _, target, _ ->
                    attempts++
                    if (attempts == 2) {
                        target.toFile().mkdirs()
                        File(target.toFile(), "existing.txt").writeText("owned elsewhere")
                    }
                    throw IOException("Provider rejected rename attempt $attempts")
                }
            }

            assertEquals(2, attempts)
            assertEquals("staged", File(staging, "staged.txt").readText())
            assertEquals("owned elsewhere", File(destination, "existing.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `failed project publication removes only its unique staging directory`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-publish-cleanup-test").toFile()
        try {
            val archive = validFtcArchive()
            val parent = File(root, "robots").apply { mkdirs() }
            val unrelated = File(parent, "existing-project").apply { mkdirs() }
            File(unrelated, "keep.txt").writeText("keep")
            val service = service(
                root,
                archive,
                projectPublisher = { _, _ -> throw IOException("Provider unavailable") },
            )

            assertFailsWith<IOException> { service.create(request(parent, "student-robot")) }

            assertFalse(File(parent, "student-robot").exists())
            assertFalse(parent.listFiles().orEmpty().any { it.name.contains("ares-partial") })
            assertEquals("keep", File(unrelated, "keep.txt").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `verified cache supports a second offline creation`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-cache-test").toFile()
        try {
            val archive = validFtcArchive()
            var downloads = 0
            val service = service(root, archive) { _, destination ->
                downloads++
                destination.writeBytes(archive)
            }
            val parent = File(root, "robots").apply { mkdirs() }

            service.create(request(parent, "first-robot"))
            val second = service.create(request(parent, "second-robot"))

            assertEquals(1, downloads)
            assertEquals(RobotProjectTemplateSource.VERIFIED_CACHE, second.source)
            assertTrue(File(second.destination, ".ares/template-provenance.json").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `installer bundled starter creates offline then populates verified cache`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-bundled-test").toFile()
        try {
            val archive = validFtcArchive()
            val template = template(archive).copy(bundledResourcePath = "/fixture-starter.zip")
            var downloads = 0
            var bundledReads = 0
            val service = RobotProjectTemplateService(
                cacheDirectory = File(root, "cache"),
                templates = listOf(template),
                archiveDownloader = { _, _ -> downloads++ },
                bundledResourceLoader = { path ->
                    assertEquals("/fixture-starter.zip", path)
                    bundledReads++
                    ByteArrayInputStream(archive)
                },
                androidSdkLocator = { File(root, "fixture-android-sdk").apply { mkdirs() } },
            )
            val parent = File(root, "robots").apply { mkdirs() }

            val first = service.create(request(parent, "first-bundled"))
            val second = service.create(request(parent, "second-from-cache"))

            assertEquals(RobotProjectTemplateSource.VERIFIED_BUNDLED, first.source)
            assertEquals(RobotProjectTemplateSource.VERIFIED_CACHE, second.source)
            assertEquals(1, bundledReads)
            assertEquals(0, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `official bundled starter resources match their pinned hashes`() {
        RobotProjectTemplateService.OFFICIAL_PROJECT_TEMPLATES.forEach { template ->
            val path = assertNotNull(template.bundledResourcePath)
            val bytes = assertNotNull(RobotProjectTemplateService::class.java.getResourceAsStream(path)).use { it.readBytes() }
            assertEquals(template.archiveSha256, sha256(bytes), template.displayName)
        }
    }

    @Test
    fun `official bundled starters create projects accepted by current tuning schema`() = runBlocking {
        val root = Files.createTempDirectory("ares-official-starter-schema-test").toFile()
        try {
            RobotProjectTemplateService.OFFICIAL_PROJECT_TEMPLATES.forEach { template ->
                val service = RobotProjectTemplateService(
                    cacheDirectory = File(root, "cache-${template.id}"),
                    templates = listOf(template),
                    archiveDownloader = { _, _ -> error("Bundled starter unexpectedly attempted a download") },
                    bundledResourceLoader = { resourcePath ->
                        RobotProjectTemplateService::class.java.getResourceAsStream(resourcePath)
                    },
                    androidSdkLocator = { File(root, "fixture-android-sdk").apply { mkdirs() } },
                )
                val parent = File(root, "projects-${template.id}").apply { mkdirs() }
                val project = service.create(
                    request(parent, "student-${template.id}").copy(
                        league = template.league,
                        templateKind = template.kind,
                    ),
                )

                val tuning = TuningProfileRepository().load(project.destination.path)
                assertTrue(tuning.isSuccess, "${template.displayName}: ${tuning.exceptionOrNull()?.message}")
                assertTrue(tuning.getOrThrow().profiles.isNotEmpty(), "${template.displayName} has no tuning profile")
                if (template.kind == RobotProjectTemplateKind.EXAMPLE) {
                    assertTrue(File(project.destination, ".ares/subsystems/indicator-lights.aressubsystem").isFile)
                    assertTrue(File(project.destination, ".ares/subsystems/prism.aressubsystem").isFile)
                    val packagedHashBeforeEdit = sha256(
                        assertNotNull(
                            RobotProjectTemplateService::class.java.getResourceAsStream(template.bundledResourcePath!!),
                        ).use { it.readBytes() },
                    )
                    File(project.destination, ".ares/project.json").appendText("\n")
                    val packagedHashAfterEdit = sha256(
                        assertNotNull(
                            RobotProjectTemplateService::class.java.getResourceAsStream(template.bundledResourcePath),
                        ).use { it.readBytes() },
                    )
                    assertEquals(packagedHashBeforeEdit, packagedHashAfterEdit)
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `reviewed initial field preset keeps starter simulation content and installs tags`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-field-preset-test").toFile()
        try {
            val archive = validFtcArchive()
            val preset = RobotFieldConfig(
                revision = 7,
                id = "fixture-season",
                name = "Fixture Season",
                gameYear = "2026",
                widthMeters = 3.6576,
                heightMeters = 3.6576,
                apriltags = listOf(
                    RobotFieldAprilTag(
                        id = 20,
                        name = "Blue target 20",
                        family = "36h11",
                        sizeMeters = 0.1651,
                        x = -1.48,
                        y = -1.41,
                    ),
                ),
            )
            val service = RobotProjectTemplateService(
                cacheDirectory = File(root, "cache"),
                templates = listOf(template(archive)),
                archiveDownloader = { _, destination -> destination.writeBytes(archive) },
                bundledResourceLoader = { path ->
                    assertEquals("/fixture-field.json", path)
                    ByteArrayInputStream(RobotFieldDocument.encode(preset).toByteArray())
                },
                androidSdkLocator = { File(root, "fixture-android-sdk").apply { mkdirs() } },
            )
            val parent = File(root, "robots").apply { mkdirs() }

            val project = service.create(
                request(parent, "demo").copy(initialFieldPresetResourcePath = "/fixture-field.json"),
            )
            val field = RobotFieldDocument.decode(
                File(project.destination, "TeamCode/src/main/assets/paths/field.json").readText(),
            )

            assertEquals("Student Robot Field", field.name)
            assertEquals("2026", field.gameYear)
            assertEquals(listOf(20), field.apriltags.map { it.id })
            assertEquals(listOf("fixture-piece"), field.elementTypes.map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `hash mismatch and zip slip both leave destination absent`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-reject-test").toFile()
        try {
            val parent = File(root, "robots").apply { mkdirs() }
            val valid = validFtcArchive()
            val badHashTemplate = template(valid).copy(archiveSha256 = "0".repeat(64))
            val badHashService = RobotProjectTemplateService(
                cacheDirectory = File(root, "bad-hash-cache"),
                templates = listOf(badHashTemplate),
                archiveDownloader = { _, destination -> destination.writeBytes(valid) },
            )
            assertFailsWith<IllegalStateException> { badHashService.create(request(parent, "bad-hash")) }
            assertFalse(File(parent, "bad-hash").exists())

            val malicious = zipOf("fixture-root/../../outside.txt" to "escape")
            val maliciousService = service(File(root, "malicious-cache"), malicious)
            assertFailsWith<IllegalStateException> { maliciousService.create(request(parent, "bad-zip")) }
            assertFalse(File(parent, "bad-zip").exists())
            assertFalse(File(root, "outside.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `starter dependency version must match its pinned template identity`() = runBlocking {
        val root = Files.createTempDirectory("ares-project-version-mismatch-test").toFile()
        try {
            val archive = validFtcArchive(aresVersion = "different-version")
            val service = service(root, archive)
            val parent = File(root, "robots").apply { mkdirs() }

            val failure = assertFailsWith<IllegalStateException> {
                service.create(request(parent, "mismatched-starter"))
            }

            assertTrue(failure.message.orEmpty().contains("declares ARES different-version"))
            assertFalse(File(parent, "mismatched-starter").exists())
            assertFalse(parent.listFiles().orEmpty().any { it.name.contains("ares-partial") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `plan rejects traversal reserved names and existing destinations`() {
        val root = Files.createTempDirectory("ares-project-plan-test").toFile()
        try {
            val parent = File(root, "robots").apply { mkdirs() }
            val service = service(root, validFtcArchive())
            assertFalse(service.plan(request(parent, "../escape")).canCreate)
            assertFalse(service.plan(request(parent, "CON")).canCreate)
            File(parent, "already-here").mkdirs()
            val existing = service.plan(request(parent, "already-here"))
            assertFalse(existing.canCreate)
            assertTrue(existing.issues.any { it.contains("already exists") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `normal projects remain deployable while invalid provenance fails closed`() {
        val root = Files.createTempDirectory("ares-project-deploy-policy-test").toFile()
        try {
            assertNull(templateDeploymentBlockReason(root))
            val provenance = File(root, ".ares/template-provenance.json")
            provenance.parentFile.mkdirs()
            provenance.writeText("not-json")
            assertTrue(templateDeploymentBlockReason(root)!!.contains("provenance is invalid"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `hardware checklist policy blocks until the current canonical mapping is reviewed`() {
        val root = Files.createTempDirectory("ares-hardware-policy-test").toFile()
        try {
            val ares = File(root, ".ares").apply { mkdirs() }
            File(ares, "project.json").writeText(
                AresProjectMetadataCodec.encode(
                    AresProjectMetadataDocument(
                projectId = "team1-robot",
                identity = com.areslib.project.AresProjectIdentityDocument("1", "2026", "robot", "Robot"),
                        league = AresLeague.FTC,
                        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                        robotLengthMeters = 0.45,
                        robotWidthMeters = 0.45,
                        fieldLengthMeters = 3.6576,
                        fieldWidthMeters = 3.6576,
                        runtimeOptions = com.areslib.project.AresRuntimeOptionsDocument(
                            ftc = com.areslib.project.AresFtcRuntimeOptionsDocument(),
                        ),
                    ),
                ),
            )
            DrivebaseProjectRepository().saveReviewed(
                root.path,
                expectedContentHash = null,
                document = defaultDrivebase("team1-robot", DrivebaseKind.FTC_MECANUM, League.FTC),
            )
            File(ares, "template-provenance.json").writeText(
                """{"schemaVersion":1,"templateId":"generic-ftc","templateRevision":"abc","templateArchiveSha256":"deadbeef","aresVersion":"6.1.0","deploymentPolicy":"HARDWARE_REVIEW_REQUIRED"}""",
            )

            assertTrue(templateDeploymentBlockReason(root)!!.contains("has not been compared"))
            HardwareSetupService().saveReview(
                root.path,
                League.FTC,
                HardwareReviewRequest("Team member", true, true, true, true, true),
            )
            assertNull(templateDeploymentBlockReason(root))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun service(
        root: File,
        archive: ByteArray,
        projectPublisher: (Path, Path) -> Unit = { staging, destination ->
            publishProjectDirectory(staging, destination)
        },
        downloader: (RobotProjectTemplate, File) -> Unit = { _, destination -> destination.writeBytes(archive) },
    ): RobotProjectTemplateService = RobotProjectTemplateService(
        cacheDirectory = File(root, "cache"),
        templates = listOf(template(archive)),
        archiveDownloader = downloader,
        androidSdkLocator = { File(root, "fixture-android-sdk").apply { mkdirs() } },
        projectPublisher = projectPublisher,
    )

    private fun request(parent: File, folder: String) = RobotProjectCreationRequest(
        parentDirectory = parent,
        folderName = folder,
        league = League.FTC,
        teamId = "23247",
        seasonId = "2026",
        robotId = "StudentBot",
        robotName = "Student Robot",
    )

    private fun template(archive: ByteArray) = RobotProjectTemplate(
        id = "fixture-ftc",
        displayName = "Fixture FTC",
        league = League.FTC,
        aresVersion = "test",
        revision = "fixture-revision",
        archiveUrl = "https://invalid.example/fixture.zip",
        archiveSha256 = sha256(archive),
    )

    private fun validFtcArchive(aresVersion: String = "test"): ByteArray {
        val metadata = AresProjectMetadataCodec.encode(
            AresProjectMetadataDocument(
        projectId = "template-project",
        identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "template-robot", "Template Robot"),
                league = AresLeague.FTC,
                coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = 0.45,
                robotWidthMeters = 0.45,
                fieldLengthMeters = 3.65,
                fieldWidthMeters = 3.65,
                runtimeOptions = com.areslib.project.AresRuntimeOptionsDocument(
                    ftc = com.areslib.project.AresFtcRuntimeOptionsDocument(),
                ),
            ),
        )
        val drivebase = defaultDrivebase("template-project", DrivebaseKind.FTC_MECANUM, League.FTC).toCanonicalDrivebase()
        val profile = TuningProfileDocument(
            uid = drivebase.canonicalProfileUid,
            profileId = "competition",
            displayName = "Competition",
            description = "Reviewed fixture tuning",
            projectId = drivebase.canonicalProfileUid.substringBefore(".profile."),
            drivebaseUid = drivebase.uid,
            authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
            values = emptyList(),
        )
        val field = RobotFieldConfig(
            revision = 1,
            id = "fixture-field",
            name = "Fixture Field",
            widthMeters = 3.6576,
            heightMeters = 3.6576,
            elementTypes = listOf(RobotFieldElementType(id = "fixture-piece", name = "Fixture Piece")),
        )
        return zipOf(
            "fixture-root/settings.gradle" to "include ':TeamCode'\n",
            "fixture-root/release/ares-versions.properties" to """
                aresVersion=$aresVersion
                studioVersion=2.0.0
                ftcStarterVersion=$aresVersion
                frcStarterVersion=$aresVersion
                githubMavenRepository=https://raw.githubusercontent.com/ARES-23247/ARES-Robotics/maven
            """.trimIndent() + "\n",
            "fixture-root/TeamCode/src/main/java/example/Robot.kt" to "package example\nclass Robot\n",
            "fixture-root/.ares/project.json" to metadata,
            "fixture-root/.ares/action-catalog.json" to CapabilityCatalogCodec.encode(
                CapabilityCatalogDocument(projectId = "template-project"),
            ),
            "fixture-root/.ares/autonomous-catalog.json" to AutonomousCatalogCodec.encode(
                AutonomousCatalogDocument(projectId = "template-project", entries = emptyList()),
            ),
            "fixture-root/.ares/drivetrains/template.aresdrivetrain" to DrivetrainDocumentCodec.encode(drivebase),
            "fixture-root/.ares/tuning/competition.arestuning" to
                TuningProfileDocumentCodec.encode(profile, drivebase.parameters),
            "fixture-root/TeamCode/src/main/assets/paths/field.json" to RobotFieldDocument.encode(field),
        )
    }

    private fun zipOf(vararg files: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
