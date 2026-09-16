package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.AresProjectGenerator
import com.ares.analytics.viewmodel.SubsystemFileChange
import com.ares.analytics.viewmodel.SubsystemGeneratorState

internal class SubsystemStarterGeneratorOps(
    private val projectGenerator: AresProjectGenerator?,
    private val getState: () -> SubsystemGeneratorState,
    private val updateState: ((SubsystemGeneratorState) -> SubsystemGeneratorState) -> Unit,
) {
    fun generate(saveIfDirty: () -> Unit) {
        saveIfDirty()
        val current = getState()
        if (current.dirty) return
        if (current.hasProtectedUserOwnedConflict) {
            updateState {
                it.copy(status = "Generation stopped: a USER-OWNED file differs from the preview and cannot be replaced.")
            }
            return
        }
        val replacements = current.previewFiles.filter { it.change == SubsystemFileChange.REPLACE_STARTER }
        if (replacements.isNotEmpty()) {
            updateState { it.copy(pendingStarterReplacements = replacements, status = null) }
            return
        }
        projectGenerator?.applySubsystemStarters(current.projectPath, current.league)
    }

    fun cancelStarterReplacement() = updateState { it.copy(pendingStarterReplacements = emptyList()) }

    fun confirmStarterReplacement() {
        val current = getState()
        val token = current.starterConfirmationToken
        if (current.pendingStarterReplacements.isEmpty() || token == null) return
        updateState { it.copy(pendingStarterReplacements = emptyList(), starterConfirmationToken = null) }
        runCatching { projectGenerator?.applySubsystemStarters(current.projectPath, current.league, token) }
            .onFailure { error ->
                updateState { it.copy(status = error.message ?: "The starter proposal changed; review it again.") }
            }
    }
}
