package com.ares.analytics.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.ares.analytics.ui.theme.AresTextPrimary
import com.ares.analytics.ui.theme.AresTextSecondary
import com.ares.analytics.service.GamepadService
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.controls.ControlsEditorPanel
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel
import java.io.File

/** Dependencies intentionally limited to the canonical robot-authoring feature family. */
internal data class RobotAuthoringFeatureScope(
    val robotStudio: RobotStudioViewModel,
    val drivebase: DrivebaseBuilderViewModel,
    val subsystem: SubsystemGeneratorViewModel,
    val superstructure: SuperstructureStudioViewModel,
    val pathPlanner: PathPlannerViewModel,
    val controls: ControlsEditorViewModel,
    val hardwareSetup: HardwareSetupViewModel,
    val projectIdentity: ProjectIdentityViewModel,
    val gamepads: GamepadService,
)

internal data class RobotAuthoringRouteActions(
    val navigate: (NavigationTarget) -> Unit,
    val runVerification: () -> Unit,
    val openInIde: () -> String,
    val createProject: () -> Unit,
    val chooseStandaloneExport: () -> File?,
    val exportStandaloneProject: suspend (File) -> String,
    val refreshRobotStudio: () -> Unit,
)

/**
 * Renders the authoring route family and collects controller state only while a controller-aware
 * route is visible. Returns false when the route belongs to another feature family.
 */
@Composable
internal fun RobotAuthoringRouteHost(
    route: NavigationTarget,
    scope: RobotAuthoringFeatureScope,
    config: WorkspaceConfig,
    hardwareStudioInitialTab: HardwareStudioTab,
    actions: RobotAuthoringRouteActions,
): Boolean {
    val authoringRoutes = setOf(
        NavigationTarget.ROBOT_STUDIO, NavigationTarget.CONTROLS, NavigationTarget.SUPERSTRUCTURE_STUDIO,
        NavigationTarget.HARDWARE_STUDIO, NavigationTarget.HARDWARE_SETUP, NavigationTarget.DRIVEBASE_BUILDER,
        NavigationTarget.SUBSYSTEM_GEN, NavigationTarget.PROJECT_IDENTITY,
    )
    if (route !in authoringRoutes) return false
    if (config.projectPath.isBlank()) {
        AuthoringProjectEmptyState(route, actions.createProject)
        return true
    }
    return when (route) {
    NavigationTarget.ROBOT_STUDIO -> {
        val controlsState by scope.controls.state.collectAsState()
        val gamepad1State by scope.gamepads.gamepad1State.collectAsState()
        val gamepad2State by scope.gamepads.gamepad2State.collectAsState()
        RobotStudioScreen(
            viewModel = scope.robotStudio,
            drivebaseViewModel = scope.drivebase,
            subsystemViewModel = scope.subsystem,
            superstructureViewModel = scope.superstructure,
            pathPlannerViewModel = scope.pathPlanner,
            controlsViewModel = scope.controls,
            controlsState = controlsState,
            gamepad1State = gamepad1State,
            gamepad2State = gamepad2State,
            hardwareSetupViewModel = scope.hardwareSetup,
            projectIdentityViewModel = scope.projectIdentity,
            config = config,
            onOpenPitDiagnostics = { actions.navigate(NavigationTarget.PIT_DIAGNOSTICS) },
            onRunVerification = actions.runVerification,
            onOpenInIde = actions.openInIde,
            onChooseStandaloneExport = actions.chooseStandaloneExport,
            onExportStandaloneProject = actions.exportStandaloneProject,
        )
        true
    }

    NavigationTarget.CONTROLS -> {
        val controlsState by scope.controls.state.collectAsState()
        val gamepad1State by scope.gamepads.gamepad1State.collectAsState()
        val gamepad2State by scope.gamepads.gamepad2State.collectAsState()
        ControlsEditorPanel(
            state = controlsState,
            viewModel = scope.controls,
            gamepad1State = gamepad1State,
            gamepad2State = gamepad2State,
            modifier = Modifier.fillMaxSize(),
        )
        true
    }

    NavigationTarget.SUPERSTRUCTURE_STUDIO -> {
        SuperstructureStudioScreen(scope.superstructure)
        true
    }

    NavigationTarget.HARDWARE_STUDIO,
    NavigationTarget.HARDWARE_SETUP,
    NavigationTarget.DRIVEBASE_BUILDER,
    NavigationTarget.SUBSYSTEM_GEN -> {
        HardwareStudioScreen(
            drivebaseViewModel = scope.drivebase,
            subsystemViewModel = scope.subsystem,
            hardwareSetupViewModel = scope.hardwareSetup,
            initialTab = when (route) {
                NavigationTarget.DRIVEBASE_BUILDER -> HardwareStudioTab.DRIVETRAIN
                NavigationTarget.SUBSYSTEM_GEN -> HardwareStudioTab.MECHANISMS
                NavigationTarget.HARDWARE_SETUP -> HardwareStudioTab.PORT_MAP
                else -> hardwareStudioInitialTab
            },
            onBackToStudio = {
                actions.refreshRobotStudio()
                actions.navigate(NavigationTarget.ROBOT_STUDIO)
            },
        )
        true
    }

    NavigationTarget.PROJECT_IDENTITY -> {
        ProjectIdentityScreen(
            viewModel = scope.projectIdentity,
            config = config,
            onBackToStudio = { actions.navigate(NavigationTarget.ROBOT_STUDIO) },
        )
        true
    }

        else -> false
    }
}

@Composable
private fun AuthoringProjectEmptyState(route: NavigationTarget, onCreateProject: () -> Unit) {
    val purpose = when (route) {
        NavigationTarget.ROBOT_STUDIO -> "Robot Studio organizes the canonical robot definition and its verification readiness."
        NavigationTarget.CONTROLS -> "TeleOp Controls maps gamepad inputs to actions declared by this robot project."
        NavigationTarget.SUPERSTRUCTURE_STUDIO -> "Superstructure Studio coordinates safe named states across several mechanisms."
        NavigationTarget.HARDWARE_SETUP -> "Port Map & Review records controller addresses and a separate physical review."
        NavigationTarget.DRIVEBASE_BUILDER -> "Drivebase Builder defines drivetrain geometry, localization, controls, and safety limits."
        NavigationTarget.SUBSYSTEM_GEN -> "Mechanism Builder defines one subsystem's hardware, states, actions, and safety behavior."
        NavigationTarget.PROJECT_IDENTITY -> "Project Identity defines the stable robot identity and physical footprint shared by every editor."
        else -> "Hardware Studio builds the canonical drivetrain, mechanisms, and port review for one robot project."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 620.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Create or select a robot project", color = AresTextPrimary)
            Text(purpose, color = AresTextSecondary)
            Button(onClick = onCreateProject) { Text("Create robot project") }
        }
    }
}
