package com.ares.analytics.viewmodel

import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.viewmodel.pathing.RobotDimensions
import com.areslib.project.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class PathPlannerMetadataAuditTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun metadata(length: Double = 0.45) = AresProjectMetadataDocument(
        projectId = "test-project", identity = AresProjectIdentityDocument("99999", "2026", "test-robot", "Test Robot"),
        league = AresLeague.FTC, coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = length, robotWidthMeters = 0.43, fieldLengthMeters = 3.6576, fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )
    private fun edit(change: String) = runBlocking {
        val root = temporary.newFolder()
        val repository = ProjectMetadataRepository()
        val initialHash = repository.save(root.path, metadata())
        val job = SupervisorJob()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        try {
            val model = PathPlannerViewModel(scope)
            model.onIntent(PathPlannerIntent.RefreshProject(root.path, League.FTC))
            withTimeout(5_000) { model.state.first { !it.projectLoading && it.projectMetadata != null } }
            when (change) {
                "concurrent" -> repository.saveReviewed(root.path, initialHash, metadata(0.48))
                "corrupt" -> repository.file(root.path).writeText("student recovery bytes")
            }
            val before = repository.file(root.path).readBytes()
            val history = File(root, ".ares/history/project")
            val countBefore = history.listFiles().orEmpty().size
            model.onIntent(PathPlannerIntent.UpdateCanonicalRobotDimensions(root.path, RobotDimensions(0.51, 0.43)))
            val state = withTimeout(5_000) { model.state.first { it.saveStatus.startsWith("Saved") || it.saveStatus.startsWith("Failed") } }
            if (change == "unchanged") {
                assertEquals(0.51, repository.load(root.path).getOrThrow().robotLengthMeters)
                assertEquals(0.51, state.projectMetadata?.robotLengthMeters)
                assertEquals(initialHash, AresProjectMetadataCodec.contentHash(AresProjectMetadataCodec.decode(File(history, "$initialHash.json").readText())))
            } else {
                assertContentEquals(before, repository.file(root.path).readBytes())
                assertEquals(countBefore, history.listFiles().orEmpty().size)
                assertEquals(0.45, state.projectMetadata?.robotLengthMeters)
                assertTrue(state.saveStatus.startsWith("Failed"))
            }
            assertTrue(failures.isEmpty(), "Failure escaped the editor: $failures")
        } finally { job.cancelAndJoin() }
    }
    @Test fun `footprint update preserves concurrent metadata and reports a reload requirement`() = edit("concurrent")
    @Test fun `footprint update preserves metadata corrupted since editor load`() = edit("corrupt")
    @Test fun `footprint update records prior geometry in canonical history`() = edit("unchanged")
}
