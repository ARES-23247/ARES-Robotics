package com.ares.analytics.viewmodel

import com.ares.analytics.service.*
import com.ares.analytics.service.project.RobotProjectTemplateService
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.mockito.Mockito.*
import org.mockito.stubbing.Answer
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.*

internal fun <T> suspendingAnswer(block: suspend () -> T): Answer<Any?> = Answer { call ->
    @Suppress("UNCHECKED_CAST")
    block.startCoroutineUninterceptedOrReturn(call.rawArguments.last() as Continuation<T>)
}

class OnboardingDetectionTest {
    @Test fun `late build tool check cannot replace a newer result`() = runBlocking {
        withFixture { f ->
            f.drain()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            `when`(f.environment.verifyJavaEnvironment()).thenAnswer(suspendingAnswer {
                entered.complete(Unit); release.await(); JavaEnvResult(false, "old missing JDK")
            }).thenReturn(JavaEnvResult(true, "openjdk version \"21.0.2\""))
            f.vm.handleIntent(OnboardingIntent.VerifyJava)
            withTimeout(5_000) { entered.await() }
            f.vm.handleIntent(OnboardingIntent.VerifyJava)
            f.drain()
            release.complete(Unit); f.drain()
            assertEquals(true, f.vm.state.value.javaEnvValid)
            assertEquals(21, f.vm.state.value.javaMajorVersion)
            assertFalse(f.vm.state.value.isVerifyingJava)
        }
    }

    @Test fun `unsupported canonical identity stays on project step and cannot bypass validation`() = runBlocking {
        withFixture { f ->
            java.io.File(f.paths[0], ".ares").mkdirs()
            java.io.File(f.paths[0], ".ares/project.json").writeText("{\"schemaVersion\":999}")
            f.vm.handleIntent(OnboardingIntent.SetProjectSetupMode(ProjectSetupMode.OPEN_EXISTING))
            f.vm.handleIntent(OnboardingIntent.UpdateProjectPath(f.paths[0]))
            withTimeout(5_000) { f.vm.state.first { it.fieldErrors.projectPath != null } }
            f.vm.handleIntent(OnboardingIntent.NextStep)
            f.drain()
            assertEquals(OnboardingStep.PROJECT, f.vm.state.value.currentStep)
            assertFalse(f.vm.state.value.isProjectReady)
            assertContains(f.vm.state.value.fieldErrors.projectPath!!, "unsupported schema")
        }
    }

    @Test fun `late detection cannot overwrite a newer project mode`() = runBlocking {
        withFixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val initialJobs = f.scope.coroutineContext[Job]!!.children.toSet()
            `when`(f.environment.readProjectIdentity(f.paths[0])).thenAnswer(suspendingAnswer {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                f.identity
            })
            f.vm.handleIntent(OnboardingIntent.SetProjectSetupMode(ProjectSetupMode.OPEN_EXISTING))
            f.vm.handleIntent(OnboardingIntent.UpdateProjectPath(f.paths[0]))
            try { withTimeout(5_000) { entered.await() } } catch (failure: TimeoutCancellationException) {
                throw AssertionError("Project reader did not start: ${f.vm.state.value}", failure)
            }
            val inspection = f.scope.coroutineContext[Job]!!.children.single { it !in initialJobs }
            f.vm.handleIntent(OnboardingIntent.SetProjectSetupMode(ProjectSetupMode.CREATE_NEW))
            f.drain()
            release.complete(Unit)
            withTimeout(5_000) { inspection.join() }
            assertEquals(ProjectSetupMode.CREATE_NEW, f.vm.state.value.projectSetupMode)
            assertEquals("", f.vm.state.value.robotId)
            assertEquals(OnboardingStep.PROJECT, f.vm.state.value.currentStep)
            assertNull(f.vm.state.value.projectDetectionMessage)
        }
    }

    @Test fun `rapid path edits inspect only the final directory`() = runBlocking {
        withFixture { f ->
            val reads = AtomicInteger()
            f.paths.forEach { path ->
                `when`(f.environment.readProjectIdentity(path)).thenAnswer { reads.incrementAndGet(); f.identity }
            }
            f.vm.handleIntent(OnboardingIntent.SetProjectSetupMode(ProjectSetupMode.OPEN_EXISTING))
            f.paths.forEach { f.vm.handleIntent(OnboardingIntent.UpdateProjectPath(it)) }
            f.drain()
            withTimeout(5_000) { f.vm.state.first { it.currentStep == OnboardingStep.ROBOT } }
            assertEquals(f.paths.last(), f.vm.state.value.projectPath)
            assertEquals(1, reads.get())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val f = Fixture()
        // Finish constructor-triggered verification before re-stubbing the same Mockito mock.
        f.drain()
        check(!f.vm.state.value.isVerifyingJava)
        try { block(f) } finally { f.scope.coroutineContext[Job]!!.cancelAndJoin(); f.dispatcher.close(); f.root.deleteRecursively() }
    }
    private class Fixture {
        val root = Files.createTempDirectory("ares-project-detection-").toFile()
        val paths = (1..3).map { root.resolve("robot-$it").apply { mkdirs() }.path }
        val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val environment = mock(EnvironmentService::class.java)
        val identity = DetectedProjectIdentity("23247", "2026", "old-robot", "Old Robot", "FTC")
        val vm: OnboardingViewModel
        init {
            val toolchain = mock(ManagedToolchainService::class.java)
            `when`(toolchain.installState).thenReturn(MutableStateFlow(ManagedToolchainInstallState.Idle))
            `when`(environment.getDefaultNt4Host(League.FTC, "23247")).thenReturn("192.168.43.1")
            runBlocking { `when`(environment.verifyJavaEnvironment()).thenReturn(JavaEnvResult(false, "Not installed")) }
            vm = OnboardingViewModel(environment, mock(SyncEngineService::class.java), mock(GoogleDriveService::class.java),
                RobotProjectTemplateService(cacheDirectory = root.resolve("cache")), toolchain, scope = scope, onConfigured = {})
        }
        suspend fun drain() = withContext(dispatcher) { }
    }
}
