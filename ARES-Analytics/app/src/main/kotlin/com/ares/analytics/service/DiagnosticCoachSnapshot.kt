package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.shared.models.AnalysisDiagnostic
import com.ares.analytics.shared.models.TelemetryFrame
import java.math.BigInteger

internal data class CoachObservation(val topic: String, val value: Double, val startUs: Long, val endUs: Long, val samples: Long) {
    val durationSeconds: Double get() = BigInteger.valueOf(endUs).subtract(BigInteger.valueOf(startUs)).toDouble() / 1_000_000.0
}

internal class CoachSnapshot(private val observations: Map<String, List<CoachObservation>>) {
    fun all(kind: String): List<CoachObservation> = observations[kind].orEmpty()
    fun first(kind: String): CoachObservation? = all(kind).firstOrNull()
    fun localization(sessionId: String): List<TelemetryFrame> = all("localization").map {
        TelemetryFrame(it.startUs / 1_000, sessionId, it.topic, it.value, timestampUs = it.startUs)
    }
}

internal data class CoachGeneratedDiagnostics(val metrics: List<AnalysisDiagnostic>, val families: Set<String>)

/** One recorded-data snapshot; only extrema, qualifying spans and latest aggregates cross into JVM memory. */
internal class DiagnosticCoachSnapshotReader(private val query: suspend (String, List<Any>) -> QueryResult) {
    suspend fun readGenerated(sessionId: String): CoachGeneratedDiagnostics {
        val result = query(GENERATED_SQL, listOf(sessionId))
        require(!result.isTruncated && result.truncatedCellCount == 0 && result.rows.size <= GENERATED_LIMIT) { "Generated coaching evidence exceeded its limit" }
        val metrics = linkedMapOf<String, AnalysisDiagnostic>()
        val families = linkedSetOf<String>()
        for (row in result.rows) {
            require(row.size == 3 && row.all { it.length <= QueryResult.MAX_CELL_CHARACTERS }) { "Unexpected generated coaching projection" }
            when (row[0]) {
                "family" -> require(row[1] in FAMILIES && families.add(row[1])) { "Invalid generated diagnostic family" }
                "metric" -> {
                    require(row[1] in DiagnosticCoachLocalization.inputKeys && row[1] !in metrics) { "Invalid or duplicate generated metric" }
                    val unavailable = row[2] == "NULL"
                    val value = if (unavailable) 0.0 else row[2].toDouble()
                    require(value.isFinite() && value >= 0) { "Invalid generated coaching value" }
                    // Preserve invalid latest results without violating the finite-value model contract.
                    metrics[row[1]] = AnalysisDiagnostic(sessionId, row[1], value, if (unavailable) "unavailable" else null)
                }
                else -> error("Unexpected generated coaching row kind")
            }
        }
        require(metrics.keys.all { it.substringBeforeLast('/') in families }) { "Generated metric is missing its family provenance" }
        return CoachGeneratedDiagnostics(metrics.values.toList(), families)
    }

    suspend fun read(sessionId: String): CoachSnapshot {
        val result = query(SQL, listOf(sessionId))
        require(!result.isTruncated && result.truncatedCellCount == 0 && result.rows.size <= MAX_ROWS) { "Coaching evidence exceeded its limit" }
        val rows = linkedMapOf<String, MutableList<CoachObservation>>()
        val identities = HashSet<Pair<String, String>>()
        for (row in result.rows) {
            require(row.size == 6 && row.all { it.length <= QueryResult.MAX_CELL_CHARACTERS }) { "Unexpected coaching projection" }
            val (kind, topic) = row
            require(kind in KINDS && topic.isNotBlank() && identities.add(kind to topic)) { "Invalid or duplicate coaching evidence" }
            require(kind == "current" || kind == "localization" || kind !in rows) { "Duplicate scalar coaching evidence" }
            val value = if (kind == "localization" && row[2] == "NULL") Double.NaN else row[2].toDouble()
            require((kind == "localization" && value.isNaN()) || (value.isFinite() && value >= 0)) { "Invalid coaching value" }
            if (kind == "scale") require(value <= 1.0)
            if (kind == "brownout") require(value in 1.0..MAX_EXACT_COUNT && value == kotlin.math.floor(value))
            if (kind == "current_present") require(value == 1.0)
            if (kind == "localization") require(topic in DiagnosticCoachLocalization.inputKeys)
            val observation = CoachObservation(topic, value, row[3].toLong(), row[4].toLong(), row[5].toLong())
            require(observation.startUs <= observation.endUs && observation.samples in 1..MAX_EXACT_COUNT.toLong()) { "Invalid coaching interval" }
            rows.getOrPut(kind) { mutableListOf() }.add(observation)
        }
        return CoachSnapshot(rows)
    }

