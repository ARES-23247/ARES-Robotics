package com.ares.analytics.viewmodel.robotstudio

import com.ares.analytics.service.RobotProjectReadinessEvidence
import com.ares.analytics.service.RobotProjectReadinessService
import com.ares.analytics.shared.models.WorkspaceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** Read-only project orchestrator. Specialized builders remain the sole writers of canonical files. */
class RobotStudioViewModel(
    private val readinessService: RobotProjectReadinessService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RobotStudioState())
    val state: StateFlow<RobotStudioState> = _state.asStateFlow()
    val shellState: StateFlow<RobotStudioShellState> = state
        .map(RobotStudioState::toShellState)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, RobotStudioState().toShellState())

    @Volatile
    private var config: WorkspaceConfig? = null
    private var evidence: RobotProjectReadinessEvidence? = null
    private var runtime = RobotStudioRuntimeEvidence()
    private var refreshJob: Job? = null
    private val refreshGeneration = AtomicLong(0L)

    fun load(workspace: WorkspaceConfig) {
        config = workspace
        refresh()
    }

    fun refresh() {
        val selected = config ?: return
        val generation = refreshGeneration.incrementAndGet()
        refreshJob?.cancel()
        evidence = null
        _state.value = _state.value.copy(
            loading = true,
            projectName = selected.robotName.ifBlank { selected.robotId },
            projectPath = selected.projectPath,
            error = null,
        )
        refreshJob = scope.launch {
            try {
                val inspected = readinessService.inspect(selected) {
                    if (generation != refreshGeneration.get() || config != selected) {
                        throw CancellationException("Robot Studio inspection superseded")
                    }
                }
                if (generation != refreshGeneration.get() || config != selected) return@launch
                evidence = inspected
                publish(selected, inspected)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                if (generation != refreshGeneration.get() || config != selected) return@launch
                _state.value = _state.value.copy(
                    loading = false,
                    stages = emptyList(),
                    error = error.message ?: "Robot Studio could not inspect this project. Check the selected folder, then refresh.",
                )
            }
        }
    }

    fun updateRuntime(updated: RobotStudioRuntimeEvidence) {
        if (runtime == updated) return
        runtime = updated
        val selected = config ?: return
        val currentEvidence = evidence ?: return
        if (currentEvidence.projectPath != selected.projectPath) return
        if (_state.value.loading) return
        publish(selected, currentEvidence)
    }

    private fun publish(selected: WorkspaceConfig, inspected: RobotProjectReadinessEvidence) {
        _state.value = RobotStudioState(
            loading = false,
            projectName = selected.robotName.ifBlank { selected.robotId },
            projectPath = inspected.projectPath,
            authoringModel = inspected.authoringModel,
            stages = evaluateRobotStudioStages(inspected, runtime),
            hardwareReadiness = evaluateRobotStudioHardwareReadiness(inspected),
            verificationReport = runtime.build.verificationReport,
            simulationProduct = inspected.simulationProduct,
            error = null,
        )
    }
}
