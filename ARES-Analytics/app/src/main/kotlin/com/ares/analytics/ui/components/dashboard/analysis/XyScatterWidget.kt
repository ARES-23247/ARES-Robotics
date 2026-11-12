package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ScatterPlot
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.analysis.*
import com.ares.analytics.shared.models.League
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun XyScatterWidget(
    databaseService: DatabaseService,
    primarySessionId: String?,
    league: League = League.FRC,
    nt4ClientService: Nt4ClientService? = null,
    modifier: Modifier = Modifier,
) {
    val catalog = rememberSessionTopicCatalog(databaseService, primarySessionId, nt4ClientService)
    val topics = catalog.topics

    var preset by remember { mutableStateOf(ScatterPresetKind.BATTERY_INTERNAL_RESISTANCE) }
    var xTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var yTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }

    fun applyPresetTopics(selectedPreset: ScatterPresetKind, available: List<String>) {
        if (available.isEmpty()) return
        when (selectedPreset) {
            ScatterPresetKind.BATTERY_INTERNAL_RESISTANCE -> {
                xTopic = pickBestTopic(available, "TotalCurrent", "PDH/TotalCurrent", "PDP/TotalCurrent", "Current") ?: available.first()
                yTopic = pickBestTopic(available, "BatteryVoltage", "Voltage", excludeKeywords = listOf("Motor", "Applied")) ?: available.last()
            }
            ScatterPresetKind.MOTOR_TORQUE_SPEED -> {
                xTopic = pickBestTopic(available, "ActualVelocity", "Velocity", "RPM") ?: available.first()
                yTopic = pickBestTopic(available, "CurrentAmps", "MotorCurrent", "Current") ?: available.last()
            }
            ScatterPresetKind.PHASE_PORTRAIT -> {
                xTopic = pickBestTopic(available, "ActualPosition", "Position", "Pose_X") ?: available.first()
                yTopic = pickBestTopic(available, "ActualVelocity", "Velocity", "Speed") ?: available.last()
            }
            ScatterPresetKind.ERROR_VS_SPEED -> {
                xTopic = pickBestTopic(available, "Velocity", "Speed") ?: available.first()
                yTopic = pickBestTopic(available, "Error", "CrossTrack", "Innovation") ?: available.last()
            }
            ScatterPresetKind.CUSTOM -> {
                if (xTopic == null) xTopic = available.first()
                if (yTopic == null) yTopic = available.getOrElse(1) { available.first() }
            }
        }
    }

    LaunchedEffect(topics, preset) {
        if (topics.isNotEmpty() && (preset != ScatterPresetKind.CUSTOM || xTopic == null || yTopic == null)) {
            applyPresetTopics(preset, topics)
        }
    }

    val scatterState by produceState<Pair<ScatterRegressionResult, BatteryInternalResistanceReport?>>(
        initialValue = ScatterRegressionAnalyzer.analyzePair(emptyList(), emptyList()) to null,
        primarySessionId,
        xTopic,
        yTopic,
        preset,
        league,
        catalog.timestampRange,
    ) {
        val xs = loadTopicScalarSamples(databaseService, primarySessionId, xTopic, catalog.timestampRange, nt4ClientService)
        val ys = loadTopicScalarSamples(databaseService, primarySessionId, yTopic, catalog.timestampRange, nt4ClientService)
        val reg = ScatterRegressionAnalyzer.analyzePair(xs, ys)
        val battery = if (preset == ScatterPresetKind.BATTERY_INTERNAL_RESISTANCE) {
            ScatterRegressionAnalyzer.analyzeBatteryInternalResistance(xs, ys, league)
        } else {
            null
        }
        value = (battery?.regression ?: reg) to battery
    }

    val (regression, batteryReport) = scatterState
    val gradeColor = when (batteryReport?.grade) {
        BatteryResistanceGrade.GOOD -> AresGreen
        BatteryResistanceGrade.FAIR -> AresAmber
        BatteryResistanceGrade.WEAK -> AresRed
        null -> AresCyan
    }

    AresCard(modifier = modifier.fillMaxSize(), backgroundColor = AresSurfaceElevated) {
        CardHeader(
            title = "XY Scatter & Battery R_int",
            icon = Icons.Default.ScatterPlot,
            iconTint = gradeColor,
            statusText = batteryReport?.let {
                "${String.format("%.1f mΩ", it.internalResistanceMilliOhms)} · ${it.grade.label}"
            } ?: "R² ${String.format("%.3f", regression.rSquared)}",
            statusColor = gradeColor,
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                ScatterPresetKind.entries.forEach { candidate ->
                    AnalysisModeChip(
                        label = candidate.label,
                        selected = preset == candidate,
                        onClick = {
                            preset = candidate
                            applyPresetTopics(candidate, topics)
                        },
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisTopicSelectorChip(
                    label = "X Axis",
                    selectedTopic = xTopic,
                    availableTopics = topics,
                    onSelect = {
                        preset = ScatterPresetKind.CUSTOM
                        xTopic = it
                    },
                )
                AnalysisTopicSelectorChip(
                    label = "Y Axis",
                    selectedTopic = yTopic,
                    availableTopics = topics,
                    onSelect = {
                        preset = ScatterPresetKind.CUSTOM
                        yTopic = it
                    },
                )
            }

            if (regression.points.size < 3) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(125.dp)
                        .background(AresBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (preset == ScatterPresetKind.BATTERY_INTERNAL_RESISTANCE) {
                            "Select a run with TotalCurrent and BatteryVoltage telemetry (>= 1.5 A span) to estimate DC R_int (mΩ)."
                        } else {
                            "Select synchronized X and Y topics to render phase portraits and OLS correlation."
                        },
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (batteryReport != null) {
                        AnalysisMetricPill("R_int", "${String.format("%.1f", batteryReport.internalResistanceMilliOhms)} mΩ", gradeColor)
                        AnalysisMetricPill("Est. V_oc", "${String.format("%.2f", batteryReport.openCircuitVoltage)} V", AresCyan)
                        AnalysisMetricPill("Peak Draw", "${String.format("%.1f", batteryReport.peakCurrentAmps)} A", AresGold)
                        AnalysisMetricPill("Min V", "${String.format("%.2f", batteryReport.minObservedVoltage)} V", AresTextPrimary)
                    } else {
                        AnalysisMetricPill("OLS Slope", String.format("%.4f", regression.slope), AresCyan)
                        AnalysisMetricPill("Intercept", String.format("%.3f", regression.intercept), AresTextPrimary)
                        AnalysisMetricPill("Pearson r", String.format("%.3f", regression.pearsonR), AresGold)
                    }
                    AnalysisMetricPill("R²", String.format("%.3f", regression.rSquared), AresGreen)
                    AnalysisMetricPill("Samples", regression.points.size.toString(), AresTextSecondary)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(145.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AresBackground)
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                ) {
                    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f) }
                    Canvas(modifier = Modifier.fillMaxSize().padding(10.dp).clipToBounds()) {
                        val xSpan = (regression.xMax - regression.xMin).takeIf { it > 1e-9 } ?: 1.0
                        val ySpan = (regression.yMax - regression.yMin).takeIf { it > 1e-9 } ?: 1.0
                        fun toCanvas(x: Double, y: Double): Offset = Offset(
                            x = ((x - regression.xMin) / xSpan).toFloat().coerceIn(0f, 1f) * size.width,
                            y = size.height - ((y - regression.yMin) / ySpan).toFloat().coerceIn(0f, 1f) * size.height,
                        )

                        for (i in 1..3) {
                            val gx = size.width * i / 4f
                            val gy = size.height * i / 4f
                            drawLine(AresBorder.copy(alpha = 0.5f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                            drawLine(AresBorder.copy(alpha = 0.5f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                        }

                        regression.points.forEach { pt ->
                            val color = lerp(AresCyan, AresAmber, pt.normalizedTime)
                            drawCircle(
                                color = color.copy(alpha = 0.78f),
                                radius = 2.6.dp.toPx(),
                                center = toCanvas(pt.x, pt.y),
                            )
                        }

                        val yAtMin = regression.intercept + regression.slope * regression.xMin
                        val yAtMax = regression.intercept + regression.slope * regression.xMax
                        drawLine(
                            color = AresGreen,
                            start = toCanvas(regression.xMin, yAtMin),
                            end = toCanvas(regression.xMax, yAtMax),
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = dashEffect,
                        )
                    }
                    Text(
                        text = "Start (cyan) → End (amber) · Green = OLS Fit",
                        color = AresTextTertiary,
                        fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }
        }
    }
}
