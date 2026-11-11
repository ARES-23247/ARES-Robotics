package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.service.commissioning.CommissioningSimulationStatus
import com.ares.analytics.service.commissioning.CommissioningVerificationService
import com.ares.analytics.ui.screens.SubsystemCommissioningLab
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresGold
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.areslib.subsystem.*
import com.areslib.subsystem.InterlockComparison
import com.areslib.subsystem.isAresGenerated
import com.areslib.subsystem.SubsystemDocument
import com.areslib.subsystem.SubsystemHomingMethod
import com.areslib.subsystem.SubsystemValueType
import com.areslib.tuning.TuningParameterType
import java.awt.datatransfer.StringSelection
import java.awt.Toolkit
import java.util.Locale

fun formatSubsystemTuningMarkdown(document: SubsystemDocument): String = buildString {
    appendLine("# ${document.displayName} — Pit Tuning & Safety Reference")
    appendLine()
    appendLine("- **Subsystem ID**: `${document.documentId}`")
    appendLine("- **Platform**: `${document.platform.name}`")
    appendLine("- **Template**: `${document.template.name}`")
    appendLine()

    appendLine("## Live Tunable Parameters (${document.tuningParameters.size})")
    if (document.tuningParameters.isEmpty()) {
        appendLine("_No tunable parameters declared._")
    } else {
        appendLine("| Parameter | Key | Type | Default | Bounds | Apply Policy |")
        appendLine("| :--- | :--- | :--- | :--- | :--- | :--- |")
        document.tuningParameters.forEach { param ->
            val defaultText = when (param.type) {
                TuningParameterType.DOUBLE -> (param.defaultValue.doubleValue ?: 0.0).toString()
                TuningParameterType.INT -> (param.defaultValue.intValue ?: 0).toString()
                TuningParameterType.BOOLEAN -> (param.defaultValue.booleanValue ?: false).toString()
                TuningParameterType.TEXT, TuningParameterType.ENUM -> param.defaultValue.textValue.orEmpty()
            }
            val unitSuffix = param.unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val boundsText = if (param.minimum != null || param.maximum != null) {
                "[${param.minimum ?: "-∞"}, ${param.maximum ?: "+∞"}]$unitSuffix"
            } else {
                "—"
            }
            appendLine(
                "| ${param.displayName} | `${param.key}` | `${param.type.name}` | `$defaultText$unitSuffix` | $boundsText | `${param.applyPolicy.name}` |"
            )
        }
    }
    appendLine()

    appendLine("## Controller Rules (${document.controlLoops.size})")
    if (document.controlLoops.isEmpty()) {
        appendLine("_No closed-loop or direct controller rules declared._")
    } else {
        appendLine("| Rule | Strategy | Actuator | Target → Measurement | Gains (kP / kI / kD) |")
        appendLine("| :--- | :--- | :--- | :--- | :--- |")
        document.controlLoops.forEach { loop ->
            val gains = String.format(
                Locale.US,
                "kP=%.3g, kI=%.3g, kD=%.3g",
                loop.kP,
                loop.kI,
                loop.kD
            )
            val feedbackTarget = "${loop.targetFieldId ?: "—"} → ${loop.measurementFieldId ?: "—"}"
            appendLine(
                "| ${loop.displayName} (`${loop.loopId}`) | `${loop.strategy.name}` | `${loop.actuatorId}` | `$feedbackTarget` | `$gains` |"
            )
        }
    }
    appendLine()

    val safety = document.safety
    appendLine("## Safety & Fault Neutralization Policy")
    appendLine("- **Feedback Freshness Timeout**: `${safety.feedbackTimeoutMs ?: 100} ms`")
    appendLine("- **Homing Method**: `${safety.homing.method.name}`")
    appendLine("- **Latch Output Faults**: `${safety.latchOutputFaults}`")
    appendLine("- **Zero-Allocation Hot Loop**: `${safety.zeroAllocationPeriodic}`")
    appendLine("- **Require Explicit Neutral Recovery**: `${safety.requiresExplicitNeutralRecovery}`")
}

@Composable
fun SubsystemCommissioningOverviewCard(
    document: SubsystemDocument,
    viewModel: SubsystemGeneratorViewModel,
) {
    val verificationService = remember { CommissioningVerificationService() }
    val summary = remember(document) { verificationService.verify(listOf(document)) }
    var expandedLoopId by remember(document.documentId) {
        mutableStateOf(document.controlLoops.firstOrNull()?.loopId)
    }
    var copiedMarkdown by remember(document.documentId) { mutableStateOf(false) }

    EditorCard("Controller Commissioning & Pit Sheet", Icons.Default.Science) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val (badgeLabel, badgeColor) = when (summary.status) {
                CommissioningSimulationStatus.VERIFIED ->
                    "COMMISSIONING VERIFIED (${summary.scenarioCount} scenarios)" to AresGreen
                CommissioningSimulationStatus.NEEDS_REVIEW ->
                    "NEEDS REVIEW (${summary.failures.size} failure${if (summary.failures.size == 1) "" else "s"})" to AresError
                CommissioningSimulationStatus.NOT_AVAILABLE ->
                    "NO CONTROLLER RULES DECLARED" to AresGold
            }
            Surface(
                color = badgeColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, badgeColor),
            ) {
                Text(
                    badgeLabel,
                    color = badgeColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            OutlinedButton(
                onClick = {
                    val markdown = formatSubsystemTuningMarkdown(document)
                    runCatching {
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(markdown), null)
                        copiedMarkdown = true
                    }
                },
                border = BorderStroke(1.dp, if (copiedMarkdown) AresGreen else AresCyan),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (copiedMarkdown) AresGreen else AresCyan
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (copiedMarkdown) "Copied Pit Sheet!" else "Copy Tuning & Safety Markdown",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (summary.failures.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary.failures.forEach { failure ->
                    Text("• $failure", color = AresError, fontSize = 10.sp)
                }
            }
        }
        if (summary.performanceWarnings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary.performanceWarnings.forEach { warning ->
                    Text("• $warning", color = AresGold, fontSize = 10.sp)
                }
            }
        }

        if (document.controlLoops.isNotEmpty()) {
            Text(
                "Step-Response & Fail-Closed Commissioning Lab (${document.controlLoops.size} rule${if (document.controlLoops.size == 1) "" else "s"}):",
                color = AresTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            document.controlLoops.forEach { loop ->
                val isExpanded = expandedLoopId == loop.loopId
                Surface(
                    color = AresSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isExpanded) AresCyan else AresBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedLoopId = if (isExpanded) null else loop.loopId },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    loop.displayName,
                                    color = AresTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "${loop.strategy.name} · actuator: ${loop.actuatorId} · target: ${loop.targetFieldId ?: "none"}",
                                    color = AresTextTertiary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    onClick = { viewModel.selectLoop(loop.uid) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(13.dp), tint = AresCyan)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Inspect Rule", color = AresCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                Text(
                                    if (isExpanded) "Hide Lab ▲" else "Open Step-Response Lab ▼",
                                    color = AresCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        if (isExpanded) {
                            Surface(
                                color = AresSurfaceElevated,
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, AresBorder),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    SubsystemCommissioningLab(
                                        document = document,
                                        loop = loop,
                                    ) { updated ->
                                        viewModel.updateControlLoop(loop.loopId) { updated }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One deliberate fault a student can inject into the generated safety-contract preview. */
