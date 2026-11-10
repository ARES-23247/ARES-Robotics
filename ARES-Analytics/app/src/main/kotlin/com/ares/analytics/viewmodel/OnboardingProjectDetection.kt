package com.ares.analytics.viewmodel

import com.ares.analytics.service.EnvironmentService
import com.ares.analytics.shared.models.League
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Owns the current selection's debounced disk inspection; older selections cannot publish. */
internal class OnboardingProjectDetection(
    private val environment: EnvironmentService,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<OnboardingState>,
) {
    private var generation = 0L
    private var job: Job? = null

    fun invalidate() {
        generation++
        job?.cancel()
        job = null
        state.update { it.copy(isDetectingProject = false) }
    }

    fun request(immediate: Boolean = false) {
        invalidate()
        val selection = state.value
        if (selection.projectSetupMode != ProjectSetupMode.OPEN_EXISTING || selection.projectPath.isBlank()) return
        val owner = generation
        val path = selection.projectPath.trim()
        fun publish(transform: (OnboardingState) -> OnboardingState) {
            state.update { current ->
                if (generation == owner && current.projectPath.trim() == path &&
                    current.projectSetupMode == ProjectSetupMode.OPEN_EXISTING &&
                    current.currentStep == OnboardingStep.PROJECT && !current.isSaving && !current.saveSuccess
                ) transform(current) else current
            }
        }
        state.update { it.copy(isDetectingProject = true) }
        job = scope.launch {
            try {
                if (!immediate) delay(200)
                val (identity, league) = withContext(Dispatchers.IO) {
                    val root = File(path)
                    require(root.isDirectory) { "Choose a folder that contains your robot project." }
                    val identity = environment.readProjectIdentity(path)
                    require(identity != null || !File(root, ".ares/project.json").exists()) {
                        "The project identity is unreadable or uses an unsupported schema. Choose a supported ARES project."
                    }
                    val league = identity?.let { League.valueOf(it.league.uppercase()) } ?: environment.detectLeague(path)
                    val legacyFtc = File(root, "TeamCode").isDirectory &&
                        (File(root, "settings.gradle").isFile || File(root, "settings.gradle.kts").isFile)
                    require(identity != null || league == League.FRC || File(root, ".ares").isDirectory ||
                        (league == League.XRP && File(root, "main.py").isFile) || legacyFtc
                    ) { "Choose the ARES robot project itself, not its parent folder." }
                    identity to league
                }
                publish { current ->
                    current.copy(
                        teamId = identity?.teamId ?: current.teamId,
                        seasonId = identity?.seasonId ?: current.seasonId,
                        robotId = identity?.robotId ?: current.robotId,
                        robotName = identity?.name ?: current.robotName,
                        league = league,
                        nt4Host = environment.getDefaultNt4Host(league, identity?.teamId ?: current.teamId),
                        isDetectingProject = false,
                        projectDetectionMessage = if (identity != null) {
                            "Project found. We filled in the ${league.name} robot details from canonical .ares/project.json."
                        } else "Project found. We detected ${league.name}; add the robot details on the next step.",
                        fieldErrors = OnboardingFieldErrors(),
                        currentStep = advanceAfterDetection(current.currentStep, recognizedProject = true),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                publish { it.copy(projectDetectionMessage = null,
                    fieldErrors = it.fieldErrors.copy(projectPath = failure.message ?: "This project could not be read.")) }
            } finally {
                // A completed older inspection must not clear a newer selection's busy state.
                if (generation == owner) state.update { it.copy(isDetectingProject = false) }
            }
        }
    }
}
