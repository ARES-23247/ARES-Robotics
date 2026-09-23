package com.ares.analytics.viewmodel

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.project.ProjectSession
import com.ares.analytics.service.tuning.TuningProfileRepository
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.areslib.controls.ControllerInputPlatform
import com.areslib.project.AresCoordinateConvention
import com.areslib.project.AresFtcRuntimeOptionsDocument
import com.areslib.project.AresLeague
import com.areslib.project.AresProjectIdentityDocument
import com.areslib.project.AresProjectMetadataCodec
import com.areslib.project.AresProjectMetadataDocument
import com.areslib.project.AresRuntimeOptionsDocument
import com.areslib.tuning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import org.mockito.Mockito.*
import kotlin.test.*

class TuningPromotionContextAuditTest {
    private val gain = TuningParameterDeclaration("gain.uid", "test.gain", "component.main", "Gain", "Test gain",
        TuningParameterType.DOUBLE, minimum = 0.0, maximum = 10.0,
        defaultValue = TuningValue(doubleValue = 1.0), applyPolicy = TuningApplyPolicy.LIVE_SAFE)
    private val profile = TuningProfileDocument(uid = "profile.main", profileId = "competition", displayName = "Competition",
        description = "Test profile", projectId = "robot.project", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        values = emptyList())

