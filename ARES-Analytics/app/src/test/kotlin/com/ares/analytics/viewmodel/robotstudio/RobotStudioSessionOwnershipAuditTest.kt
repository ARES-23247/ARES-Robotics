package com.ares.analytics.viewmodel.robotstudio

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.RobotProjectReadinessService
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.project.persistence.ProjectMetadataRepository
import com.ares.analytics.shared.models.League
import com.ares.analytics.shared.models.WorkspaceConfig
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectAuthoringModel
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.eq
import org.mockito.Mockito.spy
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobotStudioSessionOwnershipAuditTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun metadata(
        id: String,
        name: String,
        teamId: String = "23247",
    ) = AresProjectMetadataDocument(
        projectId = id,
        identity = AresProjectIdentityDocument(teamId, "2026", id, name),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.45,
        robotWidthMeters = 0.43,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(ftc = AresFtcRuntimeOptionsDocument()),
    )

    private fun workspaceConfig(
        id: String,
        path: String,
        name: String = "Test Robot",
        robotId: String = "practice",
    ) = WorkspaceConfig(
        id = id,
        teamId = "23247",
        seasonId = "2026",
        robotId = robotId,
        robotName = name,
        projectPath = path,
        league = League.FTC,
    )

    private class Fixture(
        val original: File,
        val other: File,
        val session: ProjectSession,
        val database: DatabaseService,
        val service: RobotProjectReadinessService,
        val scope: CoroutineScope,
        val vm: RobotStudioViewModel,
        val metadataRepo: ProjectMetadataRepository,
        val initialJobs: Set<Job>,
    ) {
        suspend fun joinWork() {
            withTimeout(10_000) {
                do {
                    val activeJobs = scope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList()
                    activeJobs.joinAll()
                } while (scope.coroutineContext[Job]!!.children.any { it !in initialJobs })
            }
        }
    }

    private suspend fun joinWork(scope: CoroutineScope, initialJobs: Set<Job>) {
        withTimeout(10_000) {
            do {
                val activeJobs = scope.coroutineContext[Job]!!.children.filter { it !in initialJobs }.toList()
                activeJobs.joinAll()
            } while (scope.coroutineContext[Job]!!.children.any { it !in initialJobs })
        }
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val original = temporary.newFolder("orig-project")
        val other = temporary.newFolder("other-project")
        val dbDir = temporary.newFolder("db")

        File(original, "TeamCode/src/main/java/fixture/Robot.kt").apply {
            parentFile.mkdirs()
            writeText("package fixture\nclass Robot")
        }
        File(other, "TeamCode/src/main/java/fixture/Robot.kt").apply {
            parentFile.mkdirs()
            writeText("package fixture\nclass Robot")
        }

        val metadataRepo = ProjectMetadataRepository()
        metadataRepo.save(original.path, metadata("orig-project", "Original Project", "23247"))
        metadataRepo.save(other.path, metadata("other-project", "Other Project", "23247"))

        val database = spy(DatabaseService(File(dbDir, "analytics.duckdb").path))
        val session = spy(ProjectSession())
        val service = RobotProjectReadinessService(
            databaseService = database,
            projectSession = session,
        )
        val job = SupervisorJob()
        val failures = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, error -> failures += error })
        val vm = RobotStudioViewModel(
            readinessService = service,
            scope = scope,
        )
        val initialJobs = scope.coroutineContext[Job]!!.children.toSet()
        val f = Fixture(original, other, session, database, service, scope, vm, metadataRepo, initialJobs)
        try {
            block(f)
            assertTrue(failures.isEmpty(), "Unhandled failures escaped scope: $failures")
        } finally {
            job.cancel()
            withTimeout(10_000) { job.join() }
            database.close()
        }
    }

    @Test
    fun `normal load and inspection with project session succeeds end to end`() = fixture { f ->
        val config = workspaceConfig("orig-id", f.original.path, "Original Robot", "orig-robot")
        f.vm.load(config)
        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.projectPath == f.original.path }
        }
        val current = f.vm.state.value
        assertEquals("Original Robot", current.projectName)
        assertEquals(f.original.path, current.projectPath)
        assertEquals(AresProjectAuthoringModel.GUI_OWNED, current.authoringModel)
        assertNotNull(current.hardwareReadiness)
        assertNull(current.error)
        assertEquals(f.original.path, f.session.state.value.snapshot?.selection?.projectRoot)
    }

    @Test
    fun `same-model same-ID workspace switch preserves successor project selection and state`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOnce.compareAndSet(false, true)) {
                originalStarted.countDown()
                check(releaseOriginal.await(10, TimeUnit.SECONDS)) { "Original snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            val workspaceA = workspaceConfig("same-id", f.original.path, "Robot A", "robot-a")
            val workspaceB = workspaceConfig("same-id", f.other.path, "Robot B", "robot-b")

            f.vm.load(workspaceA)
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // While original snapshot is blocked, switch to successor B on same viewmodel with identical workspace ID
            f.vm.load(workspaceB)
            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.projectPath == f.other.path }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let old work complete or abort
            releaseOriginal.countDown()
            f.joinWork()

            // Session must remain selected on other, not overwritten by obsolete original
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)
            val finalState = f.vm.state.value
            assertEquals(f.other.path, finalState.projectPath)
            assertEquals("Robot B", finalState.projectName)
            assertFalse(finalState.loading)
            assertNull(finalState.error)
        } finally {
            releaseOriginal.countDown()
        }
    }

    @Test
    fun `cancelled old model does not revert shared session selection when replacement model loads`() = fixture { f ->
        val originalStarted = CountDownLatch(1)
        val releaseOriginal = CountDownLatch(1)
        val blockedOnce = AtomicBoolean(false)

        val replacementJob = SupervisorJob()
        val replacementScope = CoroutineScope(replacementJob + Dispatchers.Default)
        val replacementVm = RobotStudioViewModel(
            readinessService = f.service,
            scope = replacementScope,
        )
        val replacementInitialJobs = replacementScope.coroutineContext[Job]!!.children.toSet()

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedOnce.compareAndSet(false, true)) {
                originalStarted.countDown()
                check(releaseOriginal.await(10, TimeUnit.SECONDS)) { "Original snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            val workspaceA = workspaceConfig("ws-a", f.original.path, "Robot A", "robot-a")
            val workspaceB = workspaceConfig("ws-b", f.other.path, "Robot B", "robot-b")

            f.vm.load(workspaceA)
            assertTrue(originalStarted.await(10, TimeUnit.SECONDS), "Original load did not start")

            // Cancel old model's scope while its snapshot call is blocked entering the lock
            f.scope.cancel()

            // Replacement model loads other project
            replacementVm.load(workspaceB)
            withTimeout(10_000) {
                replacementVm.state.first { !it.loading && it.projectPath == f.other.path }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            // Release original and let it attempt to enter lock
            releaseOriginal.countDown()
            f.joinWork()
            joinWork(replacementScope, replacementInitialJobs)

            // Shared session selection must remain on other, not overwritten by cancelled model
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)
            val finalState = replacementVm.state.value
            assertEquals(f.other.path, finalState.projectPath)
            assertEquals("Robot B", finalState.projectName)
            assertFalse(finalState.loading)
            assertNull(finalState.error)
        } finally {
            releaseOriginal.countDown()
            replacementJob.cancel()
            withTimeout(10_000) { replacementJob.join() }
        }
    }

    @Test
    fun `runtime update during successor load does not publish predecessor evidence or path`() = fixture { f ->
        val workspaceA = workspaceConfig("ws-a", f.original.path, "Robot A", "robot-a")
        val workspaceB = workspaceConfig("ws-b", f.other.path, "Robot B", "robot-b")

        // First load project A to completion so evidence field holds A
        f.vm.load(workspaceA)
        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.projectPath == f.original.path }
        }
        assertEquals(f.original.path, f.vm.state.value.projectPath)

        val successorStarted = CountDownLatch(1)
        val releaseSuccessor = CountDownLatch(1)
        val blockedSuccessor = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.other.path && blockedSuccessor.compareAndSet(false, true)) {
                successorStarted.countDown()
                check(releaseSuccessor.await(10, TimeUnit.SECONDS)) { "Successor snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            // Load successor project B
            f.vm.load(workspaceB)
            assertTrue(successorStarted.await(10, TimeUnit.SECONDS), "Successor load did not start")

            // View model state is now loading B
            assertTrue(f.vm.state.value.loading)
            assertEquals(f.other.path, f.vm.state.value.projectPath)

            // While successor B is blocked loading, a runtime event arrives
            f.vm.updateRuntime(RobotStudioRuntimeEvidence(simulatorRunning = true))

            // Predecessor A evidence must NOT be published under selected B
            val stateDuringLoad = f.vm.state.value
            assertEquals(f.other.path, stateDuringLoad.projectPath, "Project path must remain B, not reverted to A")
            assertTrue(stateDuringLoad.loading, "State must remain loading while inspection is in flight")

            // Release successor B
            releaseSuccessor.countDown()
            f.joinWork()

            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.projectPath == f.other.path }
            }
            val finalState = f.vm.state.value
            assertEquals(f.other.path, finalState.projectPath)
            assertEquals("Robot B", finalState.projectName)
            assertNull(finalState.error)
        } finally {
            releaseSuccessor.countDown()
        }
    }

    @Test
    fun `same-project refresh blocks snapshot and runtime update does not clear loading or publish old readiness`() = fixture { f ->
        val config = workspaceConfig("orig-id", f.original.path, "Original Robot", "orig-robot")
        f.vm.load(config)
        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.projectPath == f.original.path }
        }
        val initialStages = f.vm.state.value.stages
        assertTrue(initialStages.isNotEmpty())

        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val blockedRefresh = AtomicBoolean(false)

        doAnswer { invocation ->
            val path = invocation.getArgument<String>(0)
            if (path == f.original.path && blockedRefresh.compareAndSet(false, true)) {
                refreshStarted.countDown()
                check(releaseRefresh.await(10, TimeUnit.SECONDS)) { "Refresh snapshot was not released" }
            }
            invocation.callRealMethod()
        }.`when`(f.session).snapshot(
            anyString() ?: "",
            eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC,
            anyBoolean(),
            any<() -> Unit>() ?: {},
        )

        try {
            f.vm.refresh()
            assertTrue(refreshStarted.await(10, TimeUnit.SECONDS), "Refresh did not start snapshot")
            assertTrue(f.vm.state.value.loading, "State must be loading after refresh begins")

            // While refresh is blocked in snapshot, a runtime event occurs
            f.vm.updateRuntime(RobotStudioRuntimeEvidence(simulatorRunning = true))

            // Runtime event must not clear loading or prematurely publish old readiness
            val stateDuringRefresh = f.vm.state.value
            assertTrue(stateDuringRefresh.loading, "updateRuntime must not clear loading while refresh is in flight")
            assertEquals(f.original.path, stateDuringRefresh.projectPath)

            releaseRefresh.countDown()
            f.joinWork()

            withTimeout(10_000) {
                f.vm.state.first { !it.loading && it.projectPath == f.original.path }
            }
            val finalState = f.vm.state.value
            assertFalse(finalState.loading)
            assertNull(finalState.error)
        } finally {
            releaseRefresh.countDown()
        }
    }

    @Test
    fun `inspection failure after prior success followed by runtime update does not erase error`() = fixture { f ->
        val config = workspaceConfig("orig-id", f.original.path, "Original Robot", "orig-robot")
        f.vm.load(config)
        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.projectPath == f.original.path && it.error == null }
        }
        assertNull(f.vm.state.value.error)

        // Snapshot failures become readiness diagnostics. A database failure propagates
        // through the service and exercises the view model's failed-inspection state.
        doAnswer { throw IllegalStateException("Simulated run database read failure") }
            .`when`(f.database).getSessions()
        f.vm.refresh()
        f.joinWork()

        withTimeout(10_000) {
            f.vm.state.first { !it.loading && it.error != null }
        }
        val failedState = f.vm.state.value
        assertFalse(failedState.loading)
        assertNotNull(failedState.error)
        assertTrue(failedState.error.contains("Simulated run database read failure"))

        // While in failed state, an updateRuntime event occurs
        f.vm.updateRuntime(RobotStudioRuntimeEvidence(simulatorRunning = true))

        // Stale cached evidence must not erase current error or restore ready stages
        val stateAfterRuntime = f.vm.state.value
        assertFalse(stateAfterRuntime.loading)
        assertEquals(failedState.error, stateAfterRuntime.error, "Runtime update must not erase current inspection error")
        assertTrue(stateAfterRuntime.stages.isEmpty(), "Stale stages must not be republished on failure")
    }
}
