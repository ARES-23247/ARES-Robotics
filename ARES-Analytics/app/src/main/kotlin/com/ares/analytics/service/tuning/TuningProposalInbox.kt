package com.ares.analytics.service.tuning

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ExternalTuningProposal(
    val source: String,
    val summary: String,
    val values: Map<String, Double>,
    val evidencePath: String? = null,
    val evidenceSha256: String? = null
)

/**
 * In-memory review inbox used by AutoTuner and future AI assistants. Submitting never writes a
 * profile, source file, vendor file, or robot topic. The Tuning board performs canonical
 * declaration/range/policy validation before a student can review promotion.
 */
class TuningProposalInbox {
    private val _proposals = MutableSharedFlow<ExternalTuningProposal>(extraBufferCapacity = 8)
    val proposals: SharedFlow<ExternalTuningProposal> = _proposals.asSharedFlow()

    fun submit(proposal: ExternalTuningProposal): Boolean = _proposals.tryEmit(proposal)
}
