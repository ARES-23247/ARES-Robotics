package com.ares.analytics.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ares.analytics.service.GamepadService
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.ui.components.NavigationTarget
import com.ares.analytics.ui.components.controls.ControlsEditorPanel
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorState
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.drivebase.DrivebaseBuilderViewModel
import com.ares.analytics.viewmodel.hardware.HardwareSetupViewModel
import com.ares.analytics.viewmodel.project.ProjectIdentityViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel

/** Dependencies intentionally limited to the canonical robot-authoring feature family. */
internal data class RobotAuthoringFeatureScope(
    val robotStudio: RobotStudioViewModel,
    val drivebase: DrivebaseBuilderViewModel,
    val subsystem: SubsystemGeneratorViewModel,
    val superstructure: SuperstructureStudioViewModel,
    val pathPlanner: PathPlannerViewModel,
    val controls: ControlsEditorViewModel,
    val controlsState: ControlsEditorState,
    val hardwareSetup: HardwareSetupViewModel,
    val projectIdentity: ProjectIdentityViewModel,
    val gamepads: GamepadService,
)

internal data class RobotAuthoringRouteActions(
    val navigate: (NavigationTarget) -> Unit,
    val runVerification: () -> Unit,
    val openInIde: () -> String,
    val createStandaloneProject: () -> Unit,
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
): Boolean = when (route) {
    NavigationTarget.ROBOT_STUDIO -> {
        val gamepad1State by scope.gamepads.gamepad1State.collectAsState()
        val gamepad2State by scope.gamepads.gamepad2State.collectAsState()
        RobotStudioScreen(
            viewModel = scope.robotStudio,
            drivebaseViewModel = scope.drivebase,
            subsystemViewModel = scope.subsystem,
            superstructureViewModel = scope.superstructure,
            pathPlannerViewModel = scope.pathPlanner,
            controlsViewModel = scope.controls,
            controlsState = scope.controlsState,
            gamepad1State = gamepad1State,
            gamepad2State = gamepad2State,
            hardwareSetupViewModel = scope.hardwareSetup,
            projectIdentityViewModel = scope.projectIdentity,
            config = config,
            onOpenPitDiagnostics = { actions.navigate(NavigationTarget.PIT_DIAGNOSTICS) },
            onRunVerification = actions.runVerification,
            onOpenInIde = actions.openInIde,
            onCreateStandaloneProject = actions.createStandaloneProject,
        )
        true
    }

    NavigationTarget.CONTROLS -> {
        val gamepad1State by scope.gamepads.gamepad1State.collectAsState()
        val gamepad2State by scope.gamepads.gamepad2State.collectAsState()
        ControlsEditorPanel(
            state = scope.controlsState,
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
