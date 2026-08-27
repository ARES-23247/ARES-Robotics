package com.ares.analytics.service

import com.ares.analytics.shared.Session
import kotlinx.serialization.Serializable

/** Machine-readable evidence describing what a log import actually produced. */
@Serializable
data class ImportReport(
    val schemaVersion: Int = 1,
    val sourceName: String,
    val sourceSha256: String,
    val sourceSizeBytes: Long,
    val decoder: String,
    val status: ImportStatus,
    val sessionId: String? = null,
    val acceptedRecords: Long = 0,
    val rejectedRecords: Long? = null,
    val detectedTopics: List<String> = emptyList(),
    val minTimestampMs: Long? = null,
    val maxTimestampMs: Long? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null
)

@Serializable
enum class ImportStatus {
    SUCCESS,
    PARTIAL,
    REJECTED
}

data class LogImportResult(
    val session: Session,
    val report: ImportReport,
    val wasAlreadyImported: Boolean = false,
)
