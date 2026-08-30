package com.ares.analytics.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ares.analytics.ui.help.AcademyRuntimeSnapshot
import com.ares.analytics.ui.help.toAcademyAutonomousSnapshot
import com.ares.analytics.ui.help.toAcademyControlsSnapshot
import com.ares.analytics.ui.help.toAcademyGraduationSnapshot
import com.ares.analytics.ui.help.toAcademyRunAnalysisSnapshot
import com.ares.analytics.ui.help.toAcademySubsystemSnapshot
import com.ares.analytics.ui.help.toAcademySuperstructureSnapshot
import com.ares.analytics.ui.help.toAcademyTuningSnapshot
import com.ares.analytics.viewmodel.PathPlannerViewModel
import com.ares.analytics.viewmodel.SubsystemGeneratorViewModel
import com.ares.analytics.viewmodel.TuningViewModel
import com.ares.analytics.viewmodel.controls.ControlsEditorViewModel
import com.ares.analytics.viewmodel.robotstudio.RobotStudioViewModel
import com.ares.analytics.viewmodel.runanalysis.GuidedRunAnalysisViewModel
import com.ares.analytics.viewmodel.superstructure.SuperstructureStudioViewModel

/** Feature owners needed to project current authoring evidence into Robot Academy. */
internal data class AcademyRuntimeFeatureScope(
    val subsystem: SubsystemGeneratorViewModel,
    val controls: ControlsEditorViewModel,
    val tuning: TuningViewModel,
    val superstructure: SuperstructureStudioViewModel,
    val autonomous: PathPlannerViewModel,
    val runAnalysis: GuidedRunAnalysisViewModel,
    val graduation: RobotStudioViewModel,
)

/** Low-frequency shell facts that are not owned by an Academy feature ViewModel. */
internal data class AcademyRuntimeEnvironment(
    val isLocalSimulatorSelected: Boolean,
    val isSimulatorRunning: Boolean,
    val isLocalSimulatorOnline: Boolean,
    val isNt4Connected: Boolean,
)

/**
 * Collects Academy evidence only while an Academy screen or active lesson observer is composed.
 * Keeping these subscriptions out of [MainScreen] prevents hidden authoring features from
 * invalidating the entire desktop shell.
 */
@Composable
internal fun AcademyRuntimeHost(
    scope: AcademyRuntimeFeatureScope,
    environment: AcademyRuntimeEnvironment,
    content: @Composable (AcademyRuntimeSnapshot) -> Unit,
) {
    val subsystem by scope.subsystem.state.collectAsState()
    val controls by scope.controls.state.collectAsState()
    val tuning by scope.tuning.state.collectAsState()
    val superstructure by scope.superstructure.state.collectAsState()
    val autonomous by scope.autonomous.state.collectAsState()
    val runAnalysis by scope.runAnalysis.state.collectAsState()
    val graduation by scope.graduation.state.collectAsState()

    content(
        AcademyRuntimeSnapshot(
            isAvailable = true,
            isLocalSimulatorSelected = environment.isLocalSimulatorSelected,
            isSimulatorRunning = environment.isSimulatorRunning,
            isLocalSimulatorOnline = environment.isLocalSimulatorOnline,
            isNt4Connected = environment.isNt4Connected,
            subsystem = subsystem.toAcademySubsystemSnapshot(),
            controls = controls.toAcademyControlsSnapshot(),
            tuning = tuning.toAcademyTuningSnapshot(),
            superstructure = superstructure.toAcademySuperstructureSnapshot(),
            autonomous = autonomous.toAcademyAutonomousSnapshot(),
            runAnalysis = runAnalysis.toAcademyRunAnalysisSnapshot(),
            graduation = graduation.toAcademyGraduationSnapshot(),
        ),
    )
}
