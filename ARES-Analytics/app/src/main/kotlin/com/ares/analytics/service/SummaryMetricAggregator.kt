package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog

/** Scalar compatibility fields use zero for unavailable data; zero is not presence evidence. */
internal data class SummaryAggregates(
    val metrics: Map<String, Double>,
    val motorCurrents: Map<String, Double>,
    val opModes: Set<String>,
) {
    operator fun get(metric: String): Double = metrics[metric] ?: 0.0
}

/** One SQL snapshot of exact, unsampled aggregates; only aggregate rows enter JVM memory. */
internal class SummaryMetricAggregator(private val query: suspend (String, List<Any>) -> QueryResult) {
    suspend fun read(sessionId: String): SummaryAggregates {
        val result = query(SQL, listOf(sessionId))
        require(!result.isTruncated && result.truncatedCellCount == 0 && result.rows.size <= MAX_SUMMARY_ROWS) {
            "Summary aggregate result exceeded its limit"
        }
        val metrics = linkedMapOf<String, Double>()
        val motors = linkedMapOf<String, Double>()
        val modes = linkedSetOf<String>()
        for (row in result.rows) {
            require(row.size == 3) { "Unexpected summary aggregate projection" }
            require(row.all { it.length <= QueryResult.MAX_CELL_CHARACTERS }) { "Summary aggregate cell exceeded its limit" }
            if (row[0] == "op_mode") {
                row[2].takeIf { it.isNotBlank() && it != "NULL" }?.let(modes::add)
            } else {
                val value = row[1].takeUnless { it == "NULL" }?.toDouble() ?: continue
                require(value.isFinite() && value >= 0.0) { "Invalid summary aggregate ${row[0]}" }
                if (row[0].startsWith("motor:")) motors[row[2]] = value else metrics[row[0]] = value
            }
        }
        return SummaryAggregates(metrics, motors, modes)
    }

