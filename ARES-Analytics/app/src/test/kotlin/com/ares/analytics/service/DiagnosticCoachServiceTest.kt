package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryFrame
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticCoachServiceTest {
    @Test
    fun reportsEvidenceAndHypothesesWithoutClaimingRootCause() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(TelemetryFrame(100, "run", "Robot/BatteryVoltage", 9.2)))
            val result = service.analyze("run")
            val finding = result.findings.single()
            assertEquals(DiagnosticSeverity.URGENT, finding.severity)
            assertTrue(finding.observation.contains("9.20 V"))
            assertTrue(finding.possibleCauses.size > 1)
            assertFalse(finding.observation.contains("caused by", ignoreCase = true))
            assertTrue(result.evidenceNotice.contains("not root-cause"))
        }
    }

    @Test
    fun currentScreenDoesNotCallTheObservationAStall() = runTest {
        withService { database, service ->
            val frames = (0..5).map { index ->
                TelemetryFrame(index * 100L, "run", "Hardware/Motors/arm/CurrentAmps", 45.0)
            }
            database.insertTelemetryFrames(frames)
            val finding = service.analyze("run").findings.single()
            assertFalse(finding.title.contains("stall", ignoreCase = true))
            assertTrue(finding.thresholdContext.contains("does not establish a stall"))
        }
    }

    @Test
    fun missingSignalsAndNoFindingsNeverClaimHealth() = runTest {
        withService { _, service ->
            val result = service.analyze("empty")
            assertTrue(result.findings.isEmpty())
            assertEquals(listOf("Battery voltage", "Per-motor current", "Control loop period"), result.missingSignals)
            assertFalse(result.evidenceNotice.contains("healthy", ignoreCase = true))
        }
    }

    @Test
    fun loopOverrunScreenDetectsHighLoopTimes() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Robot/BatteryVoltage", 12.5),
                TelemetryFrame(100, "run", "Hardware/Motors/arm/CurrentAmps", 5.0),
                TelemetryFrame(100, "run", "Robot/LoopTimeMs", 42.0)
            ))
            val result = service.analyze("run")
            val loopFinding = result.findings.firstOrNull { it.id == "loop-time-overrun" }
            kotlin.test.assertNotNull(loopFinding)
            assertEquals(DiagnosticSeverity.REVIEW, loopFinding.severity)
            assertTrue(loopFinding.observation.contains("42.0 ms"))
            assertTrue(loopFinding.possibleCauses.isNotEmpty())
        }
    }

    @Test
    fun brownoutGuardScreenDetectsThrottlingAndEvents() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Robot/BatteryVoltage", 11.0),
                TelemetryFrame(100, "run", "Hardware/Motors/arm/CurrentAmps", 15.0),
                TelemetryFrame(100, "run", "Robot/LoopTimeMs", 20.0),
                TelemetryFrame(150, "run", "Diagnostics/Power/BrownoutCount", 2.0)
            ))
            val result = service.analyze("run")
            val brownoutFinding = result.findings.firstOrNull { it.id == "brownout-guard-tripped" }
            kotlin.test.assertNotNull(brownoutFinding)
            assertEquals(DiagnosticSeverity.URGENT, brownoutFinding.severity)
            assertTrue(brownoutFinding.observation.contains("2 brownout event"))
        }
    }

    @Test
    fun brownoutGuardScreenDetectsPowerScaleThrottlingWithoutCount() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Robot/BatteryVoltage", 11.0),
                TelemetryFrame(100, "run", "Hardware/Motors/arm/CurrentAmps", 15.0),
                TelemetryFrame(100, "run", "Robot/LoopTimeMs", 20.0),
                TelemetryFrame(150, "run", "Robot/BrownoutPowerScale", 0.75)
            ))
            val result = service.analyze("run")
            val brownoutFinding = result.findings.firstOrNull { it.id == "brownout-guard-tripped" }
            kotlin.test.assertNotNull(brownoutFinding)
            assertEquals(DiagnosticSeverity.URGENT, brownoutFinding.severity)
            assertTrue(brownoutFinding.observation.contains("75%"))
        }
    }

    @Test
    fun urgentLoopOverrunDetectsSevereLoopLatency() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Robot/BatteryVoltage", 12.5),
                TelemetryFrame(100, "run", "Hardware/Motors/arm/CurrentAmps", 5.0),
                TelemetryFrame(100, "run", "Robot/LoopTimeMs", 55.0)
            ))
            val result = service.analyze("run")
            val loopFinding = result.findings.firstOrNull { it.id == "loop-time-overrun" }
            kotlin.test.assertNotNull(loopFinding)
            assertEquals(DiagnosticSeverity.URGENT, loopFinding.severity)
            assertTrue(loopFinding.observation.contains("55.0 ms"))
        }
    }

    @Test
    fun blankSessionIdThrowsIllegalArgumentException() = runTest {
        withService { _, service ->
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                service.analyze("   ")
            }
        }
    }

    @Test
    fun ekfDiagnosticFindingDetectsCameraSkew() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Diagnostics/EKF/AvgNIS", 2.2),
                TelemetryFrame(100, "run", "Diagnostics/EKF/ResidualBiasM", 0.055),
                TelemetryFrame(100, "run", "Diagnostics/EKF/NISOutlierRatio", 0.02)
            ))
            val result = service.analyze("run")
            val finding = result.findings.firstOrNull { it.id == "ekf-extrinsic-skew" }
            kotlin.test.assertNotNull(finding)
            assertEquals(DiagnosticSeverity.REVIEW, finding.severity)
            assertTrue(finding.observation.contains("5.5 cm"))
        }
    }

    @Test
    fun autoTrackingFindingDetectsDeviation() = runTest {
        withService { database, service ->
            database.insertTelemetryFrames(listOf(
                TelemetryFrame(100, "run", "Diagnostics/Auto/CrossTrackRMSE", 0.08),
                TelemetryFrame(100, "run", "Diagnostics/Auto/MaxCrossTrackM", 0.18)
            ))
            val result = service.analyze("run")
            val finding = result.findings.firstOrNull { it.id == "auto-path-deviation" }
            kotlin.test.assertNotNull(finding)
            assertEquals(DiagnosticSeverity.REVIEW, finding.severity)
            assertTrue(finding.observation.contains("8.0 cm"))
        }
    }

    private suspend fun withService(block: suspend (DatabaseService, DiagnosticCoachService) -> Unit) {
        val file = File.createTempFile("diagnostic-coach", ".db").apply { deleteOnExit() }
        val database = DatabaseService(file.absolutePath)
        try {
            block(database, DiagnosticCoachService(database))
        } finally {
            database.close()
            file.delete()
        }
    }
}
