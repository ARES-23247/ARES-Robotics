package com.ares.analytics.service.project

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.service.AresGenerationPhase
import com.ares.analytics.service.ProjectBuildService
import com.ares.analytics.service.project.persistence.CapabilityCatalogProjectRepository
import com.ares.analytics.service.project.persistence.ProjectDocumentRemovalPlan
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.RemovedProjectDocument
import com.ares.analytics.service.project.persistence.SavedProjectRevision
import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.defaultDrivebase
import com.areslib.catalog.CapabilityCatalogDocument
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControllerAnchorDocument
import com.areslib.controls.ControllerAssignment
import com.areslib.controls.ControllerControlDocument
import com.areslib.controls.ControllerControlTypeDocument
import com.areslib.controls.ControllerInputMappingDocument
import com.areslib.controls.ControllerInputPlatform
import com.areslib.controls.ControllerProfileDocument
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.routine.RoutineDocument
import com.areslib.routine.RoutineStep
import com.areslib.simulation.SimulationProductId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectSessionTest {
    @Test
    fun `one snapshot is reused until an explicit reload observes new canonical bytes`() = withProject { root ->
        val session = ProjectSession()
        val first = session.snapshot(root.path, ControllerInputPlatform.FTC)
        val cached = session.snapshot(root.path, ControllerInputPlatform.FTC)

        assertEquals(first.revision, cached.revision)
        File(root, ".ares/project-note.txt").apply {
            parentFile.mkdirs()
            writeText("external edit")
        }

        assertEquals(first.revision, session.snapshot(root.path, ControllerInputPlatform.FTC).revision)
        val refreshed = session.snapshot(root.path, ControllerInputPlatform.FTC, forceReload = true)
        assertNotEquals(first.revision, refreshed.revision)
        assertEquals(ProjectSessionPhase.READY, session.state.value.phase)
    }

    @Test
    fun `run scoped evidence does not invalidate the canonical project revision`() = withProject { root ->
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)
        File(root, ".ares/evidence/hardware/configuration/review.json").apply {
            parentFile.mkdirs()
            writeText("{\"reviewed\":true}")
        }

        val refreshed = session.snapshot(root.path, ControllerInputPlatform.FTC, forceReload = true)

        assertEquals(opened.revision, refreshed.revision)
    }

    @Test
    fun `external changes fail closed before a session-owned save`() = withProject { root ->
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)
        File(root, ".ares/external.json").apply {
            parentFile.mkdirs()
            writeText("changed")
        }

        val result = session.saveControls(opened.revision, emptyList(), emptyList())

        assertIs<ProjectSessionMutationResult.Conflict>(result)
    }

    @Test
    fun `concurrent saves from one revision produce one commit and one stale result`() = withProject { root ->
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)
        val profile = controllerProfile()
        val scheme = controlScheme(profile.documentId)
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<ProjectSessionMutationResult<SavedControlDocuments>> {
                    gate.await()
                    session.saveControls(opened.revision, listOf(profile), listOf(scheme))
                }
            }
            gate.countDown()
            val results = futures.map { it.get() }

            assertEquals(1, results.count { it is ProjectSessionMutationResult.Applied })
            assertEquals(1, results.count { it is ProjectSessionMutationResult.Stale })
            assertTrue(File(root, ".ares/controllers/gamepad.arescontroller").isFile)
            assertTrue(File(root, ".ares/controls/teleop.arescontrols").isFile)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reviewed removal is revision bound and preserves a recovery copy`() = withProject { root ->
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)
        val saved = assertIs<ProjectSessionMutationResult.Applied<SavedControlDocuments>>(
            session.saveControls(opened.revision, listOf(controllerProfile()), listOf(controlScheme("gamepad"))),
        )
        val plan = assertIs<ProjectSessionMutationResult.Applied<*>>(
            session.removalPlan(
                saved.snapshot.revision,
                RemovableProjectDocumentKind.CONTROL_SCHEME,
                "teleop",
            ),
        ).value as ProjectDocumentRemovalPlan
        val removed = assertIs<ProjectSessionMutationResult.Applied<*>>(
            session.remove(
                saved.snapshot.revision,
                RemovableProjectDocumentKind.CONTROL_SCHEME,
                "teleop",
                plan.contentHash,
            ),
        ).value as RemovedProjectDocument

        assertFalse(removed.removedFile.exists())
        assertTrue(removed.recoveryFile.isFile)
    }

    @Test
    fun `workspace switching replaces the session snapshot without leaking the prior project`() {
        val firstRoot = Files.createTempDirectory("project-session-first").toFile()
        val secondRoot = Files.createTempDirectory("project-session-second").toFile()
        try {
            seedProject(firstRoot, "first")
            seedProject(secondRoot, "second")
            val session = ProjectSession()
            session.snapshot(firstRoot.path, ControllerInputPlatform.FTC)
            val second = session.snapshot(secondRoot.path, ControllerInputPlatform.FTC)

            assertEquals(secondRoot.canonicalPath, second.selection.projectRoot)
            assertEquals("second", second.documents.query.metadata?.projectId)
            assertEquals(second.selection, session.state.value.selection)
        } finally {
            firstRoot.deleteRecursively()
            secondRoot.deleteRecursively()
        }
    }

    @Test
    fun `invalid identity repair is hash bound and becomes one ready session snapshot`() = withProject { root ->
        val metadata = ProjectMetadataRepository()
        metadata.file(root.path).apply {
            parentFile.mkdirs()
            writeText("{\"projectId\":null}")
        }
        val rawHash = metadata.rawContentHash(root.path)
        val session = ProjectSession()

        val result = session.repairProjectIdentity(
            root.path,
            ControllerInputPlatform.FTC,
            rawHash,
            metadataDocument("repaired"),
        )

        val applied = assertIs<ProjectSessionMutationResult.Applied<*>>(result)
        assertEquals("repaired", applied.snapshot.documents.query.metadata?.projectId)
        assertEquals(ProjectSessionPhase.READY, session.state.value.phase)
        assertTrue(File(root, ".ares/recovery/project/$rawHash.raw").isFile)
    }

    @Test
    fun `present retired metadata reports its decode error without claiming the file is missing`() = withProject { root ->
        ProjectMetadataRepository().file(root.path).apply {
            parentFile.mkdirs()
            writeText(
                """{"schemaVersion":3,"projectId":"retired","identity":{"teamId":"23247","seasonId":"2026","robotId":"Lightbot","displayName":"Lightbot"},"league":"FTC","coordinateConvention":"CENTER_ORIGIN_CCW","robotLengthMeters":0.46,"robotWidthMeters":0.46,"fieldLengthMeters":3.6576,"fieldWidthMeters":3.6576}""",
            )
        }

        val snapshot = AresProjectDocuments().load(root.path, ControllerInputPlatform.FTC)
        val messages = snapshot.diagnostics.map { it.message }

        assertTrue(messages.any { it.contains("authoringModel") })
        assertFalse(messages.any { it.contains("Canonical .ares/project.json is required") })
    }

    @Test
    fun `drivebase save is revision bound and refreshes tuning plus drivetrain as one project view`() = withProject { root ->
        seedProject(root, "drive-project")
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)

        val result = session.saveDrivebase(
            opened.revision,
            expectedContentHash = null,
            defaultDrivebase("drive-project", DrivebaseKind.FTC_MECANUM),
        )

        val applied = assertIs<ProjectSessionMutationResult.Applied<*>>(result)
        assertEquals(1, applied.snapshot.documents.query.drivetrains.size)
        assertTrue(File(root, ".ares/drivetrains").listFiles().orEmpty().single().isFile)
        assertTrue(File(root, ".ares/tuning").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `routine restore is revision bound and restores history as a new canonical revision`() = withProject { root ->
        seedProject(root, "routine-project")
        val session = ProjectSession()
        val opened = session.snapshot(root.path, ControllerInputPlatform.FTC)
        val first = assertIs<ProjectSessionMutationResult.Applied<SavedRoutineDocuments>>(
            session.saveRoutine(opened.revision, routine("First", 0.5), null),
        )
        val second = assertIs<ProjectSessionMutationResult.Applied<SavedRoutineDocuments>>(
            session.saveRoutine(first.snapshot.revision, routine("Second", 1.0), null),
        )

        val restored = assertIs<ProjectSessionMutationResult.Applied<SavedProjectRevision<RoutineDocument>>>(
            session.restoreRoutineRevision(
                second.snapshot.revision,
                "practice",
                first.value.routine.contentHash,
            ),
        )

        assertEquals("First", restored.value.document.name)
        assertEquals(3, restored.value.document.revision)
        assertIs<ProjectSessionMutationResult.Stale>(
            session.restoreRoutineRevision(second.snapshot.revision, "practice", first.value.routine.contentHash),
        )
    }

    @Test
    fun `execution coordinator derives the simulator product from the effective project`() = withProject { root ->
        seedProject(root, "robot")
        val gateway = RecordingProjectProcessGateway()
        val coordinator = ProjectExecutionCoordinator(ProjectSession(), gateway)
        val workspace = workspace(root, League.FTC)

        assertTrue(coordinator.execute(workspace, ProjectExecutionCommand.VERIFY_AND_BUILD).accepted)
        assertTrue(coordinator.execute(workspace, ProjectExecutionCommand.SIMULATE).accepted)
        assertEquals(
            listOf("build:FTC", "simulate:${SimulationProductId.FTC_DESKTOP_OPMODE.stableId}"),
            gateway.calls,
        )

        val mismatched = coordinator.execute(workspace.copy(league = League.FRC), ProjectExecutionCommand.DEPLOY)
        assertFalse(mismatched.accepted)
        assertEquals(2, gateway.calls.size)
    }

    @Test
    fun `authoring generation is rejected before a process starts when the project is invalid`() = withProject { root ->
        val session = ProjectSession()
        val gateway = RecordingProjectProcessGateway()
        val processManager = ProjectBuildService(aresRepositoryUri = null)
        val generator = SessionProjectGenerator(
            session,
            ProjectExecutionCoordinator(session, gateway),
            processManager,
        )

        generator.generateAresProject(root.path, League.FTC)

        assertTrue(gateway.calls.isEmpty())
        assertEquals(AresGenerationPhase.FAILED, processManager.aresGenerationState.value.phase)
        assertTrue(processManager.aresGenerationState.value.message.isNotBlank())
    }

    private fun withProject(block: (File) -> Unit) {
        val root = Files.createTempDirectory("project-session").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun seedProject(root: File, projectId: String) {
        ProjectMetadataRepository().save(
            root.path,
            metadataDocument(projectId),
        )
        CapabilityCatalogProjectRepository().save(
            root.path,
            CapabilityCatalogDocument(projectId = projectId, actions = emptyList()),
        )
    }

    private fun metadataDocument(projectId: String) = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument("23247", "2026", projectId, projectId),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.46,
        robotWidthMeters = 0.46,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = com.areslib.project.AresRuntimeOptionsDocument(
            ftc = com.areslib.project.AresFtcRuntimeOptionsDocument(),
        ),
    )

    private fun controllerProfile() = ControllerProfileDocument(
        documentId = "gamepad",
        displayName = "Gamepad",
        controls = listOf(
            ControllerControlDocument(
                controlId = "a",
                displayName = "A",
                type = ControllerControlTypeDocument.BUTTON,
                anchor = ControllerAnchorDocument(0.5, 0.5),
                mappings = listOf(ControllerInputMappingDocument(ControllerInputPlatform.FTC, buttonIndex = 0)),
            ),
        ),
    )

    private fun controlScheme(profileId: String) = ControlSchemeDocument(
        documentId = "teleop",
        name = "TeleOp",
        controllers = listOf(ControllerAssignment("driver", "Driver", profileId, 0)),
        bindings = emptyList(),
    )

    private fun routine(name: String, waitSeconds: Double) = RoutineDocument(
        documentId = "practice",
        name = name,
        steps = listOf(RoutineStep.wait(waitSeconds, stepId = "wait")),
    )

    private fun workspace(root: File, league: League) = WorkspaceConfig(
        id = "workspace",
        teamId = "23247",
        seasonId = "2026",
        robotId = "robot",
        robotName = "Robot",
        projectPath = root.path,
        league = league,
    )

    private class RecordingProjectProcessGateway : ProjectProcessGateway {
        val calls = mutableListOf<String>()

        override fun generate(projectPath: String, league: League) {
            calls += "generate:$league"
        }

        override fun verifyAndBuild(projectPath: String, league: League) {
            calls += "build:$league"
        }

        override fun simulate(projectPath: String, product: SimulationProductId, simulatorCommand: String?) {
            calls += "simulate:${product.stableId}"
        }

        override fun deploy(projectPath: String, league: League) {
            calls += "deploy:$league"
        }
    }
}
