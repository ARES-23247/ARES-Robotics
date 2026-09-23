package com.ares.analytics.ui.screens

import com.ares.analytics.service.EnvironmentService
import com.ares.analytics.service.EventApiService
import com.ares.analytics.service.KeybindingParserService
import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.ares.analytics.viewmodel.MainIntent
import com.ares.analytics.viewmodel.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceProjectSaveAuditTest {

    @Test
    fun `saveConfig with same workspace ID but changed project path releases prior ownership before exposing target`() = runBlocking {
        val tempDir = Files.createTempDirectory("ares-save-audit").toFile()
        val environment = EnvironmentService(tempDir.resolve("workspaces.json").path)
        val initialConfig = workspace(id = "ares-workspace-1", projectPath = tempDir.resolve("project-a").path)
        environment.saveWorkspaces(AppWorkspaces(initialConfig.id, listOf(initialConfig)))

        val transitionStarted = CompletableDeferred<Unit>()
        val allowTransition = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val vmScope = CoroutineScope(coroutineContext + job)
        val eventApiService = EventApiService()
        val viewModel = MainViewModel(
            environmentService = environment,
            eventApiService = eventApiService,
            keybindingParserService = KeybindingParserService(),
            scope = vmScope,
            beforeWorkspaceChange = {
                transitionStarted.complete(Unit)
                allowTransition.await()
            },
        )

        try {
            withTimeout(5_000) { viewModel.state.first { it.config?.projectPath == initialConfig.projectPath } }
            assertEquals(initialConfig.projectPath, viewModel.state.value.config?.projectPath)

            // Save modified config with the same ID but a changed project directory.
            val targetProjectPath = tempDir.resolve("project-b").path
            val updatedConfig = initialConfig.copy(projectPath = targetProjectPath)
            viewModel.onIntent(MainIntent.SaveConfig(updatedConfig))

            val published = vmScope.async {
                viewModel.state.first { it.config?.projectPath == targetProjectPath }
            }
            try {
                withTimeout(5_000) {
                    select<Unit> {
                        transitionStarted.onAwait { }
                        published.onAwait { }
                    }
                }
            } finally {
                published.cancelAndJoin()
            }

            // On baseline, beforeWorkspaceChange was skipped because id did not differ.
            // Assertion fails immediately on baseline without timing out.
            assertTrue(transitionStarted.isCompleted, "beforeWorkspaceChange barrier must be triggered on project path change")

            // Prior to completing release: verify both in-memory state and persisted environment still reflect initialConfig.
            assertEquals(initialConfig.projectPath, viewModel.state.value.config?.projectPath)
            val persistedBefore = environment.loadWorkspaces()
            assertEquals(initialConfig.projectPath, persistedBefore.workspaces.find { it.id == initialConfig.id }?.projectPath)

            // Allow release to finish and advance dispatcher.
            allowTransition.complete(Unit)
            withTimeout(5_000) { viewModel.state.first { it.config?.projectPath == targetProjectPath } }

            // Target config is now exposed in state and persisted in environment.
            assertEquals(targetProjectPath, viewModel.state.value.config?.projectPath)
            val persistedAfter = environment.loadWorkspaces()
            assertEquals(targetProjectPath, persistedAfter.workspaces.find { it.id == initialConfig.id }?.projectPath)
        } finally {
            job.cancelAndJoin()
            eventApiService.close()
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `saveConfig with same workspace ID and same project path preserves workspace continuity without transition barrier`() = runBlocking {
        val tempDir = Files.createTempDirectory("ares-save-pref-audit").toFile()
        val environment = EnvironmentService(tempDir.resolve("workspaces.json").path)
        val initialConfig = workspace(id = "ares-workspace-1", projectPath = tempDir.resolve("project-a").path)
        environment.saveWorkspaces(AppWorkspaces(initialConfig.id, listOf(initialConfig)))

        var transitionTriggered = false
        val job = SupervisorJob()
        val vmScope = CoroutineScope(coroutineContext + job)
        val eventApiService = EventApiService()
        val viewModel = MainViewModel(
            environmentService = environment,
            eventApiService = eventApiService,
            keybindingParserService = KeybindingParserService(),
            scope = vmScope,
            beforeWorkspaceChange = {
                transitionTriggered = true
            },
        )

        try {
            withTimeout(5_000) { viewModel.state.first { it.config?.id == initialConfig.id } }
            assertEquals(initialConfig.id, viewModel.state.value.config?.id)

            // In-workspace preference change (colorblindMode = true, preserving id and projectPath).
            val prefUpdatedConfig = initialConfig.copy(colorblindMode = true)
            viewModel.onIntent(MainIntent.SaveConfig(prefUpdatedConfig))

            withTimeout(5_000) { viewModel.state.first { it.config?.colorblindMode == true } }

            assertFalse(transitionTriggered, "In-workspace preference change must not trigger beforeWorkspaceChange")
            assertEquals(initialConfig.projectPath, viewModel.state.value.config?.projectPath)
            assertTrue(viewModel.state.value.config?.colorblindMode == true)

            val persisted = environment.loadWorkspaces()
            val persistedConfig = persisted.workspaces.find { it.id == initialConfig.id }
            assertEquals(initialConfig.projectPath, persistedConfig?.projectPath)
            assertTrue(persistedConfig?.colorblindMode == true)
        } finally {
            job.cancelAndJoin()
            eventApiService.close()
            tempDir.deleteRecursively()
        }
    }

    private fun workspace(id: String, projectPath: String): WorkspaceConfig = WorkspaceConfig(
        id = id,
        teamId = "23247",
        seasonId = "2026",
        robotId = id,
        projectPath = projectPath,
        league = League.FTC,
    )
}
