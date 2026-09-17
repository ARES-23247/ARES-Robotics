package com.ares.analytics.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.domain.learning.LearningCheckpoint
import com.ares.analytics.domain.learning.LearningCheckpointEvidence
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresGreen
import com.ares.analytics.ui.theme.AresSurfaceElevated
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary

@Composable
internal fun CheckpointSection(
    checkpoints: List<LearningCheckpoint>,
    completedIds: Set<String>,
    checkpointReflections: Map<String, String>,
    onCheckpointChange: (LearningCheckpoint, Boolean) -> Unit,
    onReflectionRecorded: (LearningCheckpoint, String) -> Unit,
    highlightedCheckpointId: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Learning checkpoints", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "ARES records only narrow app facts automatically, such as a running simulator or a valid saved descriptor. Understanding, code quality, and physical safety decisions stay with people.",
            color = AresTextSecondary,
            fontSize = 12.sp,
        )
        checkpoints.forEachIndexed { index, checkpoint ->
            val completed = checkpoint.id in completedIds
            val automatic = checkpoint.evidence != LearningCheckpointEvidence.SELF_REPORTED
            val highlighted = checkpoint.id == highlightedCheckpointId
            var reflectionDraft by remember(checkpoint.id) {
                mutableStateOf(checkpointReflections[checkpoint.id].orEmpty())
            }
            Surface(
                color = if (highlighted) AresCyan.copy(alpha = .12f) else AresSurfaceElevated,
                border = BorderStroke(2.dp.takeIf { highlighted } ?: 1.dp, when {
                    highlighted -> AresCyan
                    completed -> AresGreen
                    else -> AresBorder
                }),
                shape = RoundedCornerShape(9.dp),
                modifier = Modifier.fillMaxWidth().semantics {
                    stateDescription = if (completed) "Recorded" else if (automatic) "Waiting for observable app evidence" else "Waiting for your reflection"
                },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(
                            if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (completed) AresGreen else AresTextTertiary,
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (highlighted) Text("Linked from the validation error", color = AresCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("${index + 1}. ${checkpoint.title}", color = AresTextPrimary, fontWeight = FontWeight.Bold)
                            Text(checkpoint.instruction, color = AresTextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                            Text(
                                when {
                                    completed && automatic -> "Observed by ARES: ${checkpoint.successText}"
                                    completed -> "Your reflection is recorded: ${checkpoint.successText}"
                                    automatic -> "Waiting for observable app evidence"
                                    else -> "Your reflection is not recorded yet"
                                },
                                color = if (completed) AresGreen else AresTextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (!automatic) {
                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                            OutlinedTextField(
                                value = reflectionDraft,
                                onValueChange = { reflectionDraft = it.take(4_000) },
                                label = { Text("Your evidence or explanation") },
                                placeholder = { Text("What did you observe, and what does it not prove?") },
                                minLines = 2,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedButton(
                                onClick = {
                                    if (completed) onCheckpointChange(checkpoint, false)
                                    else onReflectionRecorded(checkpoint, reflectionDraft)
                                },
                                enabled = completed || reflectionDraft.isNotBlank(),
                            ) {
                                Text(if (completed) "Remove reflection" else "Record reflection")
                            }
                        }
                    }
                }
            }
        }
    }
}
