package com.ares.analytics.service

import com.ares.analytics.shared.Session
import com.ares.analytics.shared.SessionSummary
import com.ares.analytics.shared.AnalysisDiagnostic
import com.ares.analytics.shared.TelemetryFrame
import com.ares.analytics.shared.TelemetryMetricCatalog
import com.ares.analytics.service.AlignedDataRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * High-performance analytics service computing statistical KPI summaries from logged match telemetry sessions.
 *
 * Utilizes vectorized DuckDB SQL aggregation queries (`PERCENTILE_CONT`, `MIN`, `MAX`, `AVG`) to extract match performance metrics
 * without pulling raw time-series frame arrays into JVM memory space.
 *
 * ### Computed Mathematical Metrics & Physical Units:
 * - **Minimum Battery Voltage**: $\min(V_{\text{batt}})$ in Volts ($V$)
 * - **Internal Battery Resistance**: $R_{\text{batt}} = \frac{\Delta V}{\Delta I}$ in Ohms ($\Omega$)
 * - **Maximum EKF Position Drift**: $\max(\text{error}_{\text{pose}})$ in Meters ($m$)
 * - **Average & P95 Control Loop Timing**: $t_{\text{loop}}$ and $P_{95}(t_{\text{loop}})$ in Milliseconds ($ms$)
 * - **Vision Acceptance Rate & Latency**: Vision pose acceptance percentage (%) and optical processing latency ($ms$)
 * - **Average Cross-Track Error**: $\bar{e}_{\text{ct}} = \frac{1}{N} \sum |e_{\text{ct}}|$ in Meters ($m$)
 * - **Motor Currents & Thermal Extremes**: Per-motor stator current averages ($A$) and maximum motor temperatures ($^\circ\text{C}$)
 *
 * ### Thread Safety & Performance Guarantees:
 * Executes SQL aggregate calculations on `Dispatchers.Default`. Stores summary metrics in the DuckDB `session_summaries` table.
 *
 * @param databaseService Primary DuckDB database management service.
 * @param sysIdService System identification service for motor parameter characterization.
 * @param driverAnalysisService Driver control analysis service.
 *
 * @see SessionSummary
 * @see DatabaseService
 * @see SysIdService
 */
