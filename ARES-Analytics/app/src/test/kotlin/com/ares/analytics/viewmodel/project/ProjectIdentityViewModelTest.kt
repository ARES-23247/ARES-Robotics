package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.service.project.persistence.decodeProjectMetadata

import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcHubCommandTransport
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.requireFtcRuntimeOptions
import com.areslib.project.requireXrpRuntimeOptions
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectIdentityViewModelTest {
    @Test
    fun `new project suggests stable identity but requires measured robot dimensions`() = runTest {
        withProject { project ->
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            viewModel.load(workspace(project))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("team23247-robot-one-decode", state.draft.projectId)
            assertEquals("23247", state.draft.teamId)
            assertEquals("decode", state.draft.seasonId)
            assertEquals("robot-one", state.draft.robotId)
            assertEquals("robot-one", state.draft.displayName)
            assertEquals("", state.draft.robotLengthMeters)
            assertEquals("", state.draft.robotWidthMeters)
            assertEquals("3.6576", state.draft.fieldLengthMeters)
            assertEquals("3.6576", state.draft.fieldWidthMeters)
            assertEquals(AresFtcHubCommandTransport.STANDARD_SDK, state.draft.ftcHubCommandTransport)
            assertFalse(state.draft.ftcLimelightProxyEnabled)
            assertFalse(state.canReview)
            assertTrue(state.message.orEmpty().contains("No canonical project identity"))

            viewModel.review()
            assertTrue(viewModel.state.value.messageIsError)
            assertTrue(viewModel.state.value.message.orEmpty().contains("Fix every project identity error"))
        }
    }

    @Test
    fun `metadata cannot be created in a folder without robot source`() = runTest {
        val project = Files.createTempDirectory("ares-project-identity-empty-").toFile()
        try {
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            viewModel.load(workspace(project).copy(robotLengthMeters = 0.45, robotWidthMeters = 0.43))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNotNull(state.projectSourceError)
            assertTrue(state.projectSourceError.orEmpty().contains("not a complete FTC robot project"))
            assertFalse(state.canReview)

            viewModel.review()
            assertNull(viewModel.state.value.proposal)
            assertFalse(File(project, ".ares/project.json").exists())
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `review is read only and apply creates exactly the previewed project identity`() = runTest {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                repository = repository,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            viewModel.load(workspace(project))
            advanceUntilIdle()
            viewModel.update(ProjectIdentityField.ROBOT_LENGTH, "0.45")
            viewModel.update(ProjectIdentityField.ROBOT_WIDTH, "0.43")
            viewModel.updateFtcHubCommandTransport(AresFtcHubCommandTransport.ARES_PHOTON)
            viewModel.updateFtcLimelightProxyEnabled(true)

            viewModel.review()

            val proposal = assertNotNull(viewModel.state.value.proposal)
            assertFalse(repository.file(project.path).exists())
            assertTrue(proposal.changes.any { it.label == "Robot length (m)" })
            assertTrue(proposal.changes.any { it.label == "FTC hub command transport" })
            assertTrue(proposal.changes.any { it.label == "Limelight camera proxy" })

            viewModel.applyReviewed()
            advanceUntilIdle()

            val saved = repository.load(project.path).getOrThrow()
            assertEquals(proposal.proposedContentHash, AresProjectMetadataCodec.contentHash(saved))
            assertEquals(AresFtcHubCommandTransport.ARES_PHOTON, saved.requireFtcRuntimeOptions().hubCommandTransport)
            assertTrue(saved.requireFtcRuntimeOptions().limelightProxyEnabled)
            assertNull(viewModel.state.value.proposal)
            assertTrue(viewModel.state.value.message.orEmpty().contains("Created .ares/project.json"))
        }
    }

    @Test
    fun `editing after preview invalidates the proposal and cannot write stale values`() = runTest {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                repository = repository,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            viewModel.load(workspace(project))
            advanceUntilIdle()
            viewModel.update(ProjectIdentityField.ROBOT_LENGTH, "0.45")
            viewModel.update(ProjectIdentityField.ROBOT_WIDTH, "0.43")
            viewModel.review()
            assertNotNull(viewModel.state.value.proposal)

            viewModel.update(ProjectIdentityField.ROBOT_LENGTH, "0.46")
            viewModel.applyReviewed()
            advanceUntilIdle()

            assertNull(viewModel.state.value.proposal)
            assertFalse(repository.file(project.path).exists())
        }
    }

    @Test
    fun `saved project id is locked and league mismatch is protected`() = runTest {
        withProject { project ->
            val repository = ProjectMetadataRepository()
            repository.save(project.path, metadata(AresLeague.FTC))
            val dispatcher = StandardTestDispatcher(testScheduler)
            val viewModel = ProjectIdentityViewModel(this, repository, dispatcher)
            viewModel.load(workspace(project, League.FTC))
            advanceUntilIdle()

            viewModel.update(ProjectIdentityField.PROJECT_ID, "renamed-project")
            assertEquals("test-project", viewModel.state.value.draft.projectId)
            assertTrue(viewModel.state.value.message.orEmpty().contains("stable"))

            viewModel.load(workspace(project, League.FRC))
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.protectedError)
            assertFalse(viewModel.state.value.canReview)
        }
    }

    @Test
    fun `corrupt current project identity is visible and never replaced`() = runTest {
        withProject { project ->
            val file = File(project, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText("student recovery evidence")
            }
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            viewModel.load(workspace(project))
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.protectedError)
            assertFalse(viewModel.state.value.canReview)
            assertEquals("student recovery evidence", file.readText())
        }
    }

    @Test
    fun `retired schema three project is blocked without offering automatic repair`() = runTest {
        withProject { project ->
            val file = File(project, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText(
                    """{"schemaVersion":3,"projectId":"retired","identity":{"teamId":"23247","seasonId":"2026","robotId":"Lightbot","displayName":"Lightbot"},"league":"FTC","coordinateConvention":"CENTER_ORIGIN_CCW","robotLengthMeters":0.46,"robotWidthMeters":0.46,"fieldLengthMeters":3.6576,"fieldWidthMeters":3.6576}""",
                )
            }
            val original = file.readBytes()
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )

            viewModel.load(workspace(project))
            advanceUntilIdle()

            val loaded = viewModel.state.value
            assertTrue(loaded.protectedError.orEmpty().contains("retired schema-3 format"))
            assertTrue(loaded.protectedError.orEmpty().contains("will not rewrite this project"))
            assertNull(loaded.protectedContentHash)
            assertFalse(loaded.canReview)
            assertTrue(file.readBytes().contentEquals(original))
        }
    }

    @Test
    fun `empty object reports missing fields without leaking a Kotlin null error`() {
        val error = runCatching { decodeProjectMetadata("{}") }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("missing required fields"))
        assertTrue(error.message.orEmpty().contains("projectId"))
        assertFalse(error.message.orEmpty().contains("Regex.matches"))
        assertFalse(error.message.orEmpty().contains("non-null is null"))
    }

    @Test
    fun `reviewed repair preserves invalid bytes before replacing canonical identity`() = runTest {
        withProject { project ->
            // Current-format corruption remains repairable. Retired schema-3 files that omit
            // authoringModel are intentionally not migrated by Studio.
            val invalidBytes = """{"schemaVersion":4,"authoringModel":"GUI_OWNED"}""".toByteArray()
            val file = File(project, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeBytes(invalidBytes)
            }
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val config = workspace(project).copy(robotLengthMeters = 0.45, robotWidthMeters = 0.43)

            viewModel.load(config)
            advanceUntilIdle()

            val loaded = viewModel.state.value
            assertNotNull(loaded.protectedContentHash)
            assertTrue(loaded.protectedError.orEmpty().contains("missing required fields"))
            assertNull(loaded.message, "An invalid file must not also report a successful load")
            assertTrue(loaded.canReview)
            assertTrue(file.readBytes().contentEquals(invalidBytes))

            viewModel.review()
            val proposal = assertNotNull(viewModel.state.value.proposal)
            assertEquals(loaded.protectedContentHash, proposal.expectedInvalidRawContentHash)
            assertTrue(file.readBytes().contentEquals(invalidBytes), "Preview must be read-only")

            viewModel.applyReviewed()
            advanceUntilIdle()

            val repaired = AresProjectMetadataCodec.decode(file.readText())
            assertEquals("team23247-robot-one-decode", repaired.projectId)
            assertEquals(0.45, repaired.robotLengthMeters)
            assertTrue(viewModel.state.value.message.orEmpty().contains("Repaired .ares/project.json"))
            val recovery = File(project, ".ares/recovery/project")
                .listFiles()
                .orEmpty()
                .single()
            assertTrue(recovery.readBytes().contentEquals(invalidBytes))
        }
    }

    @Test
    fun `reviewed repair aborts when invalid file changes after preview`() = runTest {
        withProject { project ->
            val file = File(project, ".ares/project.json").apply {
                parentFile.mkdirs()
                writeText("""{"schemaVersion":4,"authoringModel":"GUI_OWNED"}""")
            }
            val viewModel = ProjectIdentityViewModel(
                scope = this,
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            val config = workspace(project).copy(robotLengthMeters = 0.45, robotWidthMeters = 0.43)
            viewModel.load(config)
            advanceUntilIdle()
            viewModel.review()
            assertNotNull(viewModel.state.value.proposal)

            file.writeText("{\"changed\":true}")
            viewModel.applyReviewed()
            advanceUntilIdle()

            assertEquals("{\"changed\":true}", file.readText())
            assertTrue(viewModel.state.value.message.orEmpty().contains("changed after preview"))
            assertFalse(File(project, ".ares/recovery/project").exists())
        }
    }

    @Test
    fun `validation rejects nonpositive geometry and robots larger than the field`() {
        val invalidNumber = validateProjectIdentityDraft(
            League.FTC,
            validDraft().copy(robotLengthMeters = "0"),
        )
        val outsideField = validateProjectIdentityDraft(
            League.FTC,
            validDraft().copy(robotLengthMeters = "4.0"),
        )

        assertNotNull(invalidNumber.fieldErrors[ProjectIdentityField.ROBOT_LENGTH])
        assertNull(invalidNumber.document)
        assertTrue(outsideField.generalErrors.isNotEmpty())
        assertNull(outsideField.document)
    }

    @Test
    fun `XRP runtime settings are validated and preserve the authoring model`() {
        val valid = validateProjectIdentityDraft(
            League.XRP,
            validDraft().copy(
                authoringModel = AresProjectAuthoringModel.HYBRID,
                fieldLengthMeters = "2.54",
                fieldWidthMeters = "1.4224",
                xrpWifiMode = "STATION",
                xrpSsid = "Robotics-Lab",
                xrpLinkPort = "5821",
                xrpDeadmanTimeoutMs = "250",
                xrpBrownoutThresholdVolts = "4.2",
            ),
        )
        val document = assertNotNull(valid.document)
        assertEquals(AresProjectAuthoringModel.HYBRID, document.authoringModel)
        assertEquals(5821, document.requireXrpRuntimeOptions().port)
        assertEquals("Robotics-Lab", document.requireXrpRuntimeOptions().ssid)

        val invalid = validateProjectIdentityDraft(
            League.XRP,
            validDraft().copy(
                fieldLengthMeters = "2.54",
                fieldWidthMeters = "1.4224",
                xrpLinkPort = "5810",
                xrpDeadmanTimeoutMs = "50",
                xrpBrownoutThresholdVolts = "8.0",
            ),
        )
        assertNotNull(invalid.fieldErrors[ProjectIdentityField.XRP_LINK_PORT])
        assertNotNull(invalid.fieldErrors[ProjectIdentityField.XRP_DEADMAN_TIMEOUT])
        assertNotNull(invalid.fieldErrors[ProjectIdentityField.XRP_BROWNOUT_THRESHOLD])
    }

    private fun validDraft() = ProjectIdentityDraft(
        projectId = "test-project",
        teamId = "99999",
        seasonId = "2026",
        robotId = "test-robot",
        displayName = "Test Robot",
        robotLengthMeters = "0.4",
        robotWidthMeters = "0.4",
        fieldLengthMeters = "3.6576",
        fieldWidthMeters = "3.6576",
    )

    private fun workspace(project: File, league: League = League.FTC) = WorkspaceConfig(
        id = "workspace-one",
        teamId = "23247",
        seasonId = "decode",
        robotId = "robot-one",
        projectPath = project.path,
        league = league,
    )

    private fun metadata(league: AresLeague) = AresProjectMetadataDocument(
        projectId = "test-project",
        identity = com.areslib.project.AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
        league = league,
        coordinateConvention = if (league == AresLeague.FTC) {
            AresCoordinateConvention.CENTER_ORIGIN_CCW
        } else {
            AresCoordinateConvention.BLUE_CORNER_ORIGIN_CCW
        },
        robotLengthMeters = .45,
        robotWidthMeters = .43,
        fieldLengthMeters = if (league == AresLeague.FTC) 3.6576 else 16.541,
        fieldWidthMeters = if (league == AresLeague.FTC) 3.6576 else 8.211,
        runtimeOptions = if (league == AresLeague.FTC) {
            com.areslib.project.AresRuntimeOptionsDocument(
                ftc = com.areslib.project.AresFtcRuntimeOptionsDocument(),
            )
        } else {
            com.areslib.project.AresRuntimeOptionsDocument()
        },
    )

    private suspend fun TestScope.withProject(block: suspend TestScope.(File) -> Unit) {
        val project = Files.createTempDirectory("ares-project-identity-").toFile()
        try {
            File(project, "TeamCode/src/main/java/ExampleRobot.kt").apply {
                parentFile.mkdirs()
                writeText("class ExampleRobot")
            }
            File(project, "src/main/kotlin/ExampleRobot.kt").apply {
                parentFile.mkdirs()
                writeText("class ExampleRobot")
            }
            block(project)
        } finally {
            project.deleteRecursively()
        }
    }
}
