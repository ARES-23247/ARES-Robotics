package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.AresProjectDocuments
import com.ares.analytics.service.project.persistence.*

import com.areslib.catalog.ActionDescriptor
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlBindingDocument
import com.areslib.controls.ControlEvent
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlSourceDocument
import com.areslib.controls.ControlSourceKind
import com.areslib.controls.ControlTargetDocument
import com.areslib.controls.ControlTargetKind
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.routine.AutonomousCatalogDocument
import com.areslib.routine.AutonomousCatalogEntry
import com.areslib.routine.AresRoutineCodec
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutinePose
import com.areslib.routine.RoutineStep
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectDocumentRepositoriesTest {
    @Test
    fun `canonical project geometry round trips through project json`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val metadata = AresProjectMetadataDocument(
            projectId = "test-project",
            identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
                league = AresLeague.FTC,
                coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                robotLengthMeters = .45,
                robotWidthMeters = .43,
                fieldLengthMeters = 3.6576,
                fieldWidthMeters = 3.6576
            )
            val hash = repository.save(project.path, metadata)

            assertEquals(canonical(metadata), repository.load(project.path).getOrThrow())
            assertEquals(64, hash.length)
            assertTrue(File(project, ".ares/project.json").isFile)
        }
    }

    @Test
    fun `reviewed project identity creates only after an explicit missing-file preview`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val metadata = metadata(robotLengthMeters = .45)

            val saved = repository.saveReviewed(project.path, expectedContentHash = null, document = metadata)

            assertTrue(saved.created)
            assertEquals(null, saved.historyFile)
            assertEquals(canonical(metadata), repository.load(project.path).getOrThrow())
        }
    }

    @Test
    fun `reviewed project identity checkpoints prior valid bytes before replacement`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val first = metadata(robotLengthMeters = .45)
            val firstSaved = repository.saveReviewed(project.path, expectedContentHash = null, first)
            val second = metadata(robotLengthMeters = .51)

            val secondSaved = repository.saveReviewed(project.path, firstSaved.contentHash, second)

            assertFalse(secondSaved.created)
            val history = requireNotNull(secondSaved.historyFile)
            assertEquals(canonical(first), AresProjectMetadataCodec.decode(history.readText()))
            assertEquals(canonical(second), repository.load(project.path).getOrThrow())
        }
    }

    @Test
    fun `reviewed project identity refuses stale preview without replacing current bytes`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val first = repository.saveReviewed(project.path, null, metadata(robotLengthMeters = .45))
            val concurrent = repository.saveReviewed(project.path, first.contentHash, metadata(robotLengthMeters = .47))
            val currentFile = repository.file(project.path)
            val bytesBeforeStaleSave = currentFile.readText()

            val failure = assertFailsWith<IllegalArgumentException> {
                repository.saveReviewed(project.path, first.contentHash, metadata(robotLengthMeters = .52))
            }

            assertTrue(failure.message.orEmpty().contains("changed after preview"))
            assertEquals(concurrent.contentHash, AresProjectMetadataCodec.contentHash(repository.load(project.path).getOrThrow()))
            assertEquals(bytesBeforeStaleSave, currentFile.readText())
        }
    }

    @Test
    fun `reviewed project identity preserves corrupt current file`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val file = repository.file(project.path).apply {
                parentFile.mkdirs()
                writeText("student recovery evidence")
            }

            assertFailsWith<IllegalArgumentException> {
                repository.saveReviewed(
                    project.path,
                    expectedContentHash = null,
                    document = metadata(robotLengthMeters = .45),
                )
            }
            assertEquals("student recovery evidence", file.readText())
        }
    }

    @Test
    fun `reviewed legacy migration preserves both old files and removes duplicate identity`() {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val projectFile = repository.file(project.path).apply {
                parentFile.mkdirs()
                writeText(
                    """{
                      "schemaVersion": 2,
                      "projectId": "legacy-project",
                      "league": "FTC",
                      "coordinateConvention": "CENTER_ORIGIN_CCW",
                      "robotLengthMeters": 0.45,
                      "robotWidthMeters": 0.43,
                      "fieldLengthMeters": 3.6576,
                      "fieldWidthMeters": 3.6576,
                      "runtimeOptions": {"ftc": {"hubCommandTransport": "STANDARD_SDK", "limelightProxyEnabled": false}}
                    }""",
                )
            }
            val legacyIdentity = File(project, ".ares-robot.json").apply {
                writeText(
                    """{"teamId":"23247","seasonId":"2026","robotId":"lightbot","name":"Lightbot","league":"FTC"}""",
                )
            }
            val oldProjectBytes = projectFile.readBytes()
            val oldIdentityBytes = legacyIdentity.readBytes()

            val candidate = repository.legacyMigrationCandidate(project.path).getOrThrow()
            val saved = repository.repairReviewed(project.path, repository.rawContentHash(project.path), candidate)

            assertTrue(saved.repaired)
            assertEquals("Lightbot", saved.document.identity.displayName)
            assertFalse(legacyIdentity.exists())
            assertTrue(
                File(project, ".ares/recovery/project").walkTopDown()
                    .filter(File::isFile)
                    .any { it.readBytes().contentEquals(oldProjectBytes) },
            )
            assertTrue(
                File(project, ".ares/recovery/identity").walkTopDown()
                    .filter(File::isFile)
                    .any { it.readBytes().contentEquals(oldIdentityBytes) },
            )
            assertEquals(candidate, repository.load(project.path).getOrThrow())
        }
    }

    @Test
    fun `routine saves are atomic deterministic and restorable as new revisions`() {
        withProject { project ->
            val repository = RoutineProjectRepository()
            val first = repository.save(project.path, routine("score", "Score", 0.1))
            assertEquals(1, first.document.revision)
            assertTrue(first.createdRevision)

            val unchanged = repository.save(project.path, first.document)
            assertFalse(unchanged.createdRevision)

            val second = repository.save(project.path, first.document.copy(steps = listOf(RoutineStep.wait(0.2))))
            assertEquals(2, second.document.revision)
            assertEquals(first.contentHash, second.document.parentContentHash)

            val restored = repository.restore(project.path, "score", first.contentHash)
            assertEquals(3, restored.document.revision)
            assertEquals(second.contentHash, restored.document.parentContentHash)
            assertEquals(0.1, restored.document.steps.single().durationSeconds)
            assertEquals(listOf(3, 2, 1), repository.listRevisions(project.path, "score").map { it.revision })
            assertTrue(project.walkTopDown().none { it.extension == "tmp" })
        }
    }

    @Test
    fun `concurrent routine saves allocate one linear revision chain`() {
        withProject { project ->
            val repository = RoutineProjectRepository()
            repository.save(project.path, routine("score", "Score", 0.1))
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val futures = listOf(0.2, 0.3).map { duration ->
                    executor.submit<SavedProjectRevision<RoutineDocument>> {
                        start.await()
                        repository.save(project.path, routine("score", "Score", duration))
                    }
                }
                start.countDown()
                assertEquals(setOf(2, 3), futures.map { it.get().document.revision }.toSet())
                assertEquals(listOf(3, 2, 1), repository.listRevisions(project.path, "score").map { it.revision })
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `listing is deterministic and reports corrupt files without hiding good documents`() {
        withProject { project ->
            val repository = RoutineProjectRepository()
            repository.save(project.path, routine("z-last", "Zulu", 0.1))
            repository.save(project.path, routine("a-first", "Alpha", 0.1))
            File(project, ".ares/routines/broken.aresroutine").writeText("{not-json")

            val listing = repository.list(project.path)

            assertEquals(listOf("a-first", "z-last"), listing.documents.map { it.documentId })
            assertEquals("broken.aresroutine", listing.diagnostics.single().file.name)
            assertTrue(listing.diagnostics.single().message.isNotBlank())
        }
    }

    @Test
    fun `routine listing rejects file identity mismatches and duplicate decoded ids`() {
        withProject { project ->
            val directory = File(project, ".ares/routines").apply { mkdirs() }
            val encoded = AresRoutineCodec.encode(routine("score", "Score", 0.1))
            File(directory, "score.aresroutine").writeText(encoded)
            File(directory, "renamed-copy.aresroutine").writeText(encoded)

            val listing = RoutineProjectRepository().list(project.path)

            assertTrue(listing.documents.isEmpty())
            assertTrue(listing.diagnostics.any { it.message.contains("does not match documentId 'score'") })
            assertEquals(2, listing.diagnostics.count { it.message.contains("Duplicate documentId 'score'") })
        }
    }

    @Test
    fun `load and save refuse current files whose body declares another id`() {
        withProject { project ->
            val file = File(project, ".ares/routines/score.aresroutine").apply {
                parentFile.mkdirs()
                writeText(AresRoutineCodec.encode(routine("other", "Other", 0.1)))
            }
            val repository = RoutineProjectRepository()

            assertFailsWith<IllegalArgumentException> { repository.load(project.path, "score") }
            assertFailsWith<IllegalArgumentException> {
                repository.save(project.path, routine("score", "Score", 0.2))
            }
            assertEquals("other", AresRoutineCodec.decode(file.readText()).documentId)
        }
    }

    @Test
    fun `document ids cannot escape their canonical directory`() {
        withProject { project ->
            val error = assertFailsWith<IllegalArgumentException> {
                RoutineProjectRepository().load(project.path, "../outside")
            }
            assertTrue(error.message.orEmpty().contains("Invalid project document ID"))
            assertFalse(File(project.parentFile, "outside.aresroutine").exists())
        }
    }

    @Test
    fun `corrupt current content is never overwritten by save`() {
        withProject { project ->
            val file = File(project, ".ares/routines/score.aresroutine").apply {
                parentFile.mkdirs()
                writeText("student recovery evidence")
            }

            assertFailsWith<IllegalArgumentException> {
                RoutineProjectRepository().save(project.path, routine("score", "Score", 0.1))
            }
            assertEquals("student recovery evidence", file.readText())
        }
    }

    @Test
    fun `all canonical documents load and cross validate without a robot`() {
        withProject { project ->
            val documents = AresProjectDocuments()
            documents.metadata.save(
                project.path,
                AresProjectMetadataDocument(
                projectId = "test-project",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
                    league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = .45,
                    robotWidthMeters = .45,
                    fieldLengthMeters = 3.6576,
                    fieldWidthMeters = 3.6576
                )
            )
            documents.routines.save(project.path, routine("score", "Score", 0.1))
            documents.controllers.save(project.path, controllerProfile())
            documents.capabilities.save(project.path, capabilityCatalog())
            documents.controls.save(project.path, controlScheme())
            documents.autonomous.save(project.path, autonomousCatalog())

            val snapshot = documents.load(project.path)

            assertEquals(project.canonicalPath, snapshot.projectRoot)
            assertEquals(listOf("score"), snapshot.query.routines.map { it.documentId })
            assertEquals(listOf("competition"), snapshot.query.controlSchemes.map { it.documentId })
            assertEquals(listOf("vader5-pro"), snapshot.query.controllerProfiles.map { it.documentId })
            assertNotNull(snapshot.query.capabilityCatalog)
            assertNotNull(snapshot.query.autonomousCatalog)
            assertEquals("test-project", snapshot.query.metadata?.projectId)
            assertTrue(snapshot.diagnostics.isEmpty(), snapshot.diagnostics.joinToString { it.message })
            assertTrue(File(project, ".ares/action-catalog.json").isFile)
            assertTrue(File(project, ".ares/autonomous-catalog.json").isFile)
        }
    }

    @Test
    fun `cross validation uses the active robot platform instead of desktop mappings`() {
        withProject { project ->
            val documents = AresProjectDocuments()
            documents.metadata.save(
                project.path,
                AresProjectMetadataDocument(
                projectId = "test-project",
                identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
                    league = AresLeague.FTC,
                    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
                    robotLengthMeters = .45,
                    robotWidthMeters = .45,
                    fieldLengthMeters = 3.6576,
                    fieldWidthMeters = 3.6576
                )
            )
            documents.routines.save(project.path, routine("score", "Score", 0.1))
            documents.controllers.save(project.path, controllerProfile())
            documents.capabilities.save(project.path, capabilityCatalog())
            documents.controls.save(project.path, controlScheme())

            val desktopAgnostic = documents.load(project.path)
            val ftc = documents.load(project.path, ControllerInputPlatform.FTC)

            assertTrue(desktopAgnostic.diagnostics.isEmpty())
            assertTrue(ftc.diagnostics.any { it.message.contains("unlearned control") })
        }
    }

    @Test
    fun `singleton catalog restore is atomic and creates a new revision`() {
        withProject { project ->
            val repository = CapabilityCatalogProjectRepository()
            val first = repository.save(project.path, capabilityCatalog())
            val second = repository.save(
                project.path,
                capabilityCatalog().copy(
                    actions = capabilityCatalog().actions + ActionDescriptor(
                        key = "intake.stop",
                        displayName = "Stop intake",
                        description = "Stops the intake roller.",
                        category = "Intake"
                    )
                )
            )
            val restored = repository.restore(project.path, first.contentHash)

            assertEquals(2, second.document.revision)
            assertEquals(3, restored.document.revision)
            assertEquals(listOf("intake.run"), restored.document.actions.map { it.key })
            assertEquals(listOf(3, 2, 1), repository.listRevisions(project.path).map { it.revision })
            assertTrue(project.walkTopDown().none { it.extension == "tmp" })
        }
    }

    @Test
    fun `autonomous catalog refuses references to missing routines`() {
        withProject { project ->
            val error = assertFailsWith<IllegalArgumentException> {
                AutonomousCatalogProjectRepository().save(project.path, autonomousCatalog())
            }
            assertTrue(error.message.orEmpty().contains("Unknown routine 'score'"))
            assertFalse(File(project, ".ares/autonomous-catalog.json").exists())
        }
    }

    private fun routine(id: String, name: String, waitSeconds: Double) = RoutineDocument(
        documentId = id,
        name = name,
        steps = listOf(RoutineStep.wait(waitSeconds))
    )

    private fun capabilityCatalog() = CapabilityCatalogDocument(
        projectId = "test-project",
        actions = listOf(
            ActionDescriptor(
                key = "intake.run",
                displayName = "Run intake",
                description = "Runs the intake roller.",
                category = "Intake"
            )
        )
    )

    private fun controllerProfile() = ControllerProfileDocument(
        documentId = "vader5-pro",
        displayName = "Flydigi Vader 5 Pro",
        controls = listOf(
            ControllerControlDocument(
                controlId = "a",
                displayName = "A",
                type = ControllerControlTypeDocument.BUTTON,
                anchor = ControllerAnchorDocument(0.75, 0.55),
                mappings = listOf(
                    ControllerInputMappingDocument(ControllerInputPlatform.DESKTOP_GLFW, buttonIndex = 0)
                )
            )
        )
    )

    private fun controlScheme() = ControlSchemeDocument(
        documentId = "competition",
        name = "Competition controls",
        controllers = listOf(ControllerAssignment("driver", "Driver", "vader5-pro", devicePort = 0)),
        bindings = listOf(
            ControlBindingDocument(
                bindingId = "run-intake",
                displayName = "Run intake",
                source = ControlSourceDocument(ControlSourceKind.BUTTON, "driver", listOf("a")),
                event = ControlEvent.PRESS,
                target = ControlTargetDocument(ControlTargetKind.ACTION, "intake.run")
            )
        )
    )

    private fun autonomousCatalog() = AutonomousCatalogDocument(
        projectId = "test-project",
        defaultEntryId = "score-red",
        entries = listOf(
            AutonomousCatalogEntry(
                entryId = "score-red",
                displayName = "Score red",
                routineId = "score",
                startingPose = RoutinePose(1.0, 2.0, 0.0)
            )
        )
    )

    @Test
    fun `autonomous catalog multi-entry round trips accurately with distinct starting poses`() {
        withProject { project ->
            val routines = RoutineProjectRepository()
            routines.save(project.path, routine("score_red", "Score Red", 1.0))
            routines.save(project.path, routine("score_blue", "Score Blue", 1.0))

            val repository = AutonomousCatalogProjectRepository(routines)
            val document = AutonomousCatalogDocument(
                projectId = "multi-test",
                defaultEntryId = "score-red",
                entries = listOf(
                    AutonomousCatalogEntry(
                        entryId = "score-red",
                        displayName = "Score red",
                        routineId = "score_red",
                        startingPose = RoutinePose(1.2, 0.8, 0.0)
                    ),
                    AutonomousCatalogEntry(
                        entryId = "score-blue",
                        displayName = "Score blue",
                        routineId = "score_blue",
                        startingPose = RoutinePose(-1.2, 0.8, 3.14159)
                    )
                )
            )
            val saved = repository.save(project.path, document)
            val loaded = repository.load(project.path).getOrThrow()

            assertEquals(listOf("score-blue", "score-red"), loaded.entries.map { it.entryId })
            assertEquals(document.defaultEntryId, loaded.defaultEntryId)
            assertEquals(document.projectId, loaded.projectId)
            assertEquals(2, loaded.entries.size)
        }
    }

    private fun metadata(robotLengthMeters: Double) = AresProjectMetadataDocument(
        projectId = "test-project",
        identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = robotLengthMeters,
        robotWidthMeters = .43,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
    )

    private fun canonical(metadata: AresProjectMetadataDocument): AresProjectMetadataDocument =
        AresProjectMetadataCodec.decode(AresProjectMetadataCodec.encode(metadata))

    private inline fun withProject(block: (File) -> Unit) {
        val project = Files.createTempDirectory("ares-project-docs-").toFile()
        try {
            block(project)
        } finally {
            project.deleteRecursively()
        }
    }
}
