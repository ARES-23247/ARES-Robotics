package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.ares.analytics.service.DashboardLayoutConfig
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.service.tuning.TuningParameterDeclaration
import com.ares.analytics.shared.ForensicsResponse
import com.ares.analytics.shared.WorkspaceConfig
import com.ares.analytics.viewmodel.DashboardState

/** Compose host that turns registry definitions into grid renderers for one immutable dashboard state. */
@Composable
fun DashboardWidgetHost(
    layout: DashboardLayoutConfig,
    services: DashboardWidgetServices,
    workspace: WorkspaceConfig,
    dashboardState: DashboardState,
    replayFrame: ReplayFrame?,
    replaySessionStartMs: Long,
    matches: List<MatchInfo>,
    tuningDeclarations: List<TuningParameterDeclaration>,
    reloadTrigger: Int,
    onForensicsCompleted: (ForensicsResponse) -> Unit,
    onSelectMatch: (MatchInfo, String) -> Unit,
    onSelectPrimarySession: (String?) -> Unit,
    onSelectCompareSession: (String?) -> Unit,
    onOpenKeybindings: () -> Unit,
    onUpdateProperties: (WidgetConfig, Map<String, String>) -> Unit,
    onLayoutChanged: (List<WidgetConfig>) -> Unit,
    onRemoveWidget: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderContext = DashboardWidgetRenderContext(
        services = services,
        workspace = workspace,
        dashboardState = dashboardState,
        layout = layout,
        replayFrame = replayFrame,
        replaySessionStartMs = replaySessionStartMs,
        matches = matches,
        tuningDeclarations = tuningDeclarations,
        reloadTrigger = reloadTrigger,
        onForensicsCompleted = onForensicsCompleted,
        onSelectMatch = onSelectMatch,
        onSelectPrimarySession = onSelectPrimarySession,
        onSelectCompareSession = onSelectCompareSession,
        onOpenKeybindings = onOpenKeybindings,
        onUpdateProperties = onUpdateProperties,
    )
    val builders: Map<String, @Composable (WidgetConfig, Modifier) -> Unit> =
        DashboardWidgetRegistry.definitions.associate { definition ->
            definition.type.serializedName to @Composable { widget: WidgetConfig, widgetModifier: Modifier ->
                definition.renderer(widget, renderContext.forDefinition(definition), widgetModifier)
            }
        }

    key(replayFrame?.sessionId ?: "live") {
        DashboardWidgetGrid(
            widgets = layout.widgets,
            isEditing = dashboardState.isLayoutEditing,
            onLayoutChanged = onLayoutChanged,
            onRemoveWidget = onRemoveWidget,
            widgetBuilders = builders,
            modifier = modifier,
        )
    }
}
