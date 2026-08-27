package com.ares.analytics.ui.components.routine

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresBackground
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresTextSecondary

internal enum class RoutineBuilderPane {
    ROUTINE,
    FIELD_PREVIEW,
}

internal data class RoutineBuilderLayoutPresentation(
    val stackHeaderActions: Boolean,
    val useTabbedBody: Boolean,
)

internal fun routineBuilderLayoutPresentation(
    availableWidthDp: Float,
    largeText: Boolean,
): RoutineBuilderLayoutPresentation = RoutineBuilderLayoutPresentation(
    stackHeaderActions = availableWidthDp < if (largeText) 1_350f else 1_200f,
    useTabbedBody = availableWidthDp < if (largeText) 1_150f else 1_000f,
)

/**
 * Preserves both authoring surfaces at every supported width. Wide workspaces show the editor and
 * field together; compact workspaces give the selected surface the full center canvas.
 */
@Composable
internal fun RoutineBuilderResponsiveBody(
    presentation: RoutineBuilderLayoutPresentation,
    selectedPane: RoutineBuilderPane,
    onPaneSelected: (RoutineBuilderPane) -> Unit,
    editor: @Composable (Modifier) -> Unit,
    fieldPreview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!presentation.useTabbedBody) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            editor(Modifier.weight(1f).fillMaxHeight())
            fieldPreview(Modifier.weight(1f).fillMaxHeight())
        }
        return
    }

    Column(modifier = modifier) {
        PrimaryTabRow(
            selectedTabIndex = selectedPane.ordinal,
            containerColor = AresBackground,
            contentColor = AresCyan,
        ) {
            RoutineBuilderPane.entries.forEach { pane ->
                Tab(
                    selected = selectedPane == pane,
                    onClick = { onPaneSelected(pane) },
                    text = {
                        Text(
                            text = when (pane) {
                                RoutineBuilderPane.ROUTINE -> "Routine steps"
                                RoutineBuilderPane.FIELD_PREVIEW -> "Field preview"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedPane == pane) AresCyan else AresTextSecondary,
                        )
                    },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedPane) {
                RoutineBuilderPane.ROUTINE -> editor(Modifier.fillMaxSize())
                RoutineBuilderPane.FIELD_PREVIEW -> fieldPreview(Modifier.fillMaxSize())
            }
        }
    }
}
