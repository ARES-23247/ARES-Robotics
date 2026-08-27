package com.ares.analytics.viewmodel.robotstudio

import com.ares.analytics.service.RobotProjectReadinessEvidence
import com.ares.analytics.service.RobotProjectReadinessService
import com.ares.analytics.shared.WorkspaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Read-only project orchestrator. Specialized builders remain the sole writers of canonical files. */
class RobotStudioViewModel(
    private val readinessService: RobotProjectReadinessService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RobotStudioState())
    val state: StateFlow<RobotStudioState> = _state.asStateFlow()

    private var config: WorkspaceConfig? = null
    private var evidence: RobotProjectReadinessEvidence? = null
    private var runtime = RobotStudioRuntimeEvidence()
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L

    fun load(workspace: WorkspaceConfig) {
        config = workspace
        refresh()
    }

    fun refresh() {
        val selected = config ?: return
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        _state.value = _state.value.copy(
            loading = true,
            projectName = selected.robotName.ifBlank { selected.robotId },
            projectPath = selected.projectPath,
            error = null,
        )
        refreshJob = scope.launch {
            runCatching { readinessService.inspect(selected) }
                .onSuccess { inspected ->
                    if (generation != refreshGeneration || config?.id != selected.id) return@onSuccess
                    evidence = inspected
                    publish(selected, inspected)
                }
                .onFailure { error ->
                    if (generation != refreshGeneration || config?.id != selected.id) return@onFailure
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
        evidence?.let { publish(selected, it) }
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