    private companion object {
        const val MAX_ROWS = 256
        const val MAX_EXACT_COUNT = 9_007_199_254_740_991.0
        val KINDS = setOf("battery", "loop", "scale", "brownout", "current", "current_present", "localization")
        val FAMILIES = setOf("Diagnostics/EKF", "Diagnostics/Auto")
        val GENERATED_LIMIT = DiagnosticCoachLocalization.inputKeys.size + FAMILIES.size
        val GENERATED_SQL = """
            WITH source AS MATERIALIZED (
                SELECT ltrim(key, '/') AS topic, CASE WHEN string_value IS NULL AND isfinite(value) AND value >= 0 THEN value END AS value
                FROM analysis_diagnostics WHERE session_id = ? AND
                    (starts_with(ltrim(key, '/'), 'Diagnostics/EKF/') OR starts_with(ltrim(key, '/'), 'Diagnostics/Auto/'))
            ), output AS (
                SELECT 'metric' AS kind, topic, value FROM source WHERE topic IN (${DiagnosticCoachLocalization.inputKeys.joinToString(",") { "'$it'" }})
                UNION ALL SELECT DISTINCT 'family', CASE WHEN starts_with(topic, 'Diagnostics/EKF/') THEN 'Diagnostics/EKF' ELSE 'Diagnostics/Auto' END, NULL
                    FROM source WHERE starts_with(topic, 'Diagnostics/EKF/') OR starts_with(topic, 'Diagnostics/Auto/')
            )
            SELECT kind, topic, value FROM output ORDER BY kind, topic LIMIT ${GENERATED_LIMIT + 1}
        """.trimIndent()
        val DEFINITIONS = buildList {
            fun signal(metric: String, keys: Iterable<String>) = keys.forEachIndexed { priority, key ->
                add("('$metric', '${key.replace("'", "''")}', $priority, '')")
            }
            signal("battery", TelemetryMetricCatalog.BATTERY_VOLTAGE.keys)
            signal("loop", TelemetryMetricCatalog.LOOP_TIME.keys)
            signal("brownout", listOf("Diagnostics/Power/BrownoutCount", "Robot/BrownoutCount"))
            signal("scale", listOf("Robot/BrownoutPowerScale"))
            for (key in DiagnosticCoachLocalization.inputKeys) add("('localization', '$key', 0, '$key')")
        }.joinToString(",\n")
        val SQL = """
            WITH definitions(metric, topic, priority, device) AS (VALUES $DEFINITIONS),
            motor_rules(pattern, priority) AS (VALUES
                ('^Hardware/Motors/([^/]+)/CurrentAmps$', 0), ('^Hardware/Motors/([^/]+)/Current$', 1),
                ('^Hardware/Motors/([^/]+)/Amps$', 2), ('^Drive/MotorCurrent_([^/]+)$', 5),
                ('^Drive/(Motor[^/]+)/CurrentAmps$', 10), ('^Drive/(Motor[^/]+)/Current$', 11), ('^Drive/(Motor[^/]+)/Amps$', 12)
            ),
            source AS MATERIALIZED (
                SELECT timestamp_us, sample_order, key, ltrim(key, '/') AS topic, value, string_value
                FROM telemetry_frames WHERE session_id = ?
            ),
            classified AS (
                SELECT s.*, d.metric, d.device, d.priority FROM source s JOIN definitions d ON s.topic = d.topic
                UNION ALL SELECT s.*, 'current', regexp_extract(s.topic, m.pattern, 1), m.priority
                    FROM source s JOIN motor_rules m ON regexp_full_match(s.topic, m.pattern)
            ),
            ranked AS (
                SELECT *, min(priority) OVER (PARTITION BY metric, device) AS preferred,
                    row_number() OVER (PARTITION BY metric, device, topic, timestamp_us ORDER BY sample_order DESC, key) AS latest
                FROM classified
            ),
            selected AS MATERIALIZED (
                SELECT metric, device, topic, timestamp_us, CASE WHEN string_value IS NULL AND isfinite(value) AND value >= 0 AND
                    (metric NOT IN ('brownout','scale') OR (metric = 'scale' AND value <= 1) OR
                     (metric = 'brownout' AND value <= 9007199254740991 AND value = floor(value))) THEN value END AS value
                FROM ranked WHERE priority = preferred AND latest = 1
            ),
            extrema AS (
                SELECT *, row_number() OVER (PARTITION BY metric ORDER BY
                    CASE WHEN metric = 'loop' THEN -value ELSE value END, timestamp_us, topic) AS choice
                FROM selected WHERE metric IN ('battery','loop','scale') AND value IS NOT NULL
            ),
            current_neighbors AS (
                SELECT *, lag(value) OVER timeline AS previous_value,
                    cast(timestamp_us AS HUGEINT) - lag(cast(timestamp_us AS HUGEINT)) OVER timeline AS gap_us
                FROM selected WHERE metric = 'current' WINDOW timeline AS (PARTITION BY device ORDER BY timestamp_us)
            ),
            current_groups AS (
                SELECT *, sum(CASE WHEN value >= ${DiagnosticCoachService.CURRENT_REVIEW_AMPS} AND previous_value >= ${DiagnosticCoachService.CURRENT_REVIEW_AMPS}
                    AND gap_us BETWEEN 1 AND ${DiagnosticCoachService.MAX_SAMPLE_GAP_US} THEN 0 ELSE 1 END)
                    OVER (PARTITION BY device ORDER BY timestamp_us ROWS UNBOUNDED PRECEDING) AS span
                FROM current_neighbors
            ),
            current_spans AS (
                SELECT device, topic, min(timestamp_us) AS start_us, max(timestamp_us) AS end_us, max(value) AS peak, count(*) AS samples
                FROM current_groups WHERE value >= ${DiagnosticCoachService.CURRENT_REVIEW_AMPS} GROUP BY device, topic, span
                HAVING cast(max(timestamp_us) AS HUGEINT) - cast(min(timestamp_us) AS HUGEINT) >= ${DiagnosticCoachService.CURRENT_REVIEW_DURATION_US}
            ),
            current_choice AS (
                SELECT *, row_number() OVER (PARTITION BY device ORDER BY start_us, end_us, topic) AS choice FROM current_spans
            ),
            guard_deltas AS (
                SELECT topic, timestamp_us, lag(timestamp_us) OVER timeline AS previous_us,
                    cast(value AS HUGEINT) - lag(cast(value AS HUGEINT)) OVER timeline AS delta
                FROM selected WHERE metric = 'brownout' WINDOW timeline AS (ORDER BY timestamp_us)
            ),
            localization_latest AS (
                SELECT *, row_number() OVER (PARTITION BY topic ORDER BY timestamp_us DESC) AS choice FROM selected WHERE metric = 'localization'
            ),
            output AS (
                SELECT metric AS kind, topic, value, timestamp_us AS start_us, timestamp_us AS end_us, 1::BIGINT AS samples FROM extrema WHERE choice = 1
                UNION ALL SELECT 'current', topic, peak, start_us, end_us, samples FROM current_choice WHERE choice = 1
                UNION ALL SELECT 'current_present', 'current', 1.0, 0, 0, count(*) FROM selected WHERE metric = 'current' AND value IS NOT NULL HAVING count(*) > 0
                UNION ALL SELECT 'brownout', min(topic), sum(delta), min(previous_us), max(timestamp_us), count(*) FROM guard_deltas WHERE delta > 0 HAVING count(*) > 0
                UNION ALL SELECT 'localization', topic, value, timestamp_us, timestamp_us, 1 FROM localization_latest WHERE choice = 1
            )
            SELECT kind, left(topic, ${QueryResult.MAX_CELL_CHARACTERS + 1}) AS topic, value, start_us, end_us, samples
            FROM output ORDER BY kind, start_us, topic LIMIT ${MAX_ROWS + 1}
        """.trimIndent()
    }
}
