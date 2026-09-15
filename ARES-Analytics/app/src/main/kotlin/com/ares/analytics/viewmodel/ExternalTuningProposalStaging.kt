package com.ares.analytics.viewmodel

import com.ares.analytics.service.tuning.ExternalTuningProposal
import com.ares.analytics.service.tuning.TuningValueProvenance
import com.ares.analytics.service.tuning.buildTuningReview
import com.areslib.tuning.TuningParameterType
import com.areslib.tuning.TuningValue

/** Stages the entire external proposal or none of it; existing student edits are preserved. */
internal fun stageExternalTuningProposal(state: TuningState, proposal: ExternalTuningProposal): TuningState {
    fun rejected(reason: String) = state.copy(review = null, saveStatus = "", errorMessage = reason)
    val profile = state.selectedProfile
    if (state.isLoading || profile == null) return rejected("Load a tuning profile before reviewing external proposals.")
    if (proposal.values.isEmpty()) return rejected("The external tuning proposal has no values.")
    val declarations = state.catalog.associateBy { it.key }
    val values = LinkedHashMap<String, TuningValue>()
    for ((key, number) in proposal.values) {
        val declaration = declarations[key] ?: return rejected("$key is not declared by a robot component.")
        if (!number.isFinite()) return rejected("$key must be finite.")
        val typed = when (declaration.type) {
            TuningParameterType.DOUBLE -> TuningValue(doubleValue = number)
            TuningParameterType.INT -> {
                if (number % 1.0 != 0.0 || number !in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
                    return rejected("Enter a whole number in the supported Int range for $key.")
                TuningValue(intValue = number.toInt())
            }
            else -> return rejected("$key does not accept a numeric proposal.")
        }
        if (key in state.proposals && state.proposals[key] != typed)
            return rejected("$key already has a different staged value. Review or remove it before sending another proposal.")
        values[key] = typed
    }
    val provenance = values.keys.associateWith {
        TuningValueProvenance(proposal.source, proposal.summary, proposal.evidencePath, proposal.evidenceSha256)
    }
    val errors = buildTuningReview(profile, state.profiles, state.catalog, values, provenance).second
    if (errors.isNotEmpty()) return rejected(errors.joinToString("\n"))
    return state.copy(proposals = state.proposals + values,
        proposalProvenance = provenance + state.proposalProvenance.filterKeys { it in state.proposals },
        review = null, errorMessage = null, saveStatus = "Staged ${values.size} external values for review. Nothing has been written or pushed.")
}