    @Test fun `superseded load cannot mutate shared project session or cause stale promotion failure`() {
        val loadDispatcher = HoldingDispatcher()
        fixture(loadDispatcher = loadDispatcher) { f ->
            loadDispatcher.hold = true
            f.vm.onIntent(TuningIntent.LoadConstants(f.original.path))
            val pendingOriginal = loadDispatcher.take()

            loadDispatcher.hold = false
            f.vm.onIntent(TuningIntent.LoadConstants(f.other.path))
            withTimeout(10_000) {
                f.vm.state.first { it.projectPath == f.other.path && !it.isLoading && it.selectedProfile != null }
            }
            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            pendingOriginal.second.run()
            f.joinWork()

            assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)

            f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 3.0)))
            f.vm.onIntent(TuningIntent.SetReviewerName("Reviewer"))
            f.vm.onIntent(TuningIntent.SetReviewSummary("Tested improvement"))
            f.vm.onIntent(TuningIntent.ReviewPromotion)
            val token = withTimeout(10_000) { f.vm.state.first { it.review?.canPromote == true } }.review!!.confirmationToken
            f.vm.onIntent(TuningIntent.ConfirmPromotion(token))
            f.joinWork()

            assertNull(f.vm.state.value.errorMessage)
            assertEquals(TuningValue(doubleValue = 3.0), f.disk(f.other).values.single().value)
        }
    }

    @Test fun `replacement load remains selected when prior session read has already started`() =
        exerciseStartedLoad(replaceViewModel = false)

    @Test fun `replacement workspace model is not overwritten by a cancelled previous model`() =
        exerciseStartedLoad(replaceViewModel = true)

    private fun exerciseStartedLoad(replaceViewModel: Boolean) {
        val loadDispatcher = HoldingDispatcher()
        fixture(loadDispatcher = loadDispatcher) { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val replacementScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val replacementVm = if (replaceViewModel) TuningViewModel(
                nt4ClientService = f.vm.nt4ClientService,
                scope = replacementScope,
                repository = f.repository,
                projectSession = f.session,
                targetPlatform = ControllerInputPlatform.FTC,
                loadDispatcher = loadDispatcher,
            ) else f.vm
            doAnswer { invocation ->
                if (invocation.getArgument<String>(0) == f.original.path) {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS)) { "Old session read was not released" }
                }
                invocation.callRealMethod()
            }.`when`(f.session).snapshot(anyString(), eq(ControllerInputPlatform.FTC) ?: ControllerInputPlatform.FTC, anyBoolean(), any<() -> Unit>() ?: {})
            try {
                loadDispatcher.hold = true
                f.vm.onIntent(TuningIntent.LoadConstants(f.original.path))
                val first = loadDispatcher.take()
                executor.submit { first.second.run() }
                withTimeout(10_000) { entered.await() }

                // Execute replacement work through its first suspension while the old synchronous
                // session call is still in progress. This is independent of scheduler delays.
                if (replaceViewModel) f.scope.cancel()
                replacementVm.onIntent(TuningIntent.LoadConstants(f.other.path))
                val replacement = loadDispatcher.take()
                loadDispatcher.hold = false
                replacement.second.run()
                release.countDown()
                f.joinWork()

                withTimeout(10_000) {
                    replacementVm.state.first { it.projectPath == f.other.path && !it.isLoading }
                }
                assertEquals(f.other.path, replacementVm.state.value.projectPath)
                assertFalse(replacementVm.state.value.isLoading)
                assertEquals(f.other.path, f.session.state.value.snapshot?.selection?.projectRoot)
                assertEquals(f.session.state.value.revision, replacementVm.state.value.projectRevision)
            } finally {
                release.countDown()
                loadDispatcher.releasePending()
                executor.shutdown()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
                replacementScope.coroutineContext[Job]!!.cancelAndJoin()
            }
        }
    }

    @Test fun `cancelled load coroutine does not surface false cancellation error in state`() {
        val loadDispatcher = HoldingDispatcher()
        fixture(loadDispatcher = loadDispatcher) { f ->
            loadDispatcher.hold = true
            f.vm.onIntent(TuningIntent.LoadConstants(f.original.path))
            val pending = loadDispatcher.take()
            try {
                loadDispatcher.hold = false
                pending.first[Job]!!.cancel()
                pending.second.run()
                f.joinWork()
                assertNull(f.vm.state.value.errorMessage)
            } finally {
                loadDispatcher.hold = false
                pending.first[Job]?.cancel()
                pending.second.run()
            }
        }
    }

    @Test fun `normal load and promotion with project session succeeds end to end`() = fixture { f ->
        f.load(f.original)
        assertEquals(f.original.path, f.session.state.value.snapshot?.selection?.projectRoot)
        assertNotNull(f.vm.state.value.projectRevision)

        f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 5.0)))
        f.vm.onIntent(TuningIntent.SetReviewerName("Auditor"))
        f.vm.onIntent(TuningIntent.SetReviewSummary("Context audit baseline"))
        f.vm.onIntent(TuningIntent.ReviewPromotion)
        val token = withTimeout(10_000) { f.vm.state.first { it.review?.canPromote == true } }.review!!.confirmationToken
        f.vm.onIntent(TuningIntent.ConfirmPromotion(token))
        f.joinWork()

        assertNull(f.vm.state.value.errorMessage)
        assertEquals(TuningValue(doubleValue = 5.0), f.disk(f.original).values.single().value)
        assertTrue(f.vm.state.value.proposals.isEmpty())
        assertNull(f.vm.state.value.review)
        assertTrue(f.vm.state.value.saveStatus.contains("Promoted canonical profile atomically"))
    }

    private fun metadataDocument(projectId: String) = AresProjectMetadataDocument(
        projectId = projectId,
        identity = AresProjectIdentityDocument("23247", "2026", projectId, projectId),
        league = AresLeague.FTC,
        coordinateConvention = AresCoordinateConvention.CENTER_ORIGIN_CCW,
        robotLengthMeters = 0.46,
        robotWidthMeters = 0.46,
        fieldLengthMeters = 3.6576,
        fieldWidthMeters = 3.6576,
        runtimeOptions = AresRuntimeOptionsDocument(
            ftc = AresFtcRuntimeOptionsDocument(),
        ),
    )

    private inner class Fixture(
        val root: File,
        val scope: CoroutineScope,
        val vm: TuningViewModel,
        val session: ProjectSession,
        val repository: TuningProfileRepository,
    ) {
        val original = File(root, "original")
        val other = File(root, "other")
        val permanentJobs = scope.coroutineContext[Job]!!.children.toSet()

        suspend fun load(path: File) {
            vm.onIntent(TuningIntent.LoadConstants(path.path))
            withTimeout(10_000) {
                vm.state.first { it.projectPath == path.path && !it.isLoading && it.selectedProfile != null }
            }
        }

        suspend fun joinWork() = withTimeout(10_000) {
            do {
                val jobs = scope.coroutineContext[Job]!!.children.filter { it !in permanentJobs }.toList()
                jobs.joinAll()
            } while (scope.coroutineContext[Job]!!.children.any { it !in permanentJobs })
        }

        fun disk(project: File) =
            TuningProfileDocumentCodec.decode(File(project, ".ares/tuning/main.arestuning").readText(), listOf(gain))
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        @Volatile var hold = false
        private val work = LinkedBlockingQueue<Pair<CoroutineContext, Runnable>>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) {
                val executed = AtomicBoolean()
                work.add(context to Runnable { if (executed.compareAndSet(false, true)) block.run() })
            } else {
                Dispatchers.IO.dispatch(context, block)
            }
        }

        suspend fun take(): Pair<CoroutineContext, Runnable> = withContext(Dispatchers.IO) {
            assertNotNull(work.poll(10, TimeUnit.SECONDS), "No queued tuning work arrived")
        }

        fun releasePending() {
            hold = false
            while (true) (work.poll() ?: return).second.run()
        }
    }

    private fun fixture(
        recorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
        loadDispatcher: CoroutineDispatcher = Dispatchers.IO,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend (Fixture) -> Unit,
    ) = runBlocking {
        val root = Files.createTempDirectory("tuning-context-audit").toFile()
        try {
            for (project in listOf("original", "other")) {
                File(root, "$project/.ares/project.json").apply {
                    parentFile.mkdirs()
                    writeText(AresProjectMetadataCodec.encode(metadataDocument("robot.project")))
                }
                File(root, "$project/.ares/tuning-components/main.arestuningcomponent").apply {
                    parentFile.mkdirs()
                    writeText(TuningComponentDocumentCodec.encode(TuningComponentDocument(uid = "component.main",
                        projectId = "robot.project", displayName = "Main", description = "Test component", parameters = listOf(gain))))
                }
                for ((filename, doc) in listOf("main" to profile, "practice" to profile.copy(uid = "profile.practice", profileId = "practice", displayName = "Practice"))) {
                    File(root, "$project/.ares/tuning/$filename.arestuning").apply {
                        parentFile.mkdirs()
                        writeText(TuningProfileDocumentCodec.encode(doc, listOf(gain)))
                    }
                }
            }
            val db = DatabaseService(File(root, "test.duckdb").path)
            val client = Nt4ClientService(db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = spy(TuningProfileRepository())
            val session = spy(ProjectSession(tuningRepository = repository))
            val vm = TuningViewModel(
                nt4ClientService = client,
                scope = scope,
                repository = repository,
                checkpointRecorder = recorder,
                projectSession = session,
                targetPlatform = ControllerInputPlatform.FTC,
                loadDispatcher = loadDispatcher,
                workDispatcher = workDispatcher,
            )
            val f = Fixture(root, scope, vm, session, repository)
            try { block(f) }
            finally {
                scope.cancel()
                (loadDispatcher as? HoldingDispatcher)?.releasePending()
                (workDispatcher as? HoldingDispatcher)?.releasePending()
                try { withTimeout(10_000) { scope.coroutineContext[Job]!!.join() } }
                finally { try { client.disposeAndJoin() } finally { db.closeAndJoin() } }
            }
        } finally { assertTrue(root.deleteRecursively(), "Fixture cleanup failed") }
    }
}
