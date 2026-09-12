package com.ares.analytics.viewmodel.project

import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.ProjectSessionMutationResult
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectIdentityRepairAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private val repository = ProjectMetadataRepository()
    private fun document() = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "robot", "Robot"),
        league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.45, robotWidthMeters = 0.43, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )
    private fun project() = temporary.newFolder().also { root ->
        File(root, "TeamCode/src/main/java/Robot.kt").apply { parentFile.mkdirs(); writeText("class Robot") }
    }
    private fun workspace(root: File) = WorkspaceConfig(
        id = "workspace", teamId = "99999", seasonId = "2026", robotId = "robot", projectPath = root.path,
        league = League.FTC, robotLengthMeters = 0.45, robotWidthMeters = 0.43,
    )
    private fun write(root: File, json: String) = repository.file(root.path).apply {
        parentFile.mkdirs(); writeText(json)
    }
    private fun altered(vararg fields: Pair<String, JsonElement?>): String {
        val values = AppJson.parseToJsonElement(AresProjectMetadataCodec.encode(document())).jsonObject.toMutableMap()
        fields.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        return JsonObject(values).toString()
    }

    @Test fun `current schema with damaged authoring model remains explicitly repairable`() = runTest {
        for (authoring in listOf(null, JsonNull, JsonPrimitive("damaged"))) {
            val root = project()
            val file = write(root, altered("authoringModel" to authoring))
            val original = file.readBytes()
            val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler))
            model.load(workspace(root)); advanceUntilIdle()
            val state = model.state.value
            assertTrue(state.canReview, "Current schema corruption must be repairable: ${state.protectedError}")
            assertEquals(Sha256.hex(original), state.protectedContentHash)
            assertNull(state.unsupportedSchemaVersion)
            assertFalse(state.protectedError.orEmpty().contains("retired"))
            model.review()
            assertNotNull(model.state.value.proposal)
            assertContentEquals(original, file.readBytes(), "Review must not write")
            model.applyReviewed(); advanceUntilIdle()
            assertFalse(model.state.value.messageIsError, model.state.value.message)
            assertEquals(AresProjectAuthoringModel.GUI_OWNED, repository.load(root.path).getOrThrow().authoringModel)
            assertContentEquals(original, File(root, ".ares/recovery/project/${Sha256.hex(original)}.raw").readBytes())
        }
    }

    @Test fun `unknown schema corruption is not inferred to be a retired project`() = runTest {
        val root = project()
        val file = write(root, "{\"projectId\":null}")
        val original = file.readBytes()
        val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler))
        model.load(workspace(root)); advanceUntilIdle()
        assertTrue(model.state.value.canReview)
        assertEquals(Sha256.hex(original), model.state.value.protectedContentHash)
        assertNull(model.state.value.unsupportedSchemaVersion)
        model.review(); model.applyReviewed(); advanceUntilIdle()
        assertFalse(model.state.value.messageIsError, model.state.value.message)
        assertContentEquals(original, File(root, ".ares/recovery/project/${Sha256.hex(original)}.raw").readBytes())
    }

    @Test fun `explicit unsupported schemas block editor repair even with other damaged fields`() = runTest {
        for (version in listOf(1, 3, 4, 6)) {
            val root = project()
            val file = write(root, altered("schemaVersion" to JsonPrimitive(version), "projectId" to null))
            val original = file.readBytes()
            val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler))
            model.load(workspace(root)); advanceUntilIdle()
            assertFalse(model.state.value.canReview, "Schema $version must not be rewritten")
            assertTrue(model.state.value.protectedError.orEmpty().contains(if (version < 5) "retired" else "newer"))
            assertNull(model.state.value.protectedContentHash)
            assertEquals(version, model.state.value.unsupportedSchemaVersion)
            model.review(); model.applyReviewed(); advanceUntilIdle()
            assertNull(model.state.value.proposal)
            assertContentEquals(original, file.readBytes())
        }
    }

    @Test fun `repository repair cannot upgrade or downgrade an unsupported schema`() {
        for (version in listOf(1, 3, 4, 6)) for (partial in listOf(false, true)) {
            val root = project()
            val json = if (partial) "{\"schemaVersion\":$version}" else altered("schemaVersion" to JsonPrimitive(version))
            val file = write(root, json)
            val original = file.readBytes()
            assertFails("Schema $version, partial=$partial") {
                repository.repairReviewed(root.path, Sha256.hex(original), document())
            }
            assertContentEquals(original, file.readBytes())
            assertFalse(File(root, ".ares/recovery").exists())
            assertFalse(File(root, ".ares/history").exists())
        }
    }

    @Test fun `session repair also preserves a retired project without recovery writes`() {
        val root = project()
        val file = write(root, "{\"schemaVersion\":3}")
        val original = file.readBytes()
        val result = ProjectSession().repairProjectIdentity(root.path, ControllerInputPlatform.FTC, Sha256.hex(original), document())
        assertIs<ProjectSessionMutationResult.Failed>(result)
        assertContentEquals(original, file.readBytes())
        assertFalse(File(root, ".ares/recovery").exists())
    }

    @Test fun `current authoring corruption can be repaired through the session editor`() = runTest {
        val root = project()
        val file = write(root, altered("authoringModel" to null))
        val original = file.readBytes()
        val session = ProjectSession()
        val model = ProjectIdentityViewModel(this, repository, StandardTestDispatcher(testScheduler), session)
        model.load(workspace(root)); advanceUntilIdle()
        assertTrue(model.state.value.canReview)
        model.review(); model.applyReviewed(); advanceUntilIdle()
        assertFalse(model.state.value.messageIsError, model.state.value.message)
        assertEquals(model.state.value.currentDocument, session.state.value.snapshot?.documents?.query?.metadata)
        assertEquals(model.state.value.projectRevision, session.state.value.revision)
        assertContentEquals(original, File(root, ".ares/recovery/project/${Sha256.hex(original)}.raw").readBytes())
    }
}
