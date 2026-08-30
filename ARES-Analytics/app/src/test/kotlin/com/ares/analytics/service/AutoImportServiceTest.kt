package com.ares.analytics.service

import com.ares.analytics.di.awaitAutoImportBeforeClosingDependencies
import com.ares.analytics.service.log.*
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.decodeFromString
import org.mockito.Mockito
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Integration tests for durable, duplicate-safe local log import. */
class AutoImportServiceTest {

    @Test
    fun `ping timeout arguments use each operating system's units`() {
        assertEquals(listOf("ping", "-n", "1", "-w", "1000", "robot.local"), pingCommand("robot.local", "Windows 11"))
        assertEquals(listOf("ping", "-c", "1", "-W", "1000", "robot.local"), pingCommand("robot.local", "Mac OS X"))
        assertEquals(listOf("ping", "-c", "1", "-W", "1", "robot.local"), pingCommand("robot.local", "Linux"))
    }

    @Test
    fun testLocalLogsAutoImport() = runBlocking {
        val tempDb = File.createTempFile("auto_import_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val driverAnalysisService = DriverAnalysisService(databaseService, sysIdService)
        val summaryEngineService = SummaryEngineService(databaseService, sysIdService, driverAnalysisService)
        val logParserService = LogParserService(databaseService, summaryEngineService)
        val hootDecoderService = HootDecoderService(databaseService, summaryEngineService, sysIdService)

        // Robot connectivity is irrelevant to this local-disk test and must not depend on
        // whether a developer happens to have a Control Hub connected.
        val processManagerService = ProcessManagerService(monitorAdbConnection = false)

        // Create a temporary project path
        val tempProjectDir = File(System.getProperty("java.io.tmpdir"), "ares_project_test_${System.currentTimeMillis()}")
        tempProjectDir.mkdirs()
        val logsDir = File(tempProjectDir, "logs")
        logsDir.mkdirs()

        // Write a mock log file
        val mockLog = File(logsDir, "test_run.csv")
        val mockContents = """
            TimestampMs, voltage, velocity
            1000, 12.0, 1.5
            2000, 11.8, 1.6
            """.trimIndent()
        mockLog.writeText(mockContents)
        val originalLastModified = mockLog.lastModified()
        val config = WorkspaceConfig(
            teamId = "1234",
            seasonId = "2026",
            robotId = "ares-test",
            projectPath = tempProjectDir.absolutePath,
            league = League.FTC
        )
        var importSuccessCalled = false
        val autoImportService = AutoImportService(
            logParserService = logParserService,
            hootDecoderService = hootDecoderService,
            processManagerService = processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 50L
        )
        val archiveRoot = File(logsDir, "imported").apply { mkdirs() }
        val traversalName = autoImportService.safeArchiveFile(
            archiveRoot,
            "0123456789abcdef",
            "..\\..\\outside.csv"
        )
        assertEquals(archiveRoot.canonicalFile, traversalName.parentFile.canonicalFile)
        assertTrue(traversalName.name.endsWith("outside.csv"))

        // Start scanner and wait for import
        autoImportService.start {
            importSuccessCalled = true
        }

        // We run a single manual loop cycle inside the test instead of delay loop,
        // or we just call the private methods by exposing them, or since the service runs in a loop,
        // we can wait a moment or just verify the file moves after a short delay since it is running on a coroutine.
        // Let's delay the test thread slightly to allow the loop to run.
        var retries = 0
        while (!importSuccessCalled && retries < 200) {
            kotlinx.coroutines.delay(100)
            retries++
        }

        autoImportService.stopAndJoin()

        // Verify the file was imported and moved
        assertTrue(importSuccessCalled, "onImportSuccess was not called")

        assertTrue(!mockLog.exists(), "Original log file was not deleted/moved")

        // Verify session was inserted into database
        val sessions = databaseService.getSessions()
        assertEquals(1, sessions.size)
        assertEquals("1234", sessions[0].teamId)
        assertEquals("ares-test", sessions[0].robotId)
        val reports = File(logsDir, "imported").listFiles { file ->
            file.name.endsWith(AutoImportService.IMPORT_REPORT_SUFFIX)
        }.orEmpty()
        assertEquals(1, reports.size)
        val report = AppJson.decodeFromString<ImportReport>(reports.single().readText())
        assertEquals(ImportStatus.SUCCESS, report.status)
        assertEquals(4L, report.acceptedRecords)
        assertEquals(listOf("velocity", "voltage"), report.detectedTopics)

        // Recreate the exact same source identity and restart the service. The durable
        // manifest must prevent a second session even though in-memory observations are new.
        mockLog.writeText(mockContents)
        mockLog.setLastModified(originalLastModified)
        val restarted = AutoImportService(
            logParserService = logParserService,
            hootDecoderService = hootDecoderService,
            processManagerService = processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 50L
        )
        restarted.start { }
        kotlinx.coroutines.delay(300)
        restarted.stopAndJoin()
        assertEquals(1, databaseService.getSessions().size, "same source identity was imported twice")

        // Replacing the source with different bytes while preserving path, size, and mtime must
        // not collide with the durable fingerprint. Metadata-only fingerprints suppressed this.
        val changedContents = mockContents.replace("1.5", "2.5").replace("1.6", "2.6")
        assertEquals(mockContents.toByteArray().size, changedContents.toByteArray().size)
        mockLog.writeText(changedContents)
        assertTrue(mockLog.setLastModified(originalLastModified))
        var changedContentImported = false
        val changedContentService = AutoImportService(
            logParserService = logParserService,
            hootDecoderService = hootDecoderService,
            processManagerService = processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 50L
        )
        changedContentService.start { changedContentImported = true }
        retries = 0
        while (!changedContentImported && retries < 200) {
            delay(100L)
            retries++
        }
        changedContentService.stopAndJoin()
        assertTrue(changedContentImported, "same-metadata content replacement was suppressed")
        assertEquals(2, databaseService.getSessions().size)

        // Clean up
        tempProjectDir.deleteRecursively()
        tempDb.delete()
        processManagerService.shutdown()
    }

    @Test
    fun `one scan with multiple imports emits one UI refresh`() = runBlocking {
        val tempDb = File.createTempFile("auto_import_batch_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val summaryEngineService = SummaryEngineService(
            databaseService,
            sysIdService,
            DriverAnalysisService(databaseService, sysIdService),
        )
        val processManagerService = ProcessManagerService(monitorAdbConnection = false)
        val projectDir = Files.createTempDirectory("auto-import-batch").toFile()
        val logsDir = projectDir.resolve("logs").apply { mkdirs() }
        val csv = """
            TimestampMs, voltage
            1000, 12.0
            2000, 11.9
        """.trimIndent()
        logsDir.resolve("first.csv").writeText(csv)
        logsDir.resolve("second.csv").writeText(csv.replace("12.0", "12.1"))
        val config = WorkspaceConfig(
            teamId = "1234",
            seasonId = "2026",
            robotId = "batch-test",
            projectPath = projectDir.absolutePath,
            league = League.FTC,
        )
        val callbacks = AtomicInteger()
        val service = AutoImportService(
            logParserService = LogParserService(databaseService, summaryEngineService),
            hootDecoderService = HootDecoderService(databaseService, summaryEngineService, sysIdService),
            processManagerService = processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 25L,
        )

        try {
            service.start { callbacks.incrementAndGet() }
            var attempts = 0
            while (databaseService.getSessions().size < 2 && attempts < 200) {
                delay(25L)
                attempts++
            }
            delay(100L)
            service.stopAndJoin()

            assertEquals(2, databaseService.getSessions().size)
            assertEquals(1, callbacks.get(), "a batch must invalidate Run History only once")
        } finally {
            service.stopAndJoin()
            databaseService.close()
            processManagerService.shutdown()
            projectDir.deleteRecursively()
            tempDb.delete()
        }
    }

    @Test
    fun `quarantine persists rejection and suppresses the same fingerprint`() = runBlocking {
        val tempDb = File.createTempFile("auto_quarantine_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val sysIdService = SysIdService(databaseService)
        val summaryEngineService = SummaryEngineService(
            databaseService,
            sysIdService,
            DriverAnalysisService(databaseService, sysIdService)
        )
        val logParserService = LogParserService(databaseService, summaryEngineService)
        val processManagerService = ProcessManagerService(monitorAdbConnection = false)
        val projectDir = File(System.getProperty("java.io.tmpdir"), "ares_quarantine_test_${System.nanoTime()}")
        val logsDir = File(projectDir, "logs").apply { mkdirs() }
        val sourceFile = File(logsDir, "bad.csv").apply { writeText("not,a,valid,log") }
        val config = WorkspaceConfig(
            teamId = "1234",
            seasonId = "2026",
            robotId = "ares-test",
            projectPath = projectDir.absolutePath,
            league = League.FTC
        )
        val service = AutoImportService(
            logParserService,
            HootDecoderService(databaseService, summaryEngineService, sysIdService),
            processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 25L
        )
        service.start { error("Rejected imports must not trigger the success callback") }
        val quarantineDir = File(projectDir, "logs/quarantine")
        var attempts = 0
        while (quarantineDir.listFiles().orEmpty().none { it.name.endsWith(AutoImportService.IMPORT_REPORT_SUFFIX) } && attempts < 100) {
            kotlinx.coroutines.delay(25)
            attempts++
        }
        service.stopAndJoin()

        assertTrue(sourceFile.exists(), "Rejected source should remain available for repair")
        val quarantined = quarantineDir.listFiles().orEmpty().single { it.name.endsWith("bad.csv") }
        val manifest = File(quarantineDir, AutoImportService.QUARANTINE_MANIFEST_NAME)
        assertTrue(manifest.isFile)
        val reportFile = File(quarantineDir, quarantined.name + AutoImportService.IMPORT_REPORT_SUFFIX)
        val report = AppJson.decodeFromString<ImportReport>(reportFile.readText())
        assertEquals(ImportStatus.REJECTED, report.status)
        assertEquals("bad.csv", report.sourceName)
        assertTrue(report.error.orEmpty().contains("no supported timestamp column with an explicit unit"))
        assertEquals(0L, report.acceptedRecords)
        assertTrue(databaseService.getSessions().isEmpty())

        val reportModifiedAt = reportFile.lastModified()
        val restarted = AutoImportService(
            logParserService,
            HootDecoderService(databaseService, summaryEngineService, sysIdService),
            processManagerService,
            configProvider = { config },
            scope = this,
            scanIntervalMs = 25L
        )
        restarted.start { error("Quarantined fingerprint was retried as a success") }
        kotlinx.coroutines.delay(200)
        restarted.stopAndJoin()
        assertEquals(reportModifiedAt, reportFile.lastModified(), "same rejected fingerprint was retried")
        assertEquals(1, quarantineDir.listFiles().orEmpty().count { it.name.endsWith(AutoImportService.IMPORT_REPORT_SUFFIX) })

        databaseService.close()
        processManagerService.shutdown()
        projectDir.deleteRecursively()
        tempDb.delete()
        Unit
    }

    @Test
    fun `scanner replacement cancels and joins the prior generation before starting another`() = runBlocking {
        val activeCycles = AtomicInteger()
        val maximumConcurrentCycles = AtomicInteger()
        val cancelledCycles = AtomicInteger()
        val cycleStarted = Channel<Unit>(Channel.UNLIMITED)
        val service = bareService(
            scope = this,
            scanCycleOverride = {
                val active = activeCycles.incrementAndGet()
                maximumConcurrentCycles.updateAndGet { prior -> maxOf(prior, active) }
                cycleStarted.send(Unit)
                try {
                    awaitCancellation()
                } finally {
                    activeCycles.decrementAndGet()
                    cancelledCycles.incrementAndGet()
                }
            }
        )

        service.startAndJoinPrevious { }
        withTimeout(1_000L) { cycleStarted.receive() }
        service.startAndJoinPrevious { }
        withTimeout(1_000L) { cycleStarted.receive() }
        service.stopAndJoin()

        assertEquals(1, maximumConcurrentCycles.get())
        assertEquals(2, cancelledCycles.get())
        assertEquals(0, activeCycles.get())
        assertFalse(service.scannerActive)
    }

    @Test
    fun `application disposal joins suspended import cleanup before database close`() = runBlocking {
        val projectDir = Files.createTempDirectory("ares-auto-import-disposal").toFile()
        val archiveDir = projectDir.resolve("logs/imported").apply { mkdirs() }
        val partialFile = archiveDir.resolve(".suspended.partial")
        val quarantineDir = projectDir.resolve("logs/quarantine")
        val scanStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val service = bareService(
            scope = this,
            scanCycleOverride = {
                partialFile.writeText("incomplete")
                scanStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(25L)
                        partialFile.delete()
                        events += "import-cleaned"
                    }
                }
            }
        )

        try {
            service.start { }
            withTimeout(1_000L) { scanStarted.await() }

            awaitAutoImportBeforeClosingDependencies(
                stopAutoImport = { service.stop() },
                closeDependencies = {
                    assertFalse(partialFile.exists(), "database closed before partial cleanup")
                    assertFalse(quarantineDir.exists(), "cancellation must not quarantine a partial import")
                    events += "database-closed"
                }
            )

            assertEquals(listOf("import-cleaned", "database-closed"), events)
            assertFalse(service.scannerActive)
        } finally {
            service.stop()
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `timed out helper is killed before returning bounded output`() = runBlocking {
        val service = bareService(this)
        val probeDirectory = compiledProcessProbe()
        val pidFile = File.createTempFile("auto-import-timeout", ".pid").apply { delete() }
        val startedAt = System.nanoTime()

        val result = service.executeProcessForTest(
            probeCommand(probeDirectory, "hang", pidFile.absolutePath),
            timeoutMs = 2_000L
        )

        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        val pid = requireNotNull(result.stdout.lineSequence().firstOrNull()?.trim()?.toLongOrNull())
        assertTrue(result.timedOut)
        assertTrue(elapsedMs < 5_000L, "timeout did not bound process completion")
        assertTrue(result.stdout.toByteArray().size <= AutoImportService.MAX_PROCESS_OUTPUT_BYTES)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        pidFile.delete()
        Unit
    }

    @Test
    fun `cancelling helper kills child process and propagates cancellation`() = runBlocking {
        val service = bareService(this)
        val probeDirectory = compiledProcessProbe()
        val pidFile = File.createTempFile("auto-import-cancel", ".pid").apply { delete() }
        val execution = async {
            service.executeProcessForTest(
                probeCommand(probeDirectory, "hang", pidFile.absolutePath),
                timeoutMs = 60_000L
            )
        }
        withTimeout(3_000L) {
            while (!pidFile.isFile || pidFile.readText().trim().toLongOrNull() == null) delay(10L)
        }
        val pid = requireNotNull(pidFile.readText().trim().toLongOrNull())

        execution.cancelAndJoin()
        withTimeout(3_000L) {
            while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(10L)
        }

        assertTrue(execution.isCancelled)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        pidFile.delete()
        Unit
    }

    @Test
    fun `helper drains both streams but retains only bounded prefixes`() = runBlocking {
        val service = bareService(this)
        val result = service.executeProcessForTest(
            probeCommand(compiledProcessProbe(), "output"),
            timeoutMs = 5_000L
        )

        assertTrue(result.succeeded)
        assertEquals(AutoImportService.MAX_PROCESS_OUTPUT_BYTES, result.stdout.toByteArray().size)
        assertEquals(AutoImportService.MAX_PROCESS_OUTPUT_BYTES, result.stderr.toByteArray().size)
    }

    @Test
    fun `only a direct logs retry file can bypass quarantine dedup`() = runBlocking {
        val project = Files.createTempDirectory("auto-import-retry-source").toFile()
        val logs = File(project, "logs").apply { mkdirs() }
        val nested = File(logs, "nested").apply { mkdirs() }
        val config = WorkspaceConfig(
            teamId = "23247",
            seasonId = "2026",
            robotId = "robot",
            projectPath = project.absolutePath,
            league = League.FTC
        )
        val service = bareService(this)

        assertTrue(service.isExplicitRetrySource(config, File(logs, "retry_123_match.csv")))
        assertFalse(service.isExplicitRetrySource(config, File(logs, "match.csv")))
        assertFalse(service.isExplicitRetrySource(config, File(nested, "retry_123_match.csv")))
        project.deleteRecursively()
        Unit
    }

    @Test
    fun `single-file fingerprints stay backward compatible and DS companions affect the run fingerprint`() = runBlocking {
        val directory = Files.createTempDirectory("auto-import-ds-fingerprint").toFile()
        val dslog = File(directory, "match.dslog").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val events = File(directory, "match.dsevents").apply { writeBytes(byteArrayOf(5, 6, 7)) }
        val service = bareService(this)
        try {
            val expectedLegacy = MessageDigest.getInstance("SHA-256")
                .digest(dslog.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedLegacy, service.contentFingerprint(dslog))

            val firstComposite = service.contentFingerprint(listOf(dslog, events))
            events.writeBytes(byteArrayOf(5, 6, 8))
            val changedComposite = service.contentFingerprint(listOf(dslog, events))
            assertTrue(firstComposite != changedComposite, "Driver Station event changes must invalidate dedup")
        } finally {
            directory.deleteRecursively()
        }
        Unit
    }

    private fun bareService(
        scope: CoroutineScope,
        scanCycleOverride: (suspend () -> Unit)? = null
    ): AutoImportService = AutoImportService(
        logParserService = Mockito.mock(LogParserService::class.java),
        hootDecoderService = Mockito.mock(HootDecoderService::class.java),
        processManagerService = Mockito.mock(ProcessManagerService::class.java),
        configProvider = { null },
        scope = scope,
        scanIntervalMs = 10L,
        scanCycleOverride = scanCycleOverride
    )

    private fun probeCommand(directory: File, mode: String, argument: String? = null): List<String> {
        val executable = File(
            System.getProperty("java.home"),
            "bin/java" + if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else ""
        )
        return buildList {
            add(executable.absolutePath)
            add("-cp")
            add(directory.absolutePath)
            add(PROCESS_PROBE_CLASS)
            add(mode)
            if (argument != null) add(argument)
        }
    }

    private fun compiledProcessProbe(): File = synchronized(PROBE_LOCK) {
        compiledProbeDirectory?.takeIf(File::isDirectory)?.let { return@synchronized it }
        val directory = Files.createTempDirectory("auto-import-process-probe").toFile().apply { deleteOnExit() }
        val source = File(directory, "$PROCESS_PROBE_CLASS.java").apply {
            writeText(
                """
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.util.Arrays;

                public final class $PROCESS_PROBE_CLASS {
                    public static void main(String[] args) throws Exception {
                        if ("hang".equals(args[0])) {
                            long pid = ProcessHandle.current().pid();
                            Files.writeString(Path.of(args[1]), Long.toString(pid));
                            System.out.println(pid);
                            System.out.flush();
                            Thread.sleep(60_000L);
                            return;
                        }
                        byte[] stdout = new byte[200_000];
                        byte[] stderr = new byte[200_000];
                        Arrays.fill(stdout, (byte) 'o');
                        Arrays.fill(stderr, (byte) 'e');
                        System.out.write(stdout);
                        System.err.write(stderr);
                        System.out.flush();
                        System.err.flush();
                    }
                }
                """.trimIndent()
            )
        }
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Tests require a JDK compiler" }
        assertEquals(0, compiler.run(null, null, null, "-d", directory.absolutePath, source.absolutePath))
        compiledProbeDirectory = directory
        directory
    }

    private companion object {
        const val PROCESS_PROBE_CLASS = "AutoImportProcessProbe"
        val PROBE_LOCK = Any()
        var compiledProbeDirectory: File? = null
    }
}
