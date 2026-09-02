package com.ares.analytics.ui.components.subsystems

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.components.core.AresDialog
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.*
import com.areslib.subsystem.SubsystemPlatform
import com.areslib.subsystem.SubsystemTemplate
import com.areslib.subsystem.supportsPlatform

/**
 * Modal dialog for reviewing an AI-generated subsystem proposal and its state/IO diff.
 */
@Composable
fun SubsystemAiProposalDialog(
    review: SubsystemAiProposalReview,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    AresDialog(
        title = "Review Gemini's Subsystem Proposal",
        onDismiss = onDismiss,
        confirmText = "Apply Proposal",
        onConfirm = onApply,
        isConfirmEnabled = review.canApply,
        dismissText = "Dismiss",
        scrollable = true,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(review.proposal.summary, color = AresTextPrimary, fontWeight = FontWeight.SemiBold)
            review.proposal.explanations.forEach { explanation ->
                Text("• $explanation", color = AresTextSecondary, fontSize = 12.sp)
            }
            if (review.problems.isNotEmpty()) {
                Text("Validation Review", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                review.problems.forEach { problem ->
                    Text(
                        "${if (problem.severity == SubsystemProblemSeverity.ERROR) "Blocking" else "Warning"}: ${problem.message}",
                        color = if (problem.severity == SubsystemProblemSeverity.ERROR) AresError else AresGold,
                        fontSize = 12.sp,
                    )
                }
            }
            if (review.diff.isNotEmpty()) {
                Text("Proposed Form Changes", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                Column(
                    Modifier.fillMaxWidth().background(AresBackground, RoundedCornerShape(6.dp))
                        .border(1.dp, AresBorder, RoundedCornerShape(6.dp)).padding(8.dp),
                ) {
                    review.diff.forEach { line ->
                        val color = when (line.kind) {
                            SubsystemDiffLineKind.ADDED -> AresGreen
                            SubsystemDiffLineKind.REMOVED -> AresRed
                            SubsystemDiffLineKind.CONTEXT -> AresTextSecondary
                        }
                        Text(line.text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

/**
 * Modal dialog for selecting a starter archetype template for a new subsystem.
 */
@Composable
fun SubsystemTemplatePickerDialog(
    currentTemplate: SubsystemTemplate,
    platform: SubsystemPlatform,
    onApplyTemplate: (SubsystemTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingTemplate by remember(currentTemplate) { mutableStateOf(currentTemplate) }
    AresDialog(
        title = "Select Subsystem Starter Template",
        onDismiss = onDismiss,
        confirmText = "Replace draft with selected starter",
        onConfirm = { onApplyTemplate(pendingTemplate) },
        isConfirmEnabled = pendingTemplate != currentTemplate,
        dismissText = "Cancel",
        scrollable = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Choose an archetype to preview. Applying it replaces the current draft, but you can immediately Undo after closing this dialog.",
                color = AresTextSecondary,
                fontSize = 12.sp,
            )
            subsystemTemplateOptions
                .filter { it.template.supportsPlatform(platform) }
                .groupBy { it.category }
                .forEach { (category, options) ->
                    Text(category.uppercase(), color = AresTextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    options.forEach { tplOption ->
                        val isSelected = pendingTemplate == tplOption.template
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) AresCyan else AresBorder,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable {
                                    pendingTemplate = tplOption.template
                                },
                            color = if (isSelected) AresCyan.copy(alpha = 0.08f) else AresSurfaceElevated,
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        tplOption.label,
                                        color = if (isSelected) AresCyan else AresTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                    Text(
                                        tplOption.description,
                                        color = AresTextSecondary,
                                        fontSize = 11.sp,
                                    )
                                    if (tplOption.beginnerRecommended) {
                                        Text("Recommended starting point", color = AresGreen, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (isSelected) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = AresCyan.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            "ACTIVE",
                                            color = AresCyan,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }
}
