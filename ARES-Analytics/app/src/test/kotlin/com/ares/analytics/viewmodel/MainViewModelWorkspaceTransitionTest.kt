package com.ares.analytics.viewmodel

import com.ares.analytics.service.EnvironmentService
import com.ares.analytics.service.EventApiService
import com.ares.analytics.service.KeybindingParserService
import com.ares.analytics.shared.models.AppWorkspaces
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MainViewModelWorkspaceTransitionTest {
    @Test
    fun `workspace selection joins prior runtime ownership before exposing target`() = runBlocking {
        val directory = Files.createTempDirectory("ares-workspace-transition").toFile()
        val environment = EnvironmentService(directory.resolve("workspaces.json").path)
        val first = workspace("first", directory.resolve("first").path)
        val second = workspace("second", directory.resolve("second").path)
        environment.saveWorkspaces(AppWorkspaces(first.id, listOf(first, second)))
        val transitionStarted = CompletableDeferred<Unit>()
        val allowTransition = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val viewModel = MainViewModel(
            environmentService = environment,
            eventApiService = EventApiService(),
            keybindingParserService = KeybindingParserService(),
            scope = scope,
            beforeWorkspaceChange = {
                transitionStarted.complete(Unit)
                allowTransition.await()
            },
        )
        try {
            awaitConfig(viewModel, first.id)
            viewModel.onIntent(MainIntent.SelectWorkspace(second.id))
            withTimeout(5_000L) { transitionStarted.await() }

            assertEquals(first.id, viewModel.state.value.config?.id)

            allowTransition.complete(Unit)
            awaitConfig(viewModel, second.id)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private suspend fun awaitConfig(viewModel: MainViewModel, id: String) {
        withTimeout(5_000L) {
            while (viewModel.state.value.config?.id != id) delay(10L)
        }
    }

    private fun workspace(id: String, projectPath: String) = WorkspaceConfig(
        id = id,
        teamId = "99999",
        seasonId = "2026",
        robotId = id,
        projectPath = projectPath,
        league = League.FTC,
    )
}