class SummaryEngineService(
    private val databaseService: DatabaseService,
    private val sysIdService: SysIdService,
    private val driverAnalysisService: DriverAnalysisService
) {

    suspend fun generateSummary(session: Session): SessionSummary = withContext(Dispatchers.Default) {
        // Use SQL aggregations instead of pulling all frames into Kotlin
        val aggregateResult = databaseService.executeQueryWithParams(
            """
            SELECT
                MIN(CASE WHEN LOWER(key) LIKE '%battery%' AND LOWER(key) LIKE '%voltage%' AND value > 1.0 THEN value END) AS min_battery_voltage,
                MAX(CASE WHEN LOWER(key) LIKE '%drift%' OR LOWER(key) LIKE '%poseerror%' THEN ABS(value) END) AS max_ekf_drift,
                AVG(CASE WHEN LOWER(key) LIKE '%looptime%' OR LOWER(key) LIKE '%loop_time%' THEN value END) AS avg_loop_time,
                AVG(CASE WHEN LOWER(key) LIKE '%vision%' AND (LOWER(key) LIKE '%acceptance%' OR LOWER(key) LIKE '%accepted%') THEN value END) AS vision_acceptance_rate,
                AVG(CASE WHEN LOWER(key) LIKE '%crosstrack%' OR LOWER(key) LIKE '%cross_track%' OR LOWER(key) LIKE '%xte%' THEN ABS(value) END) AS avg_cross_track,
                AVG(CASE WHEN LOWER(key) LIKE '%vision%' AND LOWER(key) LIKE '%latency%' THEN value END) AS avg_vision_latency
            FROM telemetry_frames WHERE session_id = ?
            """.trimIndent(),
            listOf(session.sessionId)
        )
        val aggRow = aggregateResult.rows.firstOrNull()
        val minBattery = aggRow?.getOrNull(0).finiteDoubleOr(12.0)
        val maxDrift = aggRow?.getOrNull(1).finiteDoubleOr(0.0)
        val avgLoop = aggRow?.getOrNull(2).finiteDoubleOr(0.0)
        val visionRate = aggRow?.getOrNull(3).finiteDoubleOr(0.0)
        val avgCrossTrack = aggRow?.getOrNull(4).finiteDoubleOr(0.0)
        val avgVisionLat = aggRow?.getOrNull(5).finiteDoubleOr(0.0)

        // P95 loop time via ordered-set aggregate (DuckDB supports PERCENTILE_CONT)
        val p95Result = databaseService.executeQueryWithParams(
            """
            SELECT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY value) AS p95_loop_time
            FROM telemetry_frames
            WHERE session_id = ? AND (LOWER(key) LIKE '%looptime%' OR LOWER(key) LIKE '%loop_time%')
            """.trimIndent(),
            listOf(session.sessionId)
        )
        val p95Loop = p95Result.rows.firstOrNull()?.getOrNull(0).finiteDoubleOr(0.0)

        // Motor current averages grouped by device name extracted from key
        val motorResult = databaseService.executeQueryWithParams(
            """
            SELECT
                CASE
                    WHEN LOWER(SPLIT_PART(key, '/', -1)) IN ('current', 'currentamps', 'amps')
                        THEN SPLIT_PART(key, '/', -2)
                    ELSE REGEXP_REPLACE(REGEXP_REPLACE(SPLIT_PART(key, '/', -1), '(?i)current', ''), '(?i)amps', '')
                END AS motor_name,
                AVG(value) AS avg_current
            FROM telemetry_frames
            WHERE session_id = ? AND LOWER(key) LIKE '%current%' AND LOWER(key) NOT LIKE '%battery%'
            GROUP BY motor_name
            HAVING motor_name IS NOT NULL AND motor_name != ''
            """.trimIndent(),
            listOf(session.sessionId)
        )
        val motorCurrentAverages = motorResult.rows.associate { row ->
            (row.getOrNull(0) ?: "Motor") to row.getOrNull(1).finiteDoubleOr(0.0)
        }

        // Battery resistance estimation using LAG() window function
        val batteryResult = databaseService.executeQueryWithParams(
            """
            WITH Batt AS (
                SELECT
                    timestamp_ms,
                    MAX(CASE WHEN LOWER(key) LIKE '%voltage%' THEN value END) as v,
                    MAX(CASE WHEN LOWER(key) LIKE '%current%' THEN value END) as i
                FROM telemetry_frames
                WHERE session_id = ? AND LOWER(key) LIKE '%battery%'
                GROUP BY timestamp_ms
            ),
            Deltas AS (
                SELECT
                    v - LAG(v) OVER(ORDER BY timestamp_ms) as dv,
                    i - LAG(i) OVER(ORDER BY timestamp_ms) as di
                FROM Batt
                WHERE v IS NOT NULL AND i IS NOT NULL
            )
            SELECT AVG(ABS(dv/NULLIF(di, 0)))
            FROM Deltas
            WHERE ABS(di) > 0.5 AND dv * di < 0
            """.trimIndent(),
            listOf(session.sessionId)
        )
        val avgResistance = batteryResult.rows.firstOrNull()?.getOrNull(0).finiteDoubleOr(0.0)

        // Detect OpModes from string_value column
        val opModeResult = databaseService.executeQueryWithParams(
            """
            SELECT DISTINCT string_value
            FROM telemetry_frames
            WHERE session_id = ?
              AND LOWER(SPLIT_PART(key, '/', -1)) = 'opmode'
              AND string_value IS NOT NULL
            """.trimIndent(),
            listOf(session.sessionId)
        )
        val detectedModes = opModeResult.rows.mapNotNull { it.getOrNull(0)?.takeIf { v -> v != "NULL" } }.toSet()

        // Motor thermal estimation requires sequential state (temperature depends on previous temperature)
        // and cannot be vectorized into SQL without recursive CTEs.
        val maxMotorTemps = emptyMap<String, Double>()
        val diagnosticTags = calculateAndSaveDiagnostics(session)
        val finalTags = (session.tags + detectedModes + diagnosticTags).distinct()
        val summary = SessionSummary(
            sessionId = session.sessionId,
            teamId = session.teamId,
            seasonId = session.seasonId,
            robotId = session.robotId,
            createdAt = session.createdAt,
            durationMs = session.durationMs,
            minBatteryVoltage = minBattery,
            maxEkfDrift = maxDrift,
            avgLoopTimeMs = avgLoop,
            p95LoopTimeMs = p95Loop,
            motorCurrentAverages = motorCurrentAverages,
            visionAcceptanceRate = visionRate,
            avgCrossTrackError = avgCrossTrack,
            avgBatteryResistance = avgResistance,
            maxMotorTemps = maxMotorTemps,
            avgVisionLatencyMs = avgVisionLat,
            tags = finalTags,
            matchNumber = session.matchNumber,
            allianceColor = session.allianceColor
        )

        databaseService.insertSessionSummary(summary)
        summary
    }

    /** DuckDB deliberately preserves IEEE NaN/Infinity; persisted summaries and JSON do not. */
    private fun String?.finiteDoubleOr(fallback: Double): Double =
        this?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    private suspend fun calculateAndSaveDiagnostics(session: Session): List<String> {
        var resolvedTags = session.tags
        try {
            val allFrames = databaseService.getTelemetryForFilters(
                sessionId = session.sessionId,
                keys = buildList {
                    addAll(TelemetryMetricCatalog.DRIVE_VOLTAGE.keys)
                    addAll(TelemetryMetricCatalog.DRIVE_VELOCITY.keys)
                    addAll(TelemetryMetricCatalog.DRIVE_ACCELERATION.keys)
                    add("Drive/Velocity_Omega")
                    add("Vision/EKF_NIS")
                    add("/Vision/EKF_NIS")
                    add("EKF/NIS")
                    add("Vision/Pose_X")
                    add("/Vision/Pose_X")
                    add("Vision/Pose_Y")
                    add("/Vision/Pose_Y")
                    add("Drive/Pose_X")
                    add("/Drive/Pose_X")
                    add("Drive/Pose_Y")
                    add("/Drive/Pose_Y")
                    add("Path/CrossTrackError")
                    add("/Path/CrossTrackError")
                    add("Drive/CrossTrackError")
                    add("/Drive/CrossTrackError")
                    addAll(TelemetryMetricCatalog.BATTERY_VOLTAGE.keys)
                    addAll(TelemetryMetricCatalog.LOOP_TIME.keys)
                },
                prefixes = listOf("Diagnostics/%", "Hardware/Motors/%", "Vision/%", "Path/%"),
                maxFrames = MAX_DIAGNOSTIC_FRAMES,
                maxFramesPerTopic = MAX_DIAGNOSTIC_FRAMES_PER_TOPIC,
            )
            if (allFrames.isEmpty()) {
                databaseService.replaceAnalysisDiagnostics(session.sessionId, emptyList())
                return resolvedTags
            }
            val framesToInsert = mutableListOf<TelemetryFrame>()

            // Loop Overruns and Comms Losses calculation
            val loopTimes = allFrames.filter { it.key.lowercase().contains("loop") || it.key.lowercase().contains("period") }.map { it.value }
            val loopOverruns = loopTimes.count { it > 40.0 }
            val commsLosses = databaseService.countTimestampGaps(session.sessionId, 1_000L)
            val minVoltage = allFrames.filter { it.key in TelemetryMetricCatalog.BATTERY_VOLTAGE.keys }
                .map { it.value }
                .minOrNull() ?: 12.0

            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/LoopOverruns", loopOverruns.toDouble()))
            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/CommsLosses", commsLosses.toDouble()))

            // CANbus status, motor faults, and brownout calculations
            val canFrames = allFrames.filter {
                val key = it.key.removePrefix("/")
                key.startsWith("Diagnostics/CAN/") || key.startsWith("Diagnostics/CANBus/")
            }
            val maxBusUtil = canFrames.filter { it.key.endsWith("BusUtilization") || it.key.endsWith("Utilization") }.maxOfOrNull { it.value } ?: 0.0
            val totalErrorCount = canFrames.filter { it.key.endsWith("ErrorCount") }.maxOfOrNull { it.value } ?: 0.0
            val totalBusOffs = canFrames.filter { it.key.endsWith("BusOffs") || it.key.endsWith("BusOffCount") }.maxOfOrNull { it.value } ?: 0.0
            val maxSignalLatency = canFrames.filter { it.key.endsWith("SignalLatencyMs") }.maxOfOrNull { it.value } ?: 0.0
            val brownoutCount = allFrames.filter { it.key == "Diagnostics/Power/BrownoutCount" }.maxOfOrNull { it.value } ?: 0.0
            val motorFaultFrames = allFrames.filter {
                val key = it.key.removePrefix("/")
                key.startsWith("Diagnostics/Motor/") && key.endsWith("/Faults")
            }
            val hasMotorFaults = motorFaultFrames.any { it.value > 0.0 }

            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/MaxCANBusUtilization", maxBusUtil))
            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/TotalCANBusErrors", totalErrorCount))
            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/CANBusOffs", totalBusOffs))
            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/MaxCANBusLatencyMs", maxSignalLatency))
            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/System/BrownoutCount", brownoutCount))
            val newTags = session.tags.toMutableList()
            if (commsLosses > 0) newTags.add("CommsLoss")
            if (loopOverruns > 5) newTags.add("LoopOverruns")
            if (minVoltage < 9.5 && minVoltage > 0.0) newTags.add("LowBattery")
            if (totalErrorCount > 0.0 || totalBusOffs > 0.0) newTags.add("CANBusFault")
            if (maxBusUtil >= 0.90) newTags.add("CANBusSaturated")
            if (brownoutCount > 0.0) newTags.add("Brownout")
            if (hasMotorFaults) newTags.add("MotorFault")
            val uniqueTags = newTags.distinct()
            resolvedTags = uniqueTags
            if (uniqueTags != session.tags) {
                databaseService.updateSessionTags(session.sessionId, uniqueTags)
            }

            // 1. Drivetrain SysId Characterization
            val voltages = allFrames.filter { it.key in TelemetryMetricCatalog.DRIVE_VOLTAGE.keys }
            val velocities = allFrames.filter { it.key in TelemetryMetricCatalog.DRIVE_VELOCITY.keys }
            val accelerations = allFrames.filter { it.key in TelemetryMetricCatalog.DRIVE_ACCELERATION.keys }

            if (voltages.isNotEmpty() && velocities.isNotEmpty()) {
                val alignedData = mutableListOf<AlignedDataRow>()
                val timeMap = voltages.associateBy { it.timestampMs }
                val directionChanges = mutableListOf<Long>()
                var lastSign = 0.0
                val sortedVelocities = velocities.sortedBy { it.timestampMs }
                for (v in sortedVelocities) {
                    val currentSign = sign(v.value)
                    if (currentSign != 0.0 && currentSign != lastSign) {
                        directionChanges.add(v.timestampMs)
                        lastSign = currentSign
                    }
                }
                val sortedAccels = accelerations.sortedBy { it.timestampMs }
                var accelIdx = 0

                for (v in sortedVelocities) {
                    val t = v.timestampMs
                    val isNearDirectionChange = directionChanges.any { abs(it - t) <= 50 }
                    if (isNearDirectionChange) continue
                    val volt = timeMap[t]?.value ?: continue
                    val accel = if (sortedAccels.isNotEmpty()) {
                        while (accelIdx < sortedAccels.size - 1 &&
                            abs(sortedAccels[accelIdx + 1].timestampMs - t) <= abs(sortedAccels[accelIdx].timestampMs - t)
                        ) {
                            accelIdx++
                        }
                        sortedAccels[accelIdx].value
                    } else 0.0

                    alignedData.add(AlignedDataRow(t, volt, v.value, accel))
                }
                val finalAlignedData = if (alignedData.isNotEmpty() && alignedData.all { it.accel == 0.0 }) {
                    val approxRows = mutableListOf<AlignedDataRow>()
                    val sorted = alignedData.sortedBy { it.timestampMs }
                    for (i in 0 until sorted.size) {
                        val current = sorted[i]
                        val accel = if (i == 0) 0.0 else {
                            val prev = sorted[i - 1]
                            val dt = (current.timestampMs - prev.timestampMs) / 1000.0
                            if (dt > 1e-4) (current.velocity - prev.velocity) / dt else 0.0
                        }
                        approxRows.add(current.copy(accel = accel))
                    }
                    approxRows
                } else {
                    alignedData
                }

                if (finalAlignedData.size >= 10) {
                    val summary = sysIdService.analyzeRawData(finalAlignedData)
                    if (summary.rSquared > 0.1) {
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/kS", summary.kS))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/kV", summary.kV))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/kA", summary.kA))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/R2", summary.rSquared))

                        if (summary.kA > 1e-6) {
                            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/ADRC_b0", 1.0 / summary.kA))
                        }
                    }
                }
            }

            // 2. Individual Subsystem & Motor SysId Characterization
            val motorVoltages = mutableMapOf<String, MutableMap<Long, Double>>()
            val motorVelocities = mutableMapOf<String, MutableMap<Long, Double>>()

            for (frame in allFrames) {
                val cleanKey = frame.key.removePrefix("/")
                if (cleanKey.startsWith("Hardware/Motors/")) {
                    val parts = cleanKey.split("/")
                    if (parts.size >= 4) {
                        val motorName = parts[2]
                        val metric = parts[3].lowercase()
                        val t = frame.timestampMs
                        when {
                            metric.contains("volt") || metric.contains("power") -> {
                                val voltVal = if (metric.contains("power") && abs(frame.value) <= 1.0) frame.value * 12.0 else frame.value
                                motorVoltages.getOrPut(motorName) { mutableMapOf() }[t] = voltVal
                            }
                            metric.contains("vel") || metric.contains("speed") -> {
                                motorVelocities.getOrPut(motorName) { mutableMapOf() }[t] = frame.value
                            }
                        }
                    }
                }
            }

            for ((motorName, velocitiesMap) in motorVelocities) {
                val voltagesMap = motorVoltages[motorName] ?: continue
                if (velocitiesMap.size < 10 || voltagesMap.size < 10) continue
                val alignedRows = mutableListOf<AlignedDataRow>()
                val sortedTimes = velocitiesMap.keys.sorted()
                var lastTime = 0L
                var lastVel = 0.0

                for (t in sortedTimes) {
                    val vel = velocitiesMap[t] ?: continue
                    val volt = voltagesMap[t] ?: continue
                    val accel = if (lastTime == 0L) 0.0 else {
                        val dt = (t - lastTime) / 1000.0
                        if (dt > 1e-4) (vel - lastVel) / dt else 0.0
                    }

                    alignedRows.add(AlignedDataRow(t, volt, vel, accel))
                    lastTime = t
                    lastVel = vel
                }

                if (alignedRows.size >= 10) {
                    val summary = sysIdService.analyzeRawData(alignedRows)
                    if (summary.rSquared > 0.5) {
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/kS", summary.kS))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/kV", summary.kV))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/kA", summary.kA))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/R2", summary.rSquared))

                        if (summary.kA > 1e-6) {
                            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/ADRC_b0", 1.0 / summary.kA))
                        }
                    }

                    // Estimate kG (Gravity Feedforward) for vertical elevators/arms (non-drivetrain)
                    val isDrivetrain = motorName.lowercase() in listOf("fl", "fr", "rl", "rr", "bl", "br", "frontleft", "frontright", "rearleft", "rearright")
                    if (!isDrivetrain) {
                        val holdingVoltages = alignedRows.filter { row ->
                            val absV = if (row.velocity < 0.0) -row.velocity else row.velocity
                            val absA = if (row.accel < 0.0) -row.accel else row.accel
                            absV < 0.05 && absA < 0.1
                        }.map { it.voltage }
                        if (holdingVoltages.size >= 10) {
                            val kgEstimate = holdingVoltages.average()
                            val absKg = if (kgEstimate < 0.0) -kgEstimate else kgEstimate
                            if (absKg > 0.1) {
                                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Motors/$motorName/kG", kgEstimate))
                            }
                        }
                    }
                }
            }

            // 3. Drivetrain Angular Characterization
            val flVolts = motorVoltages["fl"] ?: motorVoltages["FL"] ?: motorVoltages["frontleft"]
            val rlVolts = motorVoltages["rl"] ?: motorVoltages["RL"] ?: motorVoltages["bl"] ?: motorVoltages["BL"] ?: motorVoltages["rearleft"]
            val frVolts = motorVoltages["fr"] ?: motorVoltages["FR"] ?: motorVoltages["frontright"]
            val rrVolts = motorVoltages["rr"] ?: motorVoltages["RR"] ?: motorVoltages["br"] ?: motorVoltages["BR"] ?: motorVoltages["rearright"]
            val leftSideVolts = mutableMapOf<Long, Double>()
            val rightSideVolts = mutableMapOf<Long, Double>()

            if (flVolts != null) {
                for ((t, v) in flVolts) leftSideVolts[t] = (leftSideVolts[t] ?: 0.0) + v * 0.5
            }
            if (rlVolts != null) {
                for ((t, v) in rlVolts) leftSideVolts[t] = (leftSideVolts[t] ?: 0.0) + v * 0.5
            }
            if (frVolts != null) {
                for ((t, v) in frVolts) rightSideVolts[t] = (rightSideVolts[t] ?: 0.0) + v * 0.5
            }
            if (rrVolts != null) {
                for ((t, v) in rrVolts) rightSideVolts[t] = (rightSideVolts[t] ?: 0.0) + v * 0.5
            }
            val angularVoltages = mutableMapOf<Long, Double>()
            for (t in leftSideVolts.keys) {
                val lv = leftSideVolts[t] ?: continue
                val rv = rightSideVolts[t] ?: continue
                angularVoltages[t] = lv - rv
            }
            val omegas = allFrames.filter { it.key == "Drive/Velocity_Omega" || it.key == "/Drive/Velocity_Omega" }
            if (angularVoltages.isNotEmpty() && omegas.isNotEmpty()) {
                val alignedAngData = mutableListOf<AlignedDataRow>()
                val sortedOmegas = omegas.sortedBy { it.timestampMs }
                var lastTime = 0L
                var lastOmega = 0.0

                for (o in sortedOmegas) {
                    val t = o.timestampMs
                    val volt = angularVoltages[t] ?: continue
                    val accel = if (lastTime == 0L) 0.0 else {
                        val dt = (t - lastTime) / 1000.0
                        if (dt > 1e-4) (o.value - lastOmega) / dt else 0.0
                    }

                    alignedAngData.add(AlignedDataRow(t, volt, o.value, accel))
                    lastTime = t
                    lastOmega = o.value
                }

                if (alignedAngData.size >= 10) {
                    val angSummary = sysIdService.analyzeRawData(alignedAngData)
                    if (angSummary.rSquared > 0.1) {
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Angular/kS", angSummary.kS))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Angular/kV", angSummary.kV))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Angular/kA", angSummary.kA))
                        framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Angular/R2", angSummary.rSquared))
                        if (angSummary.kA > 1e-6) {
                            framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/SysId/Angular/ADRC_b0", 1.0 / angSummary.kA))
                        }
                    }
                }
            }

            // 4. Wheel Slippage / Traction Loss Calculation
            val ekfVels = allFrames.filter { it.key == "Drive/Velocity" || it.key == "/Drive/Velocity" }
            if (ekfVels.isNotEmpty() && motorVelocities.isNotEmpty()) {
                val slippages = mutableListOf<Double>()
                for (ev in ekfVels) {
                    val t = ev.timestampMs
                    val ekfV = abs(ev.value)
                    var wheelSum = 0.0
                    var wheelCount = 0
                    for (motorName in listOf("fl", "fr", "rl", "rr", "bl", "br")) {
                        val mVel = motorVelocities[motorName]?.get(t) ?: continue
                        wheelSum += abs(mVel)
                        wheelCount++
                    }

                    if (wheelCount > 0) {
                        val avgWheelV = wheelSum / wheelCount
                        val diff = abs(avgWheelV - ekfV)
                        val denominator = maxOf(ekfV, 0.1)
                        slippages.add(diff / denominator)
                    }
                }
                if (slippages.isNotEmpty()) {
                    framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Drive/TractionLoss", slippages.average()))
                }
            }

            // 5. Driver Jitter Analysis
            val j = driverAnalysisService.analyzeDriverJitter(session.sessionId)
            if (j.peakFrequencyHz > 0.1) {
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/RecommendedExponent", j.recommendedExponent))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/RecommendedSlewRate", if (j.recommendedSlewRate == Double.MAX_VALUE) 999.0 else j.recommendedSlewRate))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/PeakJitterFrequency", j.peakFrequencyHz))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Driver/JitterPresent", if (j.hasJitter) 1.0 else 0.0))
            }

            // 6. EKF Innovation Residual & NIS Whiteness Diagnostics
            val nisFrames = allFrames.filter { (it.key.removePrefix("/") == "Vision/EKF_NIS" || it.key.removePrefix("/") == "EKF/NIS") && it.value > 0.0 }
            val visionXFrames = allFrames.filter { it.key.removePrefix("/") == "Vision/Pose_X" }.associateBy { it.timestampMs }
            val visionYFrames = allFrames.filter { it.key.removePrefix("/") == "Vision/Pose_Y" }.associateBy { it.timestampMs }
            val driveXFrames = allFrames.filter { it.key.removePrefix("/") == "Drive/Pose_X" }.associateBy { it.timestampMs }
            val driveYFrames = allFrames.filter { it.key.removePrefix("/") == "Drive/Pose_Y" }.associateBy { it.timestampMs }

            if (nisFrames.isNotEmpty()) {
                val avgNis = nisFrames.map { it.value }.average()
                val outlierCount = nisFrames.count { it.value > 9.0 } // 3-sigma chi-squared gate
                val outlierRatio = outlierCount.toDouble() / nisFrames.size

                val commonTimestamps = visionXFrames.keys.intersect(driveXFrames.keys)
                val residualOffsets = commonTimestamps.mapNotNull { t ->
                    val vx = visionXFrames[t]?.value ?: return@mapNotNull null
                    val vy = visionYFrames[t]?.value ?: return@mapNotNull null
                    val dx = driveXFrames[t]?.value ?: return@mapNotNull null
                    val dy = driveYFrames[t]?.value ?: return@mapNotNull null
                    sqrt((vx - dx).pow(2) + (vy - dy).pow(2))
                }
                val residualBiasM = if (residualOffsets.isNotEmpty()) residualOffsets.average() else 0.0

                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/EKF/AvgNIS", avgNis))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/EKF/ResidualBiasM", residualBiasM))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/EKF/NISOutlierRatio", outlierRatio))

                when {
                    avgNis in 1.2..2.8 && residualBiasM < 0.03 -> newTags.add("EKFOptimal")
                    avgNis < 0.6 -> newTags.add("VisionUnderweighted")
                    avgNis > 4.5 || outlierRatio > 0.10 -> newTags.add("VisionJitter")
                    residualBiasM >= 0.04 -> newTags.add("CameraExtrinsicSkew")
                }
            }

            // 7. Autonomous Path Tracking RMS & Deviation Diagnostics
            val cteFrames = allFrames.filter {
                val clean = it.key.removePrefix("/")
                clean == "Path/CrossTrackError" || clean == "Drive/CrossTrackError" || clean == "Drive/Cross_Track"
            }
            if (cteFrames.isNotEmpty()) {
                val ctes = cteFrames.map { abs(it.value) }
                val crossTrackRmse = sqrt(ctes.map { it * it }.average())
                val maxCte = ctes.maxOrNull() ?: 0.0

                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Auto/CrossTrackRMSE", crossTrackRmse))
                framesToInsert.add(TelemetryFrame(session.createdAt, session.sessionId, "Diagnostics/Auto/MaxCrossTrackM", maxCte))

                if (crossTrackRmse > 0.06 || maxCte > 0.15) {
                    newTags.add("AutoPathDeviation")
                }
            }

            val finalUniqueTags = newTags.distinct()
            resolvedTags = finalUniqueTags
            if (finalUniqueTags != session.tags) {
                databaseService.updateSessionTags(session.sessionId, finalUniqueTags)
            }

            databaseService.replaceAnalysisDiagnostics(
                session.sessionId,
                framesToInsert.map { frame ->
                    AnalysisDiagnostic(
                        sessionId = frame.sessionId,
                        key = frame.key,
                        value = frame.value,
                        stringValue = frame.stringValue,
                    )
                },
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return resolvedTags
    }

    private fun cleanKeyToDeviceName(key: String): String {
        // e.g. "/Drive/MotorFL/Current" -> "MotorFL"
        // e.g. "Drive/Motors/FrontLeftCurrent" -> "FrontLeft"
        val parts = key.split("/")
        if (parts.size >= 2) {
            val last = parts.last()
            if (last.lowercase() == "current" || last.lowercase() == "amps") {
                return parts[parts.size - 2]
            }
        }
        val lastPart = parts.last()
        return lastPart
            .replace("current", "", ignoreCase = true)
            .replace("amps", "", ignoreCase = true)
            .replace("/", "")
            .ifEmpty { "Motor" }
    }

    private companion object {
        /**
         * Secondary diagnostic algorithms operate on a deterministic, per-topic sample. Core
         * summary values above remain exact SQL aggregates. These bounds prevent a long WPILOG
         * from materializing millions of JVM objects while retaining endpoints and uniform
         * coverage for every ordinary topic.
         */
        const val MAX_DIAGNOSTIC_FRAMES = 100_000
        const val MAX_DIAGNOSTIC_FRAMES_PER_TOPIC = 2_048
    }
}
