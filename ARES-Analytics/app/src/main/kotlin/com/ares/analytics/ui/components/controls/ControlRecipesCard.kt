package com.ares.analytics.ui.components.controls

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ares.analytics.ui.theme.AresBorder
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresSurface
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.ui.theme.AresTextTertiary
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.areslib.controls.ControllerControlTypeDocument

internal data class ControlRecipeAvailability(
    val canCreateChord: Boolean,
    val canCreateMacro: Boolean,
    val guidance: String,
)

internal fun controlRecipeAvailability(state: ControlsEditorState): ControlRecipeAvailability {
    if (state.draftHasUnappliedChanges) {
        return ControlRecipeAvailability(false, false, "Apply or discard the open binding draft first.")
    }
    val selected = state.selectedControl
        ?: return ControlRecipeAvailability(false, false, "Select a controller button to start a shortcut.")
    if (selected.type != ControllerControlTypeDocument.BUTTON) {
        return ControlRecipeAvailability(false, false, "Select a button; analog axes use normal bindings.")
    }
    return ControlRecipeAvailability(
        canCreateChord = true,
        canCreateMacro = state.routineIds.isNotEmpty(),
        guidance = if (state.routineIds.isEmpty()) {
            "Chords are ready. Create a reusable routine in Routines & Auto to enable macros."
        } else {
            "Start with ${selected.displayName}; every recipe opens a normal reviewed binding draft."
        },
    )
}

/** Novice-facing shortcuts into the existing chord and trigger-neutral routine contracts. */
@Composable
internal fun ControlRecipesCard(state: ControlsEditorState, viewModel: ControlsEditorViewModel) {
    val availability = controlRecipeAvailability(state)
    Surface(
        color = AresSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, AresBorder),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Chords & macros", color = AresTextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "A chord combines two or more buttons. A macro runs a reusable routine, so the same behavior can also be used in autonomous.",
                color = AresTextSecondary,
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = viewModel::createChordBinding,
                    enabled = availability.canCreateChord,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("New chord", fontSize = 10.sp)
                }
                OutlinedButton(
                    onClick = viewModel::createRoutineMacroBinding,
                    enabled = availability.canCreateMacro,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Bind routine macro", fontSize = 10.sp)
                }
            }
            Text(
                availability.guidance,
                color = if (availability.canCreateChord) AresCyan else AresTextTertiary,
                fontSize = 10.sp,
            )
        }
    }
}
