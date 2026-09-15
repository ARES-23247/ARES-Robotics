package com.ares.analytics.viewmodel.sysid

import com.ares.analytics.service.AutoTunerService
import com.ares.analytics.service.AutoTuningDigitalTwin
import com.ares.analytics.viewmodel.SysIdState
import com.areslib.control.assist.SysIdMechanism
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Called from the owning view-model scope; computation never publishes measured tuning state. */
internal class SysIdSimulationPreview(
    private val state: MutableStateFlow<SysIdState>,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val twin: AutoTuningDigitalTwin,
    private val tuner: AutoTunerService,
) {
    private var generation = 0L
    private var job: Job? = null
    private var requestedMechanism: SysIdMechanism? = null

    fun selectMechanism(mechanism: SysIdMechanism) {
        if (state.value.selectedMechanism == mechanism) return
        cancelCurrent()
        state.update { it.copy(selectedMechanism = mechanism, simulationEvaluation = null, isSimulationRunning = false,
            simulationMessage = "Run the ${mechanism.name.lowercase()} teaching model before connecting a robot.") }
    }

    fun cancelPending() {
        if (job == null) return
        cancelCurrent()
        state.update { it.copy(isSimulationRunning = false, simulationEvaluation = null,
            simulationMessage = "Lesson canceled for live measurement. Run it again after the experiment.") }
    }

    private fun cancelCurrent() {
        generation++
        job?.cancel()
        job = null
        requestedMechanism = null
    }

    fun start() {
        val current = state.value
        if (!scope.isActive || current.isRoutineRunning || current.isLoading) return
        val mechanism = current.selectedMechanism
        if (job?.isActive == true && requestedMechanism == mechanism) return
        cancelCurrent()
        val request = generation
        requestedMechanism = mechanism
        state.update { it.copy(isSimulationRunning = true, simulationEvaluation = null,
            simulationMessage = "Running the ${mechanism.name.lowercase()} teaching model…") }
        val work = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val evaluation = withContext(dispatcher) {
                    currentCoroutineContext().ensureActive()
                    val result = twin.evaluate(AutoTuningDigitalTwin.teachingScenario(mechanism)) { selected, samples, source ->
                        tuner.computeSampleAnalysis(selected, samples, source).recommendation
                    }
                    currentCoroutineContext().ensureActive()
                    result
                }
                if (request == generation && state.value.selectedMechanism == mechanism) {
                    val passed = evaluation.recoveredWithinTolerance && evaluation.closedLoop?.stable == true
                    state.update { it.copy(simulationEvaluation = evaluation,
                        simulationMessage = if (passed) {
                            "Simulation verified: the workflow recovered this known teaching plant and its bounded closed-loop preview stayed stable."
                        } else {
                            "Simulation needs review: inspect data quality and the bounded prediction before any measured experiment."
                        }) }
                }
            } catch (cancelled: CancellationException) {
                if (request == generation) state.update { it.copy(simulationMessage = "Simulation canceled.") }
                throw cancelled
            } catch (failure: Exception) {
                if (request == generation) state.update { it.copy(simulationEvaluation = null,
                    simulationMessage = "Could not run the teaching model: ${failure.message ?: "analysis failed"}") }
            } finally {
                if (request == generation) {
                    job = null
                    state.update { it.copy(isSimulationRunning = false) }
                }
            }
        }
        job = work
        work.start()
    }
}
