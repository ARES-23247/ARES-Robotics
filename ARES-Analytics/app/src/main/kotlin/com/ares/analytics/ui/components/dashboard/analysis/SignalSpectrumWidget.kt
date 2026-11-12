package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.analysis.*
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalSpectrumWidget(
    databaseService: DatabaseService,
    primarySessionId: String?,
    nt4ClientService: Nt4ClientService? = null,
    modifier: Modifier = Modifier,
) {
    val catalog = rememberSessionTopicCatalog(databaseService, primarySessionId, nt4ClientService)
    val topics = catalog.topics

    var topicA by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var topicB by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf(DerivedChannelOperation.DERIVATIVE) }
    var fftOnDerived by remember { mutableStateOf(false) }

    LaunchedEffect(topics) {
        if (topics.isEmpty()) return@LaunchedEffect
        if (topicA == null || topicA !in topics) {
            topicA = pickBestTopic(topics, "TotalCurrent", "Current", "Velocity", "LoopTimeMs") ?: topics.first()
        }
        if (topicB == null || topicB !in topics) {
            topicB = topics.firstOrNull { it != topicA } ?: topicA
        }
    }

    val computed by produceState<Triple<List<TimedScalarSample>, List<TimedScalarSample>, SignalSpectrumAnalysis>>(
        initialValue = Triple(emptyList(), emptyList(), SignalSpectrumAnalysis(0.0, DoubleArray(0), DoubleArray(0), 0.0, emptyList())),
        primarySessionId,
        topicA,
        topicB,
        operation,
        fftOnDerived,
        catalog.timestampRange,
    ) {
        val rawA = loadTopicScalarSamples(databaseService, primarySessionId, topicA, catalog.timestampRange, nt4ClientService)
        val rawB = if (operation == DerivedChannelOperation.CHANNEL_DELTA) {
            loadTopicScalarSamples(databaseService, primarySessionId, topicB, catalog.timestampRange, nt4ClientService)
        } else {
            emptyList()
        }
        val derived = SignalMathEngine.applyOperation(operation, rawA, rawB)
        val spectrumInput = if (fftOnDerived) derived else rawA
        val spectrum = SignalMathEngine.computeSpectrum(spectrumInput, topPeakCount = 3)
        value = Triple(rawA, derived, spectrum)
    }

    val (_, derivedSamples, spectrum) = computed

    AresCard(modifier = modifier.fillMaxSize(), backgroundColor = AresSurfaceElevated) {
        CardHeader(
            title = "Derived Math & FFT Spectrum",
            icon = Icons.Default.GraphicEq,
            iconTint = AresCyan,
            statusText = if (spectrum.dominantFrequencyHz > 0.0) {
                "Peak ${String.format("%.1f Hz", spectrum.dominantFrequencyHz)}"
            } else {
                "READY"
            },
            statusColor = AresCyan,
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisTopicSelectorChip("Ch A", topicA, topics, onSelect = { topicA = it })
                if (operation == DerivedChannelOperation.CHANNEL_DELTA) {
                    AnalysisTopicSelectorChip("Ch B", topicB, topics, onSelect = { topicB = it })
                }
                AnalysisModeChip(
                    label = if (fftOnDerived) "FFT: Derived" else "FFT: Raw Ch A",
                    selected = fftOnDerived,
                    onClick = { fftOnDerived = !fftOnDerived },
                    accent = AresGold,
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                DerivedChannelOperation.entries.forEach { op ->
                    AnalysisModeChip(
                        label = op.label,
                        selected = operation == op,
                        onClick = { operation = op },
                    )
                }
            }

            if (derivedSamples.size < 2) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(AresBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Select a recorded run or active topic to compute derived waveforms and Hann-windowed FFT peaks.",
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                val minVal = derivedSamples.minOf { it.value }
                val maxVal = derivedSamples.maxOf { it.value }
                val latestVal = derivedSamples.last().value
                val unitLabel = operation.unitSuffix

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AnalysisMetricPill("Latest ${unitLabel}".trim(), String.format("%.3f", latestVal), AresCyan)
                    AnalysisMetricPill("Min", String.format("%.3f", minVal), AresTextSecondary)
                    AnalysisMetricPill("Max", String.format("%.3f", maxVal), AresTextSecondary)
                    if (spectrum.sampleRateHz > 0.0) {
                        AnalysisMetricPill("Fs", "${String.format("%.0f", spectrum.sampleRateHz)} Hz", AresGold)
                    }
                    spectrum.peaks.forEachIndexed { index, peak ->
                        AnalysisMetricPill(
                            label = "Peak #${index + 1}",
                            value = "${String.format("%.1f", peak.frequencyHz)} Hz (${String.format("%.2f", peak.amplitude)})",
                            valueColor = if (index == 0) AresGreen else AresTextPrimary,
                        )
                    }
                }

                // Derived Time-Domain Waveform
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AresBackground)
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(6.dp).clipToBounds()) {
                        val span = (maxVal - minVal).takeIf { it > 1e-9 } ?: 1.0
                        val t0 = derivedSamples.first().timestampMs
                        val tSpan = max(1L, derivedSamples.last().timestampMs - t0).toFloat()
                        val path = Path()
                        derivedSamples.forEachIndexed { idx, pt ->
                            val x = ((pt.timestampMs - t0) / tSpan) * size.width
                            val y = size.height - ((pt.value - minVal) / span).toFloat() * size.height
                            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = AresCyan, style = Stroke(width = 1.8.dp.toPx()))
                    }
                    Text(
                        text = operation.label,
                        color = AresTextTertiary,
                        fontSize = 9.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }

                // Hann-Windowed FFT Spectrum Plot
                if (spectrum.frequenciesHz.size > 2) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AresBackground)
                            .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp).clipToBounds()) {
                            val bins = spectrum.frequenciesHz.size
                            val maxMag = (spectrum.magnitudes.drop(1).maxOrNull() ?: 1.0).coerceAtLeast(1e-6)
                            val barWidth = (size.width / (bins - 1).coerceAtLeast(1)).coerceAtLeast(1.5f)
                            for (i in 1 until bins) {
                                val x = ((i - 1).toFloat() / (bins - 1).coerceAtLeast(1)) * size.width
                                val h = ((spectrum.magnitudes[i] / maxMag).toFloat().coerceIn(0f, 1f)) * size.height
                                drawRect(
                                    color = AresGold.copy(alpha = 0.78f),
                                    topLeft = Offset(x, size.height - h),
                                    size = Size(barWidth * 0.85f, h),
                                )
                            }
                        }
                        val nyquist = spectrum.frequenciesHz.lastOrNull() ?: 0.0
                        Text(
                            text = "Hann FFT Spectrum (0 – ${String.format("%.1f", nyquist)} Hz)",
                            color = AresTextTertiary,
                            fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        )
                    }
                }
            }
        }
    }
}
