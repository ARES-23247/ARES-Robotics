package com.ares.analytics.viewmodel

import com.ares.analytics.service.*
import com.ares.analytics.service.tuning.*
import com.areslib.tuning.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ExternalTuningProposalAuditTest {
    private val gain = TuningParameterDeclaration("gain.uid", "test.gain", "component.main", "Gain", "Test gain",
        TuningParameterType.DOUBLE, minimum=0.0, maximum=5.0, defaultValue=TuningValue(doubleValue=1.0), applyPolicy=TuningApplyPolicy.LIVE_SAFE)
    private val integer = gain.copy(uid="count.uid", key="test.count", type=TuningParameterType.INT, defaultValue=TuningValue(intValue=1))
    private val profile = TuningProfileDocument(uid="profile.main", profileId="competition", displayName="Competition",
        description="Test profile", projectId="robot.project", authority=TuningProfileAuthority.CANONICAL_CHECKED_IN, values=emptyList())
    private fun state(catalog: List<TuningParameterDeclaration> = listOf(gain, integer)) =
        TuningState(catalog=catalog, profiles=listOf(profile), selectedProfileId=profile.profileId)
    private fun proposal(values: Map<String, Double>) = ExternalTuningProposal("recorded", "review this run", values, "logs/run.csv", "a".repeat(64))
    @Test fun `whole valid proposal stages typed values and evidence together`() {
        val result = stageExternalTuningProposal(state(), proposal(mapOf("test.gain" to 2.0, "test.count" to 3.0)))
        assertNull(result.errorMessage); assertNull(result.review)
        assertEquals(mapOf("test.gain" to TuningValue(doubleValue=2.0), "test.count" to TuningValue(intValue=3)), result.proposals)
        assertEquals("logs/run.csv", result.proposalProvenance.getValue("test.gain").evidencePath)
        assertEquals("a".repeat(64), result.proposalProvenance.getValue("test.count").evidenceSha256)
    }
    @Test fun `invalid second entry cannot leave a partial first entry`() {
        for (values in listOf(mapOf("test.gain" to 2.0, "missing" to 3.0), mapOf("test.gain" to 2.0, "test.count" to 1.5),
            mapOf("test.gain" to 2.0, "test.count" to 6.0), mapOf("test.gain" to 2.0, "test.count" to Double.NaN))) {
            val result = stageExternalTuningProposal(state(), proposal(values))
            assertNotNull(result.errorMessage); assertTrue(result.proposals.isEmpty()); assertTrue(result.proposalProvenance.isEmpty())
        }
    }
    @Test fun `read only vendor and nonnumeric declarations reject the whole proposal`() {
        for (restricted in listOf(integer.copy(applyPolicy=TuningApplyPolicy.READ_ONLY_VENDOR),
            integer.copy(type=TuningParameterType.BOOLEAN, minimum=null, maximum=null, defaultValue=TuningValue(booleanValue=false)))) {
            val result = stageExternalTuningProposal(state(listOf(gain, restricted)), proposal(mapOf("test.gain" to 2.0, "test.count" to 1.0)))
            assertNotNull(result.errorMessage); assertTrue(result.proposals.isEmpty())
        }
    }
    @Test fun `conflicting student value and its provenance are preserved`() {
        val before = state().copy(proposals=mapOf("test.gain" to TuningValue(doubleValue=4.0)),
            proposalProvenance=mapOf("test.gain" to TuningValueProvenance("Student", "my edit")))
        val result = stageExternalTuningProposal(before, proposal(mapOf("test.gain" to 2.0, "test.count" to 3.0)))
        assertNotNull(result.errorMessage); assertEquals(before.proposals, result.proposals)
        assertEquals(before.proposalProvenance, result.proposalProvenance)
    }
    @Test fun `identical existing value retains its original provenance`() {
        val before = state().copy(proposals=mapOf("test.gain" to TuningValue(doubleValue=2.0)),
            proposalProvenance=mapOf("test.gain" to TuningValueProvenance("Student", "my edit")))
        val result = stageExternalTuningProposal(before, proposal(mapOf("test.gain" to 2.0, "test.count" to 3.0)))
        assertNull(result.errorMessage); assertEquals(before.proposalProvenance["test.gain"], result.proposalProvenance["test.gain"])
        assertEquals("recorded", result.proposalProvenance["test.count"]?.source)
    }
    @Test fun `unloaded and loading profiles cannot receive values`() {
        for (before in listOf(TuningState(), state().copy(isLoading=true))) {
            val result = stageExternalTuningProposal(before, proposal(mapOf("test.gain" to 2.0)))
            assertNotNull(result.errorMessage); assertTrue(result.proposals.isEmpty())
        }
        assertNotNull(stageExternalTuningProposal(state(), proposal(emptyMap())).errorMessage)
    }
    @Test fun `numeric integer bounds and nonfinite doubles cannot be truncated or clamped`() {
        for (number in listOf(Int.MAX_VALUE.toDouble()+1, Int.MIN_VALUE.toDouble()-1, Double.POSITIVE_INFINITY)) {
            assertNotNull(stageExternalTuningProposal(state(), proposal(mapOf("test.count" to number))).errorMessage)
        }
        assertNotNull(stageExternalTuningProposal(state(), proposal(mapOf("test.gain" to Double.NaN))).errorMessage)
    }
    @Test fun `a board receives a previously queued proposal only after loading a profile`() = integration { vm, inbox, root, client, _ ->
        assertTrue(inbox.submit(proposal(mapOf("test.gain" to 2.0))))
        yield()
        assertEquals(1, inbox.pendingCount.value); assertTrue(vm.state.value.proposals.isEmpty())
        vm.onIntent(TuningIntent.LoadConstants(root.path))
        withTimeout(10_000) { vm.state.first { it.proposals["test.gain"] == TuningValue(doubleValue=2.0) } }
        withTimeout(10_000) { inbox.pendingCount.first { it == 0 } }
        assertTrue(client.latestValues.isEmpty())
        val disk = TuningProfileDocumentCodec.decode(File(root, ".ares/tuning/main.arestuning").readText(), listOf(gain, integer))
        assertEquals(profile, disk)
    }
    @Test fun `failed project load cannot consume proposals using the previous catalog`() = integration { vm, inbox, root, _, _ ->
        vm.onIntent(TuningIntent.LoadConstants(root.path))
        withTimeout(10_000) { vm.state.first { it.selectedProfile != null } }
        val invalid = File(root, "invalid").apply { mkdirs() }
        val invalidProfile = File(invalid, ".ares/tuning/broken.arestuning").apply { parentFile.mkdirs(); writeText("invalid") }
        vm.onIntent(TuningIntent.LoadConstants(invalid.path))
        assertNull(vm.state.value.selectedProfile)
        withTimeout(10_000) { vm.state.first { it.projectPath == invalid.path && !it.isLoading && it.errorMessage != null } }
        assertTrue(invalidProfile.isFile); assertNull(vm.state.value.selectedProfile)
        assertTrue(inbox.submit(proposal(mapOf("test.gain" to 2.0))))
        yield(); assertEquals(1, inbox.pendingCount.value)
        vm.onIntent(TuningIntent.LoadConstants(root.path))
        withTimeout(10_000) { vm.state.first { it.proposals.isNotEmpty() } }
        withTimeout(10_000) { inbox.pendingCount.first { it == 0 } }
    }
    @Test fun `older delayed project load cannot replace a newer loaded profile`() {
        val work = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { work.add(block) }
        }
        integration(dispatcher) { vm, _, root, _, scope ->
            val priorJobs = scope.coroutineContext[Job]!!.children.toSet()
            vm.onIntent(TuningIntent.LoadConstants(root.path))
            val oldLoad = assertNotNull(work.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            val oldJob = scope.coroutineContext[Job]!!.children.first { it !in priorJobs }
            val newRoot = File(root, "new-project")
            val newProfile = profile.copy(uid="profile.new", profileId="new", displayName="New")
            File(newRoot, ".ares/tuning/main.arestuning").apply {
                parentFile.mkdirs(); writeText(TuningProfileDocumentCodec.encode(newProfile, emptyList()))
            }
            try {
                vm.onIntent(TuningIntent.LoadConstants(newRoot.path))
                assertNotNull(work.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
                withTimeout(10_000) { vm.state.first { it.selectedProfileId == "new" } }
            } finally { oldLoad.run() }
            withTimeout(10_000) { oldJob.join() }
            assertEquals(newRoot.path, vm.state.value.projectPath)
            assertEquals(newProfile, vm.state.value.selectedProfile)
            assertFalse(vm.state.value.isLoading)
        }
    }
    private fun integration(dispatcher: CoroutineDispatcher = Dispatchers.IO,
        block: suspend (TuningViewModel, TuningProposalInbox, File, Nt4ClientService, CoroutineScope) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("tuning-inbox-audit").toFile()
        try {
        File(root, ".ares/tuning-components/main.arestuningcomponent").apply {
            parentFile.mkdirs(); writeText(TuningComponentDocumentCodec.encode(TuningComponentDocument(uid="component.main",
                projectId="robot.project", displayName="Main", description="Test component", parameters=listOf(gain, integer))))
        }
        File(root, ".ares/tuning/main.arestuning").apply {
            parentFile.mkdirs(); writeText(TuningProfileDocumentCodec.encode(profile, listOf(gain, integer)))
        }
        val db = DatabaseService(File(root, "test.duckdb").path)
        val client = Nt4ClientService(db)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inbox = TuningProposalInbox()
        val vm = TuningViewModel(client, scope, proposalInbox=inbox, loadDispatcher=dispatcher)
        try { block(vm, inbox, root, client, scope) }
        finally { scope.coroutineContext[Job]!!.cancelAndJoin(); client.stop(); db.close() }
        } finally { root.deleteRecursively() }
    }
}
