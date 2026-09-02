package com.ares.analytics.service

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.ThresholdRule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * MockNt4ClientService class.
 */
class MockNt4ClientService(databaseService: DatabaseService) : Nt4ClientService(databaseService) {
    val mockTelemetryFlow = MutableSharedFlow<TelemetryFrame>(replay = 100)
    override val telemetryFlow: SharedFlow<TelemetryFrame> = mockTelemetryFlow.asSharedFlow()
}

/**
 * AlertEngineServiceTest class.
 */
class AlertEngineServiceTest {

    @Test
    fun `one moderate loop spike is diagnostic evidence but repeated spikes alert`() = runBlocking {
        val tempDb = File.createTempFile("loop_alert_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val nt4Service = MockNt4ClientService(databaseService)
        val thresholds = File(tempDb.parentFile, "missing-loop-thresholds-${System.nanoTime()}.json")
        val alertService = AlertEngineService(databaseService, nt4Service, thresholds.absolutePath)
        try {
            delay(100)
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(1_000L, "loop-session", "Robot/LoopTimeMs", 40.0))
            delay(100)
            assertFalse(alertService.alerts.value.any { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey })

            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(1_020L, "loop-session", "Robot/LoopTimeMs", 30.0))
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(1_040L, "loop-session", "Robot/LoopTimeMs", 28.0))
            val alerts = kotlinx.coroutines.withTimeout(2_000) {
                alertService.alerts.first { list ->
                    list.any { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey && it.resolveTimestampMs == null }
                }
            }
            assertEquals(40.0, alerts.first { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey }.peakValue)
        } finally {
            alertService.dispose()
            databaseService.close()
            thresholds.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `severe loop stall alerts immediately and sustained healthy timing resolves it`() = runBlocking {
        val tempDb = File.createTempFile("severe_loop_alert_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val nt4Service = MockNt4ClientService(databaseService)
        val thresholds = File(tempDb.parentFile, "missing-severe-loop-thresholds-${System.nanoTime()}.json")
        val alertService = AlertEngineService(databaseService, nt4Service, thresholds.absolutePath)
        try {
            delay(100)
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(2_000L, "severe-session", "Robot/LoopTimeMs", 120.0))
            kotlinx.coroutines.withTimeout(2_000) {
                alertService.alerts.first { list ->
                    list.any { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey && it.resolveTimestampMs == null }
                }
            }
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(3_100L, "severe-session", "Robot/LoopTimeMs", 20.0))
            val resolved = kotlinx.coroutines.withTimeout(2_000) {
                alertService.alerts.first { list ->
                    list.any { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey && it.resolveTimestampMs != null }
                }
            }
            assertTrue(resolved.first { it.ruleKey == TelemetryMetricCatalog.LOOP_TIME.canonicalKey }.resolveTimestampMs != null)
        } finally {
            alertService.dispose()
            databaseService.close()
            thresholds.delete()
            tempDb.delete()
        }
    }

    @Test
    /**
     * testAlertEvaluation fun.
     */
    fun testAlertEvaluation() {
        runBlocking {
            val tempDb = File.createTempFile("alert_db_test", ".db").apply { deleteOnExit() }
            val databaseService = DatabaseService(tempDb.absolutePath)
            val nt4Service = MockNt4ClientService(databaseService)

            // Write custom thresholds rules to a temp file
            val tempFile = File.createTempFile("thresholds_test", ".json")
            tempFile.delete() // Delete so AlertEngineService writes defaults/customs
            val rulesList = listOf(
                ThresholdRule("/Drive/Voltage", "Low Battery Voltage", minValue = 11.5, audibleAlert = false),
                ThresholdRule("/Drive/EkfDrift", "High EKF Position Drift", maxValue = 0.20, audibleAlert = false)
            )
            tempFile.writeText(Json.encodeToString(rulesList))
            val alertService = AlertEngineService(databaseService, nt4Service, tempFile.absolutePath)

            // Give coroutines a moment to initialize subscription
            delay(200)

            // Emit telemetry violating /Drive/Voltage (< 11.5)
            val frame1 = TelemetryFrame(1000L, "session-123", "/Drive/Voltage", 11.0)
            nt4Service.mockTelemetryFlow.emit(frame1)

            delay(200)
            val activeAlerts = alertService.alerts.value
            assertEquals(1, activeAlerts.size)
            val alert = activeAlerts[0]
            assertEquals("/Drive/Voltage", alert.ruleKey)
            assertEquals("session-123", alert.sessionId)
            assertEquals(11.0, alert.peakValue)
            kotlin.test.assertFalse(alert.triaged)

            // Emit telemetry restoring normal voltage
            val frame2 = TelemetryFrame(1020L, "session-123", "/Drive/Voltage", 12.0)
            nt4Service.mockTelemetryFlow.emit(frame2)

            delay(200)

            // Should resolve the alert (resolveTimestampMs set)
            val resolvedAlerts = alertService.alerts.value
            assertEquals(1, resolvedAlerts.size)
            val resolved = resolvedAlerts[0]
            assertTrue(resolved.resolveTimestampMs != null)
            assertEquals(20L, resolved.durationMs)

            // Triage the alert
            alertService.triageAlert(resolved.alertId)
            delay(200)
            assertTrue(alertService.alerts.value[0].triaged)

            alertService.stop()
            tempFile.delete()
            tempDb.delete()
        }
    }

    @Test
    fun `same rule in consecutive sessions creates independent alerts`() = runBlocking {
        val tempDb = File.createTempFile("alert_session_db", ".db").apply { deleteOnExit() }
        val databaseService = DatabaseService(tempDb.absolutePath)
        val nt4Service = MockNt4ClientService(databaseService)
        val thresholds = File.createTempFile("thresholds_session_test", ".json").apply {
            writeText(
                Json.encodeToString(
                    listOf(ThresholdRule("Robot/BatteryVoltage", "Low battery", minValue = 10.5, audibleAlert = false))
                )
            )
        }
        val alertService = AlertEngineService(databaseService, nt4Service, thresholds.absolutePath)
        try {
            delay(100)
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(1_000L, "session-a", "Robot/BatteryVoltage", 10.0))
            nt4Service.mockTelemetryFlow.emit(TelemetryFrame(2_000L, "session-b", "/Robot/BatteryVoltage", 9.8))
            val alerts = kotlinx.coroutines.withTimeout(2_000) {
                alertService.alerts.first { records ->
                    records
                        .filter { it.ruleKey == "Robot/BatteryVoltage" }
                        .map { it.sessionId }
                        .toSet() == setOf("session-a", "session-b")
                }
            }.filter { it.ruleKey == "Robot/BatteryVoltage" }
            assertEquals(setOf("session-a", "session-b"), alerts.map { it.sessionId }.toSet())
        } finally {
            alertService.dispose()
            databaseService.close()
            thresholds.delete()
            tempDb.delete()
        }
    }
}
