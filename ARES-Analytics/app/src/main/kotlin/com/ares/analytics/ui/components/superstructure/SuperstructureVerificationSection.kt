package com.ares.analytics.ui.components.superstructure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.areslib.superstructure.SuperstructureDocument
import com.ares.analytics.ui.theme.*
import com.ares.analytics.viewmodel.superstructure.*

@Composable
fun SuperstructureVerificationSection(
    state: SuperstructureStudioState,
    draft: SuperstructureDocument,
    viewModel: SuperstructureStudioViewModel,
    modifier: Modifier = Modifier,
) {
    val review = state.review

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Validation Diagnostics Panel (if errors or warnings exist)
        if (state.validationErrors.isNotEmpty() || state.validationWarnings.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (state.validationErrors.isNotEmpty()) AresError.copy(alpha = 0.10f) else AresGold.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, if (state.validationErrors.isNotEmpty()) AresError else AresGold),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (state.validationErrors.isNotEmpty()) "VALIDATION ISSUES (${state.validationErrors.size})" else "VALIDATION NOTICES (${state.validationWarnings.size})",
                        color = if (state.validationErrors.isNotEmpty()) AresError else AresGold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                    state.validationErrors.forEach { err ->
                        Text("• $err", color = AresError, fontSize = 10.sp)
                    }
                    state.validationWarnings.forEach { warn ->
                        Text("• $warn", color = AresGold, fontSize = 10.sp)
                    }
                }
            }
        }

        // Deterministic Simulation Lab & Trace Panel
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DETERMINISTIC SIMULATION TRACE", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                val preview = state.preview
                if (preview != null) {
                    Surface(
                        color = AresSurfaceElevated,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, AresBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Current Posture: ${preview.currentStateId}", color = AresCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Status: ${if (preview.isFaulted) "FAULTED (${preview.faultReason ?: "Unknown"})" else if (preview.isEnabled) "ENABLED" else "DISABLED"}", color = if (preview.isFaulted) AresError else AresGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text("State evaluator runs deterministically on every edit against cached sensor and action inputs.", color = AresTextTertiary, fontSize = 10.sp)
                }
            }
        }

        // Structured Diff & Code Generation Review
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AresSurface,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SAVE & CODE GENERATION REVIEW", color = AresTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)

                if (review == null) {
                    if (state.dirty) {
                        Text("Select Review Changes to validate the draft against ARES rules and generate a structured diff.", color = AresTextSecondary, fontSize = 11.sp)
                        Button(
                            onClick = viewModel::reviewSave,
                            enabled = state.canSave,
                            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
                        ) {
                            Text("Create Reviewed Diff", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AresGreen.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, AresGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "✓ Saved · The canonical superstructure document matches this configuration.",
                                color = AresGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                } else {
                    Text("The following changes will be written to .ares/superstructures/${draft.superstructureId}.aressuperstructure:", color = AresTextPrimary, fontSize = 11.sp)
                    review.summary.forEach { line ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = AresSurfaceElevated,
                            border = BorderStroke(1.dp, AresBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(line, color = AresTextPrimary, fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.confirmSave(review.confirmationToken) },
                            colors = ButtonDefaults.buttonColors(containerColor = AresGreen, contentColor = AresOnAccent),
                        ) {
                            Text("Confirm & Save", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = { viewModel.reload() }) {
                            Text("Discard Changes")
                        }
                    }
                }
            }
        }
    }
}
