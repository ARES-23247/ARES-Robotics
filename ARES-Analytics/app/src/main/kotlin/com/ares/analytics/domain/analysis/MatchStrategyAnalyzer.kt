package com.ares.analytics.domain.analysis

import com.ares.analytics.shared.models.AlertRecord
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.RobotActionRecord
import com.ares.analytics.shared.models.Session
import com.ares.analytics.shared.models.SessionSummary
import com.ares.analytics.shared.models.TelemetryFrame
import com.areslib.math.coordinate.CoordinateTransformers
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

object MatchStrategyAnalyzer {

    val STRATEGY_TELEMETRY_KEY_PATTERNS: List<String> = listOf(
        "%BatteryVoltage%",
        "%battery_voltage%",
        "%Vin%",
        "%LoopTime%",
        "%loop_time%",
        "%TrackingError%",
        "%tracking_error%",
        "%CrossTrackError%",
        "%AlongTrackError%",
        "%PoseError%",
        "%HeadingError%",
        "%Current%",
        "%current%",
        "%Driver/%",
        "%Stick%",
        "%Throttle%",
        "%Vision/%",
    )

    fun fieldBoundsFor(league: League): FieldBoundsMeters = when (league) {
        League.FTC -> {
            val half = CoordinateTransformers.FTC_FIELD_SIZE / 2.0
            FieldBoundsMeters(-half, half, -half, half)
        }
        League.FRC -> FieldBoundsMeters(
            0.0,
            CoordinateTransformers.FRC_FIELD_LENGTH,
            0.0,
            CoordinateTransformers.FRC_FIELD_WIDTH,
        )
        League.XRP -> FieldBoundsMeters(-1.27, 1.27, -0.7112, 0.7112)
    }

