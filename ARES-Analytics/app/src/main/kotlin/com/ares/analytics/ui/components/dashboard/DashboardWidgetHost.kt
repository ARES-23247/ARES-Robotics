package com.ares.analytics.ui.components.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ares.analytics.service.DashboardLayoutConfig
import com.ares.analytics.service.MatchInfo
import com.ares.analytics.service.ReplayFrame
import com.ares.analytics.service.WidgetConfig
import com.ares.analytics.shared.models.ForensicsResponse
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.viewmodel.DashboardState
import com.areslib.tuning.TuningParameterDeclaration
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresCyan
import com.ares.analytics.ui.theme.AresTextSecondary

/** Compose host that turns registry definitions into grid renderers for one immutable dashboard state. */
@Composable
fun DashboardWidgetHost(
    layout: DashboardLayoutConfig,
    services: DashboardWidgetServices,
    workspace: WorkspaceConfig,
    isRobotLinkConnected: Boolean,
    xrpBrownoutThresholdVolts: Double?,
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
    controllerHealth: ControllerHealthObservation? = null,
) {
    // Presentation state only: expanding a card never rewrites the user's saved grid.
    var fullscreenWidgetId by remember(workspace.id, workspace.projectPath, dashboardState.currentRoleProfile) {
        mutableStateOf<String?>(null)
    }
    val fullscreenWidget = fullscreenWidgetId?.takeIf { id ->
        !dashboardState.isLayoutEditing && layout.widgets.any { it.id == id }
    }
    LaunchedEffect(fullscreenWidget) {
        if (fullscreenWidget == null) fullscreenWidgetId = null
    }
    val renderContext = DashboardWidgetRenderContext(
        services = services,
        workspace = workspace,
        isRobotLinkConnected = isRobotLinkConnected,
        xrpBrownoutThresholdVolts = xrpBrownoutThresholdVolts,
        dashboardState = dashboardState,
        layout = layout,
        replayFrame = replayFrame,
        controllerHealth = controllerHealth,
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
        fullscreenWidgetId = fullscreenWidget,
        onToggleWidgetFullscreen = { id ->
            fullscreenWidgetId = if (fullscreenWidgetId == id) null else id
        },
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
            fullscreenWidgetId = fullscreenWidget,
        )
    }
}

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WidgetFullscreenButton(isFullscreen: Boolean, onClick: () -> Unit) {
    val label = if (isFullscreen) "Restore to dashboard" else "Expand to full screen"
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        state = rememberTooltipState(),
        tooltip = { PlainTooltip { Text(label) } },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
            Icon(
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = label,
                tint = if (isFullscreen) AresCyan else AresTextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

