package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.*
import com.ares.analytics.viewmodel.*
import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.Mockito
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SysIdSimulationPreviewAuditTest {
    private class Fixture(val scope: TestScope, realTwin: Boolean) {
        val failures = mutableListOf<Throwable>()
        val owner = CoroutineScope(scope.backgroundScope.coroutineContext +
            SupervisorJob(scope.backgroundScope.coroutineContext[Job]) + CoroutineExceptionHandler { _, error -> failures += error })
        val queued = ArrayDeque<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued += block }
        }
        val store = TelemetryStore()
        val connected = MutableStateFlow(false)
        val commands = mutableListOf<String>()
        val transport = object : CalibrationCommandTransport {
            override suspend fun publishString(pubuid: Int, value: String): Boolean { commands += value; return true }
            override suspend fun publishDouble(pubuid: Int, value: Double) = true
        }
        val client = Mockito.mock(Nt4ClientService::class.java).also {
            Mockito.`when`(it.isConnected).thenReturn(connected)
            Mockito.`when`(it.isReplayActive).thenReturn(MutableStateFlow(false))
            Mockito.`when`(it.telemetryStore).thenReturn(store)
            Mockito.`when`(it.telemetryFlow).thenReturn(store.updates)
        }
        val db = Mockito.mock(DatabaseService::class.java)
        val solver = SysIdService(db)
        val tuner = AutoTunerService(client, solver)
        var evaluations = 0
        var evaluationFailure: Exception? = null
        var onEvaluate: () -> Unit = {}
        val twin = if (realTwin) AutoTuningDigitalTwin() else Mockito.mock(AutoTuningDigitalTwin::class.java,
            org.mockito.stubbing.Answer { invocation ->
                check(invocation.method.name == "evaluate")
                evaluations++
                onEvaluate()
                evaluationFailure?.let { throw it }
                val scenario = invocation.getArgument<DigitalTwinScenario>(0)
                DigitalTwinEvaluation(scenario, null, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, null)
            })
        val vm = SysIdViewModel(tuner, client, owner,
            digitalTwin = twin, previewDispatcher = dispatcher, calibrationTransport = transport)
        fun intent(intent: SysIdIntent) { vm.onIntent(intent); scope.runCurrent() }
        fun work() { while (queued.isNotEmpty()) { queued.removeFirst().run(); scope.runCurrent() } }
        fun close() { owner.cancel(); work(); scope.runCurrent() }
        suspend fun readyFrc() {
            connected.value = true; scope.runCurrent()
            intent(SysIdIntent.ConfigurePlatform(false))
            store.accept(com.ares.analytics.shared.models.TelemetryFrame(1, "live", "SysId/SupportedMechanisms", 0.0, "LINEAR"))
            scope.runCurrent()
        }
    }
    private fun test(realTwin: Boolean = false, block: suspend Fixture.() -> Unit) = runTest {
        val fixture = Fixture(this, realTwin)
        try { runCurrent(); fixture.block() } finally { fixture.close() }
    }

    @Test fun `changing mechanism discards a queued old preview`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.SetMechanism(SysIdMechanism.ANGULAR))
        work()
        assertEquals(SysIdMechanism.ANGULAR, vm.state.value.selectedMechanism)
        assertNull(vm.state.value.simulationEvaluation)
    }
    @Test fun `duplicate pending preview requests compute only once`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.RunSimulationPreview)
        work()
        assertEquals(1, evaluations)
    }
    @Test fun `obsolete queued work is canceled before evaluating the new mechanism`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.SetMechanism(SysIdMechanism.ANGULAR))
        intent(SysIdIntent.RunSimulationPreview)
        work()
        assertEquals(1, evaluations)
        assertEquals(SysIdMechanism.ANGULAR, vm.state.value.simulationEvaluation?.scenario?.plant?.mechanism)
    }
    @Test fun `simulation keeps the existing measured recommendation and apply state`() = test(realTwin = true) {
        val scenario = AutoTuningDigitalTwin.teachingScenario(SysIdMechanism.LINEAR)
        val measured = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, twin.generateSamples(scenario), "live-nt4"))
        val applyState = tuner.applyState.value
        intent(SysIdIntent.RunSimulationPreview); work()
        assertNotNull(vm.state.value.simulationEvaluation)
        assertSame(measured, tuner.currentRecommendation.value)
        assertEquals(applyState, tuner.applyState.value)
    }
    @Test fun `preview failures are presented without escaping the view model scope`() = test {
        evaluationFailure = java.io.IOException("injected preview failure")
        intent(SysIdIntent.RunSimulationPreview); work()
        assertTrue(failures.isEmpty())
        assertNull(vm.state.value.simulationEvaluation)
        assertTrue(vm.state.value.simulationMessage.contains("injected preview failure"))
    }
    @Test fun `failure from an obsolete request does not replace the new selection message`() = test {
        evaluationFailure = java.io.IOException("obsolete failure")
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.SetMechanism(SysIdMechanism.ANGULAR))
        val message = vm.state.value.simulationMessage
        work()
        assertTrue(failures.isEmpty())
        assertEquals(message, vm.state.value.simulationMessage)
    }
    @Test fun `preview busy state is independent of live collection state`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        assertTrue(vm.state.value.isSimulationRunning)
        assertFalse(vm.state.value.isLoading)
        assertFalse(vm.state.value.isRoutineRunning)
        work()
        assertFalse(vm.state.value.isSimulationRunning)
        assertNotNull(vm.state.value.simulationEvaluation)
    }
    @Test fun `reselecting the same mechanism preserves pending work`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.SetMechanism(SysIdMechanism.LINEAR))
        work()
        assertEquals(1, evaluations)
        assertNotNull(vm.state.value.simulationEvaluation)
    }
    @Test fun `completed lessons can be run again without reusing old results`() = test {
        intent(SysIdIntent.RunSimulationPreview); work()
        val first = vm.state.value.simulationEvaluation
        intent(SysIdIntent.RunSimulationPreview)
        assertNull(vm.state.value.simulationEvaluation)
        work()
        assertEquals(2, evaluations)
        assertNotSame(first, vm.state.value.simulationEvaluation)
    }
    @Test fun `a failed lesson clears busy state and permits retry`() = test {
        evaluationFailure = java.io.IOException("first attempt failed")
        intent(SysIdIntent.RunSimulationPreview); work()
        assertFalse(vm.state.value.isSimulationRunning)
        evaluationFailure = null
        intent(SysIdIntent.RunSimulationPreview); work()
        assertNotNull(vm.state.value.simulationEvaluation)
        assertTrue(failures.isEmpty())
    }
    @Test fun `canceling the owner prevents queued computation and clears busy state`() = test {
        intent(SysIdIntent.RunSimulationPreview)
        owner.cancel(); work()
        assertEquals(0, evaluations)
        assertFalse(vm.state.value.isSimulationRunning)
        assertNull(vm.state.value.simulationEvaluation)
        assertTrue(failures.isEmpty())
    }
    @Test fun `selection made during computation prevents the old result from publishing`() = test {
        onEvaluate = { vm.onIntent(SysIdIntent.SetMechanism(SysIdMechanism.ANGULAR)) }
        intent(SysIdIntent.RunSimulationPreview); work()
        assertEquals(1, evaluations)
        assertEquals(SysIdMechanism.ANGULAR, vm.state.value.selectedMechanism)
        assertNull(vm.state.value.simulationEvaluation)
        assertFalse(vm.state.value.isSimulationRunning)
    }
    @Test fun `starting a measured routine cancels pending teaching work`() = test {
        readyFrc()
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.StartRoutine(com.areslib.control.assist.SysIdRoutine.DYNAMIC))
        work()
        assertEquals(0, evaluations)
        assertFalse(vm.state.value.isSimulationRunning)
        assertTrue(vm.state.value.isRoutineRunning)
        assertTrue(commands.contains("START_LINEAR_DYNAMIC"))
    }
    @Test fun `teaching work is not started while a measured routine is running`() = test {
        readyFrc()
        intent(SysIdIntent.StartRoutine(com.areslib.control.assist.SysIdRoutine.DYNAMIC))
        intent(SysIdIntent.RunSimulationPreview); work()
        assertEquals(0, evaluations)
        assertFalse(vm.state.value.isSimulationRunning)
    }
    @Test fun `hardware free lesson returns teaching evidence without creating a measured recommendation`() = test(realTwin = true) {
        assertNull(tuner.currentRecommendation.value)
        val before = tuner.applyState.value
        intent(SysIdIntent.RunSimulationPreview); work()
        val result = assertNotNull(vm.state.value.simulationEvaluation)
        assertTrue(assertNotNull(result.recommendation).logSource.startsWith("digital-twin:"))
        assertNull(tuner.currentRecommendation.value)
        assertEquals(before, tuner.applyState.value)
        assertTrue(commands.isEmpty())
    }
    @Test fun `canceling a pending lesson does not remove a measured recommendation`() = test(realTwin = true) {
        val scenario = AutoTuningDigitalTwin.teachingScenario(SysIdMechanism.LINEAR)
        val measured = assertNotNull(tuner.analyzeSamples(SysIdMechanism.LINEAR, twin.generateSamples(scenario), "measured-file"))
        val before = tuner.applyState.value
        intent(SysIdIntent.RunSimulationPreview)
        intent(SysIdIntent.SetMechanism(SysIdMechanism.ANGULAR)); work()
        assertSame(measured, tuner.currentRecommendation.value)
        assertEquals(before, tuner.applyState.value)
    }
}