    fun computeSpatialFieldHeatmap(
        league: League,
        poses: List<StrategyPosePoint>,
        telemetryFrames: List<TelemetryFrame>,
    ): SpatialFieldHeatmap {
        val bounds = fieldBoundsFor(league)
        val (cols, rows) = when (league) {
            League.FTC -> 12 to 12
            League.FRC -> 16 to 8
            League.XRP -> 12 to 8
        }
        val validPoses = poses
            .filter { it.x.isFinite() && it.y.isFinite() && it.headingRadians.isFinite() }
            .sortedBy { it.timestampMs }
        if (validPoses.isEmpty()) {
            return SpatialFieldHeatmap(
                league = league,
                bounds = bounds,
                cols = cols,
                rows = rows,
                cells = emptyList(),
                trajectory = emptyList(),
                poseSampleCount = 0,
                totalDistanceMeters = 0.0,
                peakSpeedMps = 0.0,
                maxSagVolts = 0.0,
            )
        }

        val batterySeries = extractBatterySeries(telemetryFrames)
        val restingVoltage = estimateRestingVoltage(league, batterySeries)

        class MutableBin {
            var count = 0
            var dwellSec = 0.0
            var speedWeightedSum = 0.0
            var maxSpeed = 0.0
            var maxSag = 0.0
        }

        val bins = Array(cols) { Array(rows) { MutableBin() } }
        var totalDistance = 0.0
        var peakSpeed = 0.0
        var globalMaxSag = 0.0

        for (index in validPoses.indices) {
            val pose = validPoses[index]
            val prev = if (index > 0) validPoses[index - 1] else null
            val dtMs = if (prev != null) (pose.timestampMs - prev.timestampMs).coerceIn(5L, 500L) else 20L
            val dtSec = dtMs / 1000.0
            val stepDist = if (prev != null) hypot(pose.x - prev.x, pose.y - prev.y) else 0.0
            val instantSpeed = if (prev != null && stepDist <= 1.5) {
                totalDistance += stepDist
                (stepDist / dtSec).coerceAtMost(8.0)
            } else {
                0.0
            }
            if (instantSpeed > peakSpeed) peakSpeed = instantSpeed

            val voltageAtPose = lookupNearestValue(batterySeries, pose.timestampMs)
            val sagVolts = if (voltageAtPose != null && voltageAtPose > 0.5) {
                (restingVoltage - voltageAtPose).coerceAtLeast(0.0)
            } else {
                0.0
            }
            if (sagVolts > globalMaxSag) globalMaxSag = sagVolts

            val normX = ((pose.x - bounds.minX) / bounds.widthMeters).coerceIn(0.0, 0.999999)
            val normY = ((pose.y - bounds.minY) / bounds.heightMeters).coerceIn(0.0, 0.999999)
            val col = (normX * cols).toInt().coerceIn(0, cols - 1)
            val row = (normY * rows).toInt().coerceIn(0, rows - 1)
            val bin = bins[col][row]
            bin.count += 1
            bin.dwellSec += dtSec
            bin.speedWeightedSum += instantSpeed * dtSec
            if (instantSpeed > bin.maxSpeed) bin.maxSpeed = instantSpeed
            if (sagVolts > bin.maxSag) bin.maxSag = sagVolts
        }

        val builtCells = buildList {
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    val bin = bins[c][r]
                    if (bin.count > 0) {
                        val avgSpeed = if (bin.dwellSec > 1e-6) bin.speedWeightedSum / bin.dwellSec else 0.0
                        add(
                            HeatmapCell(
                                col = c,
                                row = r,
                                sampleCount = bin.count,
                                dwellSeconds = bin.dwellSec,
                                avgSpeedMps = avgSpeed,
                                maxSpeedMps = bin.maxSpeed,
                                maxSagVolts = bin.maxSag,
                            )
                        )
                    }
                }
            }
        }

        val downsampledTrajectory = if (validPoses.size <= 450) {
            validPoses
        } else {
            val stride = (validPoses.size / 450).coerceAtLeast(1)
            validPoses.filterIndexed { idx, _ -> idx % stride == 0 || idx == validPoses.lastIndex }
        }

        return SpatialFieldHeatmap(
            league = league,
            bounds = bounds,
            cols = cols,
            rows = rows,
            cells = builtCells,
            trajectory = downsampledTrajectory,
            poseSampleCount = validPoses.size,
            totalDistanceMeters = totalDistance,
            peakSpeedMps = peakSpeed,
            maxSagVolts = globalMaxSag,
        )
    }

    fun analyzeMatchCoachingInsights(
        league: League,
        summary: SessionSummary?,
        telemetryFrames: List<TelemetryFrame>,
        alerts: List<AlertRecord>,
        actions: List<RobotActionRecord>,
    ): List<MatchCoachingInsight> {
        val insights = mutableListOf<MatchCoachingInsight>()
        val brownoutWarnV = when (league) {
            League.FRC -> 8.5
            League.FTC -> 10.0
            League.XRP -> 4.5
        }
        val healthyFloorV = when (league) {
            League.FRC -> 10.2
            League.FTC -> 11.2
            League.XRP -> 4.8
        }

        val batterySeries = extractBatterySeries(telemetryFrames)
        var steepestDrop = 0.0
        var steepestDropTs: Long? = null
        var minVoltsTs: Long? = null
        var minVoltsFromFrames = Double.POSITIVE_INFINITY
        val recentWindow = ArrayDeque<Pair<Long, Double>>()
        for ((ts, volts) in batterySeries) {
            if (volts < minVoltsFromFrames) {
                minVoltsFromFrames = volts
                minVoltsTs = ts
            }
            while (recentWindow.isNotEmpty() && ts - recentWindow.first().first > 450L) {
                recentWindow.removeFirst()
            }
            val peakRecent = recentWindow.maxOfOrNull { it.second }
            recentWindow.addLast(ts to volts)
            if (peakRecent != null && peakRecent - volts > steepestDrop) {
                steepestDrop = peakRecent - volts
                steepestDropTs = ts
            }
        }

        val effectiveMinV = when {
            minVoltsFromFrames.isFinite() -> minVoltsFromFrames
            summary != null && summary.minBatteryVoltage > 0.5 -> summary.minBatteryVoltage
            else -> null
        }

        if (effectiveMinV != null) {
            val jumpTs = steepestDropTs ?: minVoltsTs
            when {
                effectiveMinV < brownoutWarnV -> insights += MatchCoachingInsight(
                    id = "battery_brownout_risk",
                    title = "Brownout-Zone Voltage Dip",
                    detail = String.format(
                        Locale.US,
                        "Battery dipped to %.2f V (below %.1f V %s brownout warning) with a %.2f V transient drop. Swap to a fresh low-R_int pack and inspect acceleration current limits.",
                        effectiveMinV,
                        brownoutWarnV,
                        league.name,
                        steepestDrop,
                    ),
                    category = "POWER",
                    severity = CoachingSeverity.CRITICAL,
                    metricBadge = String.format(Locale.US, "%.2f V min", effectiveMinV),
                    jumpTimestampMs = jumpTs,
                )
                steepestDrop >= 1.1 || effectiveMinV < healthyFloorV -> insights += MatchCoachingInsight(
                    id = "stick_step_voltage_sag",
                    title = "Acceleration Voltage Sag Under Load",
                    detail = String.format(
                        Locale.US,
                        "Peak transient drop of %.2f V brought bus voltage to %.2f V. Smoother joystick slew rates or translational acceleration caps preserve endgame voltage.",
                        steepestDrop,
                        effectiveMinV,
                    ),
                    category = "DRIVER & POWER",
                    severity = CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "-%.2f V sag", steepestDrop.coerceAtLeast(0.0)),
                    jumpTimestampMs = jumpTs,
                )
                else -> insights += MatchCoachingInsight(
                    id = "battery_headroom_healthy",
                    title = "Strong Battery Voltage Headroom",
                    detail = String.format(
                        Locale.US,
                        "Minimum bus voltage stayed at %.2f V (well above %.1f V %s floor) with <= %.2f V transient sag.",
                        effectiveMinV,
                        brownoutWarnV,
                        league.name,
                        steepestDrop,
                    ),
                    category = "POWER",
                    severity = CoachingSeverity.EXCELLENT,
                    metricBadge = String.format(Locale.US, "%.2f V min", effectiveMinV),
                    jumpTimestampMs = minVoltsTs,
                )
            }
        }

        val rIntMohm = summary?.avgBatteryResistance?.takeIf { it.isFinite() && it > 0.0005 }?.times(1000.0)
        if (rIntMohm != null) {
            val warnMohm = if (league == League.FRC) 22.0 else 45.0
            insights += if (rIntMohm > warnMohm) {
                MatchCoachingInsight(
                    id = "battery_internal_resistance",
                    title = "Elevated Battery Internal Resistance",
                    detail = String.format(
                        Locale.US,
                        "Estimated R_int is %.1f mΩ (> %.0f mΩ %s pit target). Check main power connector seating, breaker contacts, or retire this pack to practice rotation.",
                        rIntMohm,
                        warnMohm,
                        league.name,
                    ),
                    category = "PIT HEALTH",
                    severity = CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "%.1f mΩ", rIntMohm),
                    jumpTimestampMs = minVoltsTs,
                )
            } else {
                MatchCoachingInsight(
                    id = "battery_internal_resistance",
                    title = "Low-Impedance Competition Pack",
                    detail = String.format(
                        Locale.US,
                        "Estimated R_int is %.1f mΩ (within %.0f mΩ %s competition target).",
                        rIntMohm,
                        warnMohm,
                        league.name,
                    ),
                    category = "PIT HEALTH",
                    severity = CoachingSeverity.EXCELLENT,
                    metricBadge = String.format(Locale.US, "%.1f mΩ", rIntMohm),
                )
            }
        }

        val trackingFrames = telemetryFrames.filter { frame ->
            val k = frame.key.lowercase(Locale.ROOT)
            frame.value.isFinite() && (
                k.contains("crosstrackerror") ||
                    k.contains("trackingerror") ||
                    k.contains("tracking_error") ||
                    k.contains("poseerror")
                )
        }
        val peakTrackFrame = trackingFrames.maxByOrNull { abs(it.value) }
        val avgCrossTrackM = summary?.avgCrossTrackError?.takeIf { it.isFinite() && it > 0.0 }
            ?: trackingFrames.takeIf { it.isNotEmpty() }?.map { abs(it.value) }?.average()
        if (avgCrossTrackM != null || peakTrackFrame != null) {
            val peakCm = (peakTrackFrame?.let { abs(it.value) } ?: avgCrossTrackM ?: 0.0) * 100.0
            val avgCm = (avgCrossTrackM ?: 0.0) * 100.0
            insights += when {
                peakCm > 18.0 || avgCm > 8.0 -> MatchCoachingInsight(
                    id = "path_tracking_hotspot",
                    title = "Path Tracking Deviation Hotspot",
                    detail = String.format(
                        Locale.US,
                        "Tracking error peaked at %.1f cm (mean %.1f cm). Review wheel slip, corner entry speed, or translational kP/kV feedforward at the linked timestamp.",
                        peakCm,
                        avgCm,
                    ),
                    category = "PATHING",
                    severity = if (peakCm > 28.0) CoachingSeverity.CRITICAL else CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "%.1f cm peak", peakCm),
                    jumpTimestampMs = peakTrackFrame?.timestampMs,
                )
                else -> MatchCoachingInsight(
                    id = "path_tracking_precision",
                    title = "Crisp Trajectory Tracking",
                    detail = String.format(
                        Locale.US,
                        "Mean cross-track error held at %.1f cm (peak %.1f cm).",
                        avgCm,
                        peakCm,
                    ),
                    category = "PATHING",
                    severity = CoachingSeverity.EXCELLENT,
                    metricBadge = String.format(Locale.US, "%.1f cm avg", avgCm),
                    jumpTimestampMs = peakTrackFrame?.timestampMs,
                )
            }
        }

        val loopFrames = telemetryFrames.filter { frame ->
            val k = frame.key.lowercase(Locale.ROOT)
            frame.value.isFinite() && (k.contains("looptime") || k.contains("loop_time"))
        }
        val peakLoopFrame = loopFrames.maxByOrNull { it.value }
        val p95LoopMs = summary?.p95LoopTimeMs?.takeIf { it.isFinite() && it > 0.0 }
        if (p95LoopMs != null || peakLoopFrame != null) {
            val effectiveP95 = p95LoopMs ?: peakLoopFrame?.value ?: 0.0
            val peakMs = peakLoopFrame?.value ?: effectiveP95
            if (effectiveP95 > 20.0 || peakMs > 28.0) {
                insights += MatchCoachingInsight(
                    id = "control_loop_overrun",
                    title = "Control Loop Timing Jitter",
                    detail = String.format(
                        Locale.US,
                        "Loop p95 is %.1f ms (peak %.1f ms). Check synchronous I2C reads, unbatched bulk hardware reads, or GC allocations.",
                        effectiveP95,
                        peakMs,
                    ),
                    category = "CONTROL LOOP",
                    severity = if (effectiveP95 > 30.0) CoachingSeverity.CRITICAL else CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "%.1f ms p95", effectiveP95),
                    jumpTimestampMs = peakLoopFrame?.timestampMs,
                )
            }
        }

        if (summary != null && (summary.visionAcceptanceRate > 0.0 || summary.avgVisionLatencyMs > 0.0)) {
            val acceptPct = summary.visionAcceptanceRate * 100.0
            insights += if (acceptPct < 65.0) {
                MatchCoachingInsight(
                    id = "vision_rejection_rate",
                    title = "High AprilTag / Vision Rejection Rate",
                    detail = String.format(
                        Locale.US,
                        "Only %.1f%% of vision updates passed gating (avg latency %.1f ms). High angular velocity or motion blur may be triggering Mahalanobis rejections.",
                        acceptPct,
                        summary.avgVisionLatencyMs,
                    ),
                    category = "VISION",
                    severity = CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "%.0f%% accepted", acceptPct),
                )
            } else {
                MatchCoachingInsight(
                    id = "vision_fusion_healthy",
                    title = "Clean Vision Pose Fusion",
                    detail = String.format(
                        Locale.US,
                        "%.1f%% of camera pose updates were accepted with %.1f ms average pipeline latency.",
                        acceptPct,
                        summary.avgVisionLatencyMs,
                    ),
                    category = "VISION",
                    severity = CoachingSeverity.EXCELLENT,
                    metricBadge = String.format(Locale.US, "%.0f%% accepted", acceptPct),
                )
            }
        }

        val cornerCurrents = extractDriveCornerCurrents(summary, telemetryFrames)
        if (cornerCurrents.size >= 3) {
            val maxEntry = cornerCurrents.maxBy { it.value }
            val peerMean = cornerCurrents.filter { it.key != maxEntry.key }.map { it.value }.average()
            if (peerMean > 0.5 && maxEntry.value >= 1.5 && maxEntry.value >= peerMean * 1.28) {
                val ratioPct = ((maxEntry.value - peerMean) / peerMean) * 100.0
                insights += MatchCoachingInsight(
                    id = "drive_current_asymmetry",
                    title = "Drive Corner Current Asymmetry (${maxEntry.key.uppercase(Locale.US)})",
                    detail = String.format(
                        Locale.US,
                        "Motor '%s' averaged %.2f A (+%.0f%% above peer corners' %.2f A mean). Inspect gearbox drag, belt tension, or wheel scrub on %s.",
                        maxEntry.key.uppercase(Locale.US),
                        maxEntry.value,
                        ratioPct,
                        peerMean,
                        maxEntry.key.uppercase(Locale.US),
                    ),
                    category = "DRIVETRAIN",
                    severity = CoachingSeverity.WARNING,
                    metricBadge = String.format(Locale.US, "+%.0f%% on %s", ratioPct, maxEntry.key.uppercase(Locale.US)),
                )
            }
        }

        val orderedActions = actions.sortedBy { it.timestampMs }
        if (orderedActions.size >= 3) {
            val splits = orderedActions.zipWithNext { a, b -> (b.timestampMs - a.timestampMs) to b.timestampMs }
                .filter { it.first in 750L..45_000L }
            if (splits.size >= 2) {
                val sortedDurations = splits.map { it.first }.sorted()
                val medianSec = sortedDurations[sortedDurations.size / 2] / 1000.0
                val slowest = splits.maxBy { it.first }
                val spreadSec = (sortedDurations.last() - sortedDurations.first()) / 1000.0
                insights += MatchCoachingInsight(
                    id = "cycle_split_consistency",
                    title = "Action & Cycle Split Cadence",
                    detail = String.format(
                        Locale.US,
                        "Recorded %d robot actions with %.2f s median split and %.2f s spread (slowest split %.2f s).",
                        orderedActions.size,
                        medianSec,
                        spreadSec,
                        slowest.first / 1000.0,
                    ),
                    category = "STRATEGY",
                    severity = if (spreadSec > 4.5) CoachingSeverity.TIP else CoachingSeverity.EXCELLENT,
                    metricBadge = String.format(Locale.US, "%.2f s med", medianSec),
                    jumpTimestampMs = slowest.second,
                )
            }
        }

        if (alerts.isNotEmpty() && insights.none { it.severity == CoachingSeverity.CRITICAL }) {
            val topAlert = alerts.maxBy { it.durationMs }
            insights += MatchCoachingInsight(
                id = "alert_triage_hotspot",
                title = "Triggered Telemetry Alert: ${topAlert.ruleKey.substringAfterLast('/')}",
                detail = String.format(
                    Locale.US,
                    "%d alert(s) recorded; longest was '%s' (peak %.2f for %d ms).",
                    alerts.size,
                    topAlert.ruleKey,
                    topAlert.peakValue,
                    topAlert.durationMs,
                ),
                category = "ALERTS",
                severity = CoachingSeverity.WARNING,
                metricBadge = "${alerts.size} alert(s)",
                jumpTimestampMs = topAlert.triggerTimestampMs,
            )
        }

        return insights.sortedBy { it.severity.ordinal }
    }

    fun buildEventProgressionSummary(
        sessions: List<Session>,
        summaries: List<SessionSummary>,
    ): List<EventProgressionMetricSeries> {
        val sessionById = sessions.associateBy { it.sessionId }
        val ordered = summaries
            .filter { it.durationMs > 0L || it.minBatteryVoltage > 0.0 || it.p95LoopTimeMs > 0.0 }
            .sortedBy { it.createdAt }
            .takeLast(16)
        if (ordered.isEmpty()) return emptyList()

        fun runLabel(index: Int, summary: SessionSummary): String {
            val session = sessionById[summary.sessionId]
            val matchNo = summary.matchNumber ?: session?.matchNumber
            return matchNo?.let { "M$it" } ?: "R${index + 1}"
        }

        return listOfNotNull(
            buildSeries(
                id = "min_battery_v",
                title = "Min Battery",
                unit = "V",
                higherIsBetter = true,
                noiseBand = 0.15,
                formatValue = { String.format(Locale.US, "%.2f V", it) },
                formatDelta = { String.format(Locale.US, "%+.2f V", it) },
                points = ordered.mapIndexedNotNull { idx, s ->
                    s.minBatteryVoltage.takeIf { it > 0.5 }?.let {
                        EventProgressionPoint(s.sessionId, runLabel(idx, s), s.createdAt, it)
                    }
                },
            ),
            buildSeries(
                id = "battery_rint_mohm",
                title = "Battery R_int",
                unit = "mΩ",
                higherIsBetter = false,
                noiseBand = 2.0,
                formatValue = { String.format(Locale.US, "%.1f mΩ", it) },
                formatDelta = { String.format(Locale.US, "%+.1f mΩ", it) },
                points = ordered.mapIndexedNotNull { idx, s ->
                    (s.avgBatteryResistance * 1000.0).takeIf { it > 0.5 }?.let {
                        EventProgressionPoint(s.sessionId, runLabel(idx, s), s.createdAt, it)
                    }
                },
            ),
            buildSeries(
                id = "loop_p95_ms",
                title = "Loop p95",
                unit = "ms",
                higherIsBetter = false,
                noiseBand = 0.8,
                formatValue = { String.format(Locale.US, "%.1f ms", it) },
                formatDelta = { String.format(Locale.US, "%+.1f ms", it) },
                points = ordered.mapIndexedNotNull { idx, s ->
                    s.p95LoopTimeMs.takeIf { it > 0.05 }?.let {
                        EventProgressionPoint(s.sessionId, runLabel(idx, s), s.createdAt, it)
                    }
                },
            ),
            buildSeries(
                id = "vision_accept_pct",
                title = "Vision Accept",
                unit = "%",
                higherIsBetter = true,
                noiseBand = 2.5,
                formatValue = { String.format(Locale.US, "%.0f%%", it) },
                formatDelta = { String.format(Locale.US, "%+.0f%%", it) },
                points = ordered.mapIndexedNotNull { idx, s ->
                    (s.visionAcceptanceRate * 100.0).takeIf { it > 0.0 }?.let {
                        EventProgressionPoint(s.sessionId, runLabel(idx, s), s.createdAt, it)
                    }
                },
            ),
            buildSeries(
                id = "cross_track_cm",
                title = "Cross-Track",
                unit = "cm",
                higherIsBetter = false,
                noiseBand = 0.6,
                formatValue = { String.format(Locale.US, "%.1f cm", it) },
                formatDelta = { String.format(Locale.US, "%+.1f cm", it) },
                points = ordered.mapIndexedNotNull { idx, s ->
                    (s.avgCrossTrackError * 100.0).takeIf { it > 0.0 }?.let {
                        EventProgressionPoint(s.sessionId, runLabel(idx, s), s.createdAt, it)
                    }
                },
            ),
        )
    }

    private fun buildSeries(
        id: String,
        title: String,
        unit: String,
        higherIsBetter: Boolean,
        noiseBand: Double,
        formatValue: (Double) -> String,
        formatDelta: (Double) -> String,
        points: List<EventProgressionPoint>,
    ): EventProgressionMetricSeries? {
        if (points.isEmpty()) return null
        val latest = points.last().value
        val baseline = points.first().value
        val delta = latest - baseline
        val trend = when {
            points.size < 2 || abs(delta) <= noiseBand -> ProgressionTrend.STABLE
            (delta > 0.0 && higherIsBetter) || (delta < 0.0 && !higherIsBetter) -> ProgressionTrend.IMPROVING
            else -> ProgressionTrend.REGRESSING
        }
        return EventProgressionMetricSeries(
            id = id,
            title = title,
            unit = unit,
            higherIsBetter = higherIsBetter,
            points = points,
            latestFormatted = formatValue(latest),
            deltaFormatted = if (points.size >= 2) "${formatDelta(delta)} vs ${points.first().label}" else "1 run",
            trend = trend,
        )
    }

    private fun extractBatterySeries(frames: List<TelemetryFrame>): List<Pair<Long, Double>> =
        frames.asSequence()
            .filter { frame ->
                val k = frame.key.lowercase(Locale.ROOT)
                frame.value.isFinite() && frame.value > 0.5 && (
                    k.contains("batteryvoltage") ||
                        k.contains("battery_voltage") ||
                        k.endsWith("/vin") ||
                        k == "vin" ||
                        k.endsWith("battery")
                    )
            }
            .sortedBy { it.timestampMs }
            .map { it.timestampMs to it.value }
            .toList()

    private fun estimateRestingVoltage(league: League, batterySeries: List<Pair<Long, Double>>): Double {
        if (batterySeries.isEmpty()) {
            return when (league) {
                League.FTC, League.FRC -> 12.8
                League.XRP -> 5.0
            }
        }
        val sortedVolts = batterySeries.map { it.second }.sorted()
        val p95Idx = kotlin.math.ceil((sortedVolts.size - 1) * 0.95).toInt().coerceIn(0, sortedVolts.lastIndex)
        return sortedVolts[p95Idx]
    }

    private fun lookupNearestValue(series: List<Pair<Long, Double>>, timestampMs: Long): Double? {
        if (series.isEmpty()) return null
        var low = 0
        var high = series.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midTs = series[mid].first
            when {
                midTs < timestampMs -> low = mid + 1
                midTs > timestampMs -> high = mid - 1
                else -> return series[mid].second
            }
        }
        val candidateA = series.getOrNull(low)
        val candidateB = series.getOrNull(high)
        val best = listOfNotNull(candidateA, candidateB).minByOrNull { abs(it.first - timestampMs) } ?: return null
        return best.second.takeIf { abs(best.first - timestampMs) <= 1_500L }
    }

    private fun extractDriveCornerCurrents(
        summary: SessionSummary?,
        frames: List<TelemetryFrame>,
    ): Map<String, Double> {
        val canonicalCorners = mapOf(
            "fl" to listOf("fl", "frontleft", "front_left"),
            "fr" to listOf("fr", "frontright", "front_right"),
            "rl" to listOf("rl", "bl", "rearleft", "backleft", "rear_left", "back_left"),
            "rr" to listOf("rr", "br", "rearright", "backright", "rear_right", "back_right"),
        )
        val result = LinkedHashMap<String, Double>()
        val summaryMap = summary?.motorCurrentAverages.orEmpty()
        for ((corner, aliases) in canonicalCorners) {
            val fromSummary = summaryMap.entries.firstOrNull { (k, v) ->
                val lower = k.lowercase(Locale.ROOT)
                v.isFinite() && v > 0.0 && aliases.any { alias -> lower == alias || lower.endsWith("/$alias") || lower.contains("_$alias") }
            }?.value
            if (fromSummary != null) {
                result[corner] = fromSummary
                continue
            }
            val matchingFrames = frames.filter { f ->
                val lower = f.key.lowercase(Locale.ROOT)
                f.value.isFinite() && lower.contains("current") && aliases.any { alias ->
                    lower.contains("/$alias/") || lower.endsWith("/$alias")
                }
            }
            if (matchingFrames.isNotEmpty()) {
                result[corner] = matchingFrames.map { abs(it.value) }.average()
            }
        }
        return result
    }
}
