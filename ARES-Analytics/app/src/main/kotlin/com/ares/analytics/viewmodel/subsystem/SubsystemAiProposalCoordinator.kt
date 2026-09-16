package com.ares.analytics.viewmodel.subsystem

import com.ares.analytics.service.SubsystemDesignAssistant
import com.ares.analytics.service.sanitizeSubsystemDesignCandidate
import com.ares.analytics.viewmodel.SubsystemAiProposalReview
import com.ares.analytics.viewmodel.SubsystemGeneratorState
import com.ares.analytics.viewmodel.SubsystemProblem
import com.ares.analytics.viewmodel.SubsystemProblemSeverity
import com.ares.analytics.viewmodel.safetyWarnings
import com.ares.analytics.viewmodel.structuredLineDiff
import com.areslib.subsystem.SubsystemSchema
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal class SubsystemAiProposalCoordinator(
    private val designAssistant: SubsystemDesignAssistant?,
    private val reviewGson: Gson,
    private val scope: CoroutineScope,
    private val aiProposalGeneration: AtomicLong,
    private val getState: () -> SubsystemGeneratorState,
    private val updateState: ((SubsystemGeneratorState) -> SubsystemGeneratorState) -> Unit,
    private val revalidate: (SubsystemGeneratorState) -> SubsystemGeneratorState,
) {
    /** Requests form edits only. The assistant cannot save, generate, or write project source. */
    fun requestAiProposal(studentRequest: String) {
        val request = studentRequest.trim()
        val base = getState().draft?.document ?: return
        val assistant = designAssistant
        if (request.isBlank()) {
            updateState { it.copy(aiProposalError = "Describe the mechanism or the change you want first.") }
            return
        }
        if (assistant == null) {
            updateState { it.copy(aiProposalError = "The AI form assistant is not available in this app session.") }
            return
        }
        updateState {
            it.copy(aiProposalInProgress = true, aiProposal = null, aiProposalError = null)
        }
        val requestGeneration = aiProposalGeneration.incrementAndGet()
        scope.launch {
            runCatching {
                val rawProposal = assistant.propose(base, request)
                val candidate = sanitizeSubsystemDesignCandidate(base, rawProposal.candidate)
                val proposal = rawProposal.copy(candidate = candidate)
                val problems = SubsystemSchema.validate(candidate).map {
                    SubsystemProblem(SubsystemProblemSeverity.ERROR, it.path, it.message)
                } + safetyWarnings(candidate)
                SubsystemAiProposalReview(
                    base = base,
                    proposal = proposal,
                    diff = structuredLineDiff(
                        reviewGson.toJson(base),
                        reviewGson.toJson(candidate),
                        contextLines = 2,
                    ),
                    problems = problems.distinctBy { Triple(it.severity, it.path, it.message) },
                )
            }
                .onSuccess { review ->
                    updateState { current ->
                        if (requestGeneration != aiProposalGeneration.get()) {
                            current
                        } else if (current.draft?.document != base) {
                            current.copy(
                                aiProposalInProgress = false,
                                aiProposalError = "The form changed while Gemini was working. Request a fresh proposal.",
                            )
                        } else {
                            current.copy(aiProposalInProgress = false, aiProposal = review, aiProposalError = null)
                        }
                    }
                }
                .onFailure { error ->
                    updateState {
                        if (requestGeneration != aiProposalGeneration.get()) it else it.copy(
                            aiProposalInProgress = false,
                            aiProposal = null,
                            aiProposalError = error.message ?: "Gemini could not create a subsystem proposal.",
                        )
                    }
                }
        }
    }

    fun dismissAiProposal() = updateState { it.copy(aiProposal = null, aiProposalError = null) }

    fun applyAiProposal() = updateState { current ->
        val review = current.aiProposal ?: return@updateState current
        val draft = current.draft ?: return@updateState current
        when {
            !review.canApply -> current.copy(aiProposalError = "Fix the proposal's validation errors before applying it.")
            draft.document != review.base -> current.copy(
                aiProposal = null,
                aiProposalError = "The form changed after this proposal was created. Request a fresh proposal.",
            )
            else -> revalidate(
                current.copy(
                    draft = draft.edit { review.proposal.candidate },
                    dirty = true,
                    aiProposal = null,
                    aiProposalError = null,
                    status = "Applied Gemini's form proposal. Review it, then Save when you are satisfied.",
                )
            )
        }
    }
}
