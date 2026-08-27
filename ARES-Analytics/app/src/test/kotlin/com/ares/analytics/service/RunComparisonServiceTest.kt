package com.ares.analytics.service

import com.ares.analytics.shared.AlertRecord
import com.ares.analytics.shared.League
import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionAnnotation
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.WorkspaceConfig
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RunComparisonServiceTest {
    @Test
    fun `golden paired runs align deterministically and preserve evidence timestamps and units`() = runTest {
        withService { root, database, service ->
            val workspace = workspace(root)
            database.insertSession(Session("run-a", "23247", "decode", "practice", 1_000L, matchNumber = 1))
            database.insertSession(Session("run-b", "23247", "decode", "practice", 2_000L, matchNumber = 2))
            database.insertTelemetryFrames(loadFixture("golden-run-a.csv", "run-a"))
            database.insertTelemetryFrames(loadFixture("golden-run-b.csv", "run-b"))
            database.insertAnnotation(SessionAnnotation("a-note", "run-a", "Cycle start", 1_200L, "Mentor"))
            database.insertAnnotation(SessionAnnotation("b-note", "run-b", "Cycle start", 2_350L, "Mentor"))
            database.insertAlert(AlertRecord("alert-b", "run-b", "Robot/BatteryLow", 2_350L, peakValue = 10.4))

            val initial = service.compare(
                workspace,
                RunComparisonRequest("run-a", listOf("run-b")),
            )

            assertEquals(listOf("run-a", "run-b"), initial.sessions.map(Session::sessionId))
            assertTrue(initial.availableAlignments.any { it.id == AUTONOMOUS_START_ALIGNMENT_ID })
            assertTrue(initial.availableAlignments.any { it.id == "match:teleop-start" })
            val annotationAlignment = initial.availableAlignments.single { it.kind == RunAlignmentKind.ANNOTATION }
            assertTrue(annotationAlignment.label.contains("Cycle start"))

            val aligned = service.compare(
                workspace,
                RunComparisonRequest("run-a", listOf("run-b"), AUTONOMOUS_START_ALIGNMENT_ID),
            )
            val oracle = loadOracle()
            assertEquals(
                oracle.getProperty("autonomous_anchors_ms").split(',').map(String::toLong),
                aligned.anchors.map(RunAlignmentAnchor::absoluteTimestampMs),
            )
            assertEquals(
                oracle.getProperty("metric_units").split('|').toSet(),
                aligned.metrics.map { it.unit }.toSet(),
            )
            assertTrue(aligned.trajectories.all { trajectory -> trajectory.points.first().alignedTimeMs == 0L })

            fun metricValue(metricId: String, selector: (RunMetricSummary) -> Double): Double =
                aligned.metrics.single { it.id == metricId }.series.single { it.sessionId == "run-b" }
                    .summary.let(selector)
            assertEquals(oracle.getProperty("run_b_battery_min_v").toDouble(), metricValue("battery_voltage", RunMetricSummary::minimum))
            assertEquals(oracle.getProperty("run_b_loop_p95_ms").toDouble(), metricValue("loop_time", RunMetricSummary::p95))
            assertEquals(oracle.getProperty("run_b_total_current_p95_a").toDouble(), metricValue("total_motor_current", RunMetricSummary::p95))

            val localization = aligned.metrics.single { it.id == "localization_error" }
            assertEquals(2, localization.series.size)
            assertTrue(localization.series.all { it.sourceTopics.size == 4 })
            assertTrue(localization.series.flatMap { it.samples }.all { it.absoluteTimestampMs > 0L })

            val batteryFinding = aligned.findings.first { it.id == "battery_voltage:run-b" }
            assertEquals(oracle.getProperty("battery_finding_session"), batteryFinding.evidence.sessionId)
            assertEquals(oracle.getProperty("battery_finding_timestamp_ms").toLong(), batteryFinding.evidence.absoluteTimestampMs)
            assertEquals(oracle.getProperty("battery_finding_aligned_ms").toLong(), batteryFinding.evidence.alignedTimeMs)
            assertEquals(ComparisonClaimKind.OBSERVATION, batteryFinding.kind)
            assertTrue(aligned.findings.any { it.kind == ComparisonClaimKind.CORRELATION })
            assertTrue(aligned.findings.any { it.id == "faults:run-b" })

            val destination = File(root, "reports/comparison.md")
            service.exportMarkdown(aligned, destination)
            val markdown = destination.readText()
            assertTrue(markdown.contains("Correlation — cause not proven"))
            assertTrue(markdown.contains("timestamp 2350 ms"))
            assertTrue(markdown.contains("Robot/BatteryVoltage"))

            val unsafe = aligned.copy(
                findings = listOf(
                    batteryFinding.copy(
                        title = "[click](https://untrusted.example)",
                        evidence = batteryFinding.evidence.copy(topics = listOf("<script>alert(1)</script>")),
                    )
                )
            )
            val sanitized = service.renderMarkdown(unsafe)
            assertFalse(sanitized.contains("[click](https://untrusted.example)"))
            assertFalse(sanitized.contains("<script>"))
        }
    }

    @Test
    fun `comparison fails closed when any selected run is outside the workspace`() = runTest {
        withService { root, database, service ->
            database.insertSession(Session("mine", "23247", "decode", "practice", 1_000L))
            database.insertSession(Session("other", "99999", "decode", "practice", 2_000L))
            database.insertTelemetryFrames(listOf(TelemetryFrame(1_000L, "mine", "Robot/BatteryVoltage", 12.0)))
            database.insertTelemetryFrames(listOf(TelemetryFrame(2_000L, "other", "Robot/BatteryVoltage", 12.0)))

            val failure = assertFailsWith<IllegalArgumentException> {
                service.compare(workspace(root), RunComparisonRequest("mine", listOf("other")))
            }
            assertTrue(failure.message.orEmpty().contains("not part of the selected"))
        }
    }

    @Test
    fun `derived metrics never join a future or merely nearby sample`() = runTest {
        withService { root, database, service ->
            val workspace = workspace(root)
            listOf("a", "b").forEachIndexed { index, id ->
                val start = 1_000L + index * 1_000L
                database.insertSession(Session(id, "23247", "decode", "practice", start))
                database.insertTelemetryFrames(
                    listOf(
                        TelemetryFrame(start, id, "Robot/BatteryVoltage", 12.0),
                        TelemetryFrame(start, id, "ARES/EstimatedPose/0", 1.0),
                        TelemetryFrame(start + 1L, id, "ARES/EstimatedPose/1", 1.0),
                        TelemetryFrame(start, id, "ARES/TruePose/0", 1.0),
                        TelemetryFrame(start, id, "ARES/TruePose/1", 1.0),
                    )
                )
            }

            val report = service.compare(workspace, RunComparisonRequest("a", listOf("b")))

            assertTrue(report.metrics.none { it.id == "localization_error" })
            assertTrue(report.trajectories.isEmpty())
            assertTrue(report.limitations.any { it.contains("exact source timestamps") })
        }
    }

    @Test
    fun `comparison requires primary evidence and compatible channel sets`() = runTest {
        withService { root, database, service ->
            val starts = mapOf("primary" to 1_000L, "second" to 2_000L, "third" to 3_000L)
            starts.forEach { (id, start) ->
                database.insertSession(Session(id, "23247", "decode", "practice", start))
                database.insertTelemetryFrames(
                    buildList {
                        add(TelemetryFrame(start, id, "Robot/BatteryVoltage", 12.0))
                        if (id != "primary") add(TelemetryFrame(start, id, "Robot/LoopTimeMs", 20.0))
                        add(TelemetryFrame(start, id, "Hardware/Motors/fl/CurrentAmps", 4.0))
                        if (id != "primary") add(TelemetryFrame(start, id, "Hardware/Motors/fr/CurrentAmps", 4.0))
                        add(TelemetryFrame(start, id, "Gamepad1/LeftX", 0.2))
                        add(TelemetryFrame(start, id, "Gamepad1/LeftY", 0.3))
                        if (id != "primary") add(TelemetryFrame(start, id, "Gamepad1/RightX", 0.1))
                    }
                )
            }

            val report = service.compare(
                workspace(root),
                RunComparisonRequest("primary", listOf("second", "third")),
            )

            assertTrue(report.metrics.any { it.id == "battery_voltage" })
            assertTrue(report.metrics.none { it.id == "loop_time" })
            assertTrue(report.metrics.none { it.id == "total_motor_current" })
            assertTrue(report.metrics.none { it.id == "driver_input_magnitude" })
        }
    }

    @Test
    fun `last same-timestamp sample wins without double-counting derived signals`() = runTest {
        withService { root, database, service ->
            listOf("a" to 1_000L, "b" to 2_000L).forEach { (id, timestamp) ->
                database.insertSession(Session(id, "23247", "decode", "practice", timestamp))
                database.insertTelemetryFrames(
                    listOf(
                        TelemetryFrame(timestamp, id, "Hardware/Motors/fl/CurrentAmps", 2.0, sampleOrder = 1L),
                        TelemetryFrame(timestamp, id, "Hardware/Motors/fl/CurrentAmps", 4.0, sampleOrder = 2L),
                        TelemetryFrame(timestamp, id, "Hardware/Motors/fr/CurrentAmps", 3.0, sampleOrder = 1L),
                        TelemetryFrame(timestamp, id, "Gamepad1/LeftX", 0.1, sampleOrder = 1L),
                        TelemetryFrame(timestamp, id, "Gamepad1/LeftX", 0.6, sampleOrder = 2L),
                        TelemetryFrame(timestamp, id, "Gamepad1/LeftY", 0.8, sampleOrder = 1L),
                    )
                )
            }

            val report = service.compare(workspace(root), RunComparisonRequest("a", listOf("b")))

            val current = report.metrics.single { it.id == "total_motor_current" }
            assertTrue(current.series.all { it.samples.single().value == 7.0 })
            val driver = report.metrics.single { it.id == "driver_input_magnitude" }
            assertTrue(driver.series.all { it.samples.single().value == 1.0 })
        }
    }

    @Test
    fun `material differences and Academy labels do not depend on which run is primary`() = runTest {
        withService { root, database, service ->
            database.insertSession(
                Session(
                    "stalled",
                    "23247",
                    "decode",
                    "practice",
                    1_000L,
                    tags = listOf("academy-synthetic-data", "academy-practice-source:stalled-arm-run.csv"),
                )
            )
            database.insertSession(
                Session(
                    "baseline",
                    "23247",
                    "decode",
                    "practice",
                    2_000L,
                    tags = listOf("academy-synthetic-data", "academy-practice-source:baseline-arm-run.csv"),
                )
            )
            database.insertTelemetryFrames(
                listOf(
                    TelemetryFrame(1_000L, "stalled", "Arm/CurrentAmps", 12.0),
                    TelemetryFrame(2_000L, "baseline", "Arm/CurrentAmps", 4.0),
                )
            )

            val report = service.compare(
                workspace(root),
                RunComparisonRequest("stalled", listOf("baseline")),
            )

            assertEquals(
                listOf("Academy · Stalled arm run", "Academy · Baseline arm run"),
                report.sessions.map { it.shortRunLabel() },
            )
            val currentFinding = report.findings.single { it.id == "total_motor_current:stalled" }
            assertEquals("stalled", currentFinding.evidence.sessionId)
            assertTrue(currentFinding.explanation.contains("Baseline arm run"))
        }
    }

    private suspend fun withService(block: suspend (File, DatabaseService, RunComparisonService) -> Unit) {
        val root = Files.createTempDirectory("run-comparison").toFile()
        val database = DatabaseService(File(root, "analytics.duckdb").path)
        try {
            block(root, database, RunComparisonService(database))
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
        projectPath = root.path,
        league = League.FTC,
    )

    private fun loadFixture(name: String, sessionId: String): List<TelemetryFrame> {
        val resource = assertNotNull(javaClass.getResourceAsStream("/run-comparison/$name"))
        return resource.bufferedReader().useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).mapIndexed { index, row ->
                val columns = row.split(',', limit = 7)
                TelemetryFrame(
                    timestampMs = columns[1].toLong(),
                    sessionId = sessionId,
                    key = columns[0],
                    value = columns[5].toDouble(),
                    stringValue = columns.getOrNull(6)?.takeIf(String::isNotBlank),
                    timestampUs = columns[2].toLong(),
                    sampleOrder = columns[3].toLong(),
                )
            }.toList()
        }
    }

    private fun loadOracle(): Properties = Properties().apply {
        val resource = assertNotNull(
            RunComparisonServiceTest::class.java.getResourceAsStream("/run-comparison/golden-oracle.properties")
        )
        resource.use { input -> load(input) }
    }
}
