package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresError
import com.ares.analytics.ui.theme.AresOnAccent
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel

@Composable
internal fun ControlsAiAssistantContent(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    var request by remember(state.selectedSchemeId) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = AresSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, AresBorder),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Describe your driver and operator control scheme.",
                    color = AresTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                )
                Text(
                    "Gemini can suggest bindings from your project's catalog actions, routines, and gamepad inputs. Nothing is applied until you review and confirm.",
                    color = AresTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }

        OutlinedTextField(
            value = request,
            onValueChange = { request = it.take(4_000) },
            label = { Text("What should these controls do?") },
            placeholder = { Text("e.g. Right trigger runs intake while held, left bumper raises elevator to High Basket...") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            enabled = !state.aiProposalInProgress,
        )

        Button(
            onClick = { viewModel.requestAiProposal(request) },
            enabled = request.isNotBlank() && !state.aiProposalInProgress && state.selectedScheme != null,
            colors = ButtonDefaults.buttonColors(containerColor = AresCyan, contentColor = AresOnAccent),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.aiProposalInProgress) "Preparing proposal…" else "Ask Gemini for binding suggestions")
        }

        state.aiProposalError?.let { Text(it, color = AresError, fontSize = 11.sp) }

        Surface(color = AresBackground.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp)) {
            Text(
                "Configure Gemini in Profile → Gemini assistance. Review source, event, timing, target, and arguments before applying.",
                color = AresTextTertiary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
