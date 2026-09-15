package com.ares.analytics.service

import com.ares.analytics.shared.TelemetryMetricCatalog
import kotlin.math.floor

/** Exact recorded health observations from one SQL snapshot; no downsampled event totals. */
internal class SummaryHealthAggregator(private val query: suspend (String, List<Any>) -> QueryResult) {
    suspend fun read(sessionId: String): Map<String, Double> {
        val result = query(SQL, listOf(sessionId))
        require(!result.isTruncated && result.truncatedCellCount == 0 && result.rows.size <= KEYS.size) {
            "Health aggregate result exceeded its limit"
        }
        return buildMap {
            for (row in result.rows) {
                require(row.size == 2 && row[0] in KEYS && row.all { it.length <= QueryResult.MAX_CELL_CHARACTERS }) {
                    "Unexpected health aggregate projection"
                }
                val value = row[1].toDouble()
                require(value.isFinite() && value >= 0.0) { "Invalid health aggregate ${row[0]}" }
                if (row[0] == "MaxCANBusUtilization") require(value <= 1.0)
                else if (row[0] != "MaxCANBusLatencyMs") {
                    require(value <= MAX_EXACT_COUNT && floor(value) == value) { "Health count exceeds exact numeric representation" }
                }
                val key = "Diagnostics/System/${row[0]}"
                require(put(key, value) == null) { "Duplicate health aggregate" }
            }
        }
    }

    private companion object {
        const val MAX_EXACT_COUNT = 9_007_199_254_740_991.0
        val KEYS = setOf("LoopSamplesOver40Ms", "LoopSamples", "RecordingGapsOver1s", "MaxCANBusUtilization",
            "PeakCANErrorCounter", "CANBusOffIncrements", "MaxCANBusLatencyMs", "BrownoutGuardTripIncrements", "MotorFaultObserved")
        val DEFINITIONS = buildList {
            fun signal(metric: String, keys: Iterable<String>) = keys.forEachIndexed { priority, key ->
                add("('$metric', '${key.replace("'", "''")}', $priority)")
            }
            signal("loop", TelemetryMetricCatalog.LOOP_TIME.keys)
            signal("brownout", listOf("Diagnostics/Power/BrownoutCount", "Robot/BrownoutCount"))
        }.joinToString(",\n")
        val DEVICE_RULES = buildList {
            for ((family, familyPriority) in listOf("CANBus" to 0, "CAN" to 10)) {
                for ((metric, suffixes) in listOf(
                    "utilization" to listOf("Utilization", "BusUtilization"),
                    "error" to listOf("ErrorCount"), "bus_off" to listOf("BusOffCount", "BusOffs"),
                    "latency" to listOf("SignalLatencyMs"),
                )) suffixes.forEachIndexed { priority, suffix ->
                    add("('$metric', '^Diagnostics/$family/([^/]+)/$suffix$', ${familyPriority + priority})")
                }
            }
            add("('motor_fault', '^Diagnostics/Motor/([^/]+)/Faults$', 0)")
        }.joinToString(",\n")
        val SQL = """
            WITH definitions(metric, topic, priority) AS (VALUES $DEFINITIONS),
            device_rules(metric, pattern, priority) AS (VALUES $DEVICE_RULES),
            source AS MATERIALIZED (
                SELECT timestamp_us, sample_order, key, ltrim(key, '/') AS topic, value, string_value
                FROM telemetry_frames WHERE session_id = ?
            ),
            classified AS (
                SELECT s.*, d.metric, '' AS device, d.priority FROM source s JOIN definitions d ON s.topic = d.topic
                UNION ALL
                SELECT s.*, d.metric, regexp_extract(s.topic, d.pattern, 1) AS device, d.priority
                FROM source s JOIN device_rules d ON regexp_full_match(s.topic, d.pattern)
            ),
            ranked AS (
                SELECT *, min(priority) OVER (PARTITION BY metric, device) AS preferred,
                       row_number() OVER (PARTITION BY metric, device, topic, timestamp_us ORDER BY sample_order DESC, key) AS latest
                FROM classified
            ),
            selected AS MATERIALIZED (
                SELECT metric, device, timestamp_us,
                    CASE WHEN string_value IS NULL AND isfinite(value) AND value >= 0 AND (
                        metric IN ('loop', 'latency') OR (metric = 'utilization' AND value <= 1) OR
                        (metric IN ('brownout', 'bus_off', 'error', 'motor_fault') AND value <= 9007199254740991 AND value = floor(value))
                    ) THEN value END AS value
                FROM ranked WHERE priority = preferred AND latest = 1
            ),
            counter_deltas AS (
                SELECT metric, device, cast(value AS HUGEINT) - lag(cast(value AS HUGEINT)) OVER (
                    PARTITION BY metric, device ORDER BY timestamp_us) AS delta
                FROM selected WHERE metric IN ('brownout', 'bus_off')
            ),
            recording_gaps AS (
                SELECT cast(timestamp_us AS HUGEINT) - lag(cast(timestamp_us AS HUGEINT)) OVER (ORDER BY timestamp_us) AS gap_us
                FROM (SELECT DISTINCT timestamp_us FROM source)
            ),
            output AS (
                SELECT 'LoopSamplesOver40Ms' AS metric, count(*) FILTER (value > 40) AS value
                    FROM selected WHERE metric = 'loop' AND value IS NOT NULL HAVING count(*) > 0
                UNION ALL SELECT 'LoopSamples', count(*) FROM selected WHERE metric = 'loop' AND value IS NOT NULL HAVING count(*) > 0
                UNION ALL SELECT 'RecordingGapsOver1s', count(*) FILTER (gap_us > 1000000)
                    FROM recording_gaps WHERE gap_us IS NOT NULL HAVING count(*) > 0
                UNION ALL SELECT 'MaxCANBusUtilization', max(value) FROM selected WHERE metric = 'utilization'
                UNION ALL SELECT 'PeakCANErrorCounter', max(value) FROM selected WHERE metric = 'error'
                UNION ALL SELECT 'MaxCANBusLatencyMs', max(value) FROM selected WHERE metric = 'latency'
                UNION ALL SELECT 'CANBusOffIncrements', sum(delta) FROM counter_deltas WHERE metric = 'bus_off' AND delta >= 0
                UNION ALL SELECT 'BrownoutGuardTripIncrements', sum(delta) FROM counter_deltas WHERE metric = 'brownout' AND delta >= 0
                UNION ALL SELECT 'MotorFaultObserved', max(CASE WHEN value > 0 THEN 1 ELSE 0 END)
                    FROM selected WHERE metric = 'motor_fault' AND value IS NOT NULL HAVING count(*) > 0
            )
            SELECT metric, value FROM output WHERE value IS NOT NULL ORDER BY metric LIMIT ${KEYS.size + 1}
        """.trimIndent()
    }
}
