package com.ares.analytics.viewmodel

import com.ares.analytics.service.DatabaseService
import com.ares.analytics.service.Nt4ClientService
import com.ares.analytics.service.tuning.TuningProfileRepository
import com.ares.analytics.service.versioncontrol.ProjectCheckpointRecorder
import com.areslib.tuning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import org.mockito.Mockito.*
import kotlin.test.*

class TuningPromotionLifecycleAuditTest {
    private val gain = TuningParameterDeclaration("gain.uid", "test.gain", "component.main", "Gain", "Test gain",
        TuningParameterType.DOUBLE, minimum = 0.0, maximum = 10.0,
        defaultValue = TuningValue(doubleValue = 1.0), applyPolicy = TuningApplyPolicy.LIVE_SAFE)
    private val profile = TuningProfileDocument(uid = "profile.main", profileId = "competition", displayName = "Competition",
        description = "Test profile", projectId = "robot.project", authority = TuningProfileAuthority.CANONICAL_CHECKED_IN,
        values = emptyList())

    @Test fun `completed promotion cannot replace the newly opened project`() = fixture { f ->
        f.prepare()
        f.blockPromotion { job ->
            f.load(f.other)
            f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 7.0)))
            val newer = f.vm.state.value
            f.release.countDown(); job.join()
            assertEquals(newer, f.vm.state.value)
            assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
            assertTrue(f.disk(f.other).values.isEmpty())
        }
    }

    @Test fun `completed promotion preserves proposals and reviewer edits made during the save`() = fixture { f ->
        f.prepare()
        f.blockPromotion { job ->
            f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 8.0)))
            f.vm.onIntent(TuningIntent.SetReviewerName("Next reviewer"))
            f.vm.onIntent(TuningIntent.SetReviewSummary("Next experiment"))
            val newer = f.vm.state.value
            f.release.countDown(); job.join()
            val result = f.vm.state.value
            assertEquals(newer.proposals, result.proposals)
            assertEquals(newer.proposalProvenance, result.proposalProvenance)
            assertEquals(newer.reviewerName, result.reviewerName)
            assertEquals(newer.reviewSummary, result.reviewSummary)
            assertEquals(TuningValue(doubleValue = 2.0), result.selectedProfile!!.values.single().value)
            assertNull(result.review)
        }
    }

    @Test fun `completed promotion preserves the other selected profile and its draft`() = fixture { f ->
        f.prepare()
        f.blockPromotion { job ->
            f.vm.onIntent(TuningIntent.SelectProfile("practice"))
            f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 6.0)))
            val newer = f.vm.state.value
            f.release.countDown(); job.join()
            assertEquals("practice", f.vm.state.value.selectedProfileId)
            assertEquals(newer.proposals, f.vm.state.value.proposals)
            assertEquals(newer.proposalProvenance, f.vm.state.value.proposalProvenance)
            assertEquals(TuningValue(doubleValue = 2.0), f.vm.state.value.profiles.first { it.uid == profile.uid }.values.single().value)
        }
    }

    @Test fun `failed promotion cannot report an old project error in a new project`() = fixture { f ->
        f.prepare()
        f.blockPromotion(fail = true) { job ->
            f.load(f.other)
            val newer = f.vm.state.value
            f.release.countDown(); job.join()
            assertEquals(newer, f.vm.state.value)
            assertTrue(f.disk(f.original).values.isEmpty())
        }
    }

    @Test fun `late checkpoint failure cannot overwrite the new project status`() = runBlocking {
        val entered = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        val recorder = ProjectCheckpointRecorder { path, _, _ ->
            entered.complete(path)
            release.await()
            error("checkpoint failure from old project")
        }
        fixture(recorder) { f ->
            f.prepare()
            f.vm.onIntent(TuningIntent.ConfirmPromotion(f.vm.state.value.review!!.confirmationToken))
            assertEquals(f.original.path, withTimeout(10_000) { entered.await() })
            val checkpointJobs = f.scope.coroutineContext[Job]!!.children.filter { it !in f.permanentJobs }.toList()
            try {
                f.load(f.other)
                val newer = f.vm.state.value
                release.complete(Unit)
                withTimeout(10_000) { checkpointJobs.joinAll() }
                assertEquals(newer, f.vm.state.value)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `evidence review does not hash files on the intent caller thread`() = fixture { f ->
        val caller = Thread.currentThread()
        var evidenceThread: Thread? = null
        doAnswer { evidenceThread = Thread.currentThread(); emptyList<String>() }
            .`when`(f.repository).evidenceErrors(anyString(), anyList())
        f.prepare()
        assertNotNull(evidenceThread)
        assertNotSame(caller, evidenceThread)
    }

    @Test fun `slow evidence review cannot restore a stale diff after a student edit`() = fixture { f ->
        f.prepare()
        val entered = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            check(f.release.await(10, TimeUnit.SECONDS))
            emptyList<String>()
        }.`when`(f.repository).evidenceErrors(anyString(), anyList())
        val caller = f.scope.async(Dispatchers.Default) { f.vm.onIntent(TuningIntent.ReviewPromotion) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            f.vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 9.0)))
            f.release.countDown(); caller.await()
            f.joinWork()
            assertEquals(TuningValue(doubleValue = 9.0), f.vm.state.value.proposals[gain.key])
            assertNull(f.vm.state.value.review)
        } finally { f.release.countDown(); caller.join() }
    }

    @Test fun `one immutable tuning state resolves rows only once`() {
        val catalog = CountedList(listOf(gain))
        val state = TuningState(catalog = catalog, profiles = listOf(profile))
        val first = state.rows
        val reads = catalog.reads
        assertTrue(reads > 0)
        repeat(64) { assertEquals(first, state.rows) }
        assertEquals(reads, catalog.reads, "Repeated UI reads must not revalidate and resort the catalog")
        println("Cached rows: first resolution read the catalog $reads times; 64 subsequent reads added ${catalog.reads - reads} accesses")
        val changed = state.copy(proposals = mapOf(gain.key to TuningValue(doubleValue = 4.0)))
        assertEquals(TuningValue(doubleValue = 4.0), changed.rows.single().proposedTypedValue)
        assertNull(state.rows.single().proposedTypedValue)
        assertEquals(5.0, state.copy(variables = mapOf(gain.key to 5.0)).rows.single().liveValue)
        val assigned = profile.copy(values = listOf(TuningAssignment(gain.uid, TuningValue(doubleValue = 6.0))))
        assertEquals(6.0, state.copy(profiles = listOf(assigned)).rows.single().sourceValue)
        val practice = profile.copy(uid = "profile.practice", profileId = "practice")
        val selection = state.copy(profiles = listOf(assigned, practice), selectedProfileId = practice.profileId)
        assertEquals(practice, selection.selectedProfile)
        assertEquals(1.0, selection.rows.single().sourceValue)
        assertEquals(TuningValue(doubleValue = 7.0), state.copy(liveTypedValues = mapOf(gain.key to TuningValue(doubleValue = 7.0))).rows.single().liveTypedValue)
    }

    private class CountedList<T>(private val values: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = values.size
        override fun get(index: Int): T { reads++; return values[index] }
    }

    @Test fun `duplicate confirmations perform one save and checkpoint and clear only the committed draft`() {
        val checkpoints = AtomicInteger()
        fixture(ProjectCheckpointRecorder { _, _, _ -> checkpoints.incrementAndGet(); null }) { f ->
            f.prepare()
            val token = f.vm.state.value.review!!.confirmationToken
            f.blockPromotion { job ->
                repeat(3) { f.vm.onIntent(TuningIntent.ConfirmPromotion(token)) }
                f.release.countDown(); job.join(); f.joinWork()
                assertEquals(1, f.replacements.get())
                assertEquals(1, checkpoints.get())
                assertTrue(f.vm.state.value.proposals.isEmpty())
                assertTrue(f.vm.state.value.proposalProvenance.isEmpty())
                assertEquals("", f.vm.state.value.reviewerName)
                assertEquals("", f.vm.state.value.reviewSummary)
                assertNull(f.vm.state.value.review)
                assertNull(f.vm.state.value.errorMessage)
                assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
            }
        }
    }

    @Test fun `current promotion failure keeps the draft and allows a successful retry`() = fixture { f ->
        f.prepare()
        val token = f.vm.state.value.review!!.confirmationToken
        val draft = f.vm.state.value.proposals
        f.blockPromotion(fail = true) { job -> f.release.countDown(); job.join() }
        assertTrue(f.vm.state.value.errorMessage.orEmpty().contains("old project replacement failure"))
        assertEquals(draft, f.vm.state.value.proposals)
        assertTrue(f.disk(f.original).values.isEmpty())
        f.vm.onIntent(TuningIntent.ConfirmPromotion(token)); f.joinWork()
        assertNull(f.vm.state.value.errorMessage)
        assertTrue(f.vm.state.value.proposals.isEmpty())
        assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
    }

    @Test fun `checkpoint failure is still reported for the current completed promotion`() {
        fixture(ProjectCheckpointRecorder { _, _, _ -> error("current checkpoint failure") }) { f ->
            f.prepare()
            f.vm.onIntent(TuningIntent.ConfirmPromotion(f.vm.state.value.review!!.confirmationToken))
            f.joinWork()
            assertTrue(f.vm.state.value.saveStatus.contains("current checkpoint failure"))
            assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
            assertTrue(f.vm.state.value.proposals.isEmpty())
            assertNull(f.vm.state.value.errorMessage)
        }
    }

    @Test fun `newest evidence review wins when the older review finishes last`() = fixture { f ->
        f.prepare()
        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        doAnswer {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(f.release.await(10, TimeUnit.SECONDS))
                listOf("obsolete evidence error")
            } else emptyList<String>()
        }.`when`(f.repository).evidenceErrors(anyString(), anyList())
        try {
            f.vm.onIntent(TuningIntent.ReviewPromotion)
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            f.vm.onIntent(TuningIntent.ReviewPromotion)
            val newer = withTimeout(10_000) { f.vm.state.first { it.review?.canPromote == true } }.review
            f.release.countDown(); f.joinWork()
            assertEquals(newer, f.vm.state.value.review)
            assertNull(f.vm.state.value.errorMessage)
        } finally { f.release.countDown() }
    }

    @Test fun `queued confirmation cannot reinterpret an identical token in another project`() {
        val dispatcher = HoldingDispatcher()
        fixture(workDispatcher = dispatcher) { f ->
            f.prepare(); f.joinWork()
            val token = f.vm.state.value.review!!.confirmationToken
            dispatcher.hold = true
            f.vm.onIntent(TuningIntent.ConfirmPromotion(token))
            val pending = dispatcher.take()
            try {
                dispatcher.hold = false
                f.prepare(f.other)
                assertEquals(token, f.vm.state.value.review!!.confirmationToken, "The two fixture copies intentionally share identical review content")
                val newer = f.vm.state.value
                pending.second.run(); f.joinWork()
                assertEquals(newer, f.vm.state.value)
                assertTrue(f.disk(f.original).values.isEmpty())
                assertTrue(f.disk(f.other).values.isEmpty())
            } finally { dispatcher.hold = false; pending.first[Job]?.cancel(); pending.second.run() }
        }
    }

    @Test fun `cancelled queued work does not write or strand the promotion guard`() {
        val dispatcher = HoldingDispatcher()
        fixture(workDispatcher = dispatcher) { f ->
            f.prepare(); f.joinWork()
            val token = f.vm.state.value.review!!.confirmationToken
            dispatcher.hold = true
            f.vm.onIntent(TuningIntent.ConfirmPromotion(token))
            val pending = dispatcher.take()
            try {
                dispatcher.hold = false
                pending.first[Job]!!.cancel()
                pending.second.run(); f.joinWork()
                assertTrue(f.disk(f.original).values.isEmpty())
                assertNull(f.vm.state.value.errorMessage)
                f.vm.onIntent(TuningIntent.ConfirmPromotion(token)); f.joinWork()
                assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
            } finally { dispatcher.hold = false; pending.first[Job]?.cancel(); pending.second.run() }
        }
    }

    @Test fun `invalid confirmation performs no save and a valid confirmation remains usable`() = fixture { f ->
        f.prepare()
        val token = f.vm.state.value.review!!.confirmationToken
        f.vm.onIntent(TuningIntent.ConfirmPromotion("invalid")); f.joinWork()
        assertTrue(f.disk(f.original).values.isEmpty())
        assertNotNull(f.vm.state.value.errorMessage)
        f.vm.onIntent(TuningIntent.ConfirmPromotion(token)); f.joinWork()
        assertNull(f.vm.state.value.errorMessage)
        assertEquals(TuningValue(doubleValue = 2.0), f.disk(f.original).values.single().value)
    }

    private inner class Fixture(val root: File, val scope: CoroutineScope, val vm: TuningViewModel,
        val repository: TuningProfileRepository) {
        val original = File(root, "original")
        val other = File(root, "other")
        val release = CountDownLatch(1)
        val replacements = AtomicInteger()
        val permanentJobs = scope.coroutineContext[Job]!!.children.toSet()
        suspend fun load(path: File) {
            vm.onIntent(TuningIntent.LoadConstants(path.path))
            withTimeout(10_000) { vm.state.first { it.projectPath == path.path && !it.isLoading && it.selectedProfile != null } }
        }
        suspend fun prepare(project: File = original) {
            load(project)
            vm.onIntent(TuningIntent.UpdateTypedConstant(gain.key, TuningValue(doubleValue = 2.0)))
            vm.onIntent(TuningIntent.SetReviewerName("Reviewer"))
            vm.onIntent(TuningIntent.SetReviewSummary("Measured improvement"))
            vm.onIntent(TuningIntent.ReviewPromotion)
            withTimeout(10_000) { vm.state.first { it.review?.canPromote == true } }
        }
        suspend fun joinWork() = withTimeout(10_000) {
            do {
                val jobs = scope.coroutineContext[Job]!!.children.filter { it !in permanentJobs }.toList()
                jobs.joinAll()
            } while (scope.coroutineContext[Job]!!.children.any { it !in permanentJobs })
        }
        fun disk(project: File) = TuningProfileDocumentCodec.decode(File(project, ".ares/tuning/main.arestuning").readText(), listOf(gain))
        suspend fun blockPromotion(fail: Boolean = false, block: suspend (Job) -> Unit) {
            val entered = CountDownLatch(1)
            repository.beforeCanonicalReplace = {
                replacements.incrementAndGet()
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Test did not release promotion" }
                if (fail) error("old project replacement failure")
            }
            val before = scope.coroutineContext[Job]!!.children.toSet()
            vm.onIntent(TuningIntent.ConfirmPromotion(vm.state.value.review!!.confirmationToken))
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
                val job = scope.coroutineContext[Job]!!.children.single { it !in before }
                withTimeout(10_000) { block(job) }
            } finally { release.countDown(); repository.beforeCanonicalReplace = {} }
        }
    }

    private class HoldingDispatcher : CoroutineDispatcher() {
        @Volatile var hold = false
        private val work = LinkedBlockingQueue<Pair<CoroutineContext, Runnable>>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) {
                val executed = AtomicBoolean()
                work.add(context to Runnable { if (executed.compareAndSet(false, true)) block.run() })
            } else Dispatchers.IO.dispatch(context, block)
        }
        suspend fun take(): Pair<CoroutineContext, Runnable> = withContext(Dispatchers.IO) {
            assertNotNull(work.poll(10, TimeUnit.SECONDS), "No queued tuning work arrived")
        }
        fun releasePending() {
            hold = false
            while (true) (work.poll() ?: return).second.run()
        }
    }

    private fun fixture(recorder: ProjectCheckpointRecorder = ProjectCheckpointRecorder.NONE,
        workDispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend (Fixture) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tuning-lifecycle-audit").toFile()
        try {
            for (project in listOf("original", "other")) {
                File(root, "$project/.ares/tuning-components/main.arestuningcomponent").apply {
                    parentFile.mkdirs()
                    writeText(TuningComponentDocumentCodec.encode(TuningComponentDocument(uid = "component.main",
                        projectId = "robot.project", displayName = "Main", description = "Test component", parameters = listOf(gain))))
                }
                for ((filename, doc) in listOf("main" to profile, "practice" to profile.copy(uid = "profile.practice", profileId = "practice", displayName = "Practice"))) {
                    File(root, "$project/.ares/tuning/$filename.arestuning").apply {
                        parentFile.mkdirs(); writeText(TuningProfileDocumentCodec.encode(doc, listOf(gain)))
                    }
                }
            }
            val db = DatabaseService(File(root, "test.duckdb").path)
            val client = Nt4ClientService(db)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repository = spy(TuningProfileRepository())
            val vm = TuningViewModel(client, scope, repository = repository, checkpointRecorder = recorder, workDispatcher = workDispatcher)
            val f = Fixture(root, scope, vm, repository)
            try { block(f) }
            finally {
                f.release.countDown()
                scope.cancel()
                (workDispatcher as? HoldingDispatcher)?.releasePending()
                try { withTimeout(10_000) { scope.coroutineContext[Job]!!.join() } }
                finally { try { client.stop() } finally { db.close() } }
            }
        } finally { assertTrue(root.deleteRecursively(), "Fixture cleanup failed") }
    }
}
