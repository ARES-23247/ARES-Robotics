package com.ares.analytics.service.db

import com.ares.analytics.service.ImportReport
import com.ares.analytics.shared.AppJson
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.sql.Connection

/** Owns analyzer output and immutable source evidence associated with an imported run. */
internal class RunEvidenceRepository(
    private val transactions: DatabaseTransactionCoordinator,
    private val sessions: SessionMetadataRepository,
) {
    private val writeConnection: Connection get() = transactions.writeConnection
    private val readConnection: Connection get() = transactions.readConnection

    suspend fun getDiagnosticsTelemetry(sessionId: String): List<TelemetryFrame> = transactions.write {
        val frames = mutableListOf<TelemetryFrame>()
        writeConnection.prepareStatement(
            "SELECT * FROM telemetry_frames WHERE session_id = ? AND key LIKE 'Diagnostics/%'",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) frames += rows.toTelemetryFrame()
            }
        }
        val displayTimestamp = writeConnection.prepareStatement(
            "SELECT COALESCE(MAX(timestamp_ms), (SELECT created_at FROM sessions WHERE session_id = ?), 0) " +
                "FROM telemetry_frames WHERE session_id = ?",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.setString(2, sessionId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
        writeConnection.prepareStatement(
            "SELECT session_id, key, value, string_value FROM analysis_diagnostics WHERE session_id = ? ORDER BY key",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                var sampleOrder = 1L
                while (rows.next()) {
                    frames += TelemetryFrame(
                        timestampMs = displayTimestamp,
                        sessionId = rows.getString("session_id"),
                        key = rows.getString("key"),
                        value = rows.getDouble("value"),
                        stringValue = rows.getString("string_value"),
                        sampleOrder = sampleOrder++,
                    )
                }
            }
        }
        frames
    }

    suspend fun replaceAnalysisDiagnostics(
        sessionId: String,
        diagnostics: List<AnalysisDiagnostic>,
    ) = transactions.write {
        require(diagnostics.all { it.sessionId == sessionId }) {
            "Every analysis diagnostic must belong to the replaced session"
        }
        val previousAutoCommit = writeConnection.autoCommit
        try {
            writeConnection.autoCommit = false
            writeConnection.prepareStatement("DELETE FROM analysis_diagnostics WHERE session_id = ?").use { statement ->
                statement.setString(1, sessionId)
                statement.executeUpdate()
            }
            writeConnection.prepareStatement(
                "INSERT INTO analysis_diagnostics (session_id, key, value, string_value) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                diagnostics.distinctBy { TelemetryMetricCatalog.normalizeTopic(it.key) }.forEach { diagnostic ->
                    statement.setString(1, sessionId)
                    statement.setString(2, TelemetryMetricCatalog.normalizeTopic(diagnostic.key))
                    statement.setDouble(3, diagnostic.value)
                    statement.setString(4, diagnostic.stringValue)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            writeConnection.commit()
        } catch (failure: Throwable) {
            writeConnection.rollback()
            throw failure
        } finally {
            writeConnection.autoCommit = previousAutoCommit
        }
    }

    suspend fun getAnalysisDiagnostics(sessionId: String): List<AnalysisDiagnostic> = transactions.read {
        val diagnostics = mutableListOf<AnalysisDiagnostic>()
        readConnection.prepareStatement(
            "SELECT session_id, key, value, string_value FROM analysis_diagnostics WHERE session_id = ? ORDER BY key",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    diagnostics += AnalysisDiagnostic(
                        sessionId = rows.getString("session_id"),
                        key = rows.getString("key"),
                        value = rows.getDouble("value"),
                        stringValue = rows.getString("string_value"),
                    )
                }
            }
        }
        diagnostics
    }

    suspend fun replaceImportReports(sessionId: String, reports: List<ImportReport>) = transactions.write {
        require(reports.all { it.sessionId == sessionId }) {
            "Every import report must belong to the replaced session"
        }
        val previousAutoCommit = writeConnection.autoCommit
        try {
            writeConnection.autoCommit = false
            replaceImportReportsWithinTransaction(sessionId, reports)
            writeConnection.commit()
        } catch (failure: Throwable) {
            writeConnection.rollback()
            throw failure
        } finally {
            writeConnection.autoCommit = previousAutoCommit
        }
    }

    /** Atomically exposes a staged import together with its immutable source evidence. */
    suspend fun completeImport(session: Session, reports: List<ImportReport>) = transactions.write {
        require(reports.isNotEmpty()) { "A completed import requires source evidence" }
        require(reports.all { it.sessionId == session.sessionId }) {
            "Every import report must belong to the completed session"
        }
        val previousAutoCommit = writeConnection.autoCommit
        try {
            writeConnection.autoCommit = false
            sessions.upsertCompletedSessionWithinTransaction(session)
            replaceImportReportsWithinTransaction(session.sessionId, reports)
            writeConnection.commit()
        } catch (failure: Throwable) {
            writeConnection.rollback()
            throw failure
        } finally {
            writeConnection.autoCommit = previousAutoCommit
        }
    }

    suspend fun getImportReports(sessionId: String): List<ImportReport> = transactions.read {
        val reports = mutableListOf<ImportReport>()
        readConnection.prepareStatement(
            "SELECT report_json FROM session_import_reports WHERE session_id = ? ORDER BY source_name, source_sha256",
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    reports += AppJson.decodeFromString<ImportReport>(rows.getString("report_json"))
                }
            }
        }
        reports
    }

    private fun replaceImportReportsWithinTransaction(sessionId: String, reports: List<ImportReport>) {
        writeConnection.prepareStatement("DELETE FROM session_import_reports WHERE session_id = ?").use { statement ->
            statement.setString(1, sessionId)
            statement.executeUpdate()
        }
        writeConnection.prepareStatement(
            "INSERT INTO session_import_reports (session_id, source_sha256, source_name, report_json) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            reports.distinctBy { it.sourceSha256 to it.sourceName }.forEach { report ->
                statement.setString(1, sessionId)
                statement.setString(2, report.sourceSha256)
                statement.setString(3, report.sourceName)
                statement.setString(4, AppJson.encodeToString(report))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun java.sql.ResultSet.toTelemetryFrame(): TelemetryFrame {
        val stringValue = getString("string_value")
        val finalStringValue = if (wasNull()) null else stringValue
        val timestampUs = runCatching { getLong("timestamp_us") }
            .getOrDefault(getLong("timestamp_ms") * 1_000L)
        val sampleOrder = runCatching { getLong("sample_order") }.getOrDefault(0L)
        return TelemetryFrame(
            timestampMs = getLong("timestamp_ms"),
            sessionId = getString("session_id"),
            key = getString("key"),
            value = getDouble("value"),
            stringValue = finalStringValue,
            timestampUs = timestampUs,
            sampleOrder = sampleOrder,
        )
    }
}
