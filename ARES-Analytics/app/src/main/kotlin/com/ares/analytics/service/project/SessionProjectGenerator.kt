package com.ares.analytics.service.project

import com.ares.analytics.service.AresGenerationState
import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.service.ProjectBuildService
import com.ares.analytics.shared.models.League
import com.areslib.codegen.SubsystemStarterPlan
import com.areslib.controls.ControllerInputPlatform
import kotlinx.coroutines.flow.StateFlow

/**
 * Authoring-facing generation boundary.
 *
 * Feature ViewModels never receive the build service. Canonical generation is authorized by the
 * same effective project snapshot used by readiness and build execution. Starter preview/apply is
 * still delegated to the build service, but only after the selected project can be loaded as a
 * stable session snapshot for the requested league.
 */
class SessionProjectGenerator(
    private val session: ProjectSession,
    private val executionCoordinator: ProjectExecutionCoordinator,
    private val projectBuild: ProjectBuildService,
) : AresProjectGenerator {
    override val aresGenerationState: StateFlow<AresGenerationState>
        get() = projectBuild.aresGenerationState

    override fun generateAresProject(projectPath: String, league: League) {
        val workspace = authoringWorkspace(projectPath, league)
        val decision = executionCoordinator.execute(workspace, ProjectExecutionCommand.GENERATE)
        if (!decision.accepted) projectBuild.rejectAresGeneration(decision.message)
    }

    override fun previewSubsystemStarters(projectPath: String, league: League): SubsystemStarterPlan {
        requireStableSelection(projectPath, league)
        return projectBuild.previewSubsystemStarters(projectPath, league)
    }

    override fun applySubsystemStarters(projectPath: String, league: League, confirmationToken: String?) {
        requireStableSelection(projectPath, league)
        projectBuild.applySubsystemStarters(projectPath, league, confirmationToken)
    }

    private fun requireStableSelection(projectPath: String, league: League) {
        session.snapshot(
            projectPath,
            when (league) {
                League.FTC -> ControllerInputPlatform.FTC
                League.FRC -> ControllerInputPlatform.FRC
                League.XRP -> ControllerInputPlatform.XRP
            },
            forceReload = true,
        )
    }

    private fun authoringWorkspace(projectPath: String, league: League) = com.ares.analytics.shared.models.WorkspaceConfig(
        teamId = "authoring",
        seasonId = "authoring",
        robotId = "authoring",
        projectPath = projectPath,
        league = league,
    )
}
