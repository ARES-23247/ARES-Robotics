package com.ares.analytics.viewmodel.drivebase

import com.ares.analytics.service.drivebase.DrivebaseKind
import com.ares.analytics.service.drivebase.DrivebaseProjectRepository
import com.ares.analytics.service.drivebase.canonicalTemplate
import com.ares.analytics.service.drivebase.toUiDrivebase
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.ares.analytics.shared.models.League
import com.areslib.drivetrain.DrivetrainDocument
import com.areslib.drivetrain.DrivetrainDocumentCodec
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeout
import org.mockito.Mockito.spy
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Shared canonical project/metadata/drivebase setup and bounded fixture cleanup
 * for drivebase view model audit regressions.
 */
private fun drivebaseAuditMetadataDocument(projectId: String): AresProjectMetadataDocument = AresProjectMetadataDocument(
    projectId = projectId,
    identity = AresProjectIdentityDocument("23247", "2026", projectId, projectId),
    league = AresLeague.FTC,
    coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
    robotLengthMeters = 0.46,
    robotWidthMeters = 0.46,
    fieldLengthMeters = 3.6576,
    fieldWidthMeters = 3.6576,
    runtimeOptions = AresRuntimeOptionsDocument(
        ftc = AresFtcRuntimeOptionsDocument(),
    ),
)

internal fun canonicalFtcMecanumTemplate(
    projectId: String,
    trackWidthMeters: Double? = null,
    wheelBaseMeters: Double? = null,
): DrivetrainDocument {
    val template = canonicalTemplate(projectId, DrivebaseKind.FTC_MECANUM, League.FTC)
    return if (trackWidthMeters != null || wheelBaseMeters != null) {
        template.copy(
            geometry = template.geometry.copy(
                trackWidthMeters = trackWidthMeters ?: template.geometry.trackWidthMeters,
                wheelBaseMeters = wheelBaseMeters ?: template.geometry.wheelBaseMeters,
            ),
        )
    } else {
        template
    }
}

internal fun writeCanonicalProjectFiles(
    projectDir: File,
    projectId: String,
    template: DrivetrainDocument,
): File {
    File(projectDir, ".ares/project.json").apply {
        parentFile.mkdirs()
        writeText(AresProjectMetadataCodec.encode(drivebaseAuditMetadataDocument(projectId)))
    }
    return File(projectDir, ".ares/drivetrains/primary.aresdrivetrain").apply {
        parentFile.mkdirs()
        writeText(DrivetrainDocumentCodec.encode(template))
    }
}

private fun saveReviewedInitialTemplate(
    repository: DrivebaseProjectRepository,
    projectPath: String,
    template: DrivetrainDocument,
): DrivetrainDocument {
    return requireNotNull(
        repository.saveReviewed(
            projectPath,
            DrivetrainDocumentCodec.contentHash(template),
            template.toUiDrivebase(),
        ).canonical,
    )
}

internal suspend fun CoroutineScope.joinScopeChildren(timeoutMs: Long = 10_000) {
    val scopeJob = coroutineContext[Job]!!
    withTimeout(timeoutMs) {
        do {
            val active = scopeJob.children.toList()
            if (active.isEmpty()) break
            active.joinAll()
        } while (scopeJob.children.any())
    }
}

internal suspend fun CoroutineScope.cancelAndJoinScope(timeoutMs: Long = 10_000) {
    val scopeJob = coroutineContext[Job]!!
    cancel()
    withTimeout(timeoutMs) {
        scopeJob.join()
    }
}

internal fun File.deleteRecursivelyAssertively() {
    assertTrue(deleteRecursively(), "Fixture cleanup failed")
}

internal class DrivebaseAuditFixture(
    val root: File,
    val projectDir: File,
    val projectId: String,
    val repository: DrivebaseProjectRepository,
    val session: ProjectSession,
    val drivetrainFile: File,
    val initialTemplate: DrivetrainDocument,
    val scope: CoroutineScope,
    var checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
) {
    fun createViewModel(
        customSession: ProjectSession? = session,
    ): DrivebaseBuilderViewModel = DrivebaseBuilderViewModel(
        projectPath = projectDir.path,
        projectId = projectId,
        league = League.FTC,
        scope = scope,
        repository = repository,
        checkpointRecorder = checkpointRecorder,
        projectSession = customSession,
    )

    suspend fun joinScopeChildren(timeoutMs: Long = 10_000) {
        scope.joinScopeChildren(timeoutMs)
    }

    suspend fun close(timeoutMs: Long = 10_000) {
        scope.cancelAndJoinScope(timeoutMs)
        root.deleteRecursivelyAssertively()
    }
}

internal fun createSaveOwnershipAuditFixture(
    projectId: String = "test-project",
    checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
): DrivebaseAuditFixture = createDrivebaseAuditFixture(
    prefix = "drivebase-save-ownership-audit",
    projectId = projectId,
    template = canonicalFtcMecanumTemplate(projectId, trackWidthMeters = 0.40, wheelBaseMeters = 0.40),
    saveReviewed = true,
    // Enter intents immediately; IO barriers control result delivery without timing sleeps.
    dispatcher = Dispatchers.Unconfined,
    checkpointRecorder = checkpointRecorder,
)

internal fun createSessionAuditFixture(projectId: String = "original"): DrivebaseAuditFixture =
    createDrivebaseAuditFixture(
        prefix = "drivebase-session-audit",
        projectId = projectId,
        template = canonicalFtcMecanumTemplate(projectId),
    )

internal fun createDiscardRecoveryAuditFixture(
    projectId: String = "test-project",
    trackWidthMeters: Double? = null,
    wheelBaseMeters: Double? = null,
    saveReviewed: Boolean = false,
): DrivebaseAuditFixture = createDrivebaseAuditFixture(
    prefix = "drivebase-discard-recovery-audit",
    projectId = projectId,
    template = canonicalFtcMecanumTemplate(projectId, trackWidthMeters, wheelBaseMeters),
    saveReviewed = saveReviewed,
)

private fun createDrivebaseAuditFixture(
    prefix: String,
    projectId: String,
    template: DrivetrainDocument,
    saveReviewed: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    checkpointRecorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
): DrivebaseAuditFixture {
    val root = Files.createTempDirectory(prefix).toFile()
    try {
        val projectDir = File(root, projectId)
        val drivetrainFile = writeCanonicalProjectFiles(projectDir, projectId, template)
        val realRepo = DrivebaseProjectRepository()
        // Only scenarios starting from a clean saved draft create canonical tuning profiles.
        val effectiveTemplate = if (saveReviewed) {
            saveReviewedInitialTemplate(realRepo, projectDir.path, template)
        } else {
            template
        }
        val repository = spy(realRepo)
        val session = spy(ProjectSession(drivebaseRepository = repository))
        return DrivebaseAuditFixture(
            root = root,
            projectDir = projectDir,
            projectId = projectId,
            repository = repository,
            session = session,
            drivetrainFile = drivetrainFile,
            initialTemplate = effectiveTemplate,
            scope = CoroutineScope(SupervisorJob() + dispatcher),
            checkpointRecorder = checkpointRecorder,
        )
    } catch (failure: Throwable) {
        // Callers cannot close a fixture whose setup failed before it was returned.
        if (!root.deleteRecursively()) failure.addSuppressed(IllegalStateException("Fixture cleanup failed: $root"))
        throw failure
    }
}
