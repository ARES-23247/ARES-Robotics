package com.ares.analytics.viewmodel

import com.ares.analytics.service.*
import com.ares.analytics.service.project.RobotProjectTemplateService
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.project.AresProjectMetadataCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.mockito.Mockito.*
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class OnboardingSubmissionTest {
    @Test fun `creation trims the same identity values accepted by validation`() = runBlocking {
        withFixture { fixture ->
            val vm = fixture.viewModel(NewProjectHistoryInitializer.NONE)
            fixture.prepare(vm)
            vm.handleIntent(OnboardingIntent.UpdateTeamId(" 23247 "))
            vm.handleIntent(OnboardingIntent.UpdateSeasonId(" 2026 "))
            vm.handleIntent(OnboardingIntent.UpdateRobotId(" test-robot "))
            fixture.drainIntents()
            vm.handleIntent(OnboardingIntent.SubmitConfig)
            withTimeout(15_000) { vm.state.first { it.saveSuccess || it.errorMessage != null } }
            assertTrue(vm.state.value.saveSuccess, vm.state.value.errorMessage)
            val identity = AresProjectMetadataCodec.decode(fixture.directory.resolve("robot/.ares/project.json").readText()).identity
            assertEquals("23247", identity.teamId)
            assertEquals("2026", identity.seasonId)
            assertEquals("test-robot", identity.robotId)
        }
    }

    @Test
    fun `switching from an example back to create restores the generic starter identity`() = runBlocking {
        withFixture { fixture ->
            val viewModel = fixture.viewModel(NewProjectHistoryInitializer.NONE)
            for (example in listOf(ProjectSetupMode.EXPLORE_LIGHTBOT, ProjectSetupMode.EXPLORE_BIOBUZZ)) {
                viewModel.handleIntent(OnboardingIntent.SetProjectSetupMode(example))
                fixture.drainIntents()
                viewModel.handleIntent(OnboardingIntent.SetProjectSetupMode(ProjectSetupMode.CREATE_NEW))
                fixture.drainIntents()
                val generic = RobotProjectTemplateService().templateFor(League.FTC)
                assertEquals(generic.displayName, viewModel.state.value.projectTemplateName)
                assertEquals(generic.artifactVersion, viewModel.state.value.projectTemplateVersion)
            }
        }
    }

    @Test
    fun `in-flight creation rejects edits and repeated submits and completes once`() = runBlocking {
        withFixture { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val creations = AtomicInteger()
            val viewModel = fixture.viewModel(NewProjectHistoryInitializer {
                creations.incrementAndGet()
                entered.complete(Unit)
                release.await()
            })
            fixture.prepare(viewModel)
            viewModel.handleIntent(OnboardingIntent.SubmitConfig)
            try { withTimeout(15_000) { entered.await() } } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Creation did not reach history hook: ${viewModel.state.value}", failure)
            }
            assertTrue(viewModel.state.value.isSaving)
            viewModel.handleIntent(OnboardingIntent.UpdateRobotId("changed-during-save"))
            repeat(3) { viewModel.handleIntent(OnboardingIntent.SubmitConfig) }
            // A later ordered intent is also rejected while the staged creation is suspended.
            fixture.drainIntents()
            assertEquals("test-robot", viewModel.state.value.robotId)
            assertEquals(1, creations.get())
            release.complete(Unit)
            withTimeout(15_000) { viewModel.state.first { it.saveSuccess || it.errorMessage != null } }
            fixture.drainIntents()
            viewModel.handleIntent(OnboardingIntent.SubmitConfig)
            fixture.drainIntents()
            assertEquals(1, fixture.configured.get())
            assertNull(viewModel.state.value.errorMessage)
            assertTrue(fixture.directory.resolve("robot/.ares").isDirectory)
            val metadata = AresProjectMetadataCodec.decode(fixture.directory.resolve("robot/.ares/project.json").readText())
            assertEquals("test-robot", metadata.identity.displayName)
        }
    }

    @Test
    fun `failed staging permits a clean retry without publishing a partial project`() = runBlocking {
        withFixture { fixture ->
            val attempts = AtomicInteger()
            val viewModel = fixture.viewModel(NewProjectHistoryInitializer {
                if (attempts.incrementAndGet() == 1) error("history initialization failed")
            })
            fixture.prepare(viewModel)
            viewModel.handleIntent(OnboardingIntent.SubmitConfig)
            withTimeout(15_000) { viewModel.state.first { it.errorMessage != null } }
            assertFalse(viewModel.state.value.isSaving)
            assertFalse(fixture.directory.resolve("robot").exists())
            assertEquals(0, fixture.configured.get())
            viewModel.handleIntent(OnboardingIntent.SubmitConfig)
            withTimeout(15_000) { viewModel.state.first { it.saveSuccess || it.errorMessage != null } }
            assertTrue(viewModel.state.value.saveSuccess, viewModel.state.value.toString())
            fixture.drainIntents()
            assertEquals(2, attempts.get())
            assertEquals(1, fixture.configured.get())
            assertNull(viewModel.state.value.errorMessage)
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        val directory = Files.createTempDirectory("ares-onboarding-submit-").toFile()
        // One ordered UI dispatcher, with real template extraction on Dispatchers.IO.
        private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val configured = AtomicInteger()

        suspend fun viewModel(history: NewProjectHistoryInitializer): OnboardingViewModel {
            val environment = mock(EnvironmentService::class.java)
            `when`(environment.verifyJavaEnvironment()).thenReturn(JavaEnvResult(false, "Not installed"))
            `when`(environment.getDefaultNt4Host(League.XRP, "")).thenReturn("127.0.0.1")
            val toolchain = mock(ManagedToolchainService::class.java)
            `when`(toolchain.installState).thenReturn(MutableStateFlow(ManagedToolchainInstallState.Idle))
            return OnboardingViewModel(
                environment, mock(SyncEngineService::class.java), mock(GoogleDriveService::class.java),
                RobotProjectTemplateService(cacheDirectory = directory.resolve("cache")), toolchain,
                history, scope,
            ) { _: WorkspaceConfig -> configured.incrementAndGet() }
        }

        suspend fun prepare(viewModel: OnboardingViewModel) {
            listOf(
                OnboardingIntent.UpdateLeague(League.XRP),
                OnboardingIntent.UpdateProjectParentPath(directory.path),
                OnboardingIntent.UpdateProjectFolderName("robot"),
                OnboardingIntent.UpdateTeamId("23247"),
                OnboardingIntent.UpdateSeasonId("2026"),
                OnboardingIntent.UpdateRobotId("test-robot"),
            ).forEach(viewModel::handleIntent)
            drainIntents()
        }

        suspend fun drainIntents() = withContext(dispatcher) { }

        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            dispatcher.close()
            directory.deleteRecursively()
        }
    }
}
