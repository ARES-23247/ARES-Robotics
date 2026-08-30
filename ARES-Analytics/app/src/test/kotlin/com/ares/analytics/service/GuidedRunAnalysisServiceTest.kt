package com.ares.analytics.service

import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuidedRunAnalysisServiceTest {
    @Test
    fun `guided review preserves import evidence and separates observations from hypotheses`() = runTest {
        withService { root, database, service ->
            val workspace = workspace(root)
            database.insertSession(Session("current", "23247", "decode", "practice", 1_000L, durationMs = 1_000L))
            database.insertSession(Session("baseline", "23247", "decode", "practice", 500L, durationMs = 1_000L))
            database.insertSessionSummary(summary("current", createdAt = 1_000L, p95 = 15.0))
            database.insertSessionSummary(summary("baseline", createdAt = 500L, p95 = 10.0))
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(100L, "current", "Robot/BatteryVoltage", 9.2),
                    TelemetryFrame(200L, "current", "Drive/Pose_X", 0.5),
                )
            )
            val reportDirectory = File(root, "logs/imported").apply { mkdirs() }
            File(reportDirectory, "run.rlog${AutoImportService.IMPORT_REPORT_SUFFIX}").writeText(
                AppJson.encodeToString(
                    ImportReport(
                        sourceName = "run.rlog",
                        sourceSha256 = "a".repeat(64),
                        sourceSizeBytes = 42L,
                        decoder = "RLOG",
                        status = ImportStatus.SUCCESS,
                        sessionId = "current",
                        acceptedRecords = 2L,
                        rejectedRecords = 0L,
                        minTimestampMs = 100L,
                        maxTimestampMs = 200L,
                    )
                )
            )

            val review = service.analyze(workspace, "current")

            assertEquals(RunEvidenceSourceKind.IMPORTED_FILE, review.source.kind)
            assertEquals("run.rlog", review.source.sourceName)
            assertEquals(GuidedRunConfidence.MODERATE, review.evidenceContext.confidence)
            assertTrue(review.evidenceContext.freshnessStatus.contains("Historical recording"))
            assertEquals(listOf("baseline"), review.comparison?.baselineSessionIds)
            val finding = review.findings.single()
            assertTrue(finding.observedEvidence.contains("9.20 V"))
            assertTrue(finding.possibleCauses.size > 1)
            assertTrue(finding.interpretationLimit.contains("not a battery diagnosis"))
            assertTrue(review.limitations.any { it.contains("not a live freshness") })

            val destination = File(root, "exports/review.md")
            service.exportMarkdown(review, destination)
            val markdown = destination.readText()
            assertTrue(markdown.contains("## Measured evidence"))
            assertTrue(markdown.contains("## Threshold findings and hypotheses"))
            assertTrue(markdown.contains("Historical desktop evidence does not prove current physical safety"))
            assertTrue(markdown.contains("Interpretation confidence: Moderate evidence"))
            assertTrue(markdown.contains("Recorded timestamp: 0.100 s"))
            assertTrue(markdown.contains("SHA-256: ${"a".repeat(64)}"))
        }
    }

    @Test
    fun `workspace scope prevents listing or analyzing another robots run`() = runTest {
        withService { root, database, service ->
            val selected = workspace(root)
            database.insertSession(Session("mine", "23247", "decode", "practice", 1_000L))
            database.insertSession(Session("other-team", "99999", "decode", "practice", 2_000L))
            database.insertSession(Session("other-robot", "23247", "decode", "competition", 3_000L))

            assertEquals(listOf("mine"), service.listWorkspaceSessions(selected).map(Session::sessionId))
            val failure = assertFailsWith<IllegalArgumentException> { service.analyze(selected, "other-team") }
            assertTrue(failure.message.orEmpty().contains("another team, season, or robot"))
        }
    }

    @Test
    fun `mismatched persisted summary cannot introduce another workspace baseline`() = runTest {
        withService { root, database, service ->
            val selected = workspace(root)
            database.insertSession(Session("mine", "23247", "decode", "practice", 1_000L))
            database.insertSessionSummary(
                summary("mine", createdAt = 1_000L, p95 = 20.0).copy(teamId = "99999", robotId = "other")
            )
            database.insertSessionSummary(
                summary("other-team-baseline", createdAt = 500L, p95 = 10.0).copy(teamId = "99999", robotId = "other")
            )
            database.insertTelemetryFrames(listOf(TelemetryFrame(100L, "mine", "Drive/Pose_X", 0.1)))

            val review = service.analyze(selected, "mine")

            assertEquals(null, review.summary)
            assertEquals(null, review.comparison)
            assertTrue(review.missingSignals.contains("Precomputed session summary"))
            assertTrue(review.limitations.any { it.contains("summary identity did not match") })
        }
    }

    private suspend fun withService(
        block: suspend (File, DatabaseService, GuidedRunAnalysisService) -> Unit,
    ) {
        val root = Files.createTempDirectory("guided-run-analysis").toFile()
        val database = DatabaseService(File(root, "analytics.duckdb").path)
        val sysId = SysIdService(database)
        val service = GuidedRunAnalysisService(
            databaseService = database,
            importArchiveService = ImportArchiveService(),
            advancedAnalyticsService = AdvancedAnalyticsService(database),
            diagnosticCoachService = DiagnosticCoachService(database),
            driverAnalysisService = DriverAnalysisService(database, sysId, File(root, "profiles.json").path),
        )
        try {
            block(root, database, service)
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    private fun workspace(root: File) = WorkspaceConfig(
        id = "workspace",
        teamId = "23247",
        seasonId = "decode",
        robotId = "practice",
        robotName = "Practice Robot",
        projectPath = root.path,
        league = League.FTC,
    )

    private fun summary(id: String, createdAt: Long, p95: Double) = SessionSummary(
        sessionId = id,
        teamId = "23247",
        seasonId = "decode",
        robotId = "practice",
        createdAt = createdAt,
        minBatteryVoltage = 12.0,
        p95LoopTimeMs = p95,
        avgCrossTrackError = 0.1,
        visionAcceptanceRate = 0.9,
    )
}
