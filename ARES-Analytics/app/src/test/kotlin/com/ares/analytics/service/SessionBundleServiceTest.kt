package com.ares.analytics.service

import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.ConsoleMessage
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionAnnotation
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.util.Sha256
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionBundleServiceTest {
    @Test
    fun `bundle round trip preserves timeline actions and analysis records`() = runTest {
        val root = Files.createTempDirectory("ares-session-bundle-test").toFile()
        val sourceDatabase = DatabaseService(root.resolve("source.duckdb").absolutePath)
        val targetDatabase = DatabaseService(root.resolve("target.duckdb").absolutePath)
        val environment = EnvironmentService(
            workspacesPath = root.resolve("workspaces.json").absolutePath,
        )
        val workspace = WorkspaceConfig(
            id = "ftc-23247-2026-ares",
            teamId = "23247",
            seasonId = "2026",
            robotId = "ares",
            projectPath = root.absolutePath,
            league = League.FTC,
        )
        environment.saveConfig(workspace)

        val session = Session("bundle-session", "23247", "2026", "ares", 1_000L, 500L)
        val summary = SessionSummary("bundle-session", "23247", "2026", "ares", 1_000L, 500L)
        sourceDatabase.insertSession(session)
        sourceDatabase.insertSessionSummary(summary)
        sourceDatabase.insertTelemetryFrames(
            listOf(TelemetryFrame(1_100L, session.sessionId, "Drive/Pose_X", 1.25, sampleOrder = 1L))
        )
        sourceDatabase.insertRobotActionsBulk(
            listOf(RobotActionRecord(1_120L, session.sessionId, "run-1", "ares", actionType = "SetDrive", payloadJson = "{}"))
        )
        sourceDatabase.insertAnnotation(SessionAnnotation("note-1", session.sessionId, "clean run", 1_130L))
        sourceDatabase.insertAlert(AlertRecord("alert-1", session.sessionId, "low-voltage", 1_140L, peakValue = 8.9))
        sourceDatabase.insertConsoleMessages(listOf(ConsoleMessage(1_150L, "ready", "INFO")), session.sessionId)
        sourceDatabase.replaceAnalysisDiagnostics(
            session.sessionId,
            listOf(AnalysisDiagnostic(session.sessionId, "Diagnostics/System/LoopOverruns", 2.0)),
        )
        val importReport = ImportReport(
            sourceName = "match.csv",
            sourceSha256 = "a".repeat(64),
            sourceSizeBytes = 42L,
            decoder = "CSV",
            status = ImportStatus.SUCCESS,
            sessionId = session.sessionId,
            acceptedRecords = 1L,
        )
        root.resolve("logs/imported").mkdirs()
        val importReportFile = root.resolve("logs/imported/match.csv${AutoImportService.IMPORT_REPORT_SUFFIX}")
        importReportFile.writeText(com.ares.analytics.shared.AppJson.encodeToString(importReport))

        val bundle = root.resolve("session.ares-session.zip")
        val sourceBundles = SessionBundleService(sourceDatabase, environment)
        try {
            val manifest = sourceBundles.createBundle(session.sessionId, summary, bundle)
            assertTrue(bundle.length() > 0L)
            val remoteSummary = manifest.summary.copy(
                fileSizeBytes = bundle.length(),
                cloudFileId = "drive-object-1",
                cloudFileName = "session.ares-session.zip",
                cloudSha256 = Sha256.fileHex(bundle),
            )

            SessionBundleService(targetDatabase, environment)
                .extractAndValidate(bundle, remoteSummary)
                .use { extracted ->
                    val restored = extracted.manifest
                    targetDatabase.importCloudSessionBundleAtomically(
                        file = extracted.telemetryFile,
                        summary = restored.summary,
                        session = restored.session,
                        actions = restored.actions,
                        annotations = restored.annotations,
                        alerts = restored.alerts,
                        consoleMessages = restored.consoleMessages,
                        analysisDiagnostics = restored.analysisDiagnostics,
                        importReports = restored.importReports,
                    )
                }

            assertEquals(1L, targetDatabase.countTelemetryFrames(session.sessionId))
            assertEquals("SetDrive", targetDatabase.getActionsForSession(session.sessionId).single().actionType)
            assertEquals("clean run", targetDatabase.getAnnotations(session.sessionId).single().text)
            assertEquals("low-voltage", targetDatabase.getAlerts(session.sessionId).single().ruleKey)
            assertEquals("ready", targetDatabase.getConsoleMessages(session.sessionId).single().text)
            assertEquals(2.0, targetDatabase.getAnalysisDiagnostics(session.sessionId).single().value)
            assertEquals("match.csv", targetDatabase.getSessionImportReports(session.sessionId).single().sourceName)

            assertTrue(importReportFile.delete())
            val reboundBundle = root.resolve("reuploaded.ares-session.zip")
            val reboundManifest = SessionBundleService(targetDatabase, environment)
                .createBundle(session.sessionId, remoteSummary, reboundBundle)
            assertEquals("match.csv", reboundManifest.importReports.single().sourceName)
        } finally {
            sourceDatabase.close()
            targetDatabase.close()
            root.deleteRecursively()
        }
    }
}
