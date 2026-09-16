package com.ares.analytics.viewmodel.controls

import com.ares.analytics.service.ControlsDesignAssistant
import com.ares.analytics.service.ControlsDesignContext
import com.areslib.controls.ControlSchemeCodec
import com.areslib.controls.ControlSchemeDocument
import com.areslib.controls.ControlTargetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ControlsAiProposalCoordinator(
    private val designAssistant: ControlsDesignAssistant?,
    private val scope: CoroutineScope,
) {
    fun request(
        stateFlow: MutableStateFlow<ControlsEditorState>,
        studentRequest: String,
    ) {
        val current = stateFlow.value
        val request = studentRequest.trim()
        val scheme = current.selectedScheme
        val assistant = designAssistant
        when {
            request.isBlank() -> stateFlow.update { it.copy(aiProposalError = "Describe the bindings you want first.") }
            assistant == null -> stateFlow.update { it.copy(aiProposalError = "Gemini is not available in this app session.") }
            scheme == null -> stateFlow.update { it.copy(aiProposalError = "Select a control scheme first.") }
            current.draftHasUnappliedChanges -> stateFlow.update {
                it.copy(aiProposalError = "Apply or discard the current binding draft before asking Gemini.")
            }
            else -> {
                val context = ControlsDesignContext(
                    actionKeys = current.actions.mapTo(linkedSetOf()) { it.key },
                    routineIds = current.routineIds.toSet(),
                    profileControls = current.profiles.associate { profile ->
                        profile.documentId to profile.controls.filter { control ->
                            control.mappings.any { it.platform == current.targetPlatform }
                        }.mapTo(linkedSetOf()) { it.controlId }
                    },
                )
                val baseHash = ControlSchemeCodec.contentHash(scheme)
                stateFlow.update { it.copy(aiProposalInProgress = true, aiProposal = null, aiProposalError = null) }
                scope.launch {
                    runCatching { assistant.propose(scheme, context, request) }
                        .onSuccess { proposal ->
                            val problems = proposal.candidate.bindings.flatMap { binding ->
                                if (binding.target.kind != ControlTargetKind.ACTION) emptyList() else {
                                    val action = current.actions.firstOrNull { it.key == binding.target.key }
                                    if (action == null) {
                                        listOf(
                                            ControlsProblem(
                                                ControlsProblemSeverity.ERROR,
                                                "Unknown action '${binding.target.key}'.",
                                                binding.bindingId,
                                            )
                                        )
                                    } else {
                                        validateArguments(action, binding.target.arguments).map {
                                            ControlsProblem(ControlsProblemSeverity.ERROR, it, binding.bindingId)
                                        }
                                    }
                                }
                            }
                            val review = ControlsAiProposalReview(
                                proposal = proposal,
                                changes = describeControlsChanges(scheme, proposal.candidate),
                                problems = problems,
                                baseContentHash = baseHash,
                            )
                            stateFlow.update { latest ->
                                val latestScheme = latest.selectedScheme
                                if (latestScheme == null || ControlSchemeCodec.contentHash(latestScheme) != baseHash) {
                                    latest.copy(
                                        aiProposalInProgress = false,
                                        aiProposalError = "The bindings changed while Gemini was working. Request a fresh proposal.",
                                    )
                                } else {
                                    latest.copy(aiProposalInProgress = false, aiProposal = review)
                                }
                            }
                        }
                        .onFailure { error ->
                            stateFlow.update {
                                it.copy(
                                    aiProposalInProgress = false,
                                    aiProposalError = error.message ?: "Gemini could not create a controls proposal.",
                                )
                            }
                        }
                }
            }
        }
    }

    fun apply(current: ControlsEditorState): ControlsEditorState {
        val review = current.aiProposal ?: return current
        val scheme = current.selectedScheme ?: return current
        return when {
            !review.canApply -> current.copy(aiProposalError = "Gemini's proposal has blocking validation errors.")
            ControlSchemeCodec.contentHash(scheme) != review.baseContentHash -> current.copy(
                aiProposal = null,
                aiProposalError = "The bindings changed. Request a fresh proposal.",
            )
            else -> current.replaceScheme(review.proposal.candidate).copy(
                dirty = true,
                dirtySchemeIds = current.dirtySchemeIds + scheme.documentId,
                selectedBindingId = null,
                draftBinding = null,
                draftHasUnappliedChanges = false,
                aiProposal = null,
                aiProposalError = null,
                status = "Applied Gemini's proposal locally. Review the bindings and Save when ready.",
            ).revalidated()
        }
    }
}

internal fun describeControlsChanges(
    before: ControlSchemeDocument,
    after: ControlSchemeDocument,
): List<String> = buildList {
    if (before.name != after.name) add("Rename scheme '${before.name}' → '${after.name}'")
    if (before.description != after.description) add("Update the scheme description")
    val beforeById = before.bindings.associateBy { it.bindingId }
    val afterById = after.bindings.associateBy { it.bindingId }
    (afterById.keys - beforeById.keys).sorted().forEach { id ->
        add("Add binding: ${afterById.getValue(id).displayName}")
    }
    (beforeById.keys - afterById.keys).sorted().forEach { id ->
        add("Remove binding: ${beforeById.getValue(id).displayName}")
    }
    (beforeById.keys intersect afterById.keys).sorted().forEach { id ->
        if (beforeById.getValue(id) != afterById.getValue(id)) {
            add("Change binding: ${afterById.getValue(id).displayName}")
        }
    }
    if (isEmpty()) add("No form changes")
}
