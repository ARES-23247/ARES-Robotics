package com.ares.analytics.service.db

import com.ares.analytics.shared.models.TelemetryFrame
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Internal, prepared selection rules. Patterns use DuckDB/RE2 full-match semantics. */
internal data class AnalysisTelemetryGroup(val id: String, val keys: List<String>, val patterns: List<String> = emptyList())

internal data class AnalysisTelemetryLimits(
    val rows: Int = 100_000,
    val topics: Int = 256,
    val projectedBytes: Long = 16 * 1024 * 1024,
) {
    init {
        require(rows in 1..100_000 && topics in 1..256 && projectedBytes in 1..16 * 1024 * 1024)
    }
}

internal data class AnalysisTelemetryInput(val status: String, val sourceRows: Long, val frames: List<TelemetryFrame>) {
    val complete: Boolean get() = status == "complete" || status == "empty"
}

/**
 * One statement snapshot, latest update per topic/source microsecond, with no sampling. Limits
 * reject a whole group before returning its frames; other groups remain independently usable.
 * Text payloads become a constant nonnumeric marker. SQL projects no raw payload and bounds both
 * row count and estimated key storage (64 bytes/row + four bytes/Unicode code point, covering
 * supplementary UTF-16 characters). This is a transfer budget, not an exact JVM heap estimate.
 */
internal class AnalysisTelemetryRepository(private val transactions: DatabaseTransactionCoordinator) {
    suspend fun read(
        sessionId: String, groups: List<AnalysisTelemetryGroup>, limits: AnalysisTelemetryLimits,
    ): Map<String, AnalysisTelemetryInput> {
        require(groups.size in 1..4 && groups.map { it.id }.distinct().size == groups.size)
        val rules = groups.flatMap { group ->
            require(group.id.isNotBlank() && group.id.length <= 64)
            require(group.keys.size + group.patterns.size in 1..128)
            require((group.keys + group.patterns).all { it.isNotBlank() && it.length <= 1024 })
            group.keys.map { listOf(group.id, it.trimStart('/'), "") } +
                group.patterns.map { listOf(group.id, "", it) }
        }
        // Ingestion normalizes keys. All identifiers, filters, session IDs and limits are values.
        val sql = """
            WITH rules(group_id, exact_key, pattern) AS (VALUES ${rules.joinToString { "(?, ?, ?)" }}),
            matching AS MATERIALIZED (
                SELECT r.group_id, t.key, t.timestamp_us, t.sample_order, t.value,
                    t.string_value IS NOT NULL AS is_text,
                    ROW_NUMBER() OVER (PARTITION BY r.group_id, t.key, t.timestamp_us
                        ORDER BY t.sample_order DESC) AS latest
                FROM telemetry_frames t JOIN rules r
                    ON (r.exact_key <> '' AND t.key = r.exact_key)
                    OR (r.pattern <> '' AND regexp_full_match(t.key, r.pattern))
                WHERE t.session_id = ?
            ), latest AS MATERIALIZED (SELECT * FROM matching WHERE latest = 1),
            counts AS (
                SELECT group_id, COUNT(*) AS source_rows, COUNT(DISTINCT key) AS topics,
                    SUM(64 + 4 * LENGTH(key)) AS projected_bytes
                FROM latest GROUP BY group_id
            ), availability AS (
                SELECT g.group_id, COALESCE(c.source_rows, 0) AS source_rows,
                    CASE WHEN c.source_rows IS NULL THEN 'empty'
                        WHEN c.source_rows > ? THEN 'row_limit'
                        WHEN c.topics > ? THEN 'topic_limit'
                        WHEN c.projected_bytes > ? THEN 'byte_limit'
                        ELSE 'complete' END AS status
                FROM (SELECT DISTINCT group_id FROM rules) g LEFT JOIN counts c USING (group_id)
            )
            SELECT a.group_id, a.status, a.source_rows, l.key, l.timestamp_us,
                l.sample_order, l.value, l.is_text
            FROM availability a LEFT JOIN latest l ON a.group_id = l.group_id AND a.status = 'complete'
            ORDER BY a.group_id, l.timestamp_us, l.sample_order, l.key
        """.trimIndent()
        return transactions.read {
            currentCoroutineContext().ensureActive()
            transactions.readConnectionFor(sessionId).prepareStatement(sql).use { statement ->
                var parameter = 1
                for (rule in rules) for (value in rule) statement.setString(parameter++, value)
                statement.setString(parameter++, sessionId)
                statement.setInt(parameter++, limits.rows)
                statement.setInt(parameter++, limits.topics)
                statement.setLong(parameter, limits.projectedBytes)
                statement.executeQuery().use { result ->
                    val inputs = linkedMapOf<String, AnalysisTelemetryInput>()
                    var previousGroup: String? = null
                    var frames = mutableListOf<TelemetryFrame>()
                    var readRows = 0
                    while (result.next()) {
                        if (readRows++ % 1024 == 0) currentCoroutineContext().ensureActive()
                        val group = result.getString(1)
                        if (group != previousGroup) {
                            frames = mutableListOf()
                            inputs[group] = AnalysisTelemetryInput(result.getString(2), result.getLong(3), frames)
                            previousGroup = group
                        }
                        val key = result.getString(4) ?: continue
                        val timeUs = result.getLong(5)
                        val value = result.getDouble(7).let { if (result.wasNull()) Double.NaN else it }
                        frames.add(TelemetryFrame(timeUs / 1000, sessionId, key, value,
                            if (result.getBoolean(8)) "" else null, timeUs, result.getLong(6)))
                    }
                    inputs
                }
            }
        }
    }
}
