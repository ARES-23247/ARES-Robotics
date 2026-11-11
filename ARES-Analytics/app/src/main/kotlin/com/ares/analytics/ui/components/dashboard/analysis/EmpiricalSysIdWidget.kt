package com.ares.analytics.ui.components.dashboard.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.analysis.*
import com.ares.analytics.service.tuning.TuningProposalInbox
import com.ares.analytics.ui.components.core.AresCard
import com.ares.analytics.ui.components.core.CardHeader
import com.ares.analytics.ui.theme.*
import com.areslib.tuning.TuningParameterDeclaration
import kotlin.math.cos
import kotlin.math.sign

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmpiricalSysIdWidget(
    databaseService: DatabaseService,
    primarySessionId: String?,
    nt4ClientService: Nt4ClientService? = null,
    tuningDeclarations: List<TuningParameterDeclaration> = emptyList(),
    tuningProposalInbox: TuningProposalInbox? = null,
    onNavigateToTuning: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val catalog = rememberSessionTopicCatalog(databaseService, primarySessionId, nt4ClientService)
    val topics = catalog.topics

    var voltageTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var velocityTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var positionTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var setpointTopic by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var gravityMode by remember { mutableStateOf(GravityModelKind.NONE) }
    var stageStatusMessage by remember(primarySessionId) { mutableStateOf<String?>(null) }
    var stagedSuccessfully by remember(primarySessionId) { mutableStateOf(false) }

    LaunchedEffect(topics) {
        if (topics.isEmpty()) return@LaunchedEffect
        if (voltageTopic == null || voltageTopic !in topics) {
            voltageTopic = pickBestTopic(topics, "AppliedVolts", "OutputVolts", "MotorVolts", "Voltage")
        }
        if (velocityTopic == null || velocityTopic !in topics) {
            velocityTopic = pickBestTopic(topics, "ActualVelocity", "Velocity", "Speed", excludeKeywords = listOf("Target", "Setpoint"))
        }
        if (positionTopic == null || positionTopic !in topics) {
            positionTopic = pickBestTopic(topics, "ActualPosition", "Position", "Angle", excludeKeywords = listOf("Target", "Setpoint", "Pose_"))
        }
        if (setpointTopic == null || setpointTopic !in topics) {
            setpointTopic = pickBestTopic(topics, "TargetVelocity", "TargetPosition", "Setpoint")
        }
    }

    val analysisState by produceState<Triple<List<EmpiricalSysIdSample>, EmpiricalFeedforwardFit, EmpiricalStepMetrics?>>(
        initialValue = Triple(emptyList(), EmpiricalFeedforwardFit(), null),
        primarySessionId,
        voltageTopic,
        velocityTopic,
        positionTopic,
        setpointTopic,
        gravityMode,
        catalog.timestampRange,
    ) {
        val volts = loadTopicScalarSamples(databaseService, primarySessionId, voltageTopic, catalog.timestampRange, nt4ClientService)
        val vels = loadTopicScalarSamples(databaseService, primarySessionId, velocityTopic, catalog.timestampRange, nt4ClientService)
        val pos = loadTopicScalarSamples(databaseService, primarySessionId, positionTopic, catalog.timestampRange, nt4ClientService)
        val sp = loadTopicScalarSamples(databaseService, primarySessionId, setpointTopic, catalog.timestampRange, nt4ClientService)
        val measuredForStep = if (setpointTopic?.contains("Position", ignoreCase = true) == true && pos.isNotEmpty()) pos else vels

        val aligned = EmpiricalStepSysIdAnalyzer.alignSamples(
            voltageSamples = volts,
            velocitySamples = vels,
            positionSamples = pos,
        )
        val fit = EmpiricalStepSysIdAnalyzer.fitFeedforward(aligned, gravityMode)
        val step = EmpiricalStepSysIdAnalyzer.analyzeStepResponse(sp, measuredForStep)
        value = Triple(aligned, fit, step)
    }

    val (alignedSamples, fit, stepMetrics) = analysisState
    val mechanismHint = remember(velocityTopic, setpointTopic) {
        (velocityTopic ?: setpointTopic).orEmpty().substringBeforeLast('/', "").substringAfterLast('/')
    }

    AresCard(modifier = modifier.fillMaxSize(), backgroundColor = AresSurfaceElevated) {
        CardHeader(
            title = "Empirical SysId & Step Analyzer",
            icon = Icons.Default.Functions,
            iconTint = AresCyan,
            statusText = if (fit.valid) "R² ${String.format("%.3f", fit.rSquared)}" else if (primarySessionId != null) "SELECT TOPICS" else "LIVE / LOG",
            statusColor = if (fit.valid && fit.rSquared >= 0.85) AresGreen else AresAmber,
        )

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisTopicSelectorChip("Volts", voltageTopic, topics, onSelect = { voltageTopic = it })
                AnalysisTopicSelectorChip("Vel", velocityTopic, topics, onSelect = { velocityTopic = it })
                AnalysisTopicSelectorChip(
                    "Pos/θ",
                    positionTopic,
                    topics,
                    allowNone = true,
                    onSelect = { positionTopic = it.ifBlank { null } },
                )
                AnalysisTopicSelectorChip(
                    "Setpoint",
                    setpointTopic,
                    topics,
                    allowNone = true,
                    onSelect = { setpointTopic = it.ifBlank { null } },
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GravityModelKind.entries.forEach { mode ->
                    AnalysisModeChip(
                        label = mode.label,
                        selected = gravityMode == mode,
                        onClick = { gravityMode = mode },
                    )
                }
            }

            if (!fit.valid && stepMetrics == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .background(AresBackground, RoundedCornerShape(8.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (topics.isEmpty()) {
                            "Select a recorded run in Recorded Sessions (or stream live telemetry) to identify kS/kV/kA and step-response metrics."
                        } else {
                            "Need excited motion samples (${alignedSamples.size} aligned) or a setpoint step on the selected channels."
                        },
                        color = AresTextTertiary,
                        fontSize = 11.sp,
                    )
                }
            } else {
                if (fit.valid) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnalysisMetricPill("kS (V)", String.format("%.4f", fit.kS), AresCyan)
                        AnalysisMetricPill("kV (V·s/u)", String.format("%.4f", fit.kV), AresCyan)
                        AnalysisMetricPill("kA (V·s²/u)", String.format("%.4f", fit.kA), AresCyan)
                        if (gravityMode != GravityModelKind.NONE) {
                            AnalysisMetricPill("kG (V)", String.format("%.4f", fit.kG), AresGold)
                        }
                        AnalysisMetricPill("kP Est.", String.format("%.3f", fit.suggestedKp), AresGreen)
                        AnalysisMetricPill("kD Est.", String.format("%.3f", fit.suggestedKd), AresGreen)
                    }

                    // Measured vs Predicted Voltage Fit Plot
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AresBackground)
                            .border(1.dp, AresBorder, RoundedCornerShape(8.dp)),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(6.dp).clipToBounds()) {
                            val subset = alignedSamples.takeLast(400)
                            if (subset.size < 2) return@Canvas
                            val predicted = subset.map { s ->
                                val g = when (gravityMode) {
                                    GravityModelKind.NONE -> 0.0
                                    GravityModelKind.ELEVATOR_CONSTANT -> fit.kG
                                    GravityModelKind.ARM_COSINE -> fit.kG * cos(s.position)
                                }
                                fit.kS * sign(s.velocity) + fit.kV * s.velocity + fit.kA * s.acceleration + g
                            }
                            val vMin = minOf(subset.minOf { it.voltage }, predicted.minOf { it }) - 0.5
                            val vMax = maxOf(subset.maxOf { it.voltage }, predicted.maxOf { it }) + 0.5
                            val span = (vMax - vMin).coerceAtLeast(1e-3)
                            val measPath = Path()
                            val predPath = Path()
                            subset.forEachIndexed { idx, s ->
                                val x = (idx.toFloat() / (subset.size - 1).coerceAtLeast(1)) * size.width
                                val ym = size.height - ((s.voltage - vMin) / span).toFloat() * size.height
                                val yp = size.height - ((predicted[idx] - vMin) / span).toFloat() * size.height
                                if (idx == 0) {
                                    measPath.moveTo(x, ym)
                                    predPath.moveTo(x, yp)
                                } else {
                                    measPath.lineTo(x, ym)
                                    predPath.lineTo(x, yp)
                                }
                            }
                            drawPath(measPath, color = AresTextSecondary.copy(alpha = 0.65f), style = Stroke(width = 1.5.dp.toPx()))
                            drawPath(predPath, color = AresCyan, style = Stroke(width = 2.dp.toPx()))
                        }
                        Text(
                            text = "Measured V (gray) vs OLS Fit (cyan) · n=${fit.sampleCount}",
                            color = AresTextTertiary,
                            fontSize = 9.sp,
                            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        )
                    }
                }

                stepMetrics?.let { step ->
                    Text("Closed-Loop Step Response", color = AresTextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        AnalysisMetricPill("Rise (10-90%)", "${String.format("%.0f", step.riseTimeMs)} ms", AresCyan)
                        AnalysisMetricPill(
                            "Overshoot",
                            "${String.format("%.1f", step.peakOvershootPct)}%",
                            if (step.peakOvershootPct > 15.0) AresAmber else AresGreen,
                        )
                        AnalysisMetricPill("Settling (±2%)", "${String.format("%.0f", step.settlingTimeMs)} ms", AresTextPrimary)
                        AnalysisMetricPill("SS Error", String.format("%.3f", step.steadyStateError), AresTextPrimary)
                        AnalysisMetricPill("Phase Lag", "${String.format("%.0f", step.phaseLagMs)} ms", AresGold)
                    }
                }

                if (fit.valid && tuningProposalInbox != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                val proposal = EmpiricalStepSysIdAnalyzer.buildTuningProposal(
                                    mechanismHint = mechanismHint,
                                    fit = fit,
                                    declarations = tuningDeclarations,
                                    evidenceSessionId = primarySessionId,
                                ) ?: EmpiricalStepSysIdAnalyzer.buildTuningProposal(
                                    mechanismHint = "",
                                    fit = fit,
                                    declarations = tuningDeclarations,
                                    evidenceSessionId = primarySessionId,
                                )
                                if (proposal == null) {
                                    stagedSuccessfully = false
                                    stageStatusMessage = "No writable kS/kV/kA/kP/kD declarations found in project tuning profile."
                                } else if (tuningProposalInbox.submit(proposal)) {
                                    stagedSuccessfully = true
                                    stageStatusMessage = "Staged ${proposal.values.size} gain(s) for review in Tuning."
                                } else {
                                    stagedSuccessfully = false
                                    stageStatusMessage = "Tuning review inbox is full; review pending proposals first."
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(30.dp),
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Stage Gains in Tuning", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        if (stagedSuccessfully) {
                            OutlinedButton(
                                onClick = onNavigateToTuning,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp),
                            ) {
                                Text("Open Tuning", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    stageStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = if (stagedSuccessfully) AresGreen else AresAmber,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        }
    }
}