    private companion object {
        const val MAX_SUMMARY_ROWS = 1_000
        // These are physical aliases, not substring matches. Earlier entries win for the whole run.
        val DEFINITIONS = buildList {
            fun signal(metric: String, keys: Iterable<String>) {
                keys.forEachIndexed { priority, key ->
                    add("('$metric', '${key.lowercase().replace("'", "''")}', $priority)")
                }
            }
            signal("battery", TelemetryMetricCatalog.BATTERY_VOLTAGE.keys)
            signal("loop", TelemetryMetricCatalog.LOOP_TIME.keys)
            signal("total_current", listOf("Robot/TotalCurrentAmps", "Battery/CurrentAmps", "Battery/Current"))
            signal("drift_scalar", listOf("Drive/EkfDrift", "Drive/PoseError"))
            signal("drift_x", listOf("Drive/EKF_Drift_X"))
            signal("drift_y", listOf("Drive/EKF_Drift_Y"))
            signal("acceptance_rate", listOf("Vision/AcceptanceRate"))
            signal("accepted_count", listOf("Vision/EKF_AcceptedCount"))
            signal("rejected_count", listOf("Vision/EKF_RejectedCount"))
            signal("latency", listOf("Vision/Primary_LatencyMs", "Vision/LatencyMs"))
            signal("vision_active", listOf("Vision/HasTarget"))
            signal("cross_track", listOf("Path/Error_CrossTrack", "Path/CrossTrackError", "Drive/CrossTrackError"))
            signal("path_active", listOf("Path/Active"))
            signal("op_mode", listOf("Robot/OpMode", "OpMode"))
        }.joinToString(",\n")

        val SQL = """
            WITH definitions(metric, topic, priority) AS (VALUES $DEFINITIONS),
            motor_rules(pattern, priority) AS (VALUES
                ('^hardware/motors/([^/]+)/(currentamps|current|amps)$', 0),
                ('^drive/motorcurrent_([^/]+)$', 5),
                ('^drive/(motor[^/]+)/(currentamps|current|amps)$', 10)
            ),
            source AS (
                SELECT timestamp_us, sample_order, key, ltrim(key, '/') AS original_topic,
                       lower(ltrim(key, '/')) AS topic, value, string_value
                FROM telemetry_frames WHERE session_id = ?
            ),
            classified AS (
                SELECT s.*, regexp_extract(original_topic, m.pattern, 1, 'i') AS device,
                       coalesce(d.metric, 'motor:' || lower(device)) AS metric,
                       coalesce(d.priority, m.priority + CASE
                           WHEN ends_with(s.topic, '/current') THEN 1
                           WHEN ends_with(s.topic, '/amps') THEN 2 ELSE 0 END) AS priority
                FROM source s
                LEFT JOIN definitions d ON s.topic = d.topic
                LEFT JOIN motor_rules m ON regexp_full_match(s.topic, m.pattern)
                WHERE d.metric IS NOT NULL OR m.pattern IS NOT NULL
            ),
            ranked AS (
                SELECT *, min(priority) OVER (PARTITION BY metric) AS preferred,
                       row_number() OVER (PARTITION BY metric, topic, timestamp_us
                                          ORDER BY sample_order DESC, key) AS latest
                FROM classified
            ),
            selected AS MATERIALIZED (
                SELECT metric, topic, device, timestamp_us, string_value,
                       CASE WHEN string_value IS NULL AND isfinite(value) THEN value END AS value
                FROM ranked WHERE priority = preferred AND latest = 1
            ),
            drift_pairs AS (
                SELECT timestamp_us, max(value) FILTER (metric = 'drift_x') AS x,
                       max(value) FILTER (metric = 'drift_y') AS y
                FROM selected WHERE metric IN ('drift_x', 'drift_y') GROUP BY timestamp_us
            ),
            drift_vectors AS (
                SELECT CASE WHEN greatest(abs(x), abs(y)) = 0 THEN 0 ELSE
                    greatest(abs(x), abs(y)) * sqrt(
                        pow(x / greatest(abs(x), abs(y)), 2) + pow(y / greatest(abs(x), abs(y)), 2))
                    END AS value
                FROM drift_pairs WHERE x IS NOT NULL AND y IS NOT NULL
            ),
            counter_pairs AS (
                SELECT timestamp_us,
                       max(value) FILTER (metric = 'accepted_count' AND value BETWEEN 0 AND 9007199254740991 AND value = floor(value)) AS a,
                       max(value) FILTER (metric = 'rejected_count' AND value BETWEEN 0 AND 9007199254740991 AND value = floor(value)) AS r
                FROM selected WHERE metric IN ('accepted_count', 'rejected_count') GROUP BY timestamp_us
            ),
            counter_deltas AS (
                SELECT a - lag(a) OVER timeline AS da, r - lag(r) OVER timeline AS dr
                FROM counter_pairs WINDOW timeline AS (ORDER BY timestamp_us)
            ),
            acceptance AS (
                SELECT sum(da) / nullif(sum(da) + sum(dr), 0) AS value
                FROM counter_deltas WHERE da >= 0 AND dr >= 0
            ),
            battery_pairs AS (
                SELECT timestamp_us,
                       max(value) FILTER (metric = 'battery' AND value > 1.0) AS v,
                       max(value) FILTER (metric = 'total_current' AND value >= 0) AS i
                FROM selected WHERE metric IN ('battery', 'total_current') GROUP BY timestamp_us
            ),
            battery_deltas AS (
                SELECT v - lag(v) OVER timeline AS dv, i - lag(i) OVER timeline AS di,
                       timestamp_us - lag(timestamp_us) OVER timeline AS dt
                FROM battery_pairs WINDOW timeline AS (ORDER BY timestamp_us)
            ),
            resistance AS (
                SELECT abs(dv / di) AS value FROM battery_deltas
                WHERE dt BETWEEN 1 AND 250000 AND abs(di) > 0.5
                  AND ((dv < 0 AND di > 0) OR (dv > 0 AND di < 0))
                  AND isfinite(dv) AND isfinite(di) AND isfinite(dv / di)
            ),
            observations AS (
                SELECT s.metric, s.device, abs(s.value) AS value FROM selected s
                WHERE s.value IS NOT NULL AND (
                    (s.metric = 'battery' AND s.value > 1.0) OR
                    (s.metric = 'loop' AND s.value >= 0) OR
                    (starts_with(s.metric, 'motor:') AND s.value >= 0) OR
                    (s.metric = 'latency' AND s.value >= 0 AND
                        (NOT EXISTS (SELECT 1 FROM selected WHERE metric = 'vision_active') OR
                         EXISTS (SELECT 1 FROM selected f WHERE f.metric = 'vision_active' AND f.value = 1 AND f.timestamp_us = s.timestamp_us))) OR
                    (s.metric = 'cross_track' AND
                        (NOT EXISTS (SELECT 1 FROM selected WHERE metric = 'path_active') OR
                         EXISTS (SELECT 1 FROM selected f WHERE f.metric = 'path_active' AND f.value = 1 AND f.timestamp_us = s.timestamp_us)))
                )
                UNION ALL SELECT 'drift', NULL, value FROM drift_vectors WHERE isfinite(value)
                UNION ALL SELECT 'drift', NULL, abs(value) FROM selected
                    WHERE metric = 'drift_scalar' AND value IS NOT NULL AND NOT EXISTS (SELECT 1 FROM drift_vectors)
                UNION ALL SELECT 'acceptance', NULL, value FROM acceptance WHERE value IS NOT NULL
                UNION ALL SELECT 'acceptance', NULL, value FROM selected
                    WHERE metric = 'acceptance_rate' AND value BETWEEN 0 AND 1
                      AND NOT EXISTS (SELECT 1 FROM acceptance WHERE value IS NOT NULL)
                UNION ALL SELECT 'resistance', NULL, value FROM resistance
            ),
            scaled AS (
                SELECT *, max(value) OVER (PARTITION BY metric) AS scale FROM observations
            ),
            stats AS (
                SELECT metric, min(device) AS device, min(value) AS low, max(value) AS high,
                       least(1.0, greatest(0.0, avg(CASE WHEN scale = 0 THEN 0 ELSE value / scale END))) * max(scale) AS mean
                FROM scaled GROUP BY metric
            ),
            output AS (
            SELECT metric, CASE metric WHEN 'battery' THEN low WHEN 'drift' THEN high ELSE mean END AS value,
                   device AS label FROM stats
            UNION ALL SELECT 'loop_p95',
                quantile_cont(CASE WHEN scale = 0 THEN 0 ELSE value / scale END, 0.95) * max(scale), NULL
                FROM scaled WHERE metric = 'loop'
            UNION ALL SELECT DISTINCT 'op_mode', NULL, trim(string_value) FROM selected
                WHERE metric = 'op_mode' AND string_value IS NOT NULL AND trim(string_value) <> ''
            )
            SELECT left(metric, ${QueryResult.MAX_CELL_CHARACTERS + 1}) AS metric, value,
                   left(label, ${QueryResult.MAX_CELL_CHARACTERS + 1}) AS label FROM output
            ORDER BY metric, label
            LIMIT ${MAX_SUMMARY_ROWS + 1}
        """.trimIndent()
    }
}
