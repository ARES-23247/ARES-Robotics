package com.ares.analytics.service

import com.ares.analytics.service.log.HootDecoderService
import com.ares.analytics.shared.League
import com.ares.analytics.shared.WorkspaceConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RobotLogIngestionServiceTest {
    @Test
    fun `robot pull archives exact bytes persists evidence and reuses one workspace run`() = runTest {
        val root = Files.createTempDirectory("ares-robot-pull").toFile()
        val project = root.resolve("robot-project").apply { mkdirs() }
        val payload = "timestampMs,Robot/BatteryVoltage\n1000,12.5\n1020,12.1\n".toByteArray()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        payload,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength, payload.size.toString()),
                    )
                }
            }
        }
        val database = DatabaseService(root.resolve("telemetry.duckdb").absolutePath)
        val sysId = SysIdService(database)
        val summary = SummaryEngineService(database, sysId, DriverAnalysisService(database, sysId))
        val parser = LogParserService(database, summary)
        val processManager = ProcessManagerService()
        val autoImport = AutoImportService(
            parser,
            HootDecoderService(database, summary, sysId),
            processManager,
            configProvider = { null },
            scope = this,
        )
        val service = RobotLogIngestionService(
            database,
            parser,
            autoImport,
            RobotLogDownloader(client),
        )
        val workspace = WorkspaceConfig(
            id = "workspace",
            teamId = "23247",
            seasonId = "2026",
            robotId = "pull-test",
            projectPath = project.absolutePath,
            league = League.FTC,
        )
        val source = RobotLogSource("robot-run.csv", payload.size.toLong(), 1_234L)
        try {
            val first = assertIs<RobotLogImportOutcome.Imported>(
                service.importRun("http://127.0.0.1:5002", listOf(source), workspace),
            )
            val second = assertIs<RobotLogImportOutcome.AlreadyImported>(
                service.importRun("http://127.0.0.1:5002", listOf(source), workspace),
            )

            assertEquals(first.session.sessionId, second.session.sessionId)
            assertEquals(1, database.getSessionsForWorkspace("23247", "2026", "pull-test").size)
            assertEquals(2L, database.countTelemetryFrames(first.session.sessionId))
            val archived = first.archivedFiles.single()
            assertTrue(archived.isFile)
            assertTrue(payload.contentEquals(archived.readBytes()))
            assertTrue(
                java.io.File(archived.parentFile, archived.name + AutoImportService.IMPORT_REPORT_SUFFIX).isFile,
            )
        } finally {
            service.close()
            autoImport.stop()
            processManager.shutdown()
            database.close()
            root.deleteRecursively()
        }
    }
}
