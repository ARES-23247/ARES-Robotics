package com.ares.analytics.service

import com.ares.analytics.service.log.HootDecoderService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualLogImportServiceTest {
    @Test
    fun `guided import archives verified evidence leaves source unchanged and is idempotent`() = runTest {
        val root = Files.createTempDirectory("ares-manual-import").toFile()
        val project = root.resolve("project").apply { mkdirs() }
        val source = root.resolve("student-run.csv").apply {
            writeText("timestampMs,Robot/BatteryVoltage,Robot/LoopTimeMs\n1000,12.5,20\n1020,12.0,30\n")
        }
        val originalBytes = source.readBytes()
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
        val service = ManualLogImportService(
            database,
            parser,
            HootDecoderService(database, summary, sysId),
            autoImport,
        )
        val workspace = WorkspaceConfig(
            id = "workspace",
            teamId = "23247",
            seasonId = "2026",
            robotId = "robot",
            projectPath = project.absolutePath,
            league = League.FTC,
        )
        try {
            val first = service.importFiles(listOf(source), workspace)
            val second = service.importFiles(listOf(source), workspace)

            assertFalse(first.wasAlreadyImported)
            assertTrue(second.wasAlreadyImported)
            assertEquals(first.session.sessionId, second.session.sessionId)
            assertEquals(originalBytes.toList(), source.readBytes().toList())
            assertEquals(1, database.getSessionsForWorkspace("23247", "2026", "robot").size)

            val archive = project.resolve("logs/imported")
            assertEquals(1, archive.listFiles().orEmpty().count { it.name.endsWith(".csv") })
            assertEquals(
                1,
                archive.listFiles().orEmpty().count { it.name.endsWith(AutoImportService.IMPORT_REPORT_SUFFIX) },
            )
        } finally {
            autoImport.stop()
            processManager.shutdown()
            database.close()
            root.deleteRecursively()
        }
    }
}
